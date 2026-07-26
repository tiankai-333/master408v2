package com.mindskip.xzs.domain.ai;

import java.util.Date;

/**
 * Prompt 操作审计：建草稿、编辑、提交、审批、灰度、发布、回滚、kill-switch、测试等。
 */
public class AiPromptAuditLog {
    private Long id;
    private Long definitionId;
    private Long versionId;
    private Long releaseId;
    /** create|edit_draft|submit|approve|reject|canary|promote|rollback|kill_switch|enable|test */
    private String action;
    private Long fromVersionId;
    private Long toVersionId;
    private String detailJson;
    private String reason;
    private Integer operatorId;
    private String operatorName;
    private Date operateTime;
    private String ipAddress;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDefinitionId() { return definitionId; }
    public void setDefinitionId(Long definitionId) { this.definitionId = definitionId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getFromVersionId() { return fromVersionId; }
    public void setFromVersionId(Long fromVersionId) { this.fromVersionId = fromVersionId; }
    public Long getToVersionId() { return toVersionId; }
    public void setToVersionId(Long toVersionId) { this.toVersionId = toVersionId; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getOperatorId() { return operatorId; }
    public void setOperatorId(Integer operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public Date getOperateTime() { return operateTime; }
    public void setOperateTime(Date operateTime) { this.operateTime = operateTime; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
}
