package com.mindskip.xzs.ai.prompt;

/**
 * 精简的 Prompt 版本引用，用于透传到运行日志（t_ai_usage_log）。
 * 当 registry 未命中、走 JSON 兜底时，除 promptKey 外其余为 null。
 */
public record PromptRef(String promptKey, Long definitionId, Long versionId, Long releaseId) {

    public static PromptRef unknown(String promptKey) {
        return new PromptRef(promptKey, null, null, null);
    }

    public boolean isResolved() {
        return versionId != null;
    }
}
