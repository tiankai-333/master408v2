package com.mindskip.xzs.domain.ai;

import java.util.Date;

/**
 * 发布记录：每个 (definition, environment) 一行。
 * stable_version_id 服务 (100 - canary_percent)% 流量；canary_version_id 命中 canary_percent 流量。
 * status=disabled 为 kill switch：冻结灰度（有效 canary=0），仍服务 stable。
 * 每次变更（promote/rollback/canary/kill-switch）都写入 ai_prompt_audit_log。
 */
public class AiPromptRelease {
    private Long id;
    private Long definitionId;
    private String environment;
    private Long stableVersionId;
    private Long canaryVersionId;
    private Integer canaryPercent;
    /** active | disabled */
    private String status;
    private Integer releasedBy;
    private Date releaseTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDefinitionId() { return definitionId; }
    public void setDefinitionId(Long definitionId) { this.definitionId = definitionId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Long getStableVersionId() { return stableVersionId; }
    public void setStableVersionId(Long stableVersionId) { this.stableVersionId = stableVersionId; }
    public Long getCanaryVersionId() { return canaryVersionId; }
    public void setCanaryVersionId(Long canaryVersionId) { this.canaryVersionId = canaryVersionId; }
    public Integer getCanaryPercent() { return canaryPercent; }
    public void setCanaryPercent(Integer canaryPercent) { this.canaryPercent = canaryPercent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getReleasedBy() { return releasedBy; }
    public void setReleasedBy(Integer releasedBy) { this.releasedBy = releasedBy; }
    public Date getReleaseTime() { return releaseTime; }
    public void setReleaseTime(Date releaseTime) { this.releaseTime = releaseTime; }
}
