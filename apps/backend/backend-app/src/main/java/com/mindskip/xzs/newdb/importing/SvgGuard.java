package com.mindskip.xzs.newdb.importing;

/**
 * 不可信 SVG 最小安全边界（M1 收口：受控资源端点接收来源站 SVG，不因认证就信任内容）。
 * 清洗规则（字节级，serve 前执行）：
 * 1. 拒绝 DOCTYPE/ENTITY（防 XXE/实体炸弹类文档结构）；
 * 2. 剥 script 元素整块；
 * 3. 剥 on* 事件属性（onclick/onload/…）；
 * 4. href/xlink:href 仅保留 data: 与 #锚点（剥 http(s)/javascript/ftp 等外链与协议跳转）。
 * 渲染层另有纵深：<img> 嵌入的 SVG 不执行脚本；响应头附 CSP default-src 'none' 与 nosniff。
 * 文字/图形内容（foreignObject/path/image data:）不受影响——保真口径与导入一致。
 */
public final class SvgGuard {

    private static final java.util.regex.Pattern DOCTYPE =
            java.util.regex.Pattern.compile("(?is)<!DOCTYPE[^>]*>?|<!ENTITY[^>]*>?");
    private static final java.util.regex.Pattern SCRIPT_BLOCK =
            java.util.regex.Pattern.compile("(?is)<script\\b[^>]*>.*?</script\\s*>|<script\\b[^>]*/?>");
    private static final java.util.regex.Pattern ON_EVENT_ATTR =
            java.util.regex.Pattern.compile("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final java.util.regex.Pattern EXTERNAL_HREF =
            java.util.regex.Pattern.compile(
                    "(?i)(\\s(?:xlink:)?href\\s*=\\s*)(\"(?!data:|#)[^\"]*\"|'(?!data:|#)[^']*'|(?!data:|#)[^\\s\"'>]+)");
    private static final java.util.regex.Pattern STYLE_BLOCK =
            java.util.regex.Pattern.compile("(?is)<style\\b[^>]*>.*?</style\\s*>");
    private static final java.util.regex.Pattern STYLE_ATTR_URL =
            java.util.regex.Pattern.compile("(?i)(\\sstyle\\s*=\\s*\"[^\"]*?)url\\s*\\([^)]*\\)");

    private SvgGuard() {
    }

    public static byte[] clean(byte[] raw) {
        String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        s = DOCTYPE.matcher(s).replaceAll("");
        s = SCRIPT_BLOCK.matcher(s).replaceAll("");
        s = STYLE_BLOCK.matcher(s).replaceAll("");
        s = ON_EVENT_ATTR.matcher(s).replaceAll("");
        s = EXTERNAL_HREF.matcher(s).replaceAll("$1\"#stripped-external-link\"");
        s = STYLE_ATTR_URL.matcher(s).replaceAll("$1none");
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
