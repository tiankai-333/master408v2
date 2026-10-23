package com.mindskip.xzs.newdb.importing;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 爬虫产物 exam_questions.json 的一条记录（snake_case 键）。
 * 这是导入器的唯一输入模型；字段即爬虫原始字段，不做清洗假设。
 * v1 新增 *_html 富内容字段（题面/选项/解析的源结构片段，公式为 `\(`…`\)` 文本）：
 * 存在时为权威内容，纯文本字段仅作派生核对与旧数据兼容。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NewdbQuestionRecord(Integer year, Integer questionNumber, String subject,
                                  String question, String options, String answer,
                                  String analysis, String images,
                                  String questionHtml, String optionsHtml, String analysisHtml,
                                  String formulaSource) {
}
