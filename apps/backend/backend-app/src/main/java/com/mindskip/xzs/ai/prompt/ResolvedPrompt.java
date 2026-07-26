package com.mindskip.xzs.ai.prompt;

/**
 * registry 解析结果：当前请求命中的具体版本内容 + 版本/发布 ID（供日志追溯）。
 * canaryPercent 是生效比例（kill-switch 时为 0）；killed 表示发布是否被冻结。
 */
public record ResolvedPrompt(
        String promptKey,
        Long definitionId,
        Long versionId,
        Long releaseId,
        String systemPrompt,
        String userPromptTemplate,
        String variablesJson,
        String modelParamsJson,
        Integer canaryPercent,
        boolean killed
) {

    public PromptRef toRef() {
        return new PromptRef(promptKey, definitionId, versionId, releaseId);
    }
}
