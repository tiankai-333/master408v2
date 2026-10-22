package com.mindskip.xzs.newdb.importing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消毒与文本派生测试（D-33 纪律 1/3）。
 */
class HtmlSanitizerTest {

    /** 用 StemHtmlAssembler 真实组装，保证测试夹具与导入器产物同构。 */
    private static final String STRUCTURED = StemHtmlAssembler.withOptions(
            StemHtmlAssembler.stem("题干第一行"), List.of("选项甲", "选项乙"));

    @Test
    void keepsWhitelistedTagsAndAttributes() {
        String clean = HtmlSanitizer.clean(STRUCTURED);
        assertTrue(clean.contains("data-key=\"A\""));
        assertEquals(2, HtmlSanitizer.countDataKeys(clean));
    }

    @Test
    void stripsScriptAndEventHandlers() {
        String dirty = "<div class=\"q-stem\" onclick=\"evil()\"><p>题干"
                + "<script>alert(1)</script></p></div>";
        String clean = HtmlSanitizer.clean(dirty);
        assertFalse(clean.contains("script"));
        assertFalse(clean.contains("onclick"));
        assertFalse(clean.contains("evil()"));
        assertTrue(clean.contains("题干"));
    }

    @Test
    void derivesPlainTextFromStructuredHtml() {
        String text = HtmlSanitizer.toText(STRUCTURED);
        // stem_text 含题干与选项文本（检索需要选项内容）
        assertTrue(text.contains("题干第一行"));
        assertTrue(text.contains("选项甲"));
        assertTrue(text.contains("B. 选项乙"));
    }

    @Test
    void countDataKeysReflectsStructure() {
        assertEquals(0, HtmlSanitizer.countDataKeys("<div><p>无选项</p></div>"));
    }
}
