"""paper_extract 结构口径测试——全部用合成夹具，不含任何真题内容。

python -m unittest discover -s tools/paper-extract -p "test_*.py" -v
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from paper_extract import ExtractError, _balance_div_end, _mc_options, extract_paper, main

# 合成夹具：与真实源同构（h4/h5/choice-option/答案表/solution-detail），内容自编。
FIXTURE_TEMPLATE = """<html><head><title>{year} 年 408 真题 | 计算机考研杂货铺</title></head><body>
<details class="elegant-detail"><summary class="elegant-summary">选择题答案速对</summary>
<table><thead><tr><th>No.</th><th>Ans</th></tr></thead><tbody>
{ansrows}
</tbody></table></details>
{mc_sections}
<h4 id=数据结构-1>数据结构</h4>
<h5 id=41>41</h5><p>合成综合题干，含公式 <span class=katex><span class=katex-html>x^2</span></span> 与图
<div class=svg-wrapper><svg viewBox="0 0 10 10"><path d="M0 0L10 10"/></svg></div>
及代码 <pre><code>int x = 1;</code></pre></p>
<div class="answer-container" data-tags=合成,标签></div>
<div class="solution-detail" style=display:none><p>参考解答甲。</p><div class=highlight><pre><code>void f() &#123;&#125;</code></pre></div></div>
<h4 id=组成原理-1>组成原理</h4>
<h5 id=42>42</h5><p>合成综合题干四十二。</p><div class="answer-container" data-tags=乙></div>
<div class="solution-detail"><p>参考解答乙。</p></div>
<h4 id=操作系统-1>操作系统</h4>
<h5 id=43>43</h5><p>合成综合题干四十三。</p><div class="answer-container"></div>
<div class="solution-detail"><p>参考解答丙。</p></div>
<h5 id=44>44</h5><p>合成综合题干四十四。</p><div class="answer-container"></div>
<div class="solution-detail"><p>参考解答丁。</p></div>
<h4 id=计算机网络-1>计算机网络</h4>
<h5 id=45>45</h5><p>合成综合题干四十五。</p><div class="answer-container"></div>
<div class="solution-detail"><p>参考解答戊。</p></div>
<h5 id=46>46</h5><p>合成综合题干四十六。</p><div class="answer-container"></div>
<div class="solution-detail"><p>参考解答己。</p></div>
<h5 id=47>47</h5><p>合成综合题干四十七。</p><div class="answer-container"></div>
<div class="solution-detail"><p>参考解答庚。</p></div>
</body></html>"""


def _mc_block(no: int, tag: str) -> str:
    opts = "".join(
        f'<label class=choice-option><input type=radio name=q{no} value={k}>'
        f'<span class=choice-label>{k}.</span><span class=choice-text>选项{k}</span></label>'
        for k in "ABCD"
    )
    return (
        f"<h5 id={no}>{no}</h5><p>合成题干{no}。</p>"
        f'<div class="answer-container" data-tags={tag}></div><form>{opts}</form>'
    )


def _fixture(year: int, mc_count: int = 40, drop_answer: int | None = None,
             missing_subject: bool = False) -> str:
    rows = []
    for no in range(1, mc_count + 1):
        if no == drop_answer:
            continue
        rows.append(f"<tr><td>{no}</td><td>{'ABCD'[no % 4]}</td></tr>")
    # 40 题均分四科目段（10/10/10/10），与真实源同构
    bounds = [(1, 11), (11, 21), (21, 31), (31, 41)]
    parts = []
    for (lo, hi), subj in zip(bounds, ("数据结构", "组成原理", "操作系统", "计算机网络")):
        if missing_subject and subj == "计算机网络":
            continue  # 缺整段 → 提取器必须拒绝
        blocks = "".join(_mc_block(no, f"标签{no}") for no in range(lo, hi))
        parts.append(f"<h4 id={subj}>{subj}</h4>\n{blocks}")
    return FIXTURE_TEMPLATE.format(year=year, ansrows="".join(rows), mc_sections="".join(parts))


def _write(tmp: Path, content: str, year: int = 2015) -> Path:
    p = tmp / f"408-real-{year}.html"
    p.write_text(content, encoding="utf-8")
    return p


class BalanceTest(unittest.TestCase):
    def test_balance_div_end(self) -> None:
        text = 'a<div class="x">b<div>c</div>d</div>e'
        start = text.index("<div")
        end = _balance_div_end(text, start)
        # 返回闭合 </div> 的起点（_inner_of_div 据此取内层）
        self.assertEqual(text[start:end], '<div class="x">b<div>c</div>d')


class McOptionsTest(unittest.TestCase):
    def test_four_options_extracted(self) -> None:
        block = _mc_block(1, "标签")
        opts, inline = _mc_options(block)
        self.assertFalse(inline)
        self.assertEqual([o["key"] for o in opts], ["A", "B", "C", "D"])
        self.assertEqual(opts[0]["html"], "选项A")

    def test_nested_markup_not_truncated(self) -> None:
        # 回归：选项内嵌套 span（公式）不得被截到首个闭合标签（原正则缺陷，22 题受影响）
        block = (
            "<h5 id=6>6</h6><p>题干</p>"
            '<div class="answer-container"></div><form>'
            '<label class=choice-option><input type=radio value=A><span class=choice-label>A.</span>'
            '<span class=choice-text>(<span class=katex><span class=katex-html>V1</span></span>,'
            '<span class=katex><span class=katex-html>V3</span></span>)</span></label>'
            '<label class=choice-option><input type=radio value=B><span class=choice-label>B.</span>'
            '<span class=choice-text>普通选项</span></label></form>'
        )
        opts, inline = _mc_options(block)
        self.assertFalse(inline)
        self.assertEqual(
            opts[0]["html"],
            "(<span class=\"katex\"><span class=\"katex-html\">V1</span></span>,"
            "<span class=\"katex\"><span class=\"katex-html\">V3</span></span>)",
        )
        self.assertEqual(opts[1]["html"], "普通选项")

    def test_inline_letter_options(self) -> None:
        # 图题变体：只有字母无 choice-text，内容（图）留在题干
        block = (
            "<h5 id=8>8</h5><p>题干含图</p><div class=svg-wrapper><svg><path d=M0/></svg></div>"
            '<div class=choice-container data-answer=B data-tags=图题>'
            '<form class=choice-inline>'
            + "".join(
                f'<label class=choice-option-inline><input type=radio value={k}>'
                f'<span class=choice-label>{k}</span></label>' for k in "ABCD")
            + "</form></div>"
        )
        opts, inline = _mc_options(block)
        self.assertTrue(inline)
        self.assertEqual([o["key"] for o in opts], ["A", "B", "C", "D"])
        self.assertTrue(all(o["html"] == "" for o in opts))


class ExtractPaperTest(unittest.TestCase):
    def test_full_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), _fixture(2015))
            paper = extract_paper(path)
        self.assertEqual(paper["year"], 2015)
        self.assertEqual(paper["question_total"], 47)
        types = [q["question_type"] for q in paper["questions"]]
        self.assertEqual(types.count(1), 40)
        self.assertEqual(types.count(5), 7)
        self.assertEqual([q["no"] for q in paper["questions"]], list(range(1, 48)))
        # 顺序与科目归位
        self.assertEqual(paper["questions"][0]["subject"], "数据结构")
        self.assertEqual(paper["questions"][40]["subject"], "数据结构")
        self.assertEqual(paper["questions"][43]["subject"], "操作系统")  # 44 在 操作系统-1
        # 单选：选项四项 + 答案来自速对表 + 解析如实为 None
        q1 = paper["questions"][0]
        self.assertEqual(q1["correct"], "ABCD"[1 % 4])
        self.assertEqual(len(q1["options"]), 4)
        self.assertFalse(q1["options_inline"])
        self.assertIsNone(q1["analysis_html"])

    def test_data_answer_conflict_rejected(self) -> None:
        # 速对表题1答案为 B；注入冲突的 data-answer=C 必须整卷拒绝
        text = _fixture(2021).replace("data-tags=标签1", "data-answer=C data-tags=标签1", 1)
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), text)
            with self.assertRaisesRegex(ExtractError, "答案冲突"):
                extract_paper(path)

    def test_data_answer_agree_used(self) -> None:
        text = _fixture(2022).replace("data-tags=标签1", "data-answer=B data-tags=标签1", 1)
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), text)
            paper = extract_paper(path)
        self.assertEqual(paper["questions"][0]["correct"], "B")
        # 综合题：解析含内层嵌套 div 的完整内容
        q41 = paper["questions"][40]
        self.assertIn("参考解答甲", q41["analysis_html"])
        self.assertIn("void f()", q41["analysis_html"])
        self.assertEqual(q41["tags"], ["合成", "标签"])
        # assets 盘点
        self.assertEqual(q41["assets"]["inline_svg"], 1)
        self.assertGreaterEqual(q41["assets"]["katex_spans"], 1)
        self.assertEqual(q41["assets"]["code_blocks"], 2)  # 题干 1 + 解析 1
        # 诚缺告警
        self.assertTrue(any("选择题解析" in w for w in paper["warnings"]))
        self.assertIn("source_sha256", paper)

    def test_missing_answer_row_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), _fixture(2016, drop_answer=7))
            with self.assertRaisesRegex(ExtractError, "无答案"):
                extract_paper(path)

    def test_missing_subject_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), _fixture(2017, missing_subject=True))
            with self.assertRaisesRegex(ExtractError, "缺科目段"):
                extract_paper(path)

    def test_broken_question_order_rejected(self) -> None:
        text = _fixture(2018).replace("<h5 id=5>5</h5>", "<h5 id=9>9</h5>", 1)
        with tempfile.TemporaryDirectory() as tmp:
            path = _write(Path(tmp), text)
            with self.assertRaisesRegex(ExtractError, "题号断档"):
                extract_paper(path)


class CliTest(unittest.TestCase):
    def test_cli_ok_and_failure_exit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            good = _write(Path(tmp), _fixture(2019))
            out = Path(tmp) / "out"
            self.assertEqual(main(["--src", str(good), "--out", str(out)]), 0)
            data = json.loads((out / "paper-2019.json").read_text(encoding="utf-8"))
            self.assertEqual(data["question_total"], 47)
        with tempfile.TemporaryDirectory() as tmp:
            bad = _write(Path(tmp), _fixture(2020, drop_answer=3))
            self.assertEqual(main(["--src", str(bad), "--out", str(Path(tmp) / "o2")]), 1)


if __name__ == "__main__":
    unittest.main()
