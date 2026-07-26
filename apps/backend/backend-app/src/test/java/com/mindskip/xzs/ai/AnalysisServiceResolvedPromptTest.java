package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.prompt.PromptContext;
import com.mindskip.xzs.ai.prompt.PromptRegistry;
import com.mindskip.xzs.ai.prompt.ResolvedPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 AnalysisService.resolveAnalysisPrompt：
 *  - registry 命中 → 使用 DB 版本内容 + 记录 versionId/releaseId；
 *  - registry 未命中 → 回退 JSON 模板 + ref.unknown；
 *  - workbench+default → systemPrompt 被覆盖为固定文案。
 */
class AnalysisServiceResolvedPromptTest {

    @Test
    void registryHitUsesResolvedContentAndRef() {
        AnalysisService svc = newAnalysisService();
        PromptRegistry registry = mock(PromptRegistry.class);
        ReflectionTestUtils.setField(svc, "promptRegistry", registry);

        ResolvedPrompt rp = new ResolvedPrompt(
                "analysis.default", 1L, 42L, 9L,
                "db system prompt", "{question}", "[]", null, 0, false);
        when(registry.resolve(eq("analysis.default"), any(PromptContext.class))).thenReturn(rp);

        ResolvedAnalysisPrompt rap = svc.resolveAnalysisPrompt("default", "chat", PromptContext.anonymous());

        assertThat(rap.systemPrompt()).isEqualTo("db system prompt");
        assertThat(rap.ref().versionId()).isEqualTo(42L);
        assertThat(rap.ref().releaseId()).isEqualTo(9L);
        assertThat(rap.formatUserPrompt("Q", null, null)).contains("Q");
    }

    @Test
    void registryMissFallsBackToJsonAndUnknownRef() {
        AnalysisService svc = newAnalysisService();
        PromptRegistry registry = mock(PromptRegistry.class);
        ReflectionTestUtils.setField(svc, "promptRegistry", registry);
        when(registry.resolve(eq("analysis.default"), any(PromptContext.class))).thenReturn(null);

        ResolvedAnalysisPrompt rap = svc.resolveAnalysisPrompt("default", "chat", PromptContext.anonymous());

        assertThat(rap.systemPrompt()).isNotBlank();
        assertThat(rap.ref().versionId()).isNull();   // unknown ref
        assertThat(rap.ref().promptKey()).isEqualTo("analysis.default");
    }

    @Test
    void workbenchDefaultOverridesSystemPrompt() {
        AnalysisService svc = newAnalysisService();
        PromptRegistry registry = mock(PromptRegistry.class);
        ReflectionTestUtils.setField(svc, "promptRegistry", registry);
        ResolvedPrompt rp = new ResolvedPrompt(
                "analysis.default", 1L, 42L, 9L, "db system", "{question}", "[]", null, 0, false);
        when(registry.resolve(eq("analysis.default"), any(PromptContext.class))).thenReturn(rp);

        // workbench 任务 + default 风格 → systemPrompt 被覆盖（主路径 applyWorkbenchOverride=true）
        ResolvedAnalysisPrompt rap = svc.resolveAnalysisPrompt("default", "explain", PromptContext.anonymous());
        assertThat(rap.systemPrompt()).isEqualTo("你是一个有帮助的AI助手。");

        // applyWorkbenchOverride=false（custom 路径）→ 不覆盖，保留 DB systemPrompt
        ResolvedAnalysisPrompt rap2 = svc.resolveAnalysisPrompt("default", "explain", PromptContext.anonymous(), false);
        assertThat(rap2.systemPrompt()).isEqualTo("db system");
    }

    private static AnalysisService newAnalysisService() {
        // 无参构造会从 classpath 加载 4 个 JSON 模板作为兜底
        return new AnalysisService();
    }
}
