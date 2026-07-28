package com.mindskip.xzs.domain.ai;

import java.math.BigDecimal;
import java.util.Date;

public class AiEvaluationRun {
    private Long id;
    private String datasetVersion;
    private String candidateLabel;
    private String engine;
    private String status;
    private Integer caseCount;
    private Integer passedCount;
    private BigDecimal averageQualityScore;
    private Integer estimatedInputTokens;
    private Integer estimatedOutputTokens;
    private BigDecimal estimatedCost;
    private Integer averageLatencyMs;
    private Integer p95LatencyMs;
    private String errorMessage;
    private Integer createdBy;
    private Date createTime;
    private Date startTime;
    private Date finishTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getCandidateLabel() { return candidateLabel; }
    public void setCandidateLabel(String candidateLabel) { this.candidateLabel = candidateLabel; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }
    public Integer getPassedCount() { return passedCount; }
    public void setPassedCount(Integer passedCount) { this.passedCount = passedCount; }
    public BigDecimal getAverageQualityScore() { return averageQualityScore; }
    public void setAverageQualityScore(BigDecimal averageQualityScore) { this.averageQualityScore = averageQualityScore; }
    public Integer getEstimatedInputTokens() { return estimatedInputTokens; }
    public void setEstimatedInputTokens(Integer estimatedInputTokens) { this.estimatedInputTokens = estimatedInputTokens; }
    public Integer getEstimatedOutputTokens() { return estimatedOutputTokens; }
    public void setEstimatedOutputTokens(Integer estimatedOutputTokens) { this.estimatedOutputTokens = estimatedOutputTokens; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public Integer getAverageLatencyMs() { return averageLatencyMs; }
    public void setAverageLatencyMs(Integer averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; }
    public Integer getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(Integer p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getFinishTime() { return finishTime; }
    public void setFinishTime(Date finishTime) { this.finishTime = finishTime; }
}
