package com.mindskip.xzs.viewmodel.admin.prompt;

/** 建草稿 / 编辑草稿的请求体。create 时字段为空表示从当前 active 版本克隆。 */
public record PromptVersionRequestVM(
        String systemPrompt,
        String userPromptTemplate,
        String variablesJson,
        String modelParamsJson,
        String changeReason,
        String riskNote
) {
}
