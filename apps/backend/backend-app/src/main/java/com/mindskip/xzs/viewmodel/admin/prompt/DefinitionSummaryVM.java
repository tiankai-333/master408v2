package com.mindskip.xzs.viewmodel.admin.prompt;

/** 列表页每行的 definition 摘要 + 当前发布状态。 */
public record DefinitionSummaryVM(
        Long id,
        String promptKey,
        String name,
        String promptKind,
        Boolean enabled,
        Integer activeVersionNo,
        Long activeVersionId,
        Integer canaryVersionNo,
        Long canaryVersionId,
        Integer canaryPercent,
        String releaseStatus
) {
}
