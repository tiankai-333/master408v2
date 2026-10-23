"""M4 语义向量路：本地 sentence-transformers 模型编码 + 精确余弦检索。

安全边界：
- 仅加载本地 HF 缓存（HF_HUB_OFFLINE/TRANSFORMERS_OFFLINE 强制为 1），
  SentenceTransformer 显式 local_files_only=True / trust_remote_code=False，
  权重为 safetensors 格式（不执行远程仓库脚本、不走不安全 pickle），不新下载；
- 缓存清单绑定：模型 repo/revision/**本地实际文件哈希**（权重/tokenizer/pooling
  配置）、语料指纹、行序、**查询文本哈希**（同 ID 改文本拒旧缓存）、编码参数
  （实际 max_seq_length/归一化/截断计数）；任一错配拒绝加载；
- 向量矩阵核验：哈希、行列与清单一致、全部有限值，np.load(allow_pickle=False)。
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import numpy as np

MODEL_REPO = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MODEL_REVISION = "e8f8c211226b894fcb81acc59f3b34ba3efd5f42"
MAX_LENGTH = 128


class CacheMismatchError(RuntimeError):
    """向量缓存与当前语料/模型/行序/查询不一致。"""


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def corpus_fingerprint(docs) -> str:
    """语料指纹：doc_id 顺序 + 每文档内容哈希 绑定行序。"""
    h = hashlib.sha256()
    for d in docs:
        h.update(d.doc_id.encode("utf-8"))
        h.update(sha256_text(d.text).encode("utf-8"))
    return h.hexdigest()


def set_offline() -> None:
    """强制离线：显式置 1，防止原环境取值 0 覆盖。"""
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"


def local_model_info() -> tuple[str, dict[str, str], int]:
    """解析本地缓存模型目录：实际文件哈希 + sentence_bert_config 的 max_seq_length。

    返回 (本地目录, {相对文件名: sha256}, max_seq_length)；离线解析，不联网，
    不加载权重即可供缓存校验使用。
    """
    set_offline()
    from huggingface_hub import snapshot_download

    snapshot = snapshot_download(MODEL_REPO, revision=MODEL_REVISION,
                                 local_files_only=True)
    root = Path(snapshot)
    files = {}
    for f in sorted(root.rglob("*")):
        if f.is_file() and not f.name.startswith("."):
            files[f.relative_to(root).as_posix()] = sha256_file(f)
    sbert_max_seq = MAX_LENGTH
    cfg = root / "sentence_bert_config.json"
    if cfg.exists():
        try:
            sbert_max_seq = int(json.loads(cfg.read_text(encoding="utf-8"))["max_seq_length"])
        except Exception:
            sbert_max_seq = MAX_LENGTH
    return str(root), files, sbert_max_seq


def _finite(matrix: np.ndarray) -> bool:
    return bool(np.isfinite(matrix).all())


class SemanticIndex:
    """实际模型编码的文档向量矩阵；行序与 docs 严格一致。"""

    def __init__(self, docs, model_dir: str | None = None,
                 model_files: dict[str, str] | None = None,
                 embed_seconds: float | None = None):
        set_offline()
        from sentence_transformers import SentenceTransformer

        # 先固定身份再加载（修 self.model_revision 先用后赋值）
        self.model_repo = MODEL_REPO
        self.model_revision = MODEL_REVISION
        self.model_dir = model_dir
        self.model_files = model_files or {}
        self.max_length = MAX_LENGTH
        self.normalize = True
        if model_dir:
            set_offline()
        load_path = model_dir or f"{MODEL_REPO}"
        self.model = SentenceTransformer(
            load_path, revision=None if model_dir else self.model_revision,
            device="cpu", local_files_only=True, trust_remote_code=False,
            model_kwargs={"use_safetensors": True})
        # 实际模型参数（加载后读取，写入缓存清单供严格匹配）
        self.actual_max_seq_length = int(self.model.max_seq_length)
        texts = [d.text for d in docs]
        n_truncated = sum(
            1 for t in texts
            if len(self.model.tokenizer(t, truncation=False)["input_ids"]) > MAX_LENGTH
        )
        t0 = _now()
        self.matrix = self.model.encode(
            texts, batch_size=16, normalize_embeddings=self.normalize,
            convert_to_numpy=True, show_progress_bar=False)
        self.embed_seconds = embed_seconds if embed_seconds is not None \
            else _now() - t0
        self.row_ids = [d.doc_id for d in docs]
        self.fingerprint = corpus_fingerprint(docs)
        self.truncated = n_truncated
        self.encode_batch_size = 16
        if not _finite(self.matrix):
            raise CacheMismatchError("模型编码出非有限值向量")

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
                "pooling": "model-default mean pooling (1_Pooling)",
            },
            "matrix_rows": int(self.matrix.shape[0]),
            "matrix_cols": int(self.matrix.shape[1]),
            "matrix_shape_dtype": f"{self.matrix.shape} fp32",
            "row_fingerprint": self.fingerprint,
            "row_ids_head": self.row_ids[:3],
            "corpus_files": len(corpus_sha_by_file),
        }

    def search(self, query: str, topk: int) -> list[tuple[str, float]]:
        q = self.model.encode([query], normalize_embeddings=self.normalize,
                              convert_to_numpy=True, show_progress_bar=False)[0]
        scores = self.matrix @ q  # 归一化后内积=余弦，精确全量
        order = sorted(range(len(scores)), key=lambda i: (-scores[i], self.row_ids[i]))
        return [(self.row_ids[i], float(scores[i])) for i in order[:topk]]

    def encode_queries(self, queries: list[str]) -> np.ndarray:
        return self.model.encode(queries, normalize_embeddings=self.normalize,
                                 convert_to_numpy=True, show_progress_bar=False)


def save_cache(index, out_dir: Path, corpus_sha_by_file: dict,
               query_matrix: np.ndarray, query_ids: list[str],
               query_texts: list[str]) -> None:
    """向量缓存 + 清单。query_sha256 绑定**查询文本**（同 ID 改文本拒旧缓存）；
    model_files_sha256 绑定本地实际模型文件；向量矩阵哈希入清单。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    np.save(out_dir / "doc_matrix.npy", index.matrix)
    np.save(out_dir / "query_matrix.npy", query_matrix)
    manifest = index.manifest(corpus_sha_by_file)
    manifest.update({
        "query_ids": query_ids,
        "query_texts_sha256": [sha256_text(t) for t in query_texts],
        "row_order": index.row_ids,
        # 对实际 .npy 文件字节求哈希（含 npy header），与 load_matrix 校验口径一致
        "doc_matrix_sha256": sha256_file(out_dir / "doc_matrix.npy"),
        "query_matrix_sha256": sha256_file(out_dir / "query_matrix.npy"),
    })
    (out_dir / "vector-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")


def load_matrix(path: Path, expect_sha: str, rows: int, cols: int) -> np.ndarray:
    """带核验的向量加载：哈希、形状、有限值；allow_pickle=False。"""
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expect_sha:
        raise CacheMismatchError(f"{path.name}: 文件哈希与清单不符（静默损坏？）")
    m = np.load(path, allow_pickle=False)
    if m.shape != (rows, cols):
        raise CacheMismatchError(f"{path.name}: 形状 {m.shape} ≠ 清单 ({rows}, {cols})")
    if not _finite(m):
        raise CacheMismatchError(f"{path.name}: 含非有限值（NaN/Inf）")
    return m


def load_cache(cache_dir: Path, docs, query_ids: list[str], query_texts: list[str],
               expect_model_files: dict[str, str], actual_max_seq_length: int,
               expect_repo: str = MODEL_REPO,
               expect_revision: str = MODEL_REVISION) -> tuple[np.ndarray, np.ndarray]:
    """加载向量缓存并校验：模型 repo/revision/**本地文件哈希**/语料指纹/行序/
    **查询文本哈希**/矩阵哈希-形状-有限值，任一错配即拒绝。"""
    manifest = json.loads((cache_dir / "vector-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("model_repo") != expect_repo or \
            manifest.get("model_revision") != expect_revision:
        raise CacheMismatchError(
            f"model mismatch: {manifest.get('model_repo')}@"
            f"{manifest.get('model_revision')}")
    actual_files = manifest.get("model_files_sha256")
    if actual_files is None:
        raise CacheMismatchError("vector manifest 缺 model_files_sha256（拒绝猜测）")
    if set(actual_files) != set(expect_model_files):
        only_cache = sorted(set(actual_files) - set(expect_model_files))
        only_local = sorted(set(expect_model_files) - set(actual_files))
        raise CacheMismatchError(
            f"model file set mismatch: cache-only={only_cache} local-only={only_local}")
    for name, digest in expect_model_files.items():
        if actual_files[name] != digest:
            raise CacheMismatchError(
                f"model file mismatch: {name}（本地模型文件与缓存清单不一致）")
    fp = corpus_fingerprint(docs)
    if manifest.get("row_fingerprint") != fp:
        raise CacheMismatchError("corpus fingerprint mismatch（语料或行序已变更）")
    if manifest.get("row_order") != [d.doc_id for d in docs]:
        raise CacheMismatchError("row order mismatch（行序与 doc_id 不一致）")
    if manifest.get("query_ids") != query_ids:
        raise CacheMismatchError("query id mapping mismatch（查询映射已变更）")
    expect_text_sha = [sha256_text(t) for t in query_texts]
    if manifest.get("query_texts_sha256") != expect_text_sha:
        raise CacheMismatchError("query text mismatch（同 ID 查询文本已变更，拒用旧向量）")
    encode = manifest.get("encode_params") or {}
    if encode.get("normalize") is not True or encode.get("max_length") != MAX_LENGTH:
        raise CacheMismatchError("encode params mismatch（归一化/截断口径变更）")
    if encode.get("actual_max_seq_length") != actual_max_seq_length:
        raise CacheMismatchError(
            f"actual_max_seq_length mismatch: cache={encode.get('actual_max_seq_length')} "
            f"current={actual_max_seq_length}")
    for key in ("doc_matrix_sha256", "query_matrix_sha256"):
        if not manifest.get(key):
            raise CacheMismatchError(f"vector manifest 缺 {key}（拒绝无哈希向量）")
    doc_matrix = load_matrix(cache_dir / "doc_matrix.npy",
                             manifest["doc_matrix_sha256"], len(docs),
                             int(manifest["matrix_cols"]))
    query_matrix = load_matrix(cache_dir / "query_matrix.npy",
                               manifest["query_matrix_sha256"], len(query_ids),
                               doc_matrix.shape[1])
    return doc_matrix, query_matrix


def _now() -> float:
    import time
    return time.perf_counter()
