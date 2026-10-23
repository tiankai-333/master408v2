"""M4 三路检索对照 runner：BM25 / 语义向量 / RRF 混合（固定 K=5、RRF 60 等权）。

复用 M3 冻结输入（corpus/queries/manifest）与 harness 指标设施；不重造引擎。
指标口径（三路统一）：Hit@5 / Recall@5 / MRR@5 只看各路 Top5（未召回=0），
不拼接其他检索路排名补尾；语义路另记自身全排名的 full MRR（可另记不补尾）。
输出（私有 data-private/m4/）：三路 per_query（rank/score/fusion 可复算）+ metrics
+ 模型/向量清单 + 分段计时。
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
    verify_against_frozen,
)
from m4_semantic import (
    SemanticIndex,
    local_model_info,
    save_cache,
    load_cache,
    CacheMismatchError,
)

RRF_K = 60


def rrf_fuse(bm25_topk: list[str], sem_topk: list[str], topk: int) -> list[dict]:
    """RRF 常数 60、两路等权：score(d)=Σ 1/(60+rank_i(d))；并列按 doc_id 升序。"""
    rrf = {}
    sources: dict[str, dict] = {}
    for rank, d in enumerate(bm25_topk, 1):
        rrf[d] = rrf.get(d, 0.0) + 1.0 / (RRF_K + rank)
        sources.setdefault(d, {})["bm25_rank"] = rank
    for rank, d in enumerate(sem_topk, 1):
        rrf[d] = rrf.get(d, 0.0) + 1.0 / (RRF_K + rank)
        sources.setdefault(d, {})["sem_rank"] = rank
    order = sorted(rrf, key=lambda d: (-rrf[d], d))
    return [{"doc_id": d, "rrf": round(rrf[d], 6), "sources": sources[d]}
            for d in order[:topk]]


def top5_metrics(topk_ranking: list[str], relevant: set[str]) -> dict:
    """只看 Top5 的指标：未召回=0（不拼接其他路排名补尾）。"""
    m = rank_metrics(topk_ranking, relevant, len(topk_ranking) or 5)
    return {"hit@5": m["hit@k"], "recall@5": m["recall@k"],
            "mrr@5": m["reciprocal_rank"],
            "first_relevant_rank_in_top5": m["first_relevant_rank"]}


def compute_routes(docs, queries, bm25_index: BM25Index,
                   doc_matrix: np.ndarray, query_matrix: np.ndarray,
                   topk: int) -> dict:
    """三路对照纯计算（可测：矩阵可注入，不依赖真实模型）。"""
    doc_ids = [d.doc_id for d in docs]
    id_by_pos = doc_ids
    rows = {"bm25": [], "semantic": [], "hybrid": []}
    metrics = {name: {"hit@5": 0, "recall@5": 0.0, "mrr@5": 0.0,
                      "full_mrr": None} for name in rows}
    full_mrr_sums = {"bm25": 0.0, "semantic": 0.0}
    answerable = 0
    for qi, q in enumerate(queries):
        relevant = set(q.relevant)
        # BM25 全排名（自身 full MRR 用）
        bm25_scores = bm25_index.score(q.text)
        bm25_full = [d for d, _ in bm25_scores]
        bm25_topk = bm25_full[:topk]
        bm25_top_scores = {d: s for d, s in bm25_scores[:topk]}
        # 语义全排名（自身 full MRR 用）与 Top5
        sem_scores_all = doc_matrix @ query_matrix[qi]
        sem_order = sorted(range(len(sem_scores_all)),
                           key=lambda i: (-sem_scores_all[i], doc_ids[i]))
        sem_full = [doc_ids[i] for i in sem_order]
        sem_topk = sem_full[:topk]
        sem_top_scores = {doc_ids[i]: float(sem_scores_all[i]) for i in sem_order[:topk]}
        # 混合：固定两路 Top5 候选融合
        fusion = rrf_fuse(bm25_topk, sem_topk, topk)
        hybrid_topk = [f["doc_id"] for f in fusion]

        answerable += 1 if q.category != "unanswerable" else 0
        for name, topk_ranking, full_ranking, extra in (
                ("bm25", bm25_topk, bm25_full, None),
                ("semantic", sem_topk, sem_full, None),
                ("hybrid", hybrid_topk, None,
                 {"fusion_detail": fusion[:3]})):
            if q.category == "unanswerable":
                m5 = {"hit@5": None, "recall@5": None, "mrr@5": None,
                      "first_relevant_rank_in_top5": None}
            else:
                m5 = top5_metrics(topk_ranking, relevant)
                metrics[name]["hit@5"] += m5["hit@5"]
                metrics[name]["recall@5"] += m5["recall@5"]
                metrics[name]["mrr@5"] += m5["mrr@5"]
            row = {"qid": q.qid, "category": q.category,
                   "ranking_topk": topk_ranking, **m5}
            if extra:
                row.update(extra)
            rows[name].append(row)
        if q.category != "unanswerable":
            for name, full in (("bm25", bm25_full), ("semantic", sem_full)):
                rr = 0.0
                for r, d in enumerate(full, 1):
                    if d in relevant:
                        rr = 1.0 / r
                        break
                full_mrr_sums[name] += rr

    for name in metrics:
        n = answerable
        metrics[name] = {
            "route": name, "topk": topk, "n_answerable": n,
            "denominator": f"{n} 条可作答（unanswerable 不入排名分母）；"
                           "MRR@5 只看本路 Top5，未召回=0，不拼接其他路排名",
            "hit@5": metrics[name]["hit@5"] / n if n else None,
            "mean_recall@5": metrics[name]["recall@5"] / n if n else None,
            "mrr@5": metrics[name]["mrr@5"] / n if n else None,
            "full_mrr": (full_mrr_sums[name] / n if n else None)
            if name != "hybrid" else None,
        }
    return {"rows": rows, "metrics": metrics}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="M4 三路检索对照（BM25/语义/RRF）")
    ap.add_argument("--corpus-dir", required=True)
    ap.add_argument("--queries", required=True)
    ap.add_argument("--verify-manifest", required=True,
                    help="M3 冻结 manifest.json（全量校验语料/查询/参数）")
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

    # 全量冻结校验：语料逐文件哈希/文件集合/queries 哈希/参数（含 topk）。
    # 任一不符 exit 3 拒绝运行且不写任何输出。
    frozen = json.loads(Path(args.verify_manifest).read_text(encoding="utf-8"))
    problems = verify_against_frozen(corpus_dir, queries_path, frozen, args.topk)
    if problems:
        print("[VERIFY-FAIL] 实际输入与冻结 manifest 不符，拒绝运行：", file=sys.stderr)
        for p_ in problems:
            print("  -", p_, file=sys.stderr)
        return 3

    # 路 1：BM25（沿 M3 参数）
    t0 = time.perf_counter()
    bm25_index = BM25Index(docs)
    bm25_build = time.perf_counter() - t0

    # 路 2：语义向量（实际模型；缓存错配拒绝；绑定本地实际模型文件与查询文本）
    query_ids = [q.qid for q in queries]
    query_texts = [q.text for q in queries]
    model_dir, model_files, sbert_max_seq = local_model_info()
    cache_dir = Path(args.cache_dir)
    sem_build = sem_encode_q = None
    semantic = None
    if not args.rebuild_cache and (cache_dir / "vector-manifest.json").exists():
        try:
            doc_matrix, query_matrix = load_cache(
                cache_dir, docs, query_ids, query_texts, model_files,
                actual_max_seq_length=sbert_max_seq)
            print("[cache] 向量缓存校验通过（模型文件/语料指纹/行序/查询文本/"
                  "max_seq_length）")
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
        if semantic.actual_max_seq_length != sbert_max_seq:
            print("[warn] 模型实际 max_seq_length 与 sentence_bert_config 不一致："
                  f"{semantic.actual_max_seq_length} vs {sbert_max_seq}", file=sys.stderr)

    if semantic is not None:
        model_manifest = semantic.manifest({
            f.name: __import__("hashlib").sha256(f.read_bytes()).hexdigest()
            for f in sorted(corpus_dir.glob("*.md"))
        })
    else:
        # 缓存命中：模型清单取自向量缓存清单（内容在 load_cache 已严格校验）
        vm = json.loads((cache_dir / "vector-manifest.json").read_text(encoding="utf-8"))
        model_manifest = {k: v for k, v in vm.items()
                          if k not in ("query_ids", "query_texts_sha256",
                                       "row_order", "query_matrix_sha256",
                                       "doc_matrix_sha256")}

    result = compute_routes(docs, queries, bm25_index, doc_matrix, query_matrix,
                            args.topk)
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
    for name, rws in result["rows"].items():
        (out / f"per_query-{name}.jsonl").write_text(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rws),
            encoding="utf-8")
        metrics = result["metrics"][name]
        (out / f"metrics-{name}.json").write_text(
            json.dumps(metrics, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  {name}: hit@5={metrics['hit@5']} mrr@5={metrics['mrr@5']} "
              + (f"full_mrr={round(metrics['full_mrr'], 4)}" if metrics["full_mrr"] is not None else ""))
    print("[OK] 三路对照输出写入", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
