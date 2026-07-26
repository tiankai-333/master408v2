package com.mindskip.xzs.ai.prompt;

/**
 * 解析 Prompt 时的请求上下文，用于灰度分桶（同一学生稳定命中同一版本）。
 * 分桶优先级：userId > conversationId > fallbackKey（匿名流量非粘性）。
 */
public record PromptContext(Integer userId, String conversationId, String fallbackKey) {

    public static PromptContext of(Integer userId, String conversationId) {
        return new PromptContext(userId, conversationId, null);
    }

    public static PromptContext anonymous() {
        return new PromptContext(null, null, null);
    }
}
