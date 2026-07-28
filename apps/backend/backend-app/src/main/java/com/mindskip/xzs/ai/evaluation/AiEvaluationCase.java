package com.mindskip.xzs.ai.evaluation;

import java.util.List;

/**
 * One immutable case in a versioned AI evaluation data set.
 *
 * <p>{@code model} cases can be sent to a model. {@code contract} cases describe
 * validation or failure behaviour and are exercised by isolated tests instead of
 * spending tokens on a prompt that cannot reproduce the failure deterministically.</p>
 */
public record AiEvaluationCase(
        String id,
        String category,
        String executionMode,
        String style,
        String taskType,
        String question,
        String knowledgePoints,
        String referenceDocs,
        List<String> expectedConcepts,
        List<String> forbiddenPhrases,
        double minimumConceptCoverage,
        int minimumResponseChars,
        int maximumResponseChars,
        String expectedOutcome) {

    public AiEvaluationCase {
        expectedConcepts = expectedConcepts == null ? List.of() : List.copyOf(expectedConcepts);
        forbiddenPhrases = forbiddenPhrases == null ? List.of() : List.copyOf(forbiddenPhrases);
        executionMode = blankToDefault(executionMode, "model");
        style = blankToDefault(style, "default");
        taskType = blankToDefault(taskType, "explain");
        minimumConceptCoverage = clamp(minimumConceptCoverage, 0, 1);
        minimumResponseChars = Math.max(0, minimumResponseChars);
        maximumResponseChars = Math.max(minimumResponseChars, maximumResponseChars);
    }

    public boolean isModelCase() {
        return "model".equalsIgnoreCase(executionMode);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
