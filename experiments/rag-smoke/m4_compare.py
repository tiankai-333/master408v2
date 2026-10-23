"""M4 三路检索对照 runner：BM25 / 语义向量 / RRF 混合（固定 K=5、RRF 60 等权）。

复用 M3 冻结输入（corpus/queries/manifest）与 harness 指标设施；不重造引擎。
输出（私有 data-private/m4/）：三路 per_query+metrics + 模型/向量清单 + 分段计时。
不要求混合必胜；逐条保留候选/排名/分数与融合依据。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

from rag_smoke.harness import (
    BM25Index,
    load_corpus,
    load_queries,
    rank_metrics,
    validate_dataset,
)
from m4_semantic import (
    SemanticIndex,
    local_model_files,
    save_cache,
    load_cache,
    CacheMismatchError,
)

RRF_K = 60


def rrf_fuse(bm25_ranking: list[str], sem_ranking: list[str],
             bm25_scores: dict[str, float], sem_scores: dict[str, float],
             topk: int) -> list[dict]:
    """RRF 常数 60、两路等权：score(d)=Σ 1/(60+rank_i(d))；并列按 doc_id 升序。"""
    rrf = {}
    sources = {}
    for rank, d in enumerate(bm25_ranking, 1):
        rrf[d] = rrf.get(d, 0.0) + 1.0 / (RRF_K + rank)
        sources.setdefault(d, {})["bm25_rank"] = rank
        sources[d]["bm25_score"] = bm25_scores.get(d)
    for rank, d in enumerate(sem_ranking, 1):
        rrf[d] = rrf.get(d, 0.0) + 1.0 / (RRF_K + rank)
        sources.setdefault(d, {})["sem_rank"] = rank
        sources[d]["sem_score"] = sem_scores.get(d)
    order = sorted(rrf, key=lambda d: (-rrf[d], d))
    return [{"doc_id": d, "rrf": round(rrf[d], 6),
             "sources": {k: sources[d][k] for k in sorted(sources[d])}}
            for d in order[:topk]]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="M4 三路检索对照（BM25/语义/RRF）")
    ap.add_argument("--corpus-dir", required=True)
    ap.add_argument("--queries", required=True)
    ap.add_argument("--verify-manifest", required=True, help="M3 冻结 manifest.json")
    ap.add_argument("--cache-dir", required=True, help="向量缓存目录（私有）")
    ap.add_argument("--out", required=True, help="输出目录（私有）")
    ap.add_argument("--topk", type=int, default=5)
    ap.add_argument("--rebuild-cache", action="store_true",
                    help="忽略既有向量缓存，实际模型重新编码")
    args = ap.parse_args(argv)
    if args.topk < 1:
        print("--topk 必须为正整数", file=sys.stderr)
        return 2

    corpus_dir = Path(args.corpus_dir)
    queries_path = Path(args.queries)
    docs = load_corpus(corpus_dir)
    queries = load_queries(queries_path)
    validate_dataset(docs, queries)
    frozen = json.loads(Path(args.verify_manifest).read_text(encoding="utf-8"))
    if frozen.get("params", {}).get("topk") != args.topk:
        print(f"--topk {args.topk} 与冻结 {frozen['params']['topk']} 不符", file=sys.stderr)
        return 2

    # 路 1：BM25（沿 M3 参数）
    t0 = time.perf_counter()
    index = BM25Index(docs)
    bm25_build = time.perf_counter() - t0

    # 路 2：语义向量（实际模型；缓存错配拒绝；绑定本地实际模型文件哈希与查询文本）
    query_ids = [q.qid for q in queries]
    query_texts = [q.text for q in queries]
    model_dir, model_files = local_model_files()
    cache_dir = Path(args.cache_dir)
    sem_build = sem_encode_q = None
    semantic = None
    if not args.rebuild_cache and (cache_dir / "vector-manifest.json").exists():
        try:
            doc_matrix, query_matrix = load_cache(
                cache_dir, docs, query_ids, query_texts, model_files)
            print("[cache] 向量缓存校验通过（模型文件/语料指纹/行序/查询文本）")
        except CacheMismatchError as e:
            print(f"[cache-mismatch] {e} → 实际模型重新编码", file=sys.stderr)
            doc_matrix = query_matrix = None
    else:
        doc_matrix = query_matrix = None
    if doc_matrix is None:
        t0 = time.perf_counter()
        semantic = SemanticIndex(docs, model_dir=model_dir, model_files=model_files)
        sem_build = time.perf_counter() - t0
        t0 = time.perf_counter()
        query_matrix = semantic.encode_queries(query_texts)
        sem_encode_q = time.perf_counter() - t0
        doc_matrix = semantic.matrix
        save_cache(semantic, cache_dir, {
            f.name: __import__("hashlib").sha256(f.read_bytes()).hexdigest()
            for f in sorted(corpus_dir.glob("*.md"))
        }, query_matrix, query_ids, query_texts)

    # 模型清单（实际模型信息；缓存命中时来自向量清单）
    if semantic is not None:
        model_manifest = semantic.manifest({
            f.name: __import__("hashlib").sha256(f.read_bytes()).hexdigest()
            for f in sorted(corpus_dir.glob("*.md"))
        })
    else:
        model_manifest = json.loads(
            (cache_dir / "vector-manifest.json").read_text(encoding="utf-8"))
        model_manifest.pop("query_ids", None)
        model_manifest.pop("row_order", None)
        model_manifest.pop("query_sha256", None)

    # 三路逐 query
    t0 = time.perf_counter()
    rows = {"bm25": [], "semantic": [], "hybrid": []}
    for qi, q in enumerate(queries):
        bm25_scores = index.score(q.text)
        bm25_ranking = [d for d, _ in bm25_scores[:args.topk]]
        bm25_all = [d for d, _ in bm25_scores]
        sem_vec = query_matrix[qi]
        sem_scores_all = doc_matrix @ sem_vec
        sem_order = sorted(range(len(sem_scores_all)),
                           key=lambda i: (-sem_scores_all[i], docs[i].doc_id))
        sem_ranking = [docs[i].doc_id for i in sem_order[:args.topk]]
        sem_scores = {docs[i].doc_id: float(sem_scores_all[i]) for i in sem_order}

        bm25_metrics = rank_metrics(bm25_all, set(q.relevant), args.topk)
        sem_metrics = rank_metrics(sem_ranking + [d for d in bm25_all
                                                   if d not in sem_ranking],
                                   set(q.relevant), args.topk)
        hybrid = rrf_fuse(bm25_ranking, sem_ranking, dict(bm25_scores), sem_scores,
                          args.topk)
        hybrid_ranking = [h["doc_id"] for h in hybrid]
        hyb_metrics = rank_metrics(hybrid_ranking + [d for d in bm25_all
                                                     if d not in hybrid_ranking],
                                   set(q.relevant), args.topk)
        for name, m, ranking in (("bm25", bm25_metrics, bm25_ranking),
                                 ("semantic", sem_metrics, sem_ranking),
                                 ("hybrid", hyb_metrics, hybrid_ranking)):
            rows[name].append({
                "qid": q.qid, "category": q.category,
                "ranking_topk": ranking,
                "first_relevant_rank": m["first_relevant_rank"],
                "hit@k": m["hit@k"], "recall@k": m["recall@k"],
                "reciprocal_rank": m["reciprocal_rank"],
            })
        rows["hybrid"][-1]["fusion_detail"] = hybrid[:3]
    search_seconds = time.perf_counter() - t0

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "model-manifest.json").write_text(
        json.dumps(model_manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "timing.json").write_text(json.dumps({
        "bm25_build_index_seconds": round(bm25_build, 4),
        "doc_embedding_seconds": round(sem_build, 4) if sem_build is not None else None,
        "query_embedding_seconds": round(sem_encode_q, 4) if sem_encode_q is not None else None,
        "search_and_fuse_seconds": round(search_seconds, 4),
        "scope": ("分段计时：BM25建索引/离线文档embedding/query编码/检索+RRF融合；"
                  "不含模型加载与进程启动；缓存命中时 doc_embedding=null；"
                  "非端到端延迟，非线上SLA"),
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    for name, rws in rows.items():
        (out / f"per_query-{name}.jsonl").write_text(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rws),
            encoding="utf-8")
        answerable = [r for r in rws if r["category"] != "unanswerable"]
        n = len(answerable)
        metrics = {
            "route": name, "topk": args.topk, "n_answerable": n,
            "denominator": f"{n} 条可作答（unanswerable 不入排名分母）",
            "hit@5": sum(r["hit@k"] for r in answerable) / n if n else None,
            "mean_recall@5": sum(r["recall@k"] for r in answerable) / n if n else None,
            "mrr": sum(r["reciprocal_rank"] for r in answerable) / n if n else None,
        }
        (out / f"metrics-{name}.json").write_text(
            json.dumps(metrics, ensure_ascii=False, indent=1), encoding="utf-8")
    print("[OK] 三路对照输出写入", out)
    for name in rows:
        m = json.loads((out / f"metrics-{name}.json").read_text(encoding="utf-8"))
        print(f"  {name}: hit@5={m['hit@5']} mrr={m['mrr']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
