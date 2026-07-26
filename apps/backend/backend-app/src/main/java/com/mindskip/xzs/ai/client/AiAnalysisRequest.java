package com.mindskip.xzs.ai.client;

import com.mindskip.xzs.ai.prompt.PromptRef;

/**
 * Provider-neutral input for an analysis request.
 * promptRef 携带解析出的版本/发布 ID（Spring 路径透传，P3 UsageLogAdvisor 落日志用）。
 */
public record AiAnalysisRequest(String systemPrompt, String userPrompt,
                                String conversationId, PromptRef promptRef) {

    public AiAnalysisRequest(String systemPrompt, String userPrompt, String conversationId) {
        this(systemPrompt, userPrompt, conversationId, null);
    }

    public AiAnalysisRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, null, null);
    }

    public AiAnalysisRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = userPrompt == null ? "" : userPrompt;
        conversationId = conversationId == null || conversationId.isBlank()
                ? null
                : conversationId;
    }
}
