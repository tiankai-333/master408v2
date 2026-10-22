package com.mindskip.xzs.newdb.importing;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 新库 raw HTML 消毒与文本派生（D-33 纪律 1/3，jsoup 一个依赖两用）。
 * 白名单与 StemHtmlAssembler 生成词汇表一致：仅 div/p 标签 + class/data-key 属性，
 * script/事件属性/其他标签一律剥除。
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.none()
            .addTags("div", "p")
            .addAttributes(":all", "class", "data-key");

    private HtmlSanitizer() {
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
