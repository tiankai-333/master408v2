package com.mindskip.xzs.newdb.viewmodel;

/**
 * 题目分页请求（字段均可空，controller 归一化默认值与边界）。
 */
public record NewdbQuestionPageRequestVM(Integer pageIndex, Integer pageSize, String keyword,
                                         Integer questionType, Integer status, Integer sourceYear) {
}
