"""真实 470 题候选库 → M3 固定私有语料快照导出（只读）。

来源：master408_candidate（M1 产物，只读 SELECT，不写业务库）。
输出（私有，勿入 Git）：
- <out>/corpus/{year}-q{no}.md   纯文本投影：标题行 + 题干/选项 + 解析；
- <out>/snapshot-export.json     逐题原始投影记录 + 投影缺损标注 + 源指纹。

文本投影规则（如实标注缺损，不虚构图义）：
- HTML 剥标签取文本；KaTeX 渲染层字符可能破碎（视觉层无语义空格），标注
  formula_render_loss=true；
- drawio/内联 SVG 图形与外链图无法文本化，标注 figure_text_missing=true，
  图题选项（options_inline）文本投影不完整；
- 解析为 legacy 纯文本段落（M1 补入）或 HTML 源（综合题），均剥标签取文本。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from bs4 import BeautifulSoup

import pymysql


def text_of(html_fragment: str | None) -> str:
    if not html_fragment:
        return ""
    return re.sub(r"\s+", " ", BeautifulSoup(html_fragment, "html.parser").get_text(" ")).strip()


def projection_flags(stem_html: str | None, assets_json: str | None) -> dict:
    flags = {
        "formula_render_loss": bool(stem_html and "katex" in stem_html),
        "figure_text_missing": False,
        "inline_svg": 0,
        "external_images": [],
        "options_inline": False,
    }
    if assets_json:
        try:
            a = json.loads(assets_json)
        except Exception:
            return flags
        flags["inline_svg"] = int(a.get("inline_svg") or 0)
        flags["external_images"] = a.get("external_images") or []
        flags["options_inline"] = bool(a.get("options_inline"))
        flags["figure_text_missing"] = (
            flags["inline_svg"] > 0 or bool(flags["external_images"])
        )
    return flags


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="候选库 → M3 固定语料快照导出（只读）")
    ap.add_argument("--db-url", required=True, help="jdbc:mysql://host:port/db 形式")
    ap.add_argument("--db-user", default=None,
                    help="缺省读环境变量 M3_DB_USER（凭据不入命令历史）")
    ap.add_argument("--db-password", default=None,
                    help="缺省读环境变量 M3_DB_PASSWORD（凭据不入命令历史/报告）")
    ap.add_argument("--out", required=True, help="输出目录（私有）")
    args = ap.parse_args(argv)

    import os
    db_user = args.db_user or os.environ.get("M3_DB_USER")
    db_password = args.db_password or os.environ.get("M3_DB_PASSWORD")
    if not db_user or db_password is None:
        print("missing credentials: pass --db-user/--db-password or set "
              "M3_DB_USER / M3_DB_PASSWORD", file=sys.stderr)
        return 2
    m = re.match(r"jdbc:mysql://([^/:]+)(?::(\d+))?/(\w+)", args.db_url)
    if not m:
        print("cannot parse db url", file=sys.stderr)
        return 2
    host, port, db = m.group(1), int(m.group(2) or 3306), m.group(3)

    import pymysql

    conn = pymysql.connect(host=host, port=port, user=db_user,
                           password=db_password, database=db,
                           charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT q.id, q.question_type, q.source_year, q.source_question_no, "
                "       q.stem_html, q.analysis_html, q.correct, q.answer_text, "
                "       q.assets_json, q.source_ref, s.name AS subject_name "
                "FROM question q LEFT JOIN subject s ON s.id = q.subject_id "
                "WHERE q.display_ready = 1 OR q.status_reason IS NOT NULL "
                "ORDER BY q.source_year, CAST(q.source_question_no AS UNSIGNED)")
            rows = cur.fetchall()
    finally:
        conn.close()

    out = Path(args.out)
    corpus_dir = out / "corpus"
    corpus_dir.mkdir(parents=True, exist_ok=True)

    records = []
    lost_formula = lost_figure = 0
    for r in rows:
        year = r["source_year"]
        no = int(r["source_question_no"])
        doc_id = f"{year}-q{no:02d}"
        stem = text_of(r["stem_html"])
        analysis = text_of(r["analysis_html"])
        flags = projection_flags(r["stem_html"], r["assets_json"])
        if flags["formula_render_loss"]:
            lost_formula += 1
        if flags["figure_text_missing"]:
            lost_figure += 1
        title = f"{year}年第{no}题 {r['subject_name'] or '未知学科'}"
        body_parts = [f"题干：{stem}"]
        if r["correct"]:
            body_parts.append(f"答案：{r['correct']}")
        if analysis:
            body_parts.append(f"解析：{analysis}")
        body = "\n\n".join(body_parts)
        (corpus_dir / f"{doc_id}.md").write_text(
            f"# {title}\n\n{body}\n", encoding="utf-8")
        records.append({
            "doc_id": doc_id,
            "source_year": year,
            "source_question_no": no,
            "subject": r["subject_name"],
            "question_type": r["question_type"],
            "source_ref": r["source_ref"],
            "stem_chars": len(stem),
            "analysis_chars": len(analysis),
            "projection": flags,
            "corpus_file": f"corpus/{doc_id}.md",
            "corpus_sha256": hashlib.sha256(
                (corpus_dir / f"{doc_id}.md").read_bytes()).hexdigest(),
        })

    empty = [r["doc_id"] for r in records if r["stem_chars"] == 0]
    snapshot = {
        "source_db": db,
        "source fingerprint_note": "来源=M1 master408_candidate 只读导出；display_ready 全量口径",
        "count": len(records),
        "projection_rules": {
            "html": "剥标签取文本，空白折叠",
            "formula_render_loss": "KaTeX 渲染层字符碎片化，公式语义不保真",
            "figure_text_missing": "SVG/外链图不可文本化，图题信息缺损",
        },
        "projection_loss": {"formula_render_loss": lost_formula,
                            "figure_text_missing": lost_figure},
        "empty_stem": empty,
        "records": records,
    }
    (out / "snapshot-export.json").write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[OK] 导出 {len(records)} 题到 {corpus_dir}；"
          f"公式投影缺损 {lost_formula} 题，图形文本缺损 {lost_figure} 题，空题干 {len(empty)} 题")
    return 0 if not empty else 1


if __name__ == "__main__":
    sys.exit(main())
