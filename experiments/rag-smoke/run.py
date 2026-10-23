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
    verify_against_frozen,
)

ROOT = Path(__file__).resolve().parent


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="rag-smoke 离线词法评测")
    parser.add_argument("--topk", type=int, default=5, help="排名指标截断 K（默认 5，须为正整数）")
    parser.add_argument("--out", default="results/run-001", help="结果输出目录")
    parser.add_argument("--corpus-dir", default=None,
                        help="语料目录（默认 data/corpus；M3 真实快照传私有路径）")
    parser.add_argument("--queries", default=None,
                        help="查询 JSONL（默认 data/queries.jsonl）")
    parser.add_argument("--verify-manifest", default=None,
                        help="冻结 manifest.json：校验实际语料/查询哈希与参数一致，"
                             "不符则拒绝运行（防篡改/错配照跑）")
    args = parser.parse_args(argv)
    if args.topk < 1:
        print(f"--topk 必须为正整数，得到 {args.topk}", file=sys.stderr)
        return 2

    corpus_dir = Path(args.corpus_dir) if args.corpus_dir else ROOT / "data" / "corpus"
    queries_path = Path(args.queries) if args.queries else ROOT / "data" / "queries.jsonl"

    docs = load_corpus(corpus_dir)
    queries = load_queries(queries_path)
    validate_dataset(docs, queries)
    print(f"[load] 语料 {len(docs)} 篇，查询 {len(queries)} 条，校验通过")

    # 冻结输入验证：提供 --verify-manifest 时，实际语料/查询哈希或参数与冻结
    # manifest 不符即拒绝运行（不写任何结果文件），防篡改/错配照跑
    if args.verify_manifest:
        frozen = json.loads(Path(args.verify_manifest).read_text(encoding="utf-8"))
        problems = verify_against_frozen(corpus_dir, queries_path, frozen, args.topk)
        if problems:
            print("[VERIFY-FAIL] 实际输入与冻结 manifest 不符，拒绝运行：", file=sys.stderr)
            for problem in problems:
                print("  -", problem, file=sys.stderr)
            return 3
        print(f"[verify] 冻结输入校验通过：{frozen.get('corpus_count')} 文件、"
              f"queries {frozen.get('queries_count')} 条")

    import time
    t0 = time.perf_counter()
    index = BM25Index(docs)
    build_seconds = time.perf_counter() - t0

    per_query: list[dict] = []
    query_seconds = 0.0
    for q in queries:
        t1 = time.perf_counter()
        scores = index.score(q.text)
        query_seconds += time.perf_counter() - t1
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
            for cat in sorted({r["category"] for r in answerable})
        },
        "unanswerable_top1_scores": {
            r["qid"]: r["top1_score"] for r in unanswerable
        },
    }

    out_dir = Path(args.out) if Path(args.out).is_absolute() else ROOT / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    # 运行 manifest 按**实际**语料/查询生成（不再假设合成固定路径）
    manifest = build_manifest(corpus_dir, queries_path, k)
    manifest["source_sha256"] = {
        p.relative_to(ROOT).as_posix(): sha256_file(p)
        for p in sorted((ROOT / "rag_smoke" / "harness.py", ROOT / "run.py"))
    }
    # 词法耗时独立计时文件（不进要求字节一致的指标）：明确仅含建索引与逐查询
    # 打分，不含 DB 导出/文件 IO/进程启动；无端到端延迟可从此外推
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "timing.json").write_text(json.dumps({
        "build_index_seconds": round(build_seconds, 4),
        "query_total_seconds": round(query_seconds, 4),
        "queries": len(queries),
        "scope": ("build_index=BM25索引构建；query=逐查询score求和；"
                  "不含DB导出/文件IO/进程启动，无端到端延迟"),
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "per_query.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in per_query),
        encoding="utf-8",
    )
    (out_dir / "metrics.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("[timing]", json.loads((out_dir / "timing.json").read_text(encoding="utf-8")))
    print(f"[done] 结果写入 {out_dir}")
    print(f"[version] harness {__version__}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
