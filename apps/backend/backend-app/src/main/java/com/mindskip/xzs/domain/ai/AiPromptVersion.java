package com.mindskip.xzs.domain.ai;

import java.util.Date;

/**
 * AI Prompt 不可变版本。内容在离开 draft 后只读，任何修改必须新建版本。
 * 状态机：draft → testing → awaiting_approval → canary → active → retired
 *                                  ↘ rejected
 */
public class AiPromptVersion {
    private Long id;
    private Long definitionId;
    private Integer versionNo;
    private String systemPrompt;
    private String userPromptTemplate;
    /** JSON: [{name, required, description}] */
    private String variablesJson;
    /** JSON: {temperature, maxTokens}。当前只存不应用到 callAiApi。 */
    private String modelParamsJson;
    /** draft|testing|awaiting_approval|rejected|canary|active|retired */
    private String status;
    private String changeReason;
    private String riskNote;
    private Integer createdBy;
    private Date createdTime;
    private Integer approvedBy;
    private Date approvedTime;
    private String approveComment;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDefinitionId() { return definitionId; }
    public void setDefinitionId(Long definitionId) { this.definitionId = definitionId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getUserPromptTemplate() { return userPromptTemplate; }
    public void setUserPromptTemplate(String userPromptTemplate) { this.userPromptTemplate = userPromptTemplate; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getModelParamsJson() { return modelParamsJson; }
    public void setModelParamsJson(String modelParamsJson) { this.modelParamsJson = modelParamsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getRiskNote() { return riskNote; }
    public void setRiskNote(String riskNote) { this.riskNote = riskNote; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
    public Date getCreatedTime() { return createdTime; }
    public void setCreatedTime(Date createdTime) { this.createdTime = createdTime; }
    public Integer getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Integer approvedBy) { this.approvedBy = approvedBy; }
    public Date getApprovedTime() { return approvedTime; }
    public void setApprovedTime(Date approvedTime) { this.approvedTime = approvedTime; }
    public String getApproveComment() { return approveComment; }
    public void setApproveComment(String approveComment) { this.approveComment = approveComment; }
}
