package com.mindskip.xzs.newdb.importing;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * M2 编辑内容守卫：消毒 + 结构完整性（复杂内容不静默丢失）。
 * 编辑提交的 HTML 经 cleanPaper 消毒后，table/pre/svg 计数与 SVG 内嵌文字
 * 必须与消毒前一致，否则拒绝（不允许编辑过程静默丢图/丢公式）。
 */
public final class NewdbM2ContentGuard {

    private NewdbM2ContentGuard() {
    }

    /**
     * 校验并消毒单段编辑内容；返回消毒后 HTML。
     *
     * @throws IllegalArgumentException 结构丢失（编辑过程静默丢内容）
     */
    public static String cleanAndVerify(String part, String rawHtml) {
        String clean = HtmlSanitizer.cleanPaper(rawHtml == null ? "" : rawHtml);
        verifyLoss(part, rawHtml == null ? "" : rawHtml, clean);
        return clean;
    }

    /** 结构丢失校验：消毒前后 table/pre/svg 计数与 SVG 内嵌文字必须一致。 */
    public static void verifyLoss(String part, String raw, String clean) {
        if (count(raw, "<table") != HtmlSanitizer.countTables(clean)) {
            throw new IllegalArgumentException(part + "消毒后 table 丢失");
        }
        if (count(raw, "<pre") != HtmlSanitizer.countCodeBlocks(clean)) {
            throw new IllegalArgumentException(part + "消毒后 pre 丢失");
        }
        if (count(raw, "<svg") != HtmlSanitizer.countInlineSvg(clean)) {
            throw new IllegalArgumentException(part + "消毒后 svg 丢失");
        }
        if (!HtmlSanitizer.svgEmbeddedText(raw).equals(HtmlSanitizer.svgEmbeddedText(clean))) {
            throw new IllegalArgumentException(part + "消毒后 SVG 内嵌文字改变");
        }
    }

    /** 编辑版本 content_sha256（stem|analysis|correct 的 SHA-256）。 */
    public static String contentSha256(String stem, String analysis, String correct) {
        String joined = (stem == null ? "" : stem) + "|"
                + (analysis == null ? "" : analysis) + "|"
                + (correct == null ? "" : correct);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 供 controller 使用的纯文本派生。 */
    public static String toText(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        return doc.text();
    }

    private static int count(String html, String tag) {
        // tag 为固定字面量（<table/<pre/<svg），split 正则无特殊字符
        return html.split(tag, -1).length - 1;
    }
}
