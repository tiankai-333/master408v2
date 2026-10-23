"""408 整卷 HTML → 逐卷 JSON 提取器（M1 候选库导入前置）。

源：csgraduates 408-real-{year}.html（私有爬取件，只读；提取结果同样私有，不进 Git）。
结构口径（2015–2024 十卷实测一致）：
- 题目区：h4 数据结构/组成原理/操作系统/计算机网络，各段 h5 = 单选题号（共 40 题），
  选项为 label.choice-option（input value + span.choice-text），答案来自"选择题答案速对"表；
- 解析区：h4 后缀 "-1"，h5 id = 综合题号 41–47；块内 answer-container 之前为题干，
  div.solution-detail 内为参考答案与解析（平衡扫描取内层）。
诚缺如实记录：单选题解析来源站不提供 → analysis_html=null；分值无来源事实 → 不输出 score。
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import shutil
import sys
from pathlib import Path

from bs4 import BeautifulSoup

SUBJECTS = ["数据结构", "组成原理", "操作系统", "计算机网络"]
CHOICE_COUNT = 40
MC_OPTIONS = ("A", "B", "C", "D")
COMP_OFFSET = 41  # 综合题号 41..47
ASSET_PREFIX = "/api/newdb/asset/"  # 本地受控资源端点（导入后经认证访问）


class ExtractError(Exception):
    """结构不符合口径（缺段/缺答案/题号断档），整卷拒绝，不输出半成品。"""


def _balance_div_end(text: str, start: int) -> int:
    """start 指向 '<div'，返回与之配对的 '</div>' 结束位置（不含）。"""
    i = start
    depth = 0
    token = re.compile(r"<div\b|</div>", re.S)
    for m in token.finditer(text, start):
        i = m.end()
        depth += 1 if m.group(0).startswith("<div") else -1
        if depth == 0:
            return m.start()
    raise ExtractError("div 未闭合：solution-detail 结构损坏")


def _inner_of_div(text: str, open_tag_start: int) -> str:
    """给定 '<div ...>' 起点与配对结束，返回内层 HTML。"""
    open_end = text.index(">", open_tag_start) + 1
    close = _balance_div_end(text, open_tag_start)
    return text[open_end:close]


def _h5_segments(section: str, h4_id: str) -> list[tuple[str, str]]:
    """h4 段内按 h5 切块，返回 [(id, 块HTML)]。"""
    marks = [(m.start(), m.group(1)) for m in re.finditer(r'<h5 id=([^>]+)>[^<]*</h5>', section)]
    if not marks:
        raise ExtractError(f"{h4_id}: 段内无 h5 题块")
    out = []
    for k, (pos, hid) in enumerate(marks):
        end = marks[k + 1][0] if k + 1 < len(marks) else len(section)
        out.append((hid, section[pos:end]))
    return out


def _answer_table(body: str) -> dict[int, str]:
    i = body.find("选择题答案速对")
    if i < 0:
        raise ExtractError("缺少选择题答案速对表")
    j = body.find("</details>", i)
    table = body[i:j]
    pairs = re.findall(r"<td>(\d{1,2})</td><td>([A-D])</td>", table)
    if not pairs:
        raise ExtractError("答案速对表解析为空")
    return {int(no): ans for no, ans in pairs}


def _assets(fragment: str) -> dict:
    return {
        "external_images": sorted(set(re.findall(r'<img[^>]*\ssrc=("[^"]*"|[^ ]+)', fragment))),
        "inline_svg": len(re.findall(r"<svg\b", fragment)),
        "katex_spans": len(re.findall(r'class="?katex\b', fragment)),
        "code_blocks": len(re.findall(r"<pre\b", fragment)),
        "tables": len(re.findall(r"<table\b", fragment)),
    }


def _resolve_local_image(src: str, images_dirs: list[Path] | None, assets_out: Path,
                         year: int, qno: int) -> dict | None:
    """外链图 src → 本地副本匹配（basename 去掉 .drawio 后在多个根目录递归比对）。
    命中则复制进私有资源目录并返回 {src, file}；否则 None（调用方记缺失）。"""
    if not images_dirs:
        return None
    base = src.rstrip("/").split("/")[-1]
    candidates = {base, base.replace(".drawio.svg", ".svg").replace(".drawio.png", ".png")}
    for images_dir in images_dirs:
        if not images_dir.is_dir():
            continue
        for path in sorted(images_dir.rglob("*")):
            if path.is_file() and path.name in candidates:
                served = f"{year}_{qno}_{path.name}"
                shutil.copyfile(path, assets_out / served)
                return {"src": src, "file": served}
    return None


def _rewrite_local_images(fragment: str, local_map: dict[str, str]) -> str:
    """把 fragment 中已有本地副本的 <img src=原始外链> 改写为受控资源相对路径。"""
    for src, served in local_map.items():
        fragment = fragment.replace(f'src={src}', f'src={ASSET_PREFIX}{served}')
        fragment = fragment.replace(f'src="{src}"', f'src="{ASSET_PREFIX}{served}"')
    return fragment


def _apply_local_images(fragment: str, local_map: dict[str, str],
                        missing: list[str]) -> str:
    """本地副本改写为受控资源路径；缺失图的 <img> 整标签移除（内容记入
    assets_json.missing_images，题面不静默丢——由 Validator 关上线阀）。"""
    fragment = _rewrite_local_images(fragment, local_map)
    for src in missing:
        fragment = re.sub(
            r'<img[^>]*\ssrc=("?' + re.escape(src) + r'"?)[^>]*>', '', fragment)
    return fragment


def _tags_of(fragment: str) -> list[str]:
    m = re.search(r'data-tags=([^\s>]+|"[^"]*")', fragment)
    if not m:
        return []
    raw = m.group(1).strip('"')
    return [t for t in raw.split(",") if t]


def _split_stem_solution(block: str) -> tuple[str, str | None, list[str]]:
    """综合题块 → (题干, 解析或None, 标签)。answer-container 之前为题干。"""
    m = re.search(r'<div class="?answer-container', block)
    if not m:
        raise ExtractError("综合题块缺少 answer-container 分界")
    stem = block[: m.start()]
    tags = _tags_of(block[m.start():])
    s = re.search(r'<div class="?solution-detail', block)
    if not s:
        return stem, None, tags
    return stem, _inner_of_div(block, s.start()), tags


def _mc_options(block: str) -> tuple[list[dict], bool]:
    """返回 (选项列表, 是否内联图题)。DOM 解析（bs4）保留 choice-text 内完整嵌套
    片段（公式 katex span 等）——正则截至首个闭合标签会截断嵌套选项（实测 22 题）。
    图题变体 choice-option-inline 只有字母无文本，选项内容（图）整体保留在题干
    svg 中，选项 html 为空串如实建模。"""
    options: list[dict] = []
    inline = False
    soup = BeautifulSoup(block, "html.parser")
    for label in soup.select("label.choice-option, label.choice-option-inline"):
        if "choice-option-inline" in (label.get("class") or []):
            inline = True
        vm = label.select_one("input[value]")
        if vm is None:
            continue
        ct = label.select_one(".choice-text")
        options.append({
            "key": vm["value"],
            "html": ct.decode_contents().strip() if ct else "",
        })
    return options, inline


def extract_paper(html_path: Path, images_dirs: list[Path] | None = None,
                  assets_out: Path | None = None) -> dict:
    raw = html_path.read_bytes()
    t = raw.decode("utf-8", errors="replace")
    body = t[t.find("<body"):]
    year_m = re.search(r"<title>(\d{4}) 年 408 真题", t)
    if not year_m:
        raise ExtractError("标题无年份")
    year = int(year_m.group(1))
    answers = _answer_table(body)

    # h4 分段
    h4s = [(m.start(), m.group(1)) for m in re.finditer(r"<h4 id=([^>]+)>", body)]
    sections: dict[str, str] = {}
    for k, (pos, hid) in enumerate(h4s):
        end = h4s[k + 1][0] if k + 1 < len(h4s) else len(body)
        sections[hid] = body[pos:end]

    questions: list[dict] = []
    warnings: list[str] = []

    def resolve_assets(qno: int, stem: str, extra: str = "") -> tuple[str, str, list[dict], list[str]]:
        """外链图 → 本地副本改写/缺失记录（stem 与解析段一并处理）。
        返回 (改写后stem, 改写后extra, local_images, missing)。"""
        srcs = sorted({m.group(2) or m.group(3) for m in re.finditer(
            r'<img[^>]*\ssrc=("([^"]+)"|([^\s">]+))', stem + extra)})
        local_images, missing = [], []
        for src in srcs:
            if src.startswith(ASSET_PREFIX):
                continue
            hit = _resolve_local_image(src, images_dirs, assets_out, year, qno)
            if hit:
                local_images.append(hit)
            else:
                missing.append(src)
        local_map = {h["src"]: h["file"] for h in local_images}
        return (_apply_local_images(stem, local_map, missing),
                _apply_local_images(extra, local_map, missing),
                local_images, missing)

    # 40 单选：题目区四科
    qno = 0
    for subj in SUBJECTS:
        if subj not in sections:
            raise ExtractError(f"缺科目段：{subj}")
        for hid, block in _h5_segments(sections[subj], subj):
            qno += 1
            if hid != str(qno):
                raise ExtractError(f"题号断档：期望 {qno}，实得 {hid}")
            bm = re.search(r'<div class="?(?:answer|choice)-container', block)
            stem = block[: bm.start()] if bm else block
            options, inline = _mc_options(block)
            # 答案双源：choice-container 的 data-answer（权威）与速对表交叉核验
            dam = re.search(r'data-answer=([A-D])', block)
            table_ans = answers.get(qno)
            if dam and table_ans and dam.group(1) != table_ans:
                raise ExtractError(f"题 {qno} 答案冲突：data-answer={dam.group(1)} 速对表={table_ans}")
            correct = dam.group(1) if dam else table_ans
            if correct is None:
                raise ExtractError(f"题 {qno} 无答案（data-answer 与速对表均缺失）")
            if len(options) != 4 or [o["key"] for o in options] != list(MC_OPTIONS):
                raise ExtractError(f"题 {qno} 选项不为 ABCD 四项：{[o['key'] for o in options]}")
            stem, _, local_images, missing = resolve_assets(qno, stem)
            questions.append({
                "no": qno, "subject": subj, "question_type": 1,
                "stem_html": stem.strip(),
                "options": options,
                "options_inline": inline,
                "correct": correct,
                "analysis_html": None,  # 诚缺：来源站无单选解析
                "answer_text": None,
                "tags": _tags_of(block[bm.start():]) if bm else [],
                "assets": {**_assets(stem), "local_images": local_images,
                           "missing_images": missing},
                "source_ref": f"https://www.csgraduates.com/study_methods/408quiz/{year}/#q{qno}",
            })
    if qno != CHOICE_COUNT:
        raise ExtractError(f"单选题数 {qno} ≠ {CHOICE_COUNT}")

    # 7 综合题：解析区 "-1" 段，科目按段名归位
    comp_sections = {k: v for k, v in sections.items() if k.endswith("-1")}
    seen: dict[int, dict] = {}
    for hid4, sec in comp_sections.items():
        subj = hid4[:-2]
        if subj not in SUBJECTS:
            raise ExtractError(f"未知解析区段名：{hid4}")
        for hid, block in _h5_segments(sec, hid4):
            no = int(hid)
            if not COMP_OFFSET <= no <= COMP_OFFSET + 6:
                raise ExtractError(f"综合题号越界：{no}")
            if no in seen:
                raise ExtractError(f"综合题号重复：{no}")
            stem, solution, tags = _split_stem_solution(block)
            stem, solution, local_images, missing = resolve_assets(no, stem, solution or "")
            seen[no] = {
                "no": no, "subject": subj, "question_type": 5,
                "stem_html": stem.strip(),
                "options": None,
                "correct": None,
                "analysis_html": solution,  # 参考答案与解析；缺失时为 None 并告警
                "answer_text": None,
                "tags": tags,
                "assets": {**_assets(stem + (solution or "")), "local_images": local_images,
                           "missing_images": missing},
                "source_ref": f"https://www.csgraduates.com/study_methods/408quiz/{year}/#q{no}",
            }
            if solution is None:
                warnings.append(f"综合题 {no}：来源无参考答案/解析块")
    if sorted(seen) != list(range(COMP_OFFSET, COMP_OFFSET + 7)):
        raise ExtractError(f"综合题号不连续：{sorted(seen)}")
    questions.extend(seen[no] for no in sorted(seen))

    # 图片缺口盘点：外链图本地不可得的如实告警（有副本的已改写为受控资源路径）
    for q in questions:
        for src in q["assets"]["missing_images"]:
            warnings.append(f"题 {q['no']}：外链图片 {src} 本地无副本，题目标未就绪")
    mc_with_analysis = sum(1 for q in questions if q["question_type"] == 1 and q["analysis_html"])
    if mc_with_analysis == 0:
        warnings.append(f"选择题解析 {CHOICE_COUNT} 题：来源站不提供，非提取损失")

    return {
        "year": year,
        "paper_name": f"{year}年408真题",
        "paper_type": 1,
        "source_url": f"http://www.csgraduates.com/study_methods/408quiz/{year}/",
        "source_file": str(html_path),
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "question_total": len(questions),
        "questions": questions,
        "warnings": warnings,
    }


def _sentences(text: str, min_len: int = 8) -> list[str]:
    parts = [re.sub(r"\s+", "", s) for s in re.split(r"[。；;：:（）()\n]", str(text or ""))]
    return [s for s in parts if len(s) >= min_len]


def _split_legacy_options(raw: str) -> dict[str, str]:
    out = {}
    for part in re.split(r"(?=[ABCD]\.)", str(raw or "").strip()):
        m = re.match(r"([ABCD])\.(.*)", part, re.S)
        if m:
            out[m.group(1)] = re.sub(r"\s+", "", m.group(2))
    return out


def _deep_verify(q: dict, rec: dict) -> dict | None:
    """probe 失败后的完整同题核对（逐条证据，非门槛放水）：
    a) legacy 题干每个 ≥8 字句（去空白）都出现在 HTML 题干纯文本中——题干全文
       覆盖（允许顺序不同/表格内嵌）；
    b) legacy 全部非空选项逐字出现在 HTML 同字母选项中；
    c) 答案双源一致（调用方已保证，此处复列入证据）。
    全部通过返回核对证据 dict；否则 None（保持未匹配）。"""
    hq = re.sub(r"\s+", "", BeautifulSoup(q["stem_html"], "html.parser").get_text())
    sents = _sentences(rec.get("question"))
    if not sents:
        return None
    hit = [s for s in sents if s in hq]
    if len(hit) != len(sents):
        return None
    lo = _split_legacy_options(rec.get("options"))
    ho = {o["key"]: re.sub(r"\s+", "",
                           BeautifulSoup(o["html"], "html.parser").get_text())
          for o in q["options"]}
    nonempty = {k: v for k, v in lo.items() if v}
    opt_hit = sum(1 for k, v in nonempty.items() if v in ho.get(k, ""))
    if len(nonempty) == 0 or opt_hit != len(nonempty):
        return None
    if (rec.get("answer") or "").strip() != q["correct"]:
        return None
    return {
        "method": "stem-sentences+options+answer",
        "stem_sentences": f"{len(hit)}/{len(sents)}",
        "options": f"{opt_hit}/{len(nonempty)}",
        "answer_agrees": True,
        "reason": "题干重排或表格/图形内嵌于 HTML 题干（legacy 与 HTML 逐句/逐选项一致）",
    }


def reconcile_legacy(papers: list[dict], legacy_path: Path) -> dict:
    """旧爬虫 exam_questions.json（纯文本）→ 单选解析交叉对账与补入。

    匹配判定（两档，均为可复现实证）：
    - probe 档：(year,question_number) 命中 + answer 双源一致 + legacy 题干前 24 个
      非空白字符出现在 HTML 题干纯文本中；
    - deep 档（probe 失败时）：完整同题核对——legacy 题干逐句（≥8 字句全覆盖）、
      全部非空选项逐字命中同字母选项、答案一致；任一不满足保持未匹配。
    补入内容：`<p>` + HTML 转义后的纯文本解析（不做富文本伪装、不覆盖 HTML 源），
    analysis_source 记录来源指纹与所用的核对证据。
    """
    legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
    by_key = {(r["year"], r["question_number"]): r for r in legacy}
    stats = {"matched": 0, "matched_probe": 0, "matched_deep_verify": 0,
             "answer_conflict": 0, "unmatched": 0, "already_present": 0}
    conflicts, unmatched = [], []
    for paper in papers:
        year = paper["year"]
        for q in paper["questions"]:
            if q["question_type"] != 1:
                continue
            if q["analysis_html"]:
                stats["already_present"] += 1
                continue
            rec = by_key.get((year, q["no"]))
            if rec is None:
                stats["unmatched"] += 1
                unmatched.append({"year": year, "no": q["no"], "reason": "no legacy record"})
                continue
            if (rec.get("answer") or "").strip() != q["correct"]:
                stats["answer_conflict"] += 1
                conflicts.append({"year": year, "no": q["no"],
                                  "html_answer": q["correct"],
                                  "legacy_answer": rec.get("answer")})
                continue
            stem_text = BeautifulSoup(q["stem_html"], "html.parser").get_text()
            stem_flat = re.sub(r"\s+", "", stem_text)
            legacy_stem = re.sub(r"\s+", "", str(rec.get("question") or ""))
            probe = legacy_stem[:24]
            verify = None
            if probe and probe in stem_flat:
                verify = {"method": "stem-probe+answer", "probe": probe[:24],
                          "answer_agrees": True}
            else:
                verify = _deep_verify(q, rec)
                if verify is None:
                    stats["unmatched"] += 1
                    unmatched.append({"year": year, "no": q["no"],
                                      "reason": "probe and deep-verify both failed",
                                      "probe": probe[:24]})
                    continue
            analysis_text = str(rec.get("analysis") or "").strip()
            if not analysis_text:
                stats["unmatched"] += 1
                unmatched.append({"year": year, "no": q["no"], "reason": "empty legacy analysis"})
                continue
            q["analysis_html"] = "<p>" + html.escape(analysis_text) + "</p>"
            q["analysis_source"] = {
                "file": legacy_path.name,
                "sha256": hashlib.sha256(legacy_path.read_bytes()).hexdigest(),
                "record_fields": "year,question_number,answer,question,analysis",
                "verify": verify,
            }
            stats["matched"] += 1
            if verify["method"].startswith("stem-probe"):
                stats["matched_probe"] += 1
            else:
                stats["matched_deep_verify"] += 1
    # 两源合并后的逐卷解析警告重生成（替代"来源站不提供"旧口径）
    for paper in papers:
        mc = [q for q in paper["questions"] if q["question_type"] == 1]
        legacy_n = sum(1 for q in mc if q.get("analysis_source"))
        html_n = sum(1 for q in paper["questions"]
                     if q["question_type"] == 5 and q["analysis_html"])
        none_n = sum(1 for q in mc if not q["analysis_html"])
        kept = [w for w in paper["warnings"] if "外链图" in w]
        if legacy_n:
            kept.append(f"单选解析 {legacy_n} 题补自旧爬虫 exam_questions.json（候选·未逐题核审）")
        if none_n:
            kept.append(f"单选解析 {none_n} 题：两源均未关联（待核，明细见 reconcile-analysis.json）")
        if html_n == 0:
            kept.append("综合题解析 0 题（异常，请核查）")
        paper["warnings"] = kept
        paper["warning_count_related"] = {"analysis_legacy": legacy_n,
                                          "analysis_none": none_n,
                                          "analysis_html": html_n}
    return {"stats": stats, "conflicts": conflicts, "unmatched": unmatched}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="408 整卷 HTML → JSON 提取")
    ap.add_argument("--src", nargs="+", required=True, help="408-real-YYYY.html 路径")
    ap.add_argument("--out", required=True, help="输出目录（私有，勿入 Git）")
    ap.add_argument("--images-dir", nargs="+", default=None, help="本地图片副本根目录（可多个，递归匹配）")
    ap.add_argument("--assets-dir", default=None, help="受控资源输出目录（本地副本复制到此，供 /api/newdb/asset 服务）")
    ap.add_argument("--legacy-json", default=None, help="旧爬虫 exam_questions.json（单选解析交叉对账与补入）")
    args = ap.parse_args(argv)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    images_dirs = [Path(d) for d in args.images_dir] if args.images_dir else None
    assets_out = Path(args.assets_dir) if args.assets_dir else None
    if assets_out is not None:
        assets_out.mkdir(parents=True, exist_ok=True)
    failures = []
    papers = []
    for src in args.src:
        path = Path(src)
        try:
            paper = extract_paper(path, images_dirs=images_dirs, assets_out=assets_out)
        except ExtractError as e:
            failures.append(f"{path.name}: {e}")
            print(f"[FAIL] {path.name}: {e}", file=sys.stderr)
            continue
        papers.append(paper)
        dest = out / f"paper-{paper['year']}.json"
        dest.write_text(json.dumps(paper, ensure_ascii=False, indent=1), encoding="utf-8")
        mc = sum(1 for q in paper["questions"] if q["question_type"] == 1)
        comp = len(paper["questions"]) - mc
        print(f"[OK] {dest.name}: 单选{mc} + 综合{comp}，告警 {len(paper['warnings'])} 条")
    if args.legacy_json:
        rec = reconcile_legacy(papers, Path(args.legacy_json))
        (out / "reconcile-analysis.json").write_text(
            json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[RECONCILE] {rec['stats']} 冲突 {len(rec['conflicts'])} 条 / 未匹配 "
              f"{len(rec['unmatched'])} 条，明细见 reconcile-analysis.json")
        # 补入后重写各卷 JSON
        for paper in papers:
            (out / f"paper-{paper['year']}.json").write_text(
                json.dumps(paper, ensure_ascii=False, indent=1), encoding="utf-8")
    if failures:
        print(f"[SUMMARY] {len(args.src) - len(failures)}/{len(args.src)} 成功", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
