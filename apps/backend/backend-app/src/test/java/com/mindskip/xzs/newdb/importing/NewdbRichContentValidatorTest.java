package com.mindskip.xzs.newdb.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 富内容导入路径测试（原创夹具，无真实题目内容）：
 * 表格结构保真、公式定界保留、图片明确拒绝、恶意输入剥除、旧纯文本兼容、四选项契约。
 */
class NewdbRichContentValidatorTest {

    private static final String TABLE = "<table><thead><tr><th>方案</th><th>命中率</th></tr></thead>"
            + "<tbody><tr><td>直接映射</td><td>高</td></tr>"
            + "<tr><td>组相联</td><td>更高</td></tr></tbody></table>";
    private static final String FORMULA = "<p>复杂度 \\(n^{2}\\) 与 \\(\\frac{a}{b}\\)</p>";
    private static final String OPTIONS_HTML =
            "<p>A. 甲</p><p>B. 乙</p><p>C. 丙</p><p>D. 丁</p>";

    private static NewdbQuestionRecord richRecord(String questionHtml, String optionsHtml,
                                                  String analysisHtml, String formulaSource) {
        return new NewdbQuestionRecord(2099, 1, "操作系统",
                "题干文本", "A. 甲B. 乙C. 丙D. 丁", "C",
                "解析文本", "", questionHtml, optionsHtml, analysisHtml, formulaSource);
    }

    private static NewdbQuestionRecord legacyRecord() {
        return richRecord(null, null, null, null);
    }

    @Test
    void richPathKeepsTableAndFormulas() {
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(
                richRecord(FORMULA + TABLE, OPTIONS_HTML,
                        "<p>解析含 \\(n^{2}\\)</p>", "annotation"), "ref");
        NewdbQuestionValidator.Prepared p = assertAccepted(r);
        assertTrue(p.stemHtml().contains("<table"));
        assertEquals(3, org.jsoup.Jsoup.parse(p.stemHtml()).select("tr").size());
        assertEquals(2, HtmlSanitizer.countInlineFormulas(p.stemHtml()));
        // 派生文本可用：含表格单元格文本与公式字符，不含标签
        assertTrue(p.stemText().contains("组相联"));
        assertTrue(p.stemText().contains("n^{2}"));
        assertFalse(p.stemText().contains("<table"));
    }

    @Test
    void imageInRichFragmentRejectedExplicitly() {
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(
                richRecord(FORMULA + "<img src=\"x.png\">", OPTIONS_HTML, "", "none"), "ref");
        assertTrue(r instanceof NewdbQuestionValidator.Rejected);
        assertEquals("images-not-supported-in-v0",
                ((NewdbQuestionValidator.Rejected) r).violation().reason());
    }

    @Test
    void maliciousContentStrippedInRichPath() {
        String dirty = FORMULA + "<script>alert(1)</script>"
                + "<p onclick=\"evil()\">点击</p><style>x</style>" + TABLE;
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(
                richRecord(dirty, OPTIONS_HTML, "", "none"), "ref");
        NewdbQuestionValidator.Prepared p = assertAccepted(r);
        assertFalse(p.stemHtml().contains("script"));
        assertFalse(p.stemHtml().contains("onclick"));
        assertFalse(p.stemHtml().contains("alert"));
        assertFalse(p.stemHtml().contains("<style"));
        assertTrue(p.stemHtml().contains("<table"));
    }

    @Test
    void optionsHtmlContractRequiresAllLetters() {
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(
                richRecord(FORMULA, "<p>A. 甲</p><p>B. 乙</p><p>C. 丙</p>", "", "none"), "ref");
        assertTrue(r instanceof NewdbQuestionValidator.Rejected);
        assertEquals("options-html-letter-missing",
                ((NewdbQuestionValidator.Rejected) r).violation().reason());
    }

    @Test
    void legacyTextPathUnchanged() {
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(legacyRecord(), "ref");
        NewdbQuestionValidator.Prepared p = assertAccepted(r);
        assertEquals(4, HtmlSanitizer.countDataKeys(p.stemHtml()));
        assertTrue(p.stemHtml().contains("data-key=\"B\""));
        assertTrue(p.stemHtml().contains("B. 乙"));
    }

    @Test
    void analysisHtmlFormulaPreserved() {
        NewdbQuestionValidator.Result r = NewdbQuestionValidator.validate(
                richRecord(FORMULA, OPTIONS_HTML, "<p>解析 \\(\\frac{a}{b}\\)</p>", "annotation"), "ref");
        NewdbQuestionValidator.Prepared p = assertAccepted(r);
        assertTrue(p.analysisHtml().contains("\\(\\frac{a}{b}\\)"));
    }

    private static NewdbQuestionValidator.Prepared assertAccepted(NewdbQuestionValidator.Result r) {
        assertTrue(r instanceof NewdbQuestionValidator.Accepted, "expected Accepted, got " + r);
        return ((NewdbQuestionValidator.Accepted) r).prepared();
    }
}
