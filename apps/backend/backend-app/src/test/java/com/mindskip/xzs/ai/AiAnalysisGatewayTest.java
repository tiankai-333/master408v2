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

        String result = new AiAnalysisGateway(legacy, provider, mock(PlatoConversationPromptPolicy.class))
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
        when(springAi.analyze(any(AiAnalysisRequest.class))).thenReturn("spring answer");

        PlatoConversationPromptPolicy policy = mock(PlatoConversationPromptPolicy.class);
        when(policy.prepare("default", "q", null, "user")).thenReturn("user");

        String result = new AiAnalysisGateway(legacy, provider, policy)
                .analyze("default", "q", "k", "r", "chat");

        assertThat(result).isEqualTo("spring answer");
        verify(springAi).analyze(new AiAnalysisRequest("system", "user"));
    }
}
