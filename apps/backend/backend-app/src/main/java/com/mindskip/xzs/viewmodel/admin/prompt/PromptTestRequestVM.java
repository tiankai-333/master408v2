package com.mindskip.xzs.viewmodel.admin.prompt;

/** Playground 一次性测试：用指定版本内容渲染并调用 LLM（绝不走 active release）。 */
public record PromptTestRequestVM(
        String question,
        String knowledgePoints,
        String referenceDocs
) {
}
