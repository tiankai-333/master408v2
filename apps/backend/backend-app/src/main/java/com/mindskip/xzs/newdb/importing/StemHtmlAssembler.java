package com.mindskip.xzs.newdb.importing;

import java.util.List;

/**
 * 从纯文本源组装新库 raw HTML（v0 无选项列：选项在 stem_html 内，plan D7）。
 * 结构约定：
 *   stem_html     = div.q-stem（题干逐行 p）+ div.q-options（4 个 p.q-option，data-key 建模字母）
 *   analysis_html = div.q-analysis
 * 组装时文本先 HTML 转义；产物再经 {@link HtmlSanitizer} 白名单消毒后入库（D-33 纪律 1）。
 */
public final class StemHtmlAssembler {

    private StemHtmlAssembler() {
    }

    public static String stem(String rawText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"q-stem\">");
        appendParagraphs(sb, rawText);
        sb.append("</div>");
        return sb.toString();
    }

    public static String withOptions(String stemHtml, List<String> options) {
        StringBuilder sb = new StringBuilder(stemHtml);
        sb.append("<div class=\"q-options\">");
        for (int i = 0; i < options.size(); i++) {
            char key = (char) ('A' + i);
            // 字母同时进 data-key（结构化）与文本（stem_text 派生保留字母，贴近来源页）
            sb.append("<p class=\"q-option\" data-key=\"").append(key).append("\">")
                    .append(escape(key + ". " + options.get(i)))
                    .append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    public static String analysis(String rawText) {
        StringBuilder sb = new StringBuilder("<div class=\"q-analysis\">");
        appendParagraphs(sb, rawText);
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 富内容路径（v1）：源 HTML 片段（爬虫保留的结构标记 + `\(`…`\)` 公式文本）按同一
     * 容器约定包裹；片段本身不转义（保留 table/sup/sub 标记），入库前仍过白名单消毒。
     */
    public static String richStem(String stemFragment) {
        return "<div class=\"q-stem\">" + stemFragment + "</div>";
    }

    public static String richWithOptions(String stemHtml, String optionsFragment) {
        return stemHtml + "<div class=\"q-options\">" + optionsFragment + "</div>";
    }

    public static String richAnalysis(String analysisFragment) {
        return "<div class=\"q-analysis\">" + analysisFragment + "</div>";
    }

    private static void appendParagraphs(StringBuilder sb, String rawText) {
        for (String line : rawText.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                sb.append("<p>").append(escape(trimmed)).append("</p>");
            }
        }
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
