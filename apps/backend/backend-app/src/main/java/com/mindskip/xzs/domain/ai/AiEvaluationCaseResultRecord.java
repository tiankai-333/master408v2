package com.mindskip.xzs.domain.ai;

import java.math.BigDecimal;
import java.util.Date;

public class AiEvaluationCaseResultRecord {
    private Long id;
    private Long runId;
    private String caseId;
    private String category;
    private String promptKey;
    private Long promptVersionId;
    private Long promptReleaseId;
    private Boolean passed;
    private BigDecimal qualityScore;
    private BigDecimal conceptCoverage;
    private String missingConceptsJson;
    private String forbiddenHitsJson;
    private String responseText;
    private Integer responseChars;
    private Integer estimatedInputTokens;
    private Integer estimatedOutputTokens;
    private BigDecimal estimatedCost;
    private Integer endToEndLatencyMs;
    private String failureReason;
    private String errorMessage;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPromptKey() { return promptKey; }
    public void setPromptKey(String promptKey) { this.promptKey = promptKey; }
    public Long getPromptVersionId() { return promptVersionId; }
    public void setPromptVersionId(Long promptVersionId) { this.promptVersionId = promptVersionId; }
    public Long getPromptReleaseId() { return promptReleaseId; }
    public void setPromptReleaseId(Long promptReleaseId) { this.promptReleaseId = promptReleaseId; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public BigDecimal getQualityScore() { return qualityScore; }
    public void setQualityScore(BigDecimal qualityScore) { this.qualityScore = qualityScore; }
    public BigDecimal getConceptCoverage() { return conceptCoverage; }
    public void setConceptCoverage(BigDecimal conceptCoverage) { this.conceptCoverage = conceptCoverage; }
    public String getMissingConceptsJson() { return missingConceptsJson; }
    public void setMissingConceptsJson(String missingConceptsJson) { this.missingConceptsJson = missingConceptsJson; }
    public String getForbiddenHitsJson() { return forbiddenHitsJson; }
    public void setForbiddenHitsJson(String forbiddenHitsJson) { this.forbiddenHitsJson = forbiddenHitsJson; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public Integer getResponseChars() { return responseChars; }
    public void setResponseChars(Integer responseChars) { this.responseChars = responseChars; }
    public Integer getEstimatedInputTokens() { return estimatedInputTokens; }
    public void setEstimatedInputTokens(Integer estimatedInputTokens) { this.estimatedInputTokens = estimatedInputTokens; }
    public Integer getEstimatedOutputTokens() { return estimatedOutputTokens; }
    public void setEstimatedOutputTokens(Integer estimatedOutputTokens) { this.estimatedOutputTokens = estimatedOutputTokens; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public Integer getEndToEndLatencyMs() { return endToEndLatencyMs; }
    public void setEndToEndLatencyMs(Integer endToEndLatencyMs) { this.endToEndLatencyMs = endToEndLatencyMs; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
