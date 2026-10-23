"""离线词法检索评测 harness。

范围（本交付块）：
- 读取并校验合成语料（data/corpus/*.md）与查询/相关性标签（data/queries.jsonl）；
- BM25 词法检索（确定性：并列分数按文档 id 升序）；
- 逐条输出 rank，按类别汇总 Hit@K / Recall@K / MRR，分母显式写出；
- 无答案查询不进入排名指标分母，单独报告其 top-1 分数。

不实现向量/混合检索、重排和真实模型调用；BM25 不做无证据阈值判断，
无答案查询也会得到分数——这是词法基线的已知限制，阈值留待接入真实环境再定。
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

CATEGORIES = {"normal", "paraphrase", "confusable", "unanswerable"}

# BM25 参数固定并写入 manifest，保证可复现。
BM25_K1 = 1.5
BM25_B = 0.75


class ValidationError(Exception):
    """数据集或标签不满足标注口径。"""


# ---------------------------------------------------------------- tokenizer

_CJK_RUN = re.compile(r"[一-鿿]+")
_ASCII_RUN = re.compile(r"[A-Za-z0-9]+")


def tokenize(text: str) -> list[str]:
    """中文按 2-gram、ASCII 按小写词切分。纯函数，保证可复现。

    中文单词没有空格边界，unigram 区分度太低，2-gram 是不引入
    分词依赖的最小可用口径；同一口径同时作用于语料与查询。
    """
    tokens: list[str] = []
    for run in _CJK_RUN.findall(text):
        if len(run) == 1:
            tokens.append(run)
        else:
            tokens.extend(run[i : i + 2] for i in range(len(run) - 1))
    tokens.extend(tok.lower() for tok in _ASCII_RUN.findall(text))
    return tokens


# ---------------------------------------------------------------- loading


@dataclass
class Doc:
    doc_id: str
    title: str
    body: str

    @property
    def text(self) -> str:
        # 标题拼两遍，让标题词与查询词的匹配略强于正文偶现词。
        return f"{self.title}\n{self.title}\n{self.body}"


@dataclass
class Query:
    qid: str
    text: str
    category: str
    relevant: list[str]
    rationale: str


def load_corpus(corpus_dir: Path) -> list[Doc]:
    docs: list[Doc] = []
    for path in sorted(corpus_dir.glob("*.md")):
        lines = path.read_text(encoding="utf-8").splitlines()
        if not lines or not lines[0].startswith("# "):
            raise ValidationError(f"{path.name}: 首行必须是 '# 标题'")
        docs.append(
            Doc(
                doc_id=path.stem,
                title=lines[0][2:].strip(),
                body="\n".join(lines[1:]).strip(),
            )
        )
    if not docs:
        raise ValidationError(f"{corpus_dir} 下没有 .md 语料")
    ids = [d.doc_id for d in docs]
    if len(set(ids)) != len(ids):
        raise ValidationError(f"文档 id 重复: {ids}")
    return docs


def load_queries(queries_path: Path) -> list[Query]:
    queries: list[Query] = []
    for lineno, line in enumerate(
        queries_path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        qid = row.get("id", f"<line {lineno}>")
        for key in ("text", "category", "relevant", "rationale"):
            if key not in row:
                raise ValidationError(f"{qid}: 缺少字段 {key}")
        if row["category"] not in CATEGORIES:
            raise ValidationError(f"{qid}: 未知类别 {row['category']!r}")
        if not str(row["text"]).strip():
            raise ValidationError(f"{qid}: 查询文本为空")
        if not str(row["rationale"]).strip():
            raise ValidationError(f"{qid}: 缺少人工标注理由")
        if not isinstance(row["relevant"], list) or not all(
            isinstance(x, str) for x in row["relevant"]
        ):
            raise ValidationError(f"{qid}: relevant 必须是文档 id 列表")
        queries.append(
            Query(
                qid=row["id"],
                text=row["text"],
                category=row["category"],
                relevant=row["relevant"],
                rationale=row["rationale"],
            )
        )
    if not queries:
        raise ValidationError(f"{queries_path} 没有查询")
    qids = [q.qid for q in queries]
    if len(set(qids)) != len(qids):
        raise ValidationError(f"查询 id 重复: {qids}")
    return queries


def validate_dataset(docs: list[Doc], queries: list[Query]) -> None:
    """标注口径：related id 必须存在；unanswerable 的 relevant 必须为空；
    其余类别必须至少标一个相关文档。"""
    doc_ids = {d.doc_id for d in docs}
    for q in queries:
        unknown = [r for r in q.relevant if r not in doc_ids]
        if unknown:
            raise ValidationError(f"{q.qid}: 标签指向不存在的文档 {unknown}")
        if q.category == "unanswerable":
            if q.relevant:
                raise ValidationError(f"{q.qid}: 无答案查询 relevant 必须为空")
        elif not q.relevant:
            raise ValidationError(f"{q.qid}: {q.category} 查询必须至少标一个相关文档")


# ---------------------------------------------------------------- bm25


@dataclass
class BM25Index:
    docs: list[Doc]
    k1: float = BM25_K1
    b: float = BM25_B
    _tf: list[dict[str, int]] = field(default_factory=list, init=False)
    _len: list[int] = field(default_factory=list, init=False)
    _df: dict[str, int] = field(default_factory=dict, init=False)
    _avgdl: float = field(default=0.0, init=False)

    def __post_init__(self) -> None:
        for doc in self.docs:
            tokens = tokenize(doc.text)
            tf: dict[str, int] = {}
            for tok in tokens:
                tf[tok] = tf.get(tok, 0) + 1
            self._tf.append(tf)
            self._len.append(len(tokens))
            for tok in tf:
                self._df[tok] = self._df.get(tok, 0) + 1
        self._avgdl = sum(self._len) / len(self.docs) if self.docs else 0.0

    def score(self, query: str) -> list[tuple[str, float]]:
        """返回全部文档的 (doc_id, bm25) 并按 (分数降序, id 升序) 排好。"""
        q_tokens = tokenize(query)
        scores: list[tuple[str, float]] = []
        n = len(self.docs)
        for i, doc in enumerate(self.docs):
            s = 0.0
            for tok in q_tokens:
                tf = self._tf[i].get(tok, 0)
                if tf == 0:
                    continue
                df = self._df[tok]
                idf = math.log((n - df + 0.5) / (df + 0.5) + 1.0)
                denom = tf + self.k1 * (1 - self.b + self.b * self._len[i] / self._avgdl)
                s += idf * tf * (self.k1 + 1) / denom
            scores.append((doc.doc_id, s))
        scores.sort(key=lambda x: (-x[1], x[0]))
        return scores


# ---------------------------------------------------------------- metrics


def rank_metrics(ranked_ids: list[str], relevant: set[str], k: int) -> dict:
    """单条查询指标。分母在调用方汇总时显式写出。"""
    topk = ranked_ids[:k]
    hits = [d for d in topk if d in relevant]
    rr = 0.0
    for rank, d in enumerate(ranked_ids, start=1):
        if d in relevant:
            rr = 1.0 / rank
            break
    return {
        "hit@k": 1 if hits else 0,
        "recall@k": len(hits) / len(relevant) if relevant else 0.0,
        "reciprocal_rank": rr,
        "first_relevant_rank": next(
            (r for r, d in enumerate(ranked_ids, 1) if d in relevant), None
        ),
    }


# ---------------------------------------------------------------- manifest


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_manifest(root: Path, topk: int) -> dict:
    corpus_dir = root / "data" / "corpus"
    files = [
        FileHash(f.relative_to(root).as_posix(), sha256_file(f))
        for f in sorted(corpus_dir.glob("*.md"))
    ]
    return {
        "harness_version": "0.1.0",
        "corpus": {p.relative_path: p.sha256 for p in files},
        "queries_sha256": sha256_file(root / "data" / "queries.jsonl"),
        "params": {"ranker": "bm25", "k1": BM25_K1, "b": BM25_B, "topk": topk,
                   "tokenizer": "cjk-bigram+ascii-word, title x2"},
    }


@dataclass
class FileHash:
    relative_path: str
    sha256: str
