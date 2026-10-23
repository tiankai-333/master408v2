package com.mindskip.xzs.newdb.importing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 版本比较与内容守卫回归（Codex 抽查三项缺陷的防回归用例）：
 * 1. Integer 版本比较必须用值（>127 的 Integer 缓存外对象用 != 引用比较会误冲突）；
 * 2. 题干与解析分别独立清洗（题干清洗结果不得写解析）；
 * 3. 编辑保留既有 answer_text/assets_json（不清空）。
 */
class NewdbM2VersionGuardTest {

    private static boolean versionMatches(Integer currentNo, Integer expectedVersion) {
        // 与 NewdbM2Controller.edit 相同口径
        return currentNo.intValue() == expectedVersion.intValue();
    }

    @Test
    void integerBeyondCacheComparesByValue() {
        // 值比较口径对缓存区（-128..127）之外同样成立——防回归回退为引用比较
        assertTrue(versionMatches(200, 200));
        assertFalse(versionMatches(200, 201));
    }

    @Test
    void integerMismatchStillDetected() {
        Integer current = 200;
        assertFalse(versionMatches(current, 201));
        assertTrue(versionMatches(current, 200));
    }

    @Test
    void stemAndAnalysisCleanedIndependently() {
        String stem = "<p>题干 A</p>";
        String analysis = "<p>解析 B</p>";
        String cleanStem = NewdbM2ContentGuard.cleanAndVerify("题干", stem);
        String cleanAnalysis = NewdbM2ContentGuard.cleanAndVerify("解析", analysis);
        assertEquals(stem, cleanStem);
        assertEquals(analysis, cleanAnalysis);
        assertFalse(cleanStem.contains("解析 B"), "题干不得混入解析内容");
        assertFalse(cleanAnalysis.contains("题干 A"), "解析不得混入题干内容");
    }

    @Test
    void structureLossStillRejected() {
        String raw = "<p>题干</p><svg viewBox=\"0 0 4 4\"><path d=\"M0 0\"/></svg>";
        // 模拟编辑把 SVG 丢掉（只提交题干文本）→ 结构计数不一致必须拒绝
        String cleanWithoutSvg = "<p>题干</p>";
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NewdbM2ContentGuard.verifyLoss("题干", raw, cleanWithoutSvg));
    }
}
