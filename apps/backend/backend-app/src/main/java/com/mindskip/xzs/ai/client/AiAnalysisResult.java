package com.mindskip.xzs.ai.client;

/**
 * Provider-neutral model result. Usage values are exact when usageSource is
 * {@code provider}; otherwise they are explicitly marked as estimated.
 */
public record AiAnalysisResult(
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        int cacheHitTokens,
        String usageSource) {

    public AiAnalysisResult {
        content = content == null ? "" : content;
        model = model == null || model.isBlank() ? "unknown" : model;
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        totalTokens = Math.max(totalTokens, inputTokens + outputTokens);
        cacheHitTokens = Math.max(0, cacheHitTokens);
        usageSource = usageSource == null || usageSource.isBlank() ? "estimated" : usageSource;
    }

    public static AiAnalysisResult estimated(AiAnalysisRequest request, String content) {
        int input = estimate(request.systemPrompt() + request.userPrompt());
        int output = estimate(content);
        return new AiAnalysisResult(content, "unknown", input, output,
                input + output, 0, "estimated");
    }

    private static int estimate(String value) {
        return value == null || value.isEmpty() ? 0 : Math.max(1, value.length() / 3);
    }
}
