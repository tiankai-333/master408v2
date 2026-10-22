package com.mindskip.xzs.newdb.domain;

import java.time.LocalDateTime;

/**
 * master408.question 行（D-33 raw 极简：一行 = 一道题的全部事实）。
 * subjectName / batchTag 为列表与详情联查别名，非表列。
 */
public class NewdbQuestionDO {

    private Long id;
    private Integer questionType;
    private Integer subjectId;
    private String subjectName;
    private Integer score;
    private Integer difficult;
    private String sourceName;
    private Integer sourceYear;
    private String sourceQuestionNo;
    private String sourceRef;
    private String stemHtml;
    private String analysisHtml;
    private String stemText;
    private String correct;
    private String answerText;
    private Integer status;
    private String statusReason;
    private Integer displayReady;
    private String licenseTag;
    private Long batchId;
    private String batchTag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
    }

    public Integer getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Integer subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public void setSubjectName(String subjectName) {
        this.subjectName = subjectName;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Integer getDifficult() {
        return difficult;
    }

    public void setDifficult(Integer difficult) {
        this.difficult = difficult;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public Integer getSourceYear() {
        return sourceYear;
    }

    public void setSourceYear(Integer sourceYear) {
        this.sourceYear = sourceYear;
    }

    public String getSourceQuestionNo() {
        return sourceQuestionNo;
    }

    public void setSourceQuestionNo(String sourceQuestionNo) {
        this.sourceQuestionNo = sourceQuestionNo;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getStemHtml() {
        return stemHtml;
    }

    public void setStemHtml(String stemHtml) {
        this.stemHtml = stemHtml;
    }

    public String getAnalysisHtml() {
        return analysisHtml;
    }

    public void setAnalysisHtml(String analysisHtml) {
        this.analysisHtml = analysisHtml;
    }

    public String getStemText() {
        return stemText;
    }

    public void setStemText(String stemText) {
        this.stemText = stemText;
    }

    public String getCorrect() {
        return correct;
    }

    public void setCorrect(String correct) {
        this.correct = correct;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public Integer getDisplayReady() {
        return displayReady;
    }

    public void setDisplayReady(Integer displayReady) {
        this.displayReady = displayReady;
    }

    public String getLicenseTag() {
        return licenseTag;
    }

    public void setLicenseTag(String licenseTag) {
        this.licenseTag = licenseTag;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getBatchTag() {
        return batchTag;
    }

    public void setBatchTag(String batchTag) {
        this.batchTag = batchTag;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
