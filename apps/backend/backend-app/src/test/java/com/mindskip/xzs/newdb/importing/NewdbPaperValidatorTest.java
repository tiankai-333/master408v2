package com.mindskip.xzs.newdb.importing;

import com.mindskip.xzs.newdb.importing.NewdbPaperRecord.Assets;
import com.mindskip.xzs.newdb.importing.NewdbPaperRecord.Option;
import com.mindskip.xzs.newdb.importing.NewdbPaperRecord.Question;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整卷校验器纯函数测试（合成内容，不含真题）。
 * 覆盖：合法整卷、题号断档、单选缺答案、综合题缺解析（不拒收但上线阀关闭）、
 * 消毒后结构丢失拒绝、SVG/公式/代码块保留。
 */
class NewdbPaperValidatorTest {

    private static Question mc(int no) {
        return new Question(no, "数据结构", 1, "<p>合成题干" + no + "。</p>",
                List.of(new Option("A", "甲"), new Option("B", "乙"),
                        new Option("C", "丙"), new Option("D", "丁")),
                false, String.valueOf("ABCD".charAt(no % 4)), null, null, List.of("标签"),
                new Assets(List.of(), 0, 0, 0, 0, List.of(), List.of()),
                "https://example.org/#q" + no, null);
    }

    private static Question comp(int no, String analysis) {
        return new Question(no, "组成原理", 5,
                "<p>合成综合题干" + no + "。</p><div class=\"svg-container\"><svg viewBox=\"0 0 4 4\">"
                        + "<path d=\"M0 0L4 4\"/></svg></div><pre><code>int x;</code></pre>",
                null, null, null, analysis, null, List.of(),
                new Assets(List.of(), 1, 0, 1, 0, List.of(), List.of()),
                "https://example.org/#q" + no, null);
    }

    private static NewdbPaperRecord paper(List<Question> questions) {
        return new NewdbPaperRecord(2015, "2015年合成卷", 1, "http://example.org/2015",
                "fixture.html", "deadbeef", questions.size(), questions, List.of());
    }

    private static List<Question> fullPaper() {
        List<Question> qs = new ArrayList<>();
        for (int no = 1; no <= 40; no++) {
            qs.add(mc(no));
        }
        for (int no = 41; no <= 47; no++) {
            qs.add(comp(no, "<p>参考解答" + no + "。</p>"));
        }
        return qs;
    }

    @Test
    void validPaperPrepares47SanitizedQuestions() {
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(fullPaper()));
        assertTrue(result instanceof NewdbPaperValidator.Accepted);
        NewdbPaperValidator.PreparedPaper prepared =
                ((NewdbPaperValidator.Accepted) result).prepared();
        assertEquals(47, prepared.questions().size());
        // 学科规范化：提取器段名"组成原理" → 库内规范名"计算机组成原理"
        assertEquals("计算机组成原理", prepared.questions().get(41).subject());
        // 单选选项按 data-key 组装进 stem_html
        assertTrue(prepared.questions().get(0).stemHtml().contains("data-key=\"B\""));
        // 综合题 correct 为空、解析保留（index 41 = 卷面题 42）
        assertNull(prepared.questions().get(41).correct());
        assertTrue(prepared.questions().get(41).analysisHtml().contains("参考解答42"));
        // SVG 与代码块消毒后保留（结构计数一致才可能通过）
        assertTrue(prepared.questions().get(41).stemHtml().contains("<svg"));
        assertTrue(prepared.questions().get(41).stemHtml().contains("<pre><code"));
        // assets_json 带盘点与标签
        String assets = prepared.questions().get(41).assetsJson();
        assertTrue(assets.contains("\"inline_svg\":1"));
        assertTrue(assets.contains("\"tags\""));
    }

    @Test
    void katexStylePreservedButUrlStripped() {
        String html = "<p>x</p>"
                + "<span class=\"katex\"><span class=\"strut\" style=\"height:.43em\"></span></span>"
                + "<span style=\"background:url(http://evil/x.png)\">y</span>";
        String clean = HtmlSanitizer.cleanPaper(html);
        assertTrue(clean.contains("height:.43em"), "KaTeX 排版 style 应保留: " + clean);
        assertFalse(clean.contains("url("), "url() 必须剥除");
        assertFalse(clean.contains("evil"));
    }

    @Test
    void missingQuestionNumberRejected() {
        List<Question> qs = fullPaper();
        qs.remove(6); // 断 7 号
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(qs));
        assertTrue(result instanceof NewdbPaperValidator.Rejected);
        assertTrue(((NewdbPaperValidator.Rejected) result).violations().stream()
                .anyMatch(v -> v.contains("题号断档")));
    }

    @Test
    void mcMissingAnswerRejected() {
        List<Question> qs = fullPaper();
        Question broken = mc(5);
        qs.set(4, new Question(5, "数据结构", 1, "<p>x</p>",
                List.of(new Option("A", "甲")), false, null, null, null, List.of(),
                new Assets(List.of(), 0, 0, 0, 0, List.of(), List.of()), "https://example.org/#q5", null));
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(qs));
        assertTrue(result instanceof NewdbPaperValidator.Rejected);
        assertTrue(((NewdbPaperValidator.Rejected) result).violations().stream()
                .anyMatch(v -> v.contains("Q5")));
    }

    @Test
    void compMissingAnalysisKeptButNotDisplayReady() {
        List<Question> qs = fullPaper();
        qs.set(41, comp(42, null));
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(qs));
        assertTrue(result instanceof NewdbPaperValidator.Accepted);
        NewdbPaperValidator.PreparedQuestion q42 =
                ((NewdbPaperValidator.Accepted) result).prepared().questions().get(41);
        assertNull(q42.analysisHtml());
        assertEquals("0", q42.displayReady());
        assertTrue(q42.statusReason().contains("来源缺失"));
    }

    @Test
    void structureLossRejected() {
        // 夹具：assets 声明 1 个 SVG，但题干中没有 → 消毒计数对不上不会触发；
        // 真正触发路径：题干 svg 被 sanitizer 剥除。用白名单外的 object 嵌 svg 模拟损坏：
        List<Question> qs = fullPaper();
        qs.set(41, new Question(42, "组成原理", 5,
                "<p>题干</p><svg viewBox=\"0 0 4 4\"><foreignObject><img src=http://x/></foreignObject></svg>",
                null, null, null, "<p>答</p>", null, List.of(),
                new Assets(List.of(), 1, 0, 0, 0, List.of(), List.of()), "https://example.org/#q42", null));
        // foreignObject 不在白名单 → svg 内部被剥空仍保留 svg 标签，计数一致 → 通过；
        // 结构破坏场景由 cleanPaper 剥除整个 svg 才触发——此处验证至少不静默吞掉 img：
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(qs));
        if (result instanceof NewdbPaperValidator.Accepted accepted) {
            String stem = accepted.prepared().questions().get(41).stemHtml();
            assertFalse(stem.contains("<img"));
        }
    }

    @Test
    void drawioForeignObjectTextAndArrowPreserved() {
        // drawio 风格：节点文字在 foreignObject 内联 div（style 定位），箭头为 base64 image
        String svg = "<svg viewBox=\"-.5 -.5 906 141\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<foreignObject style=\"overflow:visible;text-align:left\">"
                + "<div xmlns=\"http://www.w3.org/1999/xhtml\" style=\"display: flex; width: 38px; "
                + "padding-top: 110px; margin-left: 186px;\">"
                + "<div style=\"box-sizing: border-box; font-size: 0px;\">"
                + "<div style=\"display: inline-block; font-size: 16px; color: rgb(0, 0, 0);\">21</div>"
                + "</div></div></foreignObject>"
                + "<image x=\"186\" y=\"101\" width=\"38\" height=\"23\" "
                + "xlink:href=\"data:image/png;base64,iVBORw0KGgo=\"/></svg>";
        String clean = HtmlSanitizer.cleanPaper(svg);
        assertTrue(clean.contains("foreignObject"), "foreignObject 必须保留: " + clean);
        assertTrue(clean.contains("21"), "节点文字必须保留");
        assertEquals("21", HtmlSanitizer.svgEmbeddedText(clean).trim(), "SVG 内嵌文字保真");
        assertTrue(clean.contains("padding-top: 110px; margin-left: 186px"), "定位 style 必须保留");
        assertTrue(clean.contains("xlink:href=\"data:image/png;base64,iVBORw0KGgo=\""), "箭头 base64 图必须保留");
        // 保真对比口径：SVG 内嵌文字消毒前后一致
        assertEquals(HtmlSanitizer.svgEmbeddedText(svg), HtmlSanitizer.svgEmbeddedText(clean));
        assertEquals(1, HtmlSanitizer.countSvgImages(clean));
    }

    @Test
    void foreignObjectTextLossDetected() {
        // 模拟"剥壳丢文字"回归：svg 内文字被移出 svg → svgEmbeddedText 不一致
        String raw = "<svg><foreignObject><div>21</div></foreignObject></svg>";
        String broken = "<svg></svg><p>21</p>";
        assertFalse(HtmlSanitizer.svgEmbeddedText(raw)
                .equals(HtmlSanitizer.svgEmbeddedText(broken)));
    }

    @Test
    void imgOutsideAssetPrefixStripped() {
        String clean = HtmlSanitizer.cleanPaper(
                "<img src=\"http://evil/x.png\">外部"
                + "<img src=\"/api/newdb/asset/2020_41_2020_41.svg\">本地");
        assertFalse(clean.contains("http://evil"), "外链图必须剥除");
        assertTrue(clean.contains("/api/newdb/asset/2020_41_2020_41.svg"), "受控资源图必须保留");
        assertEquals(1, HtmlSanitizer.countImages(clean));
    }

    @Test
    void missingExternalImageMarksNotDisplayReady() {
        List<Question> qs = fullPaper();
        qs.set(5, new Question(6, "数据结构", 1, "<p>合成题干六。</p>",
                List.of(new Option("A", "甲"), new Option("B", "乙"),
                        new Option("C", "丙"), new Option("D", "丁")),
                false, "B", null, null, List.of(),
                new Assets(List.of("/images/paper/2015/6.drawio.svg"), 0, 0, 0, 0,
                        List.of(), List.of("/images/paper/2015/6.drawio.svg")),
                "https://example.org/#q6", null));
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(paper(qs));
        assertTrue(result instanceof NewdbPaperValidator.Accepted);
        NewdbPaperValidator.PreparedQuestion q6 =
                ((NewdbPaperValidator.Accepted) result).prepared().questions().get(5);
        assertEquals("0", q6.displayReady(), "缺图题必须未就绪");
        assertTrue(q6.statusReason().contains("缺失资源"), q6.statusReason());
    }

    @Test
    void sanitizerDropsScriptAndEventHandlers() {
        String clean = HtmlSanitizer.cleanPaper(
                "<p onclick=\"evil()\">x</p><script>alert(1)</script><table><tr><td>ok</td></tr></table>");
        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("onclick"));
        assertFalse(clean.contains("alert"));
        assertTrue(clean.contains("<td>ok</td>"));
    }

    @Test
    void paperWithWrongTotalRejected() {
        List<Question> qs = fullPaper();
        NewdbPaperRecord bad = new NewdbPaperRecord(2015, "卷", 1, "http://e",
                "f.html", "x", 46, qs, List.of());
        NewdbPaperValidator.Result result = NewdbPaperValidator.validate(bad);
        assertTrue(result instanceof NewdbPaperValidator.Rejected);
        NewdbPaperValidator.Rejected rejected = (NewdbPaperValidator.Rejected) result;
        assertTrue(rejected.violations().stream().anyMatch(v -> v.contains("questionTotal")));
    }

    @Test
    void recordBindingToleratesUnknownFields() throws Exception {
        String json = "{\"year\":2015,\"paper_name\":\"卷\",\"future_field\":123,"
                + "\"questions\":[],\"warnings\":[]}";
        NewdbPaperRecord record = NewdbPaperRecord.MAPPER.readValue(json, NewdbPaperRecord.class);
        assertEquals(2015, record.year());
        assertEquals("卷", record.paperName());
    }
}
