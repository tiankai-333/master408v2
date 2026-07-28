package com.mindskip.xzs.ai.evaluation;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.ai.AnalysisService;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.prompt.PromptRef;
import com.mindskip.xzs.domain.ai.AiEvaluationCaseResultRecord;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.repository.AiEvaluationMapper;
import com.mindskip.xzs.service.AiProviderConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvaluationWorkerTest {

    @Test
    void successfulCasePersistsPromptVersionQualityCostAndAggregate() throws Exception {
        AiEvaluationMapper mapper = mock(AiEvaluationMapper.class);
        AiAnalysisGateway gateway = mock(AiAnalysisGateway.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        AiProviderConfigService providerService = mock(AiProviderConfigService.class);
        when(mapper.markRunStarted(9L)).thenReturn(1);
        when(analysisService.buildAnalysisRequest(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AiAnalysisRequest(
                        "system", "user", null,
                        new PromptRef("workbench.default", 1L, 2L, 3L)));
        when(gateway.analyze(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("进程负责资源分配，线程通常是处理器调度的基本单位。");
        AiProviderConfig provider = new AiProviderConfig();
        provider.setChatModel("deepseek-chat");
        when(providerService.getFirstEnabled()).thenReturn(provider);

        AiEvaluationWorker worker = new AiEvaluationWorker(
                mapper, gateway, analysisService, providerService, new ObjectMapper());
        worker.execute(9L, List.of(modelCase()));

        ArgumentCaptor<AiEvaluationCaseResultRecord> caseRecord =
                ArgumentCaptor.forClass(AiEvaluationCaseResultRecord.class);
        verify(mapper).insertCaseResult(caseRecord.capture());
        assertThat(caseRecord.getValue().getPassed()).isTrue();
        assertThat(caseRecord.getValue().getPromptKey()).isEqualTo("workbench.default");
        assertThat(caseRecord.getValue().getPromptVersionId()).isEqualTo(2);
        assertThat(caseRecord.getValue().getPromptReleaseId()).isEqualTo(3);
        assertThat(caseRecord.getValue().getEstimatedCost()).isPositive();

        ArgumentCaptor<AiEvaluationRun> aggregate =
                ArgumentCaptor.forClass(AiEvaluationRun.class);
        verify(mapper).completeRun(aggregate.capture());
        assertThat(aggregate.getValue().getPassedCount()).isEqualTo(1);
        assertThat(aggregate.getValue().getAverageQualityScore()).isPositive();
        assertThat(aggregate.getValue().getEstimatedInputTokens()).isPositive();
        assertThat(aggregate.getValue().getEstimatedOutputTokens()).isPositive();
    }

    private AiEvaluationCase modelCase() {
        return new AiEvaluationCase(
                "basic-process-thread", "basic", "model", "default",
                "explain_knowledge", "进程和线程有什么区别？", "进程、线程", "",
                List.of("资源", "调度"), List.of("向量检索"),
                1, 10, 300, "answer");
    }
}
