package com.mindskip.xzs.service;

import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionDetailVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionSummaryVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DiffResultVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptTestRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptVersionRequestVM;

import java.util.List;

public interface PromptOpsService {

    /** 操作者上下文（管理员），随每次变更写审计。 */
    record Operator(Integer id, String name, String ip) {
    }

    List<DefinitionSummaryVM> listDefinitions();

    DefinitionDetailVM getDefinitionDetail(String promptKey);

    AiPromptVersion getVersion(Long versionId);

    /** 建草稿；systemPrompt 为空时从当前 active 版本克隆。 */
    AiPromptVersion createDraft(String promptKey, PromptVersionRequestVM request, Operator operator);

    /** 仅 draft/testing/rejected 可编辑。 */
    AiPromptVersion editDraft(Long versionId, PromptVersionRequestVM request, Operator operator);

    DiffResultVM diff(Long versionId, Long againstVersionId);

    /** Playground：用指定版本内容一次性调用 LLM（不经过 active release）。 */
    String test(Long versionId, PromptTestRequestVM request) throws Exception;

    AiPromptVersion submitForApproval(Long versionId, Operator operator);

    /** 审批通过 → 进入 canary，初始灰度 percent%。 */
    AiPromptVersion approve(Long versionId, Integer percent, String comment, Operator operator);

    AiPromptVersion setCanary(Long versionId, Integer percent, Operator operator);

    /** canary → active，旧 active → retired。 */
    AiPromptVersion promote(Long versionId, Operator operator);

    AiPromptVersion rollback(String promptKey, Long toVersionId, Operator operator);

    void killSwitch(String promptKey, Boolean enabled, Operator operator);
}
