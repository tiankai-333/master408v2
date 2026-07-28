package com.mindskip.xzs.ai.evaluation;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import com.mindskip.xzs.repository.AiEvaluationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEvaluationServiceTest {

    @Test
    void startPersistsBoundedSelectionAndDispatchesOnlyModelCases() {
        AiEvaluationMapper mapper = mock(AiEvaluationMapper.class);
        AiEvaluationWorker worker = mock(AiEvaluationWorker.class);
        AiAnalysisGateway gateway = mock(AiAnalysisGateway.class);
        when(gateway.engineName()).thenReturn("spring-ai");
        doAnswer(invocation -> {
            AiEvaluationRun run = invocation.getArgument(0);
            run.setId(7L);
            return 1;
        }).when(mapper).insertRun(any(AiEvaluationRun.class));
        when(mapper.selectRun(7L)).thenAnswer(invocation -> {
            AiEvaluationRun run = new AiEvaluationRun();
            run.setId(7L);
            run.setStatus("queued");
            return run;
        });
        AiEvaluationService service = new AiEvaluationService(
                mapper, new ObjectMapper(), worker, gateway);

        AiEvaluationRun run = service.start(new AiEvaluationRunRequest(
                "candidate-a", List.of("basic-process-thread")), 3);

        assertThat(run.getId()).isEqualTo(7);
        ArgumentCaptor<List<AiEvaluationCase>> cases = ArgumentCaptor.forClass(List.class);
        verify(worker).execute(org.mockito.ArgumentMatchers.eq(7L), cases.capture());
        assertThat(cases.getValue()).extracting(AiEvaluationCase::id)
                .containsExactly("basic-process-thread");
        assertThat(cases.getValue()).allMatch(AiEvaluationCase::isModelCase);
    }

    @Test
    void contractCaseCannotBeAccidentallySentToExternalModel() {
        AiEvaluationMapper mapper = mock(AiEvaluationMapper.class);
        AiEvaluationService service = new AiEvaluationService(
                mapper,
                new ObjectMapper(),
                mock(AiEvaluationWorker.class),
                mock(AiAnalysisGateway.class));

        assertThatThrownBy(() -> service.start(new AiEvaluationRunRequest(
                "bad-run", List.of("failure-provider-rate-limit")), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-model");
    }
}
