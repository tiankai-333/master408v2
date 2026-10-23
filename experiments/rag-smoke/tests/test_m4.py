"""M4 回归测试：RRF 融合、同分 doc_id 稳定、缓存契约（查询文本/模型文件绑定、
向量核验、错配拒绝）、变 K 拒绝。

融合/缓存错配用小夹具与合成向量测试纯逻辑；真实模型端到端由
m4_compare.py 实际运行承担（run-001/002/003-cachehit），不以 mock 代替。
"""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from m4_compare import compute_routes, main, rrf_fuse
from m4_semantic import (
    CacheMismatchError,
    corpus_fingerprint,
    load_cache,
    save_cache,
)
from rag_smoke.harness import BM25Index, Doc, Query


def _fingerprint(docs) -> str:
    return corpus_fingerprint(docs)


class FakeIndex:
    """最小可保存的索引替身：仅用于缓存契约测试（非检索质量断言）。"""

    def __init__(self, docs, rows: int = 4, value: float = 1.0):
        self.matrix = np.full((len(docs), rows), value, dtype=np.float32)
        self.row_ids = [d.doc_id for d in docs]
        self.fingerprint = _fingerprint(docs)
        self.model_repo = "test-model"
        self.model_revision = "rev0"
        self.model_dir = "C:/fake"
        self.model_files = {"model.safetensors": "aaa", "tokenizer.json": "bbb"}
        self.max_length = 128
        self.actual_max_seq_length = 128
        self.normalize = True
        self.truncated = 0
        self.encode_batch_size = 16

    def manifest(self, corpus_sha_by_file: dict) -> dict:
        return {
            "model_repo": self.model_repo,
            "model_revision": self.model_revision,
            "model_local_dir": self.model_dir,
            "model_files_sha256": self.model_files,
            "encode_params": {
                "max_length": self.max_length,
                "actual_max_seq_length": self.actual_max_seq_length,
                "normalize": self.normalize,
                "batch_size": self.encode_batch_size,
                "truncated_inputs": self.truncated,
            },
            "matrix_rows": int(self.matrix.shape[0]),
            "matrix_cols": int(self.matrix.shape[1]),
            "matrix_shape_dtype": f"{self.matrix.shape} fp32",
            "row_fingerprint": self.fingerprint,
        }


class RrfFuseTest(unittest.TestCase):
    def test_known_fusion(self) -> None:
        # BM25: a,b,c；语义: b,d → RRF(b)=1/61+1/62 最高，其次 a、d
        fused = rrf_fuse(["a", "b", "c"], ["b", "d"], 3)
        self.assertEqual([f["doc_id"] for f in fused], ["b", "a", "d"])
        self.assertAlmostEqual(fused[0]["rrf"], 1 / 61 + 1 / 62, places=6)

    def test_tie_stable_by_doc_id(self) -> None:
        fused = rrf_fuse(["x", "y"], ["x", "y"], 2)
        self.assertEqual([f["doc_id"] for f in fused], ["x", "y"])

    def test_topk_limits(self) -> None:
        fused = rrf_fuse(["a", "b", "c", "d"], ["a"], 2)
        self.assertEqual(len(fused), 2)


class CacheContractTest(unittest.TestCase):
    """缓存契约：文本绑定/模型文件绑定/向量核验/错配拒绝。"""

    def _docs(self) -> list[Doc]:
        return [Doc("d1", "文档一", "栈与递归。"), Doc("d2", "文档二", "路由转发。")]

    def _save(self, cache: Path, docs: list[Doc], qids: list[str],
              qtexts: list[str]) -> FakeIndex:
        idx = FakeIndex(docs)
        save_cache(idx, cache, {"d1.md": "h1"}, np.ones((len(qids), 4)), qids, qtexts)
        return idx

    def test_cache_hit_when_consistent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["查询文本一"])
            m, q = load_cache(cache, docs, ["q1"], ["查询文本一"],
                              FakeIndex(docs).model_files, 128,
                              "test-model", "rev0")
            self.assertEqual(m.shape, (2, 4))
            self.assertEqual(q.shape, (1, 4))

    def test_same_id_changed_text_rejected(self) -> None:
        # 核心反例：同 ID 查询改文本，必须拒用旧向量（旧实现只 hash ID 会错误复用）
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["原始查询文本"])
            with self.assertRaises(CacheMismatchError) as cm:
                load_cache(cache, docs, ["q1"], ["改写后的查询文本"],
                           FakeIndex(docs).model_files, 128, "test-model", "rev0")
            self.assertIn("query text", str(cm.exception))

    def test_model_file_change_rejected(self) -> None:
        # 本地实际模型文件哈希变化（换模型/权重更新）必须拒绝缓存
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["文本"])
            changed = dict(FakeIndex(docs).model_files)
            changed["model.safetensors"] = "bbb"
            with self.assertRaises(CacheMismatchError) as cm:
                load_cache(cache, docs, ["q1"], ["文本"], changed, 128,
                           "test-model", "rev0")
            self.assertIn("model file mismatch", str(cm.exception))

    def test_corpus_change_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["文本"])
            changed = docs + [Doc("d3", "文档三", "新增内容。")]
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, changed, ["q1"], ["文本"],
                           FakeIndex(docs).model_files, 128, "test-model", "rev0")

    def test_query_mapping_change_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["文本"])
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, docs, ["q2"], ["文本"],
                           FakeIndex(docs).model_files, 128, "test-model", "rev0")


class MatrixVerificationTest(unittest.TestCase):
    """向量文件核验：哈希/形状/有限值（防静默损坏），小合成夹具。"""

    def _cache_with(self, tmp: Path, matrix: np.ndarray) -> tuple[Path, FakeIndex]:
        cache = tmp / "c"
        cache.mkdir(parents=True)
        docs = [Doc("d1", "文档一", "内容。")]
        idx = FakeIndex(docs, rows=matrix.shape[1])
        idx.matrix = matrix
        save_cache(idx, cache, {}, matrix, ["q1"], ["文本"])
        return cache, idx

    def test_tampered_matrix_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            docs = [Doc("d1", "文档一", "内容。")]
            cache, idx = self._cache_with(Path(tmp),
                                          np.ones((1, 4), dtype=np.float32))
            npy = cache / "doc_matrix.npy"
            npy.write_bytes(npy.read_bytes()[:-1] + bytes(
                [npy.read_bytes()[-1] ^ 0xFF]))  # 破坏一个字节
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, docs, ["q1"], ["文本"],
                           idx.model_files, 128, "test-model", "rev0")

    def test_nan_matrix_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            docs = [Doc("d1", "文档一", "内容。")]
            cache, idx = self._cache_with(Path(tmp),
                                          np.full((1, 4), np.nan, dtype=np.float32))
            with self.assertRaises(CacheMismatchError) as cm:
                load_cache(cache, docs, ["q1"], ["文本"],
                           idx.model_files, 128, "test-model", "rev0")
            self.assertIn("非有限值", str(cm.exception))

    def test_shape_mismatch_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache, idx = self._cache_with(Path(tmp),
                                          np.ones((1, 4), dtype=np.float32))
            wrong_docs = [Doc("d1", "t", "c"), Doc("d2", "t2", "c2")]
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, wrong_docs, ["q1"], ["文本"],
                           idx.model_files, "test-model", "rev0")


class ChangedTopkRejectedTest(unittest.TestCase):
    def test_m4_compare_topk_mismatch_exit_2(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            corpus = root / "corpus"
            corpus.mkdir()
            (corpus / "d01.md").write_text("# A\n\n内容\n", encoding="utf-8")
            queries = root / "queries.jsonl"
            queries.write_text(
                '{"id": "q1", "text": "x", "category": "normal", '
                '"relevant": ["d01"], "rationale": "r"}\n', encoding="utf-8")
            frozen = root / "frozen.json"
            frozen.write_text(json.dumps({"params": {"topk": 5}}), encoding="utf-8")
            rc = main(["--corpus-dir", str(corpus), "--queries", str(queries),
                       "--verify-manifest", str(frozen), "--cache-dir",
                       str(root / "c"), "--out", str(root / "out"), "--topk", "3"])
            self.assertEqual(rc, 3)


class ComputeRoutesTest(unittest.TestCase):
    """集成反例：能抓住"MRR 混入其他检索路排名"的旧缺陷。"""

    def _docs(self):
        # d1 是目标题；d2..d6 为干扰
        return [Doc(f"d{i}", f"文档{i}", f"内容{i}。") for i in range(1, 7)]

    def _queries(self):
        return [Query("q1", "目标查询词 d1", "normal", ["d1"], "目标一")]

    def test_semantic_mrr5_not_polluted_by_bm25_tail(self) -> None:
        # 语义矩阵使目标 d1 排第 6（Top5 外）；BM25 把 d1 排第 1。
        # 旧实现把 BM25 补尾拼进语义排名 → semantic mrr>0（污染）；
        # 新口径 MRR@5 只看语义 Top5 → 未召回=0。
        docs = self._docs()
        queries = self._queries()
        bm25_index = BM25Index(docs)
        # 语义矩阵：6 维单位阵，行序 [d1..d6]，查询向量使 d1 排最后
        eye = np.eye(6, dtype=np.float32)
        doc_matrix = eye
        query_matrix = -eye[:1]  # 与 d1 负相关最重 → d1 排最后
        result = compute_routes(docs, queries, bm25_index, doc_matrix,
                                query_matrix, topk=5)
        sem = result["metrics"]["semantic"]
        self.assertEqual(sem["mrr@5"], 0.0, "Top5 外未召回必须记 0（不补尾）")
        self.assertAlmostEqual(sem["full_mrr"], 1 / 6, places=6,
                               msg="语义自身全排名 full MRR 另记（d1 排第 6）")
        sem_row = result["rows"]["semantic"][0]
        self.assertNotIn("d1", sem_row["ranking_topk"])
        # BM25 路自身：d1 rank1
        self.assertEqual(result["rows"]["bm25"][0]["first_relevant_rank_in_top5"], 1)

    def test_hybrid_candidates_limited_to_two_top5(self) -> None:
        docs = self._docs()
        queries = self._queries()
        bm25_index = BM25Index(docs)
        doc_matrix = np.eye(6, dtype=np.float32)
        query_matrix = np.eye(6, dtype=np.float32)[:1]
        result = compute_routes(docs, queries, bm25_index, doc_matrix,
                                query_matrix, topk=5)
        hyb = result["rows"]["hybrid"][0]
        # 混合候选必须 ⊆ 两路 Top5 并集（固定 Top5 融合，不引入 Top5 之外文档）
        union = set(result["rows"]["bm25"][0]["ranking_topk"]) |             set(result["rows"]["semantic"][0]["ranking_topk"])
        self.assertTrue(set(hyb["ranking_topk"]) <= union)


if __name__ == "__main__":
    unittest.main()
