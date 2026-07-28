package com.mindskip.xzs.ai.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicAiResponseEvaluatorTest {

    private final DeterministicAiResponseEvaluator evaluator =
            new DeterministicAiResponseEvaluator();

    @Test
    void passingResponseProducesReproducibleQualityCostAndLatencyMetrics() {
        AiEvaluationCase evaluationCase = modelCase();

        AiEvaluationResult result = evaluator.evaluate(
                evaluationCase,
                "你是学习导师",
                "解释进程与线程",
                "进程是资源分配的基本单位，线程通常是处理器调度的基本单位。",
                "deepseek-chat",
                321);

        assertThat(result.passed()).isTrue();
        assertThat(result.qualityScore()).isEqualTo(100);
        assertThat(result.conceptCoverage()).isEqualTo(1);
        assertThat(result.missingConcepts()).isEmpty();
        assertThat(result.estimatedInputTokens()).isPositive();
        assertThat(result.estimatedOutputTokens()).isPositive();
        assertThat(result.estimatedTotalTokens())
                .isEqualTo(result.estimatedInputTokens() + result.estimatedOutputTokens());
        assertThat(result.estimatedCost()).isPositive();
        assertThat(result.endToEndLatencyMs()).isEqualTo(321);
        assertThat(result.failureReason()).isEmpty();
    }

    @Test
    void missingConceptAndForbiddenPhraseFailWithActionableReasons() {
        AiEvaluationResult result = evaluator.evaluate(
                modelCase(),
                "",
                "",
                "线程更轻量。我的内部推理使用了向量检索。",
                "unknown",
                -1);

        assertThat(result.passed()).isFalse();
        assertThat(result.missingConcepts()).contains("资源", "调度");
        assertThat(result.forbiddenPhraseHits()).containsExactly("向量检索");
        assertThat(result.failureReason()).contains("concept-coverage", "forbidden-phrase");
        assertThat(result.endToEndLatencyMs()).isZero();
        assertThat(result.estimatedCost()).isZero();
    }

    @Test
    void tokenEstimatorIsExplicitAndStableForMixedChineseAndAscii() {
        assertThat(HeuristicTokenEstimator.estimate("数组 n O(n)")).isEqualTo(4);
        assertThat(HeuristicTokenEstimator.estimate((String[]) null)).isZero();
    }

    private AiEvaluationCase modelCase() {
        return new AiEvaluationCase(
                "process-thread",
                "basic",
                "model",
                "default",
                "explain_knowledge",
                "解释进程与线程",
                "",
                "",
                List.of("资源", "调度"),
                List.of("向量检索"),
                1,
                10,
                200,
                "answer");
    }
}
