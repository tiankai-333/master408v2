package com.mindskip.xzs.ai.evaluation;

import java.util.List;

/**
 * Deterministic quality and cost result for one model response.
 *
 * <p>Token fields are explicitly named estimates. Exact provider usage is recorded
 * separately when response metadata is available.</p>
 */
public record AiEvaluationResult(
        String caseId,
        boolean passed,
        double qualityScore,
        double conceptCoverage,
        List<String> missingConcepts,
        List<String> forbiddenPhraseHits,
        int responseChars,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        int estimatedTotalTokens,
        double estimatedCost,
        long endToEndLatencyMs,
        String failureReason) {

    public AiEvaluationResult {
        missingConcepts = missingConcepts == null ? List.of() : List.copyOf(missingConcepts);
        forbiddenPhraseHits = forbiddenPhraseHits == null ? List.of() : List.copyOf(forbiddenPhraseHits);
    }
}
