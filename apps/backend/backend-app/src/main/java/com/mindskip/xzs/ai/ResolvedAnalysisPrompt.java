package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.prompt.PromptRef;

/**
 * AnalysisService 解析一次分析型 Prompt 的结果。
 * - {@code ref} 透传到运行日志（prompt_key + versionId + releaseId）；
 * - {@code template} 由 resolved 内容构造，复用 {@link PromptTemplate#formatUserPrompt} 渲染；
 * - {@code systemPrompt} 为当前发布版本（或代码兜底）的最终 system 文案。
 */
public record ResolvedAnalysisPrompt(PromptRef ref, PromptTemplate template, String systemPrompt) {

    /** 非首屏：渲染 user prompt，兼容 {question}/{knowledge_points_block}/{knowledge_points}/{reference_docs}。 */
    public String formatUserPrompt(String question, String knowledgePoints, String referenceDocs) {
        return template.formatUserPrompt(question, knowledgePoints, referenceDocs);
    }
}
