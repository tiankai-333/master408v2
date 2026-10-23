"""来源核对补强分析（M1 收口）：对 reconcile 补入的 387 题给出可审计的差异摘要。

输入：data-private/m1/paper-*.json（提取产物，含 analysis_source）+ 旧爬虫 JSON。
输出（私有 artifact）：逐题差异摘要 + 重复 key 检查 + 代表样本清单。
摘要口径（不是"全题已核"的证明，是差异可见性）：
- 题干：legacy 题干纯文本 与 HTML 题干纯文本 的包含率/长度比；
- 选项：legacy options 拼接串按 A./B./C./D. 切分（旧爬虫口径）后的纯文本，
  与 HTML 选项纯文本的逐项包含率；
- 答案：双源一致性（reconcile 已保证一致，此处复列）。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from bs4 import BeautifulSoup

LETTER_SPLIT = re.compile(r"(?=[ABCD]\.)")


def flat(text: str) -> str:
    return re.sub(r"\s+", "", str(text or ""))


def split_legacy_options(raw: str) -> dict[str, str]:
    parts = LETTER_SPLIT.split(str(raw or "").strip())
    out = {}
    for part in parts:
        m = re.match(r"([ABCD])\.(.*)", part, re.S)
        if m:
            out[m.group(1)] = flat(m.group(2))
    return out


def analyze(m1_dir: Path, legacy_path: Path) -> dict:
    legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
    keys = [(r["year"], r["question_number"]) for r in legacy]
    dup = [k for k in set(keys) if keys.count(k) > 1]

    rows = []
    for y in range(2015, 2025):
        paper = json.loads((m1_dir / f"paper-{y}.json").read_text(encoding="utf-8"))
        legacy_by_no = {(r["year"], r["question_number"]): r for r in legacy}
        for q in paper["questions"]:
            if q["question_type"] != 1:
                continue
            src = (q.get("analysis_source") or {})
            row = {
                "year": y, "no": q["no"],
                "analysis_source": "legacy" if src else None,
                "answer": q["correct"],
            }
            rec = legacy_by_no.get((y, q["no"]))
            if rec:
                row["legacy_answer"] = (rec.get("answer") or "").strip()
                row["answer_agrees"] = row["legacy_answer"] == q["correct"]
                lq = flat(rec.get("question"))
                hq = flat(BeautifulSoup(q["stem_html"], "html.parser").get_text())
                row["stem_probe_in_html"] = lq[:24] in hq if lq else None
                row["stem_len_ratio"] = round(len(lq) / max(len(hq), 1), 2)
                lo = split_legacy_options(rec.get("options"))
                ho = {}
                for o in q["options"]:
                    ho[o["key"]] = flat(BeautifulSoup(o["html"], "html.parser").get_text())
                row["option_contains"] = {
                    k: (lo[k] in ho.get(k, "")) for k in sorted(lo) if lo[k]
                }
                row["option_contains_all"] = all(row["option_contains"].values()) if lo else None
                row["legacy_stem_head"] = lq[:40]
                row["html_stem_head"] = hq[:40]
            rows.append(row)

    legacy_rows = [r for r in rows if r["analysis_source"] == "legacy"]
    summary = {
        "legacy_total": len(legacy),
        "duplicate_keys": dup,
        "matched": len(legacy_rows),
        "answer_agrees": sum(1 for r in legacy_rows if r["answer_agrees"]),
        "stem_probe_in_html_yes": sum(1 for r in legacy_rows if r["stem_probe_in_html"]),
        "stem_len_ratio_min": min((r["stem_len_ratio"] for r in legacy_rows), default=None),
        "stem_len_ratio_max": max((r["stem_len_ratio"] for r in legacy_rows), default=None),
        "option_contains_all_yes": sum(1 for r in legacy_rows if r["option_contains_all"]),
        "option_contains_all_no": sum(1 for r in legacy_rows
                                      if r["option_contains_all"] is False),
        "option_contains_all_none": sum(1 for r in legacy_rows
                                        if r["option_contains_all"] is None),
    }
    return {"summary": summary, "rows": rows}


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: analyze_legacy_match.py <m1_dir> <legacy_json> <out_json>", file=sys.stderr)
        return 2
    result = analyze(Path(sys.argv[1]), Path(sys.argv[2]))
    Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, indent=1),
                                 encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
