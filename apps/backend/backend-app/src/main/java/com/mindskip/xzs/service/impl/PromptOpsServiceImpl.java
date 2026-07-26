package com.mindskip.xzs.service.impl;

import com.mindskip.xzs.ai.AnalysisService;
import com.mindskip.xzs.ai.PromptTemplate;
import com.mindskip.xzs.ai.prompt.PromptRegistry;
import com.mindskip.xzs.domain.ai.AiPromptAuditLog;
import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.repository.AiPromptAuditLogMapper;
import com.mindskip.xzs.repository.AiPromptDefinitionMapper;
import com.mindskip.xzs.repository.AiPromptReleaseMapper;
import com.mindskip.xzs.repository.AiPromptVersionMapper;
import com.mindskip.xzs.service.PromptOpsService;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionDetailVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DefinitionSummaryVM;
import com.mindskip.xzs.viewmodel.admin.prompt.DiffResultVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptTestRequestVM;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptVersionRequestVM;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PromptOpsServiceImpl implements PromptOpsService {

    private static final String ENVIRONMENT = "default";

    private final AiPromptDefinitionMapper definitionMapper;
    private final AiPromptVersionMapper versionMapper;
    private final AiPromptReleaseMapper releaseMapper;
    private final AiPromptAuditLogMapper auditMapper;
    private final PromptRegistry registry;
    private final AnalysisService analysisService;

    public PromptOpsServiceImpl(AiPromptDefinitionMapper definitionMapper,
                                AiPromptVersionMapper versionMapper,
                                AiPromptReleaseMapper releaseMapper,
                                AiPromptAuditLogMapper auditMapper,
                                PromptRegistry registry,
                                AnalysisService analysisService) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.releaseMapper = releaseMapper;
        this.auditMapper = auditMapper;
        this.registry = registry;
        this.analysisService = analysisService;
    }

    // ---------------- reads ----------------

    @Override
    public List<DefinitionSummaryVM> listDefinitions() {
        List<AiPromptDefinition> defs = definitionMapper.selectAll();
        List<AiPromptRelease> releases = releaseMapper.selectAll();
        Map<Long, AiPromptRelease> relByDef = new HashMap<>();
        Set<Long> versionIds = new HashSet<>();
        for (AiPromptRelease r : releases) {
            relByDef.put(r.getDefinitionId(), r);
            if (r.getStableVersionId() != null) versionIds.add(r.getStableVersionId());
            if (r.getCanaryVersionId() != null) versionIds.add(r.getCanaryVersionId());
        }
        Map<Long, AiPromptVersion> vById = loadVersions(versionIds);

        List<DefinitionSummaryVM> out = new ArrayList<>();
        for (AiPromptDefinition d : defs) {
            AiPromptRelease r = relByDef.get(d.getId());
            if (r == null) {
                out.add(new DefinitionSummaryVM(d.getId(), d.getPromptKey(), d.getName(),
                        d.getPromptKind(), d.getEnabled(), null, null, null, null, null, null));
                continue;
            }
            AiPromptVersion stable = vById.get(r.getStableVersionId());
            AiPromptVersion canary = r.getCanaryVersionId() == null ? null : vById.get(r.getCanaryVersionId());
            out.add(new DefinitionSummaryVM(d.getId(), d.getPromptKey(), d.getName(), d.getPromptKind(),
                    d.getEnabled(),
                    stable != null ? stable.getVersionNo() : null, r.getStableVersionId(),
                    canary != null ? canary.getVersionNo() : null, r.getCanaryVersionId(),
                    r.getCanaryPercent(), r.getStatus()));
        }
        return out;
    }

    @Override
    public DefinitionDetailVM getDefinitionDetail(String promptKey) {
        AiPromptDefinition def = requireDefinition(promptKey);
        AiPromptRelease release = releaseMapper.selectByDefinitionAndEnv(def.getId(), ENVIRONMENT);
        List<AiPromptVersion> versions = versionMapper.selectByDefinitionId(def.getId());
        List<AiPromptAuditLog> audit = auditMapper.selectByDefinitionId(def.getId(), 100);
        return new DefinitionDetailVM(def, release, versions, audit);
    }

    @Override
    public AiPromptVersion getVersion(Long versionId) {
        AiPromptVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        return v;
    }

    @Override
    public DiffResultVM diff(Long versionId, Long againstVersionId) {
        AiPromptVersion to = getVersion(versionId);
        AiPromptVersion from = getVersion(againstVersionId);
        return new DiffResultVM(from.getId(), to.getId(),
                diffLines(from.getSystemPrompt(), to.getSystemPrompt()),
                diffLines(from.getUserPromptTemplate(), to.getUserPromptTemplate()));
    }

    @Override
    public String test(Long versionId, PromptTestRequestVM request) throws Exception {
        AiPromptVersion v = getVersion(versionId);
        PromptTemplate tpl = new PromptTemplate();
        tpl.setSystemPrompt(v.getSystemPrompt());
        tpl.setUserPromptTemplate(v.getUserPromptTemplate());
        String userPrompt = tpl.formatUserPrompt(
                request.question(), request.knowledgePoints(), request.referenceDocs());
        String response = analysisService.runPromptTest(v.getSystemPrompt(), userPrompt);
        // 测试不入版本状态机，仅留一条审计（单条 insert，无需事务包裹 LLM 调用）
        audit(v.getDefinitionId(), v.getId(), null, "test", null, v.getId(),
                "{\"question\":\"" + limit(request.question(), 80) + "\"}", null, null);
        return response;
    }

    // ---------------- mutations ----------------

    @Override
    @Transactional
    public AiPromptVersion createDraft(String promptKey, PromptVersionRequestVM request, Operator operator) {
        AiPromptDefinition def = requireDefinition(promptKey);
        Integer maxNo = versionMapper.selectMaxVersionNo(def.getId());
        int nextNo = (maxNo == null ? 0 : maxNo) + 1;

        AiPromptVersion v = new AiPromptVersion();
        v.setDefinitionId(def.getId());
        v.setVersionNo(nextNo);
        v.setStatus("draft");
        v.setCreatedBy(operator.id());

        // systemPrompt 为空 → 从当前 active 版本克隆
        if (isBlank(request.systemPrompt())) {
            AiPromptRelease rel = releaseMapper.selectByDefinitionAndEnv(def.getId(), ENVIRONMENT);
            if (rel != null && rel.getStableVersionId() != null) {
                AiPromptVersion cur = versionMapper.selectById(rel.getStableVersionId());
                if (cur != null) {
                    v.setSystemPrompt(cur.getSystemPrompt());
                    v.setUserPromptTemplate(cur.getUserPromptTemplate());
                    v.setVariablesJson(cur.getVariablesJson());
                    v.setModelParamsJson(cur.getModelParamsJson());
                }
            }
        } else {
            v.setSystemPrompt(request.systemPrompt());
            v.setUserPromptTemplate(request.userPromptTemplate());
            v.setVariablesJson(request.variablesJson());
            v.setModelParamsJson(request.modelParamsJson());
        }
        v.setChangeReason(request.changeReason());
        v.setRiskNote(request.riskNote());
        versionMapper.insert(v);
        audit(def.getId(), v.getId(), null, "create", null, v.getId(), null, "create draft", operator);
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion editDraft(Long versionId, PromptVersionRequestVM request, Operator operator) {
        AiPromptVersion v = getVersion(versionId);
        if (!"draft".equals(v.getStatus()) && !"testing".equals(v.getStatus()) && !"rejected".equals(v.getStatus())) {
            throw new IllegalStateException("只有 draft/testing/rejected 版本可编辑，当前状态: " + v.getStatus());
        }
        v.setSystemPrompt(request.systemPrompt());
        v.setUserPromptTemplate(request.userPromptTemplate());
        v.setVariablesJson(request.variablesJson());
        v.setModelParamsJson(request.modelParamsJson());
        v.setChangeReason(request.changeReason());
        v.setRiskNote(request.riskNote());
        versionMapper.updateContent(v);
        audit(v.getDefinitionId(), v.getId(), null, "edit_draft", null, v.getId(), null, "edit draft", operator);
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion submitForApproval(Long versionId, Operator operator) {
        AiPromptVersion v = getVersion(versionId);
        if (!"draft".equals(v.getStatus()) && !"rejected".equals(v.getStatus())) {
            throw new IllegalStateException("只有 draft/rejected 可提交审批，当前状态: " + v.getStatus());
        }
        versionMapper.updateStatus(v.getId(), "awaiting_approval");
        v.setStatus("awaiting_approval");
        audit(v.getDefinitionId(), v.getId(), null, "submit", null, v.getId(), null, "submit for approval", operator);
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion approve(Long versionId, Integer percent, String comment, Operator operator) {
        AiPromptVersion v = getVersion(versionId);
        if (!"awaiting_approval".equals(v.getStatus())) {
            throw new IllegalStateException("只有 awaiting_approval 可审批，当前状态: " + v.getStatus());
        }
        int pct = percent == null ? 0 : Math.max(0, Math.min(100, percent));
        versionMapper.updateApproval(v.getId(), "canary", operator.id(), comment);
        v.setStatus("canary");

        AiPromptRelease rel = requireRelease(v.getDefinitionId());
        rel.setCanaryVersionId(v.getId());
        rel.setCanaryPercent(pct);
        rel.setStatus("active");
        rel.setReleasedBy(operator.id());
        releaseMapper.update(rel);

        audit(v.getDefinitionId(), v.getId(), rel.getId(), "approve", rel.getStableVersionId(), v.getId(),
                "{\"percent\":" + pct + "}", comment, operator);
        registry.refresh();
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion setCanary(Long versionId, Integer percent, Operator operator) {
        AiPromptVersion v = getVersion(versionId);
        AiPromptRelease rel = requireRelease(v.getDefinitionId());
        if (!v.getId().equals(rel.getCanaryVersionId())) {
            throw new IllegalStateException("该版本不是当前 canary，无法调整灰度比例");
        }
        int pct = percent == null ? 0 : Math.max(0, Math.min(100, percent));
        rel.setCanaryPercent(pct);
        rel.setReleasedBy(operator.id());
        releaseMapper.update(rel);
        audit(v.getDefinitionId(), v.getId(), rel.getId(), "canary", null, v.getId(),
                "{\"percent\":" + pct + "}", null, operator);
        registry.refresh();
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion promote(Long versionId, Operator operator) {
        AiPromptVersion v = getVersion(versionId);
        if (!"canary".equals(v.getStatus())) {
            throw new IllegalStateException("只有 canary 可提升为 active，当前状态: " + v.getStatus());
        }
        AiPromptRelease rel = requireRelease(v.getDefinitionId());
        Long oldActiveId = rel.getStableVersionId();

        if (oldActiveId != null && !oldActiveId.equals(v.getId())) {
            versionMapper.updateStatus(oldActiveId, "retired");
        }
        versionMapper.updateStatus(v.getId(), "active");
        v.setStatus("active");

        rel.setStableVersionId(v.getId());
        rel.setCanaryVersionId(null);
        rel.setCanaryPercent(0);
        rel.setStatus("active");
        rel.setReleasedBy(operator.id());
        releaseMapper.update(rel);

        audit(v.getDefinitionId(), v.getId(), rel.getId(), "promote", oldActiveId, v.getId(), null, null, operator);
        registry.refresh();
        return v;
    }

    @Override
    @Transactional
    public AiPromptVersion rollback(String promptKey, Long toVersionId, Operator operator) {
        AiPromptDefinition def = requireDefinition(promptKey);
        AiPromptVersion target = getVersion(toVersionId);
        if (!def.getId().equals(target.getDefinitionId())) {
            throw new IllegalArgumentException("目标版本不属于该 prompt");
        }
        String ts = target.getStatus();
        if ("draft".equals(ts) || "testing".equals(ts) || "rejected".equals(ts)) {
            throw new IllegalStateException("不能回滚到 draft/testing/rejected 版本");
        }
        AiPromptRelease rel = requireRelease(def.getId());
        Long currentActiveId = rel.getStableVersionId();

        if (currentActiveId != null && !currentActiveId.equals(target.getId())) {
            versionMapper.updateStatus(currentActiveId, "retired");
        }
        if (!"active".equals(target.getStatus())) {
            versionMapper.updateStatus(target.getId(), "active");
            target.setStatus("active");
        }
        rel.setStableVersionId(target.getId());
        rel.setCanaryVersionId(null);
        rel.setCanaryPercent(0);
        rel.setStatus("active");
        rel.setReleasedBy(operator.id());
        releaseMapper.update(rel);

        audit(def.getId(), target.getId(), rel.getId(), "rollback", currentActiveId, target.getId(), null, null, operator);
        registry.refresh();
        return target;
    }

    @Override
    @Transactional
    public void killSwitch(String promptKey, Boolean enabled, Operator operator) {
        AiPromptDefinition def = requireDefinition(promptKey);
        AiPromptRelease rel = requireRelease(def.getId());
        boolean enable = enabled == null || enabled;
        rel.setStatus(enable ? "active" : "disabled");
        rel.setReleasedBy(operator.id());
        releaseMapper.update(rel);
        audit(def.getId(), null, rel.getId(), enable ? "enable" : "kill_switch", null, null,
                "{\"enabled\":" + enable + "}", null, operator);
        registry.refresh();
    }

    // ---------------- helpers ----------------

    private AiPromptDefinition requireDefinition(String promptKey) {
        AiPromptDefinition def = definitionMapper.selectByKey(promptKey);
        if (def == null) {
            throw new IllegalArgumentException("prompt_key 不存在: " + promptKey);
        }
        return def;
    }

    private AiPromptRelease requireRelease(Long definitionId) {
        AiPromptRelease rel = releaseMapper.selectByDefinitionAndEnv(definitionId, ENVIRONMENT);
        if (rel == null) {
            throw new IllegalStateException("definition " + definitionId + " 缺少发布记录");
        }
        return rel;
    }

    private Map<Long, AiPromptVersion> loadVersions(Set<Long> ids) {
        Map<Long, AiPromptVersion> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        for (AiPromptVersion v : versionMapper.selectByIds(ids)) {
            map.put(v.getId(), v);
        }
        return map;
    }

    private void audit(Long definitionId, Long versionId, Long releaseId, String action,
                       Long fromVersionId, Long toVersionId, String detailJson, String reason, Operator operator) {
        AiPromptAuditLog log = new AiPromptAuditLog();
        log.setDefinitionId(definitionId);
        log.setVersionId(versionId);
        log.setReleaseId(releaseId);
        log.setAction(action);
        log.setFromVersionId(fromVersionId);
        log.setToVersionId(toVersionId);
        log.setDetailJson(detailJson);
        log.setReason(reason);
        log.setOperatorId(operator == null ? null : operator.id());
        log.setOperatorName(operator == null ? null : operator.name());
        log.setOperateTime(new Date());
        log.setIpAddress(operator == null ? null : operator.ip());
        auditMapper.insert(log);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 简单 LCS 行级 diff：from=旧，to=新；remove=from 独有，add=to 独有，context=共有。 */
    private static List<DiffResultVM.DiffLine> diffLines(String from, String to) {
        String[] a = from == null ? new String[0] : from.split("\n", -1);
        String[] b = to == null ? new String[0] : to.split("\n", -1);
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffResultVM.DiffLine> res = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (a[i].equals(b[j])) {
                res.add(new DiffResultVM.DiffLine("context", a[i]));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                res.add(new DiffResultVM.DiffLine("remove", a[i]));
                i++;
            } else {
                res.add(new DiffResultVM.DiffLine("add", b[j]));
                j++;
            }
        }
        while (i < m) { res.add(new DiffResultVM.DiffLine("remove", a[i++])); }
        while (j < n) { res.add(new DiffResultVM.DiffLine("add", b[j++])); }
        return res;
    }
}
