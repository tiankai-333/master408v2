package com.mindskip.xzs.newdb.importing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 整卷提取器（tools/paper-extract）输出 JSON 的绑定模型。
 * 字段与提取器契约一一对应；未知字段忽略以容忍提取器加列。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewdbPaperRecord(
        int year,
        String paperName,
        Integer paperType,
        String sourceUrl,
        String sourceFile,
        String sourceSha256,
        Integer questionTotal,
        List<Question> questions,
        List<String> warnings) {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // 提取器 JSON 为 snake_case（paper_name/source_ref/...）
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(
            int no,
            String subject,
            int questionType,
            String stemHtml,
            List<Option> options,
            Boolean optionsInline,
            String correct,
            String analysisHtml,
            String answerText,
            List<String> tags,
            Assets assets,
            String sourceRef,
            java.util.Map<String, Object> analysisSource) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(String key, String html) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assets(
            List<String> externalImages,
            Integer inlineSvg,
            Integer katexSpans,
            Integer codeBlocks,
            Integer tables,
            // 候选库扩展：本地有副本（已复制到资源目录并改写 src）/ 无副本缺失清单
            List<LocalImage> localImages,
            List<String> missingImages) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record LocalImage(String src, String file) {
        }
    }
}
