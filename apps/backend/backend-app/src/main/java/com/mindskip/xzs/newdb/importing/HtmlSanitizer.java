package com.mindskip.xzs.newdb.importing;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.util.regex.Pattern;

/**
 * 新库 raw HTML 消毒与文本派生（D-33 纪律 1/3，jsoup 一个依赖两用）。
 * 白名单与 StemHtmlAssembler 生成词汇表一致：结构标签（div/p + 富内容表格/上下标）
 * + class/data-key 属性；script/事件属性/style/URL 属性一律剥除（无 a/img 即无危险 URL 面）。
 */
public final class HtmlSanitizer {

    private static final Pattern STYLE_ATTR = Pattern.compile("style=\"([^\"]*)\"");

    private static final Safelist SAFELIST = Safelist.none()
            .addTags("div", "p", "table", "thead", "tbody", "tr", "td", "th",
                     "sup", "sub", "br")
            .addAttributes(":all", "class", "data-key");

    /**
     * 候选库整卷 profile（M1）：在 v0 白名单上扩展富内容词汇——代码块、行内 span（KaTeX
     * 渲染层）、表格跨列/跨行、内联 SVG 几何。script/事件属性/URL 面依旧全剥
     * （img/a 不在白名单即无危险 URL 标签）；span.style 经 {@link #cleanPaper}
     * 后置过滤，仅保留 KaTeX 排版所需几何属性，含 url()/expression 的值整条拒绝。
     */
    private static final Safelist PAPER_SAFELIST = Safelist.none()
            .addTags("div", "p", "table", "thead", "tbody", "tr", "td", "th",
                     "sup", "sub", "br", "span", "pre", "code",
                     "svg", "g", "path", "circle", "rect", "line", "polyline", "polygon",
                     "text", "tspan", "title", "defs", "use", "ellipse",
                     // drawio 图形保真：文字在 foreignObject 内联 HTML，箭头为 base64 image
                     "foreignObject", "image",
                     // 本地受控资源图（导入器已改写为 /api/newdb/asset/ 相对路径）
                     "img")
            .addAttributes(":all", "class", "data-key", "xmlns")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("span", "style")
            .addAttributes("div", "style")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("foreignObject", "width", "height", "style")
            .addAttributes("image", "x", "y", "width", "height", "xlink:href")
            .addAttributes("svg", "viewBox", "xmlns", "width", "height",
                           "preserveAspectRatio", "fill", "stroke", "content")
            .addAttributes("path", "d", "fill", "stroke", "stroke-width", "stroke-linecap",
                           "stroke-linejoin", "stroke-dasharray", "transform", "fill-opacity",
                           "stroke-opacity")
            .addAttributes("g", "transform", "fill", "stroke", "stroke-width", "font-size",
                           "font-family", "font-style", "font-weight", "stroke-dasharray")
            .addAttributes("text", "x", "y", "font-size", "font-family", "font-style",
                           "font-weight", "text-anchor", "fill", "stroke", "transform")
            .addAttributes("tspan", "x", "y", "dx", "dy", "font-size", "font-style",
                           "font-weight", "fill", "stroke")
            .addAttributes("circle", "cx", "cy", "r", "fill", "stroke", "stroke-width", "transform")
            .addAttributes("ellipse", "cx", "cy", "rx", "ry", "fill", "stroke", "stroke-width", "transform")
            .addAttributes("rect", "x", "y", "width", "height", "rx", "ry",
                           "fill", "stroke", "stroke-width", "transform")
            .addAttributes("line", "x1", "y1", "x2", "y2", "stroke", "stroke-width",
                           "transform", "stroke-dasharray")
            .addAttributes("polyline", "points", "fill", "stroke", "stroke-width", "transform")
            .addAttributes("polygon", "points", "fill", "stroke", "stroke-width", "transform")
            // image 的 xlink:href 仅允许 data: 协议（drawio 箭头内嵌 PNG），外链一律剥
            .addProtocols("image", "xlink:href", "data");

    /**
     * style 属性仅放行的几何/排版声明（KaTeX struts、表格与 drawio foreignObject
     * 定位所需）；含 url()/expression/javascript 的值整条拒绝。
     */
    private static final java.util.Set<String> STYLE_ALLOWED_PROPERTIES = java.util.Set.of(
            "height", "vertical-align", "margin-left", "margin-right", "margin-top",
            "margin-bottom", "padding-left", "padding-right", "padding-top", "padding-bottom",
            "top", "left", "right", "bottom", "position",
            "min-width", "width", "max-width", "text-align", "white-space", "border-collapse",
            "font-size", "font-style", "font-weight", "font-family", "color",
            "display", "align-items", "justify-content", "box-sizing", "line-height",
            "overflow", "overflow-wrap", "overflow-x", "overflow-y", "border",
            "background-color", "border-color", "border-width", "border-style",
            "stroke", "fill", "stroke-width", "letter-spacing", "word-break",
            "box-shadow", "border-radius", "cursor");

    /** 本地受控资源 img 的唯一合法 src 前缀（导入器改写产物）。 */
    public static final String ASSET_SRC_PREFIX = "/api/newdb/asset/";

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
     * 候选库整卷消毒（M1）：PAPER_SAFELIST + style 属性后置过滤 + img src 后置校验。
     * 过滤规则：仅保留 {@link #STYLE_ALLOWED_PROPERTIES} 中的属性；任一声明值含
     * url(/expression/javascript 即整条丢弃。img 仅允许 {@link #ASSET_SRC_PREFIX}
     * 相对路径（受控资源端点），其余 src 剥除并记缺失。消毒后 script/事件属性不存在。
     */
    public static String cleanPaper(String html) {
        String cleaned = Jsoup.clean(html, "", PAPER_SAFELIST);
        cleaned = STYLE_ATTR.matcher(cleaned).replaceAll(mr -> {
            String kept = filterStyleDeclarations(mr.group(1));
            return kept.isEmpty() ? "" : "style=\"" + kept + "\"";
        });
        return BAD_IMG_SRC.matcher(cleaned).replaceAll(mr -> {
            String src = mr.group(2) != null ? mr.group(2)
                    : mr.group(3) != null ? mr.group(3) : mr.group(1);
            return src.startsWith(ASSET_SRC_PREFIX) ? mr.group(0) : "";
        });
    }

    private static final java.util.regex.Pattern BAD_IMG_SRC =
            java.util.regex.Pattern.compile("<img\\b[^>]*\\bsrc=(\"([^\"]*)\"|'([^']*)'|[^\\s>]+)[^>]*>");

    private static String filterStyleDeclarations(String value) {
        StringBuilder kept = new StringBuilder();
        for (String decl : value.split(";")) {
            String d = decl.trim();
            if (d.isEmpty()) {
                continue;
            }
            String lower = d.toLowerCase();
            if (lower.contains("url(") || lower.contains("expression")
                    || lower.contains("javascript")) {
                continue;
            }
            int colon = d.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String prop = d.substring(0, colon).trim().toLowerCase();
            if (STYLE_ALLOWED_PROPERTIES.contains(prop)) {
                kept.append(d).append("; ");
            }
        }
        return kept.toString().stripTrailing();
    }

    /** 统计内联 SVG 数（结构完整性对账：消毒前后必须一致）。 */
    public static int countInlineSvg(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        return doc.select("svg").size();
    }

    /** 统计 pre 代码块数（同上）。 */
    public static int countCodeBlocks(String html) {
        return Jsoup.parse(html).select("pre").size();
    }

    /** 统计合法 img 数（消毒前后必须一致，防静默丢图）。 */
    public static int countImages(String html) {
        return Jsoup.parse(html).select("img").size();
    }

    /** 统计 SVG 内 image 元素（drawio 箭头 base64 图）数。 */
    public static int countSvgImages(String html) {
        return Jsoup.parse(html).select("svg image").size();
    }

    /**
     * SVG 内嵌文字（foreignObject 及 text）全文拼接，供保真对比：
     * drawio 的节点文字在 foreignObject 里，剥壳留文或丢 div 定位都会在此暴露。
     */
    public static String svgEmbeddedText(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Element svg : doc.select("svg")) {
            sb.append(svg.select("foreignObject").text());
            sb.append(svg.select("text").text());
        }
        return sb.toString();
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
