package com.mindskip.xzs.ai.evaluation;

import com.mindskip.xzs.ai.AiPricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Low-cost first-pass evaluator.
 *
 * <p>It measures explicit contract properties only. Semantic correctness beyond the
 * expected concepts still requires human review or a separately configured model judge.</p>
 */
public class DeterministicAiResponseEvaluator {

    public AiEvaluationResult evaluate(AiEvaluationCase evaluationCase,
                                       String systemPrompt,
                                       String userPrompt,
                                       String response,
                                       String model,
                                       long endToEndLatencyMs) {
        String safeResponse = response == null ? "" : response.trim();
        String normalized = normalize(safeResponse);

        List<String> missingConcepts = evaluationCase.expectedConcepts().stream()
                .filter(concept -> !normalized.contains(normalize(concept)))
                .toList();
        int expectedCount = evaluationCase.expectedConcepts().size();
        double coverage = expectedCount == 0
                ? 1
                : (double) (expectedCount - missingConcepts.size()) / expectedCount;

        List<String> forbiddenHits = new ArrayList<>();
        for (String phrase : evaluationCase.forbiddenPhrases()) {
            if (!phrase.isBlank() && normalized.contains(normalize(phrase))) {
                forbiddenHits.add(phrase);
            }
        }

        int responseChars = safeResponse.codePointCount(0, safeResponse.length());
        boolean lengthPassed = responseChars >= evaluationCase.minimumResponseChars()
                && responseChars <= evaluationCase.maximumResponseChars();
        boolean coveragePassed = coverage >= evaluationCase.minimumConceptCoverage();
        boolean safetyPassed = forbiddenHits.isEmpty();
        boolean nonEmpty = !safeResponse.isEmpty();

        double qualityScore = round2(
                coverage * 70
                        + (safetyPassed ? 15 : 0)
                        + (lengthPassed && nonEmpty ? 15 : 0));
        boolean passed = nonEmpty && lengthPassed && coveragePassed && safetyPassed;

        int inputTokens = HeuristicTokenEstimator.estimate(systemPrompt, userPrompt);
        int outputTokens = HeuristicTokenEstimator.estimate(safeResponse);
        String failureReason = failureReason(nonEmpty, lengthPassed, coveragePassed, safetyPassed);
        return new AiEvaluationResult(
                evaluationCase.id(),
                passed,
                qualityScore,
                round4(coverage),
                missingConcepts,
                forbiddenHits,
                responseChars,
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                AiPricing.calculateCost(model, inputTokens, outputTokens),
                Math.max(0, endToEndLatencyMs),
                failureReason);
    }

    private String failureReason(boolean nonEmpty, boolean lengthPassed,
                                 boolean coveragePassed, boolean safetyPassed) {
        List<String> reasons = new ArrayList<>();
        if (!nonEmpty) {
            reasons.add("empty-response");
        }
        if (!lengthPassed) {
            reasons.add("response-length");
        }
        if (!coveragePassed) {
            reasons.add("concept-coverage");
        }
        if (!safetyPassed) {
            reasons.add("forbidden-phrase");
        }
        return String.join(",", reasons);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private double round4(double value) {
        return Math.round(value * 10_000D) / 10_000D;
    }
}
