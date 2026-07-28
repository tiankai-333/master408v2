package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.client.AiAnalysisResult;
import com.mindskip.xzs.ai.prompt.PromptRef;
import com.mindskip.xzs.domain.ai.AiUsageLog;
import com.mindskip.xzs.repository.AiUsageLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiUsageObservationServiceTest {

    @AfterEach
    void clearContext() {
        AnalysisService.clearCurrentUserId();
    }

    @Test
    void persistsTraceUsagePromptVersionAndCurrentUserTogether() {
        AiUsageLogMapper mapper = mock(AiUsageLogMapper.class);
        doAnswer(invocation -> {
            invocation.<AiUsageLog>getArgument(0).setId(44);
            return 1;
        }).when(mapper).insert(org.mockito.ArgumentMatchers.any(AiUsageLog.class));
        AnalysisService.setCurrentUserId(8);
        AiAnalysisRequest request = new AiAnalysisRequest(
                "system", "user", "user:8:conversation:c1",
                new PromptRef("analysis.default", 2L, 3L, 4L));
        AiAnalysisResult result = new AiAnalysisResult(
                "answer", "deepseek-chat", 100, 20, 120, 10, "provider");

        AiUsageObservationService.Observation observation = new AiUsageObservationService(mapper)
                .observe("sync", "default", "question", "kp", "docs",
                        request, result, 80, null, true, null);

        ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(mapper).insert(captor.capture());
        AiUsageLog log = captor.getValue();
        assertThat(observation.usageLogId()).isEqualTo(44);
        assertThat(log.getRequestId()).isNotBlank();
        assertThat(log.getUserId()).isEqualTo(8);
        assertThat(log.getEngine()).isEqualTo("spring-ai");
        assertThat(log.getUsageSource()).isEqualTo("provider");
        assertThat(log.getInputTokens()).isEqualTo(100);
        assertThat(log.getPromptVersionId()).isEqualTo(3L);
    }
}
