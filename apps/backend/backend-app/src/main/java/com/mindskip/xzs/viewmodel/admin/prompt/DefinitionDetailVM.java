package com.mindskip.xzs.viewmodel.admin.prompt;

import com.mindskip.xzs.domain.ai.AiPromptAuditLog;
import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;

import java.util.List;

/** 单个 definition 详情：定义 + 版本历史 + 当前发布 + 审计。 */
public record DefinitionDetailVM(
        AiPromptDefinition definition,
        AiPromptRelease release,
        List<AiPromptVersion> versions,
        List<AiPromptAuditLog> auditLogs
) {
}
