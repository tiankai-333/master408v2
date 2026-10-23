package com.mindskip.xzs.newdb.importing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不可信 SVG 清洗反例测试（资源端点安全边界）：
 * script 块/on* 事件/外链 href/DOCTYPE 实体/style url() 必须剥除；
 * drawio 正常内容（foreignObject 文字、image data: 箭头）必须原样保留。
 */
class SvgGuardTest {

    @Test
    void maliciousVectorsStripped() {
        String evil = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<script>alert(1)</script>"
                + "<rect width=\"10\" height=\"10\" onload=\"evil()\" onclick='evil()' fill=\"red\"/>"
                + "<a xlink:href=\"http://evil.example/track\"><text>go</text></a>"
                + "<circle r=\"5\" href=\"javascript:evil()\"/>"
                + "<style>@import url(http://evil.example/x.css);</style>"
                + "<rect style=\"fill:url(http://evil.example/x.svg);stroke:none\"/>"
                + "</svg>";
        String clean = new String(SvgGuard.clean(evil.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        assertFalse(clean.contains("<script"), "script 块必须剥除: " + clean);
        assertFalse(clean.contains("alert(1)"));
        assertFalse(clean.toLowerCase().contains("onload"), "on* 事件必须剥除");
        assertFalse(clean.toLowerCase().contains("onclick"));
        assertFalse(clean.contains("http://evil.example"), "外链必须剥除");
        assertFalse(clean.contains("javascript:"), "javascript: 协议必须剥除");
        assertFalse(clean.toUpperCase().contains("<!DOCTYPE"), "DOCTYPE/ENTITY 必须剥除");
        assertFalse(clean.contains("@import"), "style 块必须剥除");
        assertFalse(clean.contains("url("), "style 属性 url() 必须剥除");
        assertTrue(clean.contains("<text>go</text>"), "普通图形内容保留");
        assertTrue(clean.contains("fill=\"red\""), "正常属性保留");
    }

    @Test
    void legitDrawioContentUntouched() {
        String legit = "<svg viewBox=\"0 0 10 10\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<foreignObject style=\"overflow:visible\"><div xmlns=\"http://www.w3.org/1999/xhtml\" "
                + "style=\"display: flex; padding-top: 110px; margin-left: 186px;\">21</div></foreignObject>"
                + "<image x=\"1\" y=\"2\" width=\"3\" height=\"4\" xlink:href=\"data:image/png;base64,iVBORw0KGgo=\"/>"
                + "</svg>";
        String clean = new String(SvgGuard.clean(legit.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        assertEquals(legit, clean, "drawio 正常内容不得被改写");
    }
}
