"""rag-smoke 确定性测试：python -m unittest discover -s tests -v"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from rag_smoke.harness import (
    BM25Index,
    Doc,
    Query,
    ValidationError,
    load_corpus,
    load_queries,
    rank_metrics,
    tokenize,
    validate_dataset,
)

REPO_RAG = Path(__file__).resolve().parent.parent


class TokenizerTest(unittest.TestCase):
    def test_cjk_bigram_and_ascii(self) -> None:
        # "死锁的" -> 死锁/锁的；ASCII 词小写保留
        self.assertEqual(tokenize("死锁的 TCP"), ["死锁", "锁的", "tcp"])

    def test_ascii_lowercase(self) -> None:
        self.assertEqual(tokenize("HTTPS Cache"), ["https", "cache"])

    def test_single_cjk_char(self) -> None:
        self.assertEqual(tokenize("页"), ["页"])


class ToyIndexTest(unittest.TestCase):
    """手工可核对的小语料：含查询词的文档应排前，且可复算分数口径。"""

    def setUp(self) -> None:
        self.docs = [
            Doc("d1", "死锁", "死锁需要互斥与循环等待。"),
            Doc("d2", "哈希表", "哈希表处理哈希冲突。"),
            Doc("d3", "进程", "进程是资源分配的基本单位，与死锁不同。"),
        ]

    def test_relevant_doc_ranks_first(self) -> None:
        index = BM25Index(self.docs)
        ranked = index.score("死锁的条件")
        self.assertEqual(ranked[0][0], "d1")
        self.assertGreater(ranked[0][1], 0.0)

    def test_query_without_match_gives_zero_scores(self) -> None:
        index = BM25Index(self.docs)
        ranked = index.score("完全无关词串")
        self.assertTrue(all(s == 0.0 for _, s in ranked))

    def test_deterministic_tie_break_by_id(self) -> None:
        docs = [Doc("b", "标题", "相同正文"), Doc("a", "标题", "相同正文")]
        index = BM25Index(docs)
        ranked = index.score("标题")
        self.assertEqual([d for d, _ in ranked], ["a", "b"])


class MetricTest(unittest.TestCase):
    def test_rank_metrics(self) -> None:
        m = rank_metrics(["x", "a", "b", "c"], {"a", "b"}, k=3)
        self.assertEqual(m["hit@k"], 1)
        self.assertAlmostEqual(m["recall@k"], 2 / 2)
        self.assertAlmostEqual(m["reciprocal_rank"], 1 / 2)
        self.assertEqual(m["first_relevant_rank"], 2)

    def test_miss(self) -> None:
        m = rank_metrics(["x", "y"], {"a"}, k=2)
        self.assertEqual(m["hit@k"], 0)
        self.assertEqual(m["recall@k"], 0.0)
        self.assertEqual(m["reciprocal_rank"], 0.0)
        self.assertIsNone(m["first_relevant_rank"])


class DatasetTest(unittest.TestCase):
    def test_real_dataset_validates(self) -> None:
        docs = load_corpus(REPO_RAG / "data" / "corpus")
        queries = load_queries(REPO_RAG / "data" / "queries.jsonl")
        validate_dataset(docs, queries)
        self.assertGreaterEqual(len(docs), 10)
        self.assertGreaterEqual(len(queries), 8)
        cats = {q.category for q in queries}
        self.assertTrue(cats <= {"normal", "paraphrase", "confusable", "unanswerable"})
        self.assertIn("unanswerable", cats)

    def test_unknown_doc_id_rejected(self) -> None:
        docs = [Doc("d1", "t", "b")]
        queries = [Query("q1", "文本", "normal", ["nope"], "理由")]
        with self.assertRaises(ValidationError):
            validate_dataset(docs, queries)

    def test_unanswerable_with_label_rejected(self) -> None:
        docs = [Doc("d1", "t", "b")]
        queries = [Query("q1", "文本", "unanswerable", ["d1"], "理由")]
        with self.assertRaises(ValidationError):
            validate_dataset(docs, queries)

    def test_answerable_without_label_rejected(self) -> None:
        docs = [Doc("d1", "t", "b")]
        queries = [Query("q1", "文本", "normal", [], "理由")]
        with self.assertRaises(ValidationError):
            validate_dataset(docs, queries)

    def test_missing_rationale_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "queries.jsonl"
            path.write_text(
                json.dumps(
                    {"id": "q1", "text": "文本", "category": "normal",
                     "relevant": ["d1"], "rationale": " "},
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValidationError):
                load_queries(path)


class EndToEndDeterminismTest(unittest.TestCase):
    def test_run_twice_identical_bytes(self) -> None:
        import subprocess
        import sys

        with tempfile.TemporaryDirectory() as tmp1, tempfile.TemporaryDirectory() as tmp2:
            outs = []
            for tmp in (tmp1, tmp2):
                out_rel = Path(tmp).name
                proc = subprocess.run(
                    [sys.executable, str(REPO_RAG / "run.py"), "--out", "results/_tmp_" + out_rel],
                    capture_output=True,
                    text=True,
                    cwd=REPO_RAG,
                    timeout=120,
                )
                self.assertEqual(proc.returncode, 0, proc.stderr)
                outs.append("results/_tmp_" + out_rel)
            for name in ("per_query.jsonl", "metrics.json"):
                a = (REPO_RAG / outs[0] / name).read_bytes()
                b = (REPO_RAG / outs[1] / name).read_bytes()
                self.assertEqual(a, b, f"{name} 两次运行不一致")
        for out in outs:
            import shutil

            shutil.rmtree(REPO_RAG / out, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
