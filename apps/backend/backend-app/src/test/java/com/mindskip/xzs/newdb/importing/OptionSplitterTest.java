package com.mindskip.xzs.newdb.importing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项切分纯函数测试：用真实爬虫数据样本（含全量验证中发现的边界形态）。
 */
class OptionSplitterTest {

    @Test
    void splitsRealSample2011Q1() {
        Optional<List<String>> result = OptionSplitter.split("A.O(logn)B.O(n)C.O(nlog2​n)D.O(n2)");
        assertTrue(result.isPresent());
        assertEquals(4, result.get().size());
        assertEquals("O(logn)", result.get().get(0));
        assertEquals("O(n2)", result.get().get(3));
    }

    @Test
    void splitsNumericOptions() {
        Optional<List<String>> result = OptionSplitter.split("A.115B.116C.1895D.1896");
        assertTrue(result.isPresent());
        assertEquals(List.of("115", "116", "1895", "1896"), result.get());
    }

    @Test
    void splitsOptionsContainingRomanNumerals() {
        // 2018 Q21 形态：选项内容含Ⅰ、Ⅱ——顺序贪婪扫描不受影响
        Optional<List<String>> result = OptionSplitter.split("A.仅Ⅰ、ⅡB.仅Ⅰ、Ⅱ、ⅢC.仅Ⅲ、ⅣD.Ⅰ、Ⅱ、Ⅲ和Ⅳ");
        assertTrue(result.isPresent());
        assertEquals("仅Ⅰ、Ⅱ", result.get().get(0));
        assertEquals("Ⅰ、Ⅱ、Ⅲ和Ⅳ", result.get().get(3));
    }

    @Test
    void rejectsBlankInput() {
        assertTrue(OptionSplitter.split("").isEmpty());
        assertTrue(OptionSplitter.split("   ").isEmpty());
        assertTrue(OptionSplitter.split(null).isEmpty());
    }

    @Test
    void rejectsMissingMarker() {
        assertTrue(OptionSplitter.split("A.仅三个标记B.缺C.缺").isEmpty());
    }

    @Test
    void rejectsEmptySegment() {
        assertTrue(OptionSplitter.split("A.B.二C.三D.四").isEmpty());
    }
}
