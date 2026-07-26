package com.mindskip.xzs.service;

import com.mindskip.xzs.ai.AnalysisService;
import com.mindskip.xzs.ai.prompt.PromptRegistry;
import com.mindskip.xzs.domain.ai.AiPromptAuditLog;
import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.repository.AiPromptAuditLogMapper;
import com.mindskip.xzs.repository.AiPromptDefinitionMapper;
import com.mindskip.xzs.repository.AiPromptReleaseMapper;
import com.mindskip.xzs.repository.AiPromptVersionMapper;
import com.mindskip.xzs.service.PromptOpsService.Operator;
import com.mindskip.xzs.service.impl.PromptOpsServiceImpl;
import com.mindskip.xzs.viewmodel.admin.prompt.PromptVersionRequestVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptOpsServiceImplTest {

    private AiPromptDefinitionMapper defMapper;
    private AiPromptVersionMapper versionMapper;
    private AiPromptReleaseMapper releaseMapper;
    private AiPromptAuditLogMapper auditMapper;
    private PromptRegistry registry;
    private PromptOpsService service;

    private static final Operator OP = new Operator(1, "admin", "1.2.3.4");
    private static final String KEY = "analysis.plato";
    private static final Long DEF_ID = 7L;

    @BeforeEach
    void setup() {
        defMapper = mock(AiPromptDefinitionMapper.class);
        versionMapper = mock(AiPromptVersionMapper.class);
        releaseMapper = mock(AiPromptReleaseMapper.class);
        auditMapper = mock(AiPromptAuditLogMapper.class);
        registry = mock(PromptRegistry.class);
        AnalysisService analysisService = mock(AnalysisService.class);
        service = new PromptOpsServiceImpl(defMapper, versionMapper, releaseMapper, auditMapper, registry, analysisService);

        AiPromptDefinition def = new AiPromptDefinition();
        def.setId(DEF_ID);
        def.setPromptKey(KEY);
        when(defMapper.selectByKey(KEY)).thenReturn(def);
    }

    @Test
    void createDraftClonesActiveWhenBodyEmpty() {
        AiPromptRelease rel = release(DEF_ID, 100L, null, 0, "active");
        AiPromptVersion active = version(100L, 1, "active system", "active user");
        when(releaseMapper.selectByDefinitionAndEnv(eq(DEF_ID), any())).thenReturn(rel);
        when(versionMapper.selectById(100L)).thenReturn(active);
        when(versionMapper.selectMaxVersionNo(DEF_ID)).thenReturn(1);

        AiPromptVersion draft = service.createDraft(KEY,
                new PromptVersionRequestVM(null, null, null, null, "tweak", "low"), OP);

        ArgumentCaptor<AiPromptVersion> cap = ArgumentCaptor.forClass(AiPromptVersion.class);
        verify(versionMapper).insert(cap.capture());
        assertThat(cap.getValue().getVersionNo()).isEqualTo(2);
        assertThat(cap.getValue().getStatus()).isEqualTo("draft");
        assertThat(cap.getValue().getSystemPrompt()).isEqualTo("active system"); // 克隆自 active
        assertThat(cap.getValue().getChangeReason()).isEqualTo("tweak");
        assertAudit("create");
    }

    @Test
    void submitMovesDraftToAwaitingApproval() {
        AiPromptVersion v = version(2L, 2, "s", "u");
        v.setStatus("draft");
        when(versionMapper.selectById(2L)).thenReturn(v);

        service.submitForApproval(2L, OP);
        verify(versionMapper).updateStatus(2L, "awaiting_approval");
        // submit 不改发布/活动版本，不触发 registry.refresh()
        verify(registry, org.mockito.Mockito.never()).refresh();
        assertAudit("submit");
    }

    @Test
    void approveStartsCanaryWithPercent() {
        AiPromptVersion v = version(2L, 2, "s", "u");
        v.setStatus("awaiting_approval");
        when(versionMapper.selectById(2L)).thenReturn(v);
        AiPromptRelease rel = release(DEF_ID, 1L, null, 0, "active");
        when(releaseMapper.selectByDefinitionAndEnv(eq(DEF_ID), any())).thenReturn(rel);

        service.approve(2L, 10, "ok", OP);

        verify(versionMapper).updateApproval(eq(2L), eq("canary"), eq(1), eq("ok"));
        ArgumentCaptor<AiPromptRelease> rc = ArgumentCaptor.forClass(AiPromptRelease.class);
        verify(releaseMapper).update(rc.capture());
        assertThat(rc.getValue().getCanaryVersionId()).isEqualTo(2L);
        assertThat(rc.getValue().getCanaryPercent()).isEqualTo(10);
        verify(registry).refresh();
        assertAudit("approve");
    }

    @Test
    void promoteActivatesCanaryAndRetiresOldActive() {
        AiPromptVersion canary = version(2L, 2, "s2", "u2");
        canary.setStatus("canary");
        when(versionMapper.selectById(2L)).thenReturn(canary);
        AiPromptRelease rel = release(DEF_ID, 1L, null, 10, "active"); // stable=1, canary=2
        rel.setCanaryVersionId(2L);
        when(releaseMapper.selectByDefinitionAndEnv(eq(DEF_ID), any())).thenReturn(rel);

        service.promote(2L, OP);

        verify(versionMapper).updateStatus(1L, "retired"); // 旧 active 退休
        verify(versionMapper).updateStatus(2L, "active");
        ArgumentCaptor<AiPromptRelease> rc = ArgumentCaptor.forClass(AiPromptRelease.class);
        verify(releaseMapper).update(rc.capture());
        assertThat(rc.getValue().getStableVersionId()).isEqualTo(2L);
        assertThat(rc.getValue().getCanaryVersionId()).isNull();
        assertThat(rc.getValue().getCanaryPercent()).isZero();
        verify(registry).refresh();
    }

    @Test
    void rollbackRestoresPriorVersion() {
        AiPromptVersion target = version(1L, 1, "s1", "u1");
        target.setStatus("retired");
        when(versionMapper.selectById(1L)).thenReturn(target);
        AiPromptRelease rel = release(DEF_ID, 2L, null, 0, "active"); // current stable=2
        when(releaseMapper.selectByDefinitionAndEnv(eq(DEF_ID), any())).thenReturn(rel);

        service.rollback(KEY, 1L, OP);

        verify(versionMapper).updateStatus(2L, "retired"); // 当前 active 退休
        verify(versionMapper).updateStatus(1L, "active");  // 目标恢复 active
        ArgumentCaptor<AiPromptRelease> rc = ArgumentCaptor.forClass(AiPromptRelease.class);
        verify(releaseMapper).update(rc.capture());
        assertThat(rc.getValue().getStableVersionId()).isEqualTo(1L);
        assertThat(rc.getValue().getCanaryVersionId()).isNull();
        verify(registry).refresh();
    }

    @Test
    void killSwitchFreezesRelease() {
        AiPromptRelease rel = release(DEF_ID, 1L, null, 0, "active");
        when(releaseMapper.selectByDefinitionAndEnv(eq(DEF_ID), any())).thenReturn(rel);

        service.killSwitch(KEY, false, OP);

        ArgumentCaptor<AiPromptRelease> rc = ArgumentCaptor.forClass(AiPromptRelease.class);
        verify(releaseMapper).update(rc.capture());
        assertThat(rc.getValue().getStatus()).isEqualTo("disabled");
        verify(registry).refresh();
    }

    @Test
    void editDraftRejectsActiveVersion() {
        AiPromptVersion v = version(2L, 2, "s", "u");
        v.setStatus("active");
        when(versionMapper.selectById(2L)).thenReturn(v);

        assertThatThrownBy(() -> service.editDraft(2L,
                new PromptVersionRequestVM("s", "u", "[]", null, null, null), OP))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollbackRejectsDraftTarget() {
        AiPromptVersion target = version(3L, 3, "s", "u");
        target.setStatus("draft");
        when(versionMapper.selectById(3L)).thenReturn(target);

        assertThatThrownBy(() -> service.rollback(KEY, 3L, OP))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertAudit(String action) {
        ArgumentCaptor<AiPromptAuditLog> ac = ArgumentCaptor.forClass(AiPromptAuditLog.class);
        verify(auditMapper).insert(ac.capture());
        assertThat(ac.getValue().getAction()).isEqualTo(action);
        assertThat(ac.getValue().getOperatorId()).isEqualTo(1);
    }

    private static AiPromptVersion version(Long id, int no, String system, String user) {
        AiPromptVersion v = new AiPromptVersion();
        v.setId(id);
        v.setVersionNo(no);
        v.setSystemPrompt(system);
        v.setUserPromptTemplate(user);
        v.setDefinitionId(DEF_ID);
        v.setStatus("active");
        return v;
    }

    private static AiPromptRelease release(Long defId, Long stableId, Long canaryId, int percent, String status) {
        AiPromptRelease r = new AiPromptRelease();
        r.setId(99L);
        r.setDefinitionId(defId);
        r.setStableVersionId(stableId);
        r.setCanaryVersionId(canaryId);
        r.setCanaryPercent(percent);
        r.setStatus(status);
        return r;
    }
}
