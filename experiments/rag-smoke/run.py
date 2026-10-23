"""离线评测入口：python run.py [--topk 5] [--out results/<name>]

确定性运行：相同输入与参数产出逐字节相同的结果文件（不含本文件记录的运行时间戳）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from rag_smoke import __version__
from rag_smoke.harness import (
    BM25Index,
    build_manifest,
    load_corpus,
    load_queries,
    rank_metrics,
    sha256_file,
    validate_dataset,
)

ROOT = Path(__file__).resolve().parent


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="rag-smoke 离线词法评测")
    parser.add_argument("--topk", type=int, default=5, help="排名指标截断 K（默认 5）")
    parser.add_argument("--out", default="results/run-001", help="结果输出目录")
    args = parser.parse_args(argv)

    corpus_dir = ROOT / "data" / "corpus"
    queries_path = ROOT / "data" / "queries.jsonl"

    docs = load_corpus(corpus_dir)
    queries = load_queries(queries_path)
    validate_dataset(docs, queries)
    print(f"[load] 语料 {len(docs)} 篇，查询 {len(queries)} 条，校验通过")

    index = BM25Index(docs)

    per_query: list[dict] = []
    for q in queries:
        scores = index.score(q.text)
        ranked = [doc_id for doc_id, _ in scores]
        row: dict = {
            "qid": q.qid,
            "category": q.category,
            "query_text": q.text,
            "relevant": q.relevant,
            "rationale": q.rationale,
            "ranking": [
                {"rank": r, "doc_id": d, "bm25": round(s, 6)}
                for r, (d, s) in enumerate(scores[: args.topk], 1)
            ],
        }
        if q.category == "unanswerable":
            row["top1_score"] = round(scores[0][1], 6) if scores else None
        else:
            row["metrics"] = rank_metrics(ranked, set(q.relevant), args.topk)
        per_query.append(row)

    answerable = [r for r in per_query if r["category"] != "unanswerable"]
    unanswerable = [r for r in per_query if r["category"] == "unanswerable"]
    n = len(answerable)
    k = args.topk
    summary = {
        "n_queries": len(queries),
        "n_answerable": n,
        "n_unanswerable": len(unanswerable),
        "denominators": {
            "hit/mrr": f"{n} 条可作答查询",
            "recall": f"逐条分母为该查询标注的相关文档数（合计 "
            f"{sum(len(set(r['relevant'])) for r in answerable)}）",
            "k": k,
        },
        "hit@k": sum(r["metrics"]["hit@k"] for r in answerable) / n if n else None,
        "mean_recall@k": (
            sum(r["metrics"]["recall@k"] for r in answerable) / n if n else None
        ),
        "mrr": sum(r["metrics"]["reciprocal_rank"] for r in answerable) / n if n else None,
        "by_category": {
            cat: {
                "n": sum(1 for r in answerable if r["category"] == cat),
                "hit@k": (
                    sum(
                        r["metrics"]["hit@k"]
                        for r in answerable
                        if r["category"] == cat
                    )
                    / sum(1 for r in answerable if r["category"] == cat)
                    if any(r["category"] == cat for r in answerable)
                    else None
                ),
            }
            for cat in ("normal", "paraphrase", "confusable")
        },
        "unanswerable_top1_scores": {
            r["qid"]: r["top1_score"] for r in unanswerable
        },
    }

    out_dir = ROOT / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = build_manifest(ROOT, k)
    manifest["source_sha256"] = {
        p.relative_to(ROOT).as_posix(): sha256_file(p)
        for p in sorted((ROOT / "rag_smoke" / "harness.py", ROOT / "run.py"))
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (out_dir / "per_query.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in per_query),
        encoding="utf-8",
    )
    (out_dir / "metrics.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"[done] 结果写入 {out_dir}")
    print(f"[version] harness {__version__}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
