package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisClient;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisGatewayTest {

    @Test
    void keepsLegacyAsFallback() throws Exception {
        AnalysisService legacy = mock(AnalysisService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAnalysisClient> provider = mock(ObjectProvider.class);
        when(legacy.analyzeWithAI("default", "q", "k", "r", "chat"))
                .thenReturn("legacy answer");

        String result = new AiAnalysisGateway(legacy, provider,
                mock(PlatoConversationPromptPolicy.class), mock(AiUsageObservationService.class),
                passthroughPolicy())
                .analyze("default", "q", "k", "r", "chat");

        assertThat(result).isEqualTo("legacy answer");
    }

    @Test
    void routesSystemAndUserPromptsThroughSpringAiWhenEnabled() throws Exception {
        AnalysisService legacy = mock(AnalysisService.class);
        AiAnalysisClient springAi = mock(AiAnalysisClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiAnalysisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(springAi);
        when(legacy.buildAnalysisRequest("default", "q", "k", "r", "chat", null))
                .thenReturn(new AiAnalysisRequest("system", "user", null, null));
        when(springAi.analyzeResult(any(AiAnalysisRequest.class)))
                .thenReturn(com.mindskip.xzs.ai.client.AiAnalysisResult.estimated(
                        new AiAnalysisRequest("system", "user"), "spring answer"));

        PlatoConversationPromptPolicy policy = mock(PlatoConversationPromptPolicy.class);
        when(policy.prepare("default", "q", null, "user")).thenReturn("user");

        AiUsageObservationService observations = mock(AiUsageObservationService.class);
        when(observations.observe(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(Boolean.class), any()))
                .thenReturn(new AiUsageObservationService.Observation(7, "request-7"));

        String result = new AiAnalysisGateway(
                legacy, provider, policy, observations, passthroughPolicy())
                .analyze("default", "q", "k", "r", "chat");

        assertThat(result).isEqualTo("spring answer");
        verify(springAi).analyzeResult(new AiAnalysisRequest("system", "user"));
    }

    private com.mindskip.xzs.ai.resilience.AiResiliencePolicy passthroughPolicy() {
        return new com.mindskip.xzs.ai.resilience.AiResiliencePolicy(
                4, 100, 1, java.time.Duration.ofMillis(1),
                5, java.time.Duration.ofSeconds(1),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
