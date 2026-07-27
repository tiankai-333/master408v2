package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.prompt.PromptContext;
import com.mindskip.xzs.ai.prompt.PromptRegistry;
import com.mindskip.xzs.ai.prompt.ResolvedPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 AnalysisService.resolveAnalysisPrompt：
 *  - registry 命中 → 使用 DB 版本内容 + 记录 versionId/releaseId；
 *  - registry 未命中 → 回退 JSON 模板 + ref.unknown；
 *  - 工作台走独立 workbench key，发布的 system/user 模板都进入最终请求。
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
    void workbenchUsesIndependentPublishedPromptInFinalRequest() {
        AnalysisService svc = newAnalysisService();
        PromptRegistry registry = mock(PromptRegistry.class);
        ReflectionTestUtils.setField(svc, "promptRegistry", registry);
        ResolvedPrompt rp = new ResolvedPrompt(
                "workbench.default", 1L, 42L, 9L,
                "published workbench system",
                "PUBLISHED_MARKER\n{knowledge_points_section}{reference_docs_section}问题={question}\n{task_rules}",
                "[]", null, 0, false);
        when(registry.resolve(eq("workbench.default"), any(PromptContext.class))).thenReturn(rp);

        AiAnalysisRequest request = svc.buildAnalysisRequest(
                "default", "为什么设备独立性重要？", "设备独立性",
                "教材资料", "explain_knowledge", "conversation-1");

        assertThat(request.systemPrompt()).isEqualTo("published workbench system");
        assertThat(request.userPrompt()).contains("PUBLISHED_MARKER");
        assertThat(request.userPrompt()).contains("设备独立性", "教材资料", "为什么设备独立性重要？");
        assertThat(request.promptRef().promptKey()).isEqualTo("workbench.default");
        assertThat(request.promptRef().versionId()).isEqualTo(42L);
        assertThat(request.promptRef().releaseId()).isEqualTo(9L);
        verify(registry).resolve(eq("workbench.default"), any(PromptContext.class));
        verify(registry, never()).resolve(eq("analysis.default"), any(PromptContext.class));
    }

    @Test
    void workbenchRegistryMissUsesSafeCodeFallback() {
        AnalysisService svc = newAnalysisService();
        PromptRegistry registry = mock(PromptRegistry.class);
        ReflectionTestUtils.setField(svc, "promptRegistry", registry);
        when(registry.resolve(eq("workbench.feynman"), any(PromptContext.class))).thenReturn(null);

        AiAnalysisRequest request = svc.buildAnalysisRequest(
                "feynman", "什么是局部性原理？", null, null,
                "explain_knowledge", "conversation-2");

        assertThat(request.systemPrompt()).contains("408Master");
        assertThat(request.userPrompt()).contains("费曼式", "什么是局部性原理？");
        assertThat(request.promptRef().promptKey()).isEqualTo("workbench.feynman");
        assertThat(request.promptRef().versionId()).isNull();
    }

    private static AnalysisService newAnalysisService() {
        // 无参构造会从 classpath 加载 4 个 JSON 模板作为兜底
        return new AnalysisService();
    }
}
