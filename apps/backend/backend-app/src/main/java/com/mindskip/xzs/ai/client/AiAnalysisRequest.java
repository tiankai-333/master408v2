package com.mindskip.xzs.ai.client;

/**
 * Provider-neutral input for an analysis request.
 */
public record AiAnalysisRequest(String systemPrompt, String userPrompt, String conversationId) {

    public AiAnalysisRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, null);
    }

    public AiAnalysisRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = userPrompt == null ? "" : userPrompt;
        conversationId = conversationId == null || conversationId.isBlank()
                ? null
                : conversationId;
    }
}
