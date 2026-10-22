package com.mindskip.xzs.newdb.viewmodel;

import java.time.LocalDateTime;

/**
 * 题目详情：全字段含 raw（stemHtml/analysisHtml，已消毒）与血缘（batchTag/licenseTag/sourceRef）。
 */
public record NewdbQuestionDetailVM(Long id, Integer questionType, String subjectName,
                                    Integer score, String sourceName, Integer sourceYear,
                                    String sourceQuestionNo, String sourceRef, String stemHtml,
                                    String analysisHtml, String stemText, String correct,
                                    String answerText, Integer status, String statusReason,
                                    Integer displayReady, String licenseTag, Long batchId,
                                    String batchTag, LocalDateTime createTime,
                                    LocalDateTime updateTime) {
}
