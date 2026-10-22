package com.mindskip.xzs.newdb.viewmodel;

import java.time.LocalDateTime;

/**
 * 题目列表行：短标题取 stem_text 前 60 字（v0 无 title 列）。
 */
public record NewdbQuestionListVM(Long id, Integer questionType, String subjectName,
                                  String sourceName, Integer sourceYear, String sourceQuestionNo,
                                  String shortTitle, String correct, Integer status,
                                  Integer displayReady, String licenseTag, String batchTag,
                                  LocalDateTime createTime) {
}
