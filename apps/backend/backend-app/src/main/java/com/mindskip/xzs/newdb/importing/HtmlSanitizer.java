package com.mindskip.xzs.newdb.importing;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 新库 raw HTML 消毒与文本派生（D-33 纪律 1/3，jsoup 一个依赖两用）。
 * 白名单与 StemHtmlAssembler 生成词汇表一致：结构标签（div/p + 富内容表格/上下标）
 * + class/data-key 属性；script/事件属性/style/URL 属性一律剥除（无 a/img 即无危险 URL 面）。
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.none()
            .addTags("div", "p", "table", "thead", "tbody", "tr", "td", "th",
                     "sup", "sub", "br")
            .addAttributes(":all", "class", "data-key");

    private HtmlSanitizer() {
    }

    /**
     * 统计表格数：富内容路径的结构完整性校验用（消毒后必须与消毒前一致）。
     */
    public static int countTables(String html) {
        return Jsoup.parse(html).select("table").size();
    }

    /**
     * 统计 KaTeX 行内公式定界符对（`\(`…`\)`）：消毒必须原样保留文本节点中的定界符。
     */
    public static int countInlineFormulas(String html) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("\\(", idx)) >= 0) {
            int end = html.indexOf("\\)", idx);
            if (end < 0) {
                break;
            }
            count++;
            idx = end + 2;
        }
        return count;
    }

    /**
     * 富内容是否夹带图片：v0 明确拒绝并计数（不静默丢弃后伪称题面完整）。
     */
    public static boolean hasImageTag(String html) {
        return !Jsoup.parse(html).select("img").isEmpty();
    }

    /**
     * XSS 消毒（入库前强制）。
     */
    public static String clean(String html) {
        return Jsoup.clean(html, "", SAFELIST);
    }

    /**
     * HTML → 纯文本（stem_text 派生；派生物必须可从 raw 重建）。
     */
    public static String toText(String html) {
        return Jsoup.parse(html).text();
    }

    /**
     * 统计 data-key 属性数：消毒后必须仍等于选项数（结构完整性校验）。
     */
    public static int countDataKeys(String html) {
        return Jsoup.parse(html).select("[data-key]").size();
    }
}
