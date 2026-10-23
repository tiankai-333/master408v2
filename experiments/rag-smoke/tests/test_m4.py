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

from m4_compare import main, rrf_fuse
from m4_semantic import (
    CacheMismatchError,
    corpus_fingerprint,
    load_cache,
    save_cache,
)
from rag_smoke.harness import Doc


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
        fused = rrf_fuse(["a", "b", "c"], ["b", "d"], {}, {}, 3)
        self.assertEqual([f["doc_id"] for f in fused], ["b", "a", "d"])
        self.assertAlmostEqual(fused[0]["rrf"], 1 / 61 + 1 / 62, places=6)

    def test_tie_stable_by_doc_id(self) -> None:
        fused = rrf_fuse(["x", "y"], ["x", "y"], {}, {}, 2)
        self.assertEqual([f["doc_id"] for f in fused], ["x", "y"])

    def test_topk_limits(self) -> None:
        fused = rrf_fuse(["a", "b", "c", "d"], ["a"], {}, {}, 2)
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
                              FakeIndex(docs).model_files, "test-model", "rev0")
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
                           FakeIndex(docs).model_files, "test-model", "rev0")
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
                load_cache(cache, docs, ["q1"], ["文本"], changed, "test-model", "rev0")
            self.assertIn("model file mismatch", str(cm.exception))

    def test_corpus_change_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["文本"])
            changed = docs + [Doc("d3", "文档三", "新增内容。")]
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, changed, ["q1"], ["文本"],
                           FakeIndex(docs).model_files, "test-model", "rev0")

    def test_query_mapping_change_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "c"
            docs = self._docs()
            self._save(cache, docs, ["q1"], ["文本"])
            with self.assertRaises(CacheMismatchError):
                load_cache(cache, docs, ["q2"], ["文本"],
                           FakeIndex(docs).model_files, "test-model", "rev0")


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
                           idx.model_files, "test-model", "rev0")

    def test_nan_matrix_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            docs = [Doc("d1", "文档一", "内容。")]
            cache, idx = self._cache_with(Path(tmp),
                                          np.full((1, 4), np.nan, dtype=np.float32))
            with self.assertRaises(CacheMismatchError) as cm:
                load_cache(cache, docs, ["q1"], ["文本"],
                           idx.model_files, "test-model", "rev0")
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
            self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
