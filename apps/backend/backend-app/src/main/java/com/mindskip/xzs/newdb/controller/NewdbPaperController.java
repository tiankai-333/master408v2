package com.mindskip.xzs.newdb.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.domain.NewdbPaperDO;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import com.mindskip.xzs.newdb.repository.NewdbPaperMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选库整卷只读浏览（M1）：按卷列题、按题看内容与来源。无编辑端点——
 * 写入唯一入口是导入管道（与 NewdbQuestionController 同约定）。
 */
@RestController
@RequestMapping("/api/newdb/paper")
@Profile("newdb")
public class NewdbPaperController {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int SHORT_STEM_LENGTH = 80;

    private final NewdbDbClient dbClient;

    public NewdbPaperController(NewdbDbClient dbClient) {
        this.dbClient = dbClient;
    }

    @GetMapping("/list")
    public RestResponse<Object> list() {
        record PaperItem(long id, String name, Integer year, Integer questionCount,
                         Integer warningCount) {
        }
        List<PaperItem> items = dbClient.withMapper(NewdbPaperMapper.class,
                NewdbPaperMapper::listAll).stream()
                .map(p -> new PaperItem(p.getId(), p.getName(), p.getSourceYear(),
                        p.getQuestionCount(), p.getWarningCount()))
                .toList();
        return RestResponse.ok(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestResponse<Object>> detail(@PathVariable long id) {
        NewdbPaperDO paper = dbClient.withMapper(NewdbPaperMapper.class,
                mapper -> mapper.selectById(id));
        if (paper == null) {
            return notFound("试卷不存在");
        }
        List<NewdbQuestionDO> questions =
                dbClient.withMapper(NewdbPaperMapper.class,
                        mapper -> mapper.selectQuestionsByIds(questionIds(paper)));
        questions.sort(Comparator.comparingInt(NewdbPaperController::questionNo));
        record QuestionItem(long id, int no, String subject, int questionType, String shortStem,
                            String correct, Integer displayReady, String statusReason,
                            Integer warningFlags) {
        }
        List<QuestionItem> items = questions.stream()
                .map(q -> new QuestionItem(q.getId(), questionNo(q), q.getSubjectName(),
                        q.getQuestionType(), shortStem(q), q.getCorrect(), q.getDisplayReady(),
                        q.getStatusReason(), warningFlags(q)))
                .toList();
        record PaperDetail(long id, String name, Integer year, int questionCount,
                           String sourceSha256, String sourceUrl, Integer warningCount,
                           List<String> warnings, List<QuestionItem> questions) {
        }
        PaperDetail detail = new PaperDetail(paper.getId(), paper.getName(),
                paper.getSourceYear(), items.size(), paper.getSourceSha256(),
                paper.getSourceUrl(), paper.getWarningCount(), warnings(paper), items);
        return ResponseEntity.ok(RestResponse.ok(detail));
    }

    @GetMapping("/{id}/question/{no}")
    public ResponseEntity<RestResponse<Object>> question(@PathVariable long id,
                                                         @PathVariable int no) {
        NewdbPaperDO paper = dbClient.withMapper(NewdbPaperMapper.class,
                mapper -> mapper.selectById(id));
        if (paper == null) {
            return notFound("试卷不存在");
        }
        List<NewdbQuestionDO> questions =
                dbClient.withMapper(NewdbPaperMapper.class,
                        mapper -> mapper.selectQuestionsByIds(questionIds(paper)));
        NewdbQuestionDO target = questions.stream()
                .filter(q -> questionNo(q) == no)
                .findFirst()
                .orElse(null);
        if (target == null) {
            return notFound("题号不存在：" + no);
        }
        Map<String, Object> assets = parseAssets(target.getAssetsJson());
        record QuestionDetail(long paperId, long id, int no, String subject, int questionType,
                              String stemHtml, String analysisHtml, String correct,
                              Integer displayReady, String statusReason, String sourceRef,
                              List<String> tags, Map<String, Object> assets) {
        }
        QuestionDetail vm = new QuestionDetail(paper.getId(), target.getId(),
                questionNo(target), target.getSubjectName(), target.getQuestionType(),
                target.getStemHtml(), target.getAnalysisHtml(), target.getCorrect(),
                target.getDisplayReady(), target.getStatusReason(), target.getSourceRef(),
                asList(assets.get("tags")), assets);
        return ResponseEntity.ok(RestResponse.ok(vm));
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseEntity<RestResponse<Object>> notFound(String message) {
        RestResponse<Object> body = new RestResponse<>(404, message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static int questionNo(NewdbQuestionDO q) {
        try {
            return Integer.parseInt(q.getSourceQuestionNo());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // 排序时沉底，不掩盖异常数据
        }
    }

    private static List<Long> questionIds(NewdbPaperDO paper) {
        try {
            return JSON.readValue(paper.getQuestionIds(), new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("paper.question_ids 损坏：" + paper.getName(), e);
        }
    }

    private static String shortStem(NewdbQuestionDO q) {
        String text = q.getStemText() == null ? "" : q.getStemText();
        return text.length() <= SHORT_STEM_LENGTH ? text
                : text.substring(0, SHORT_STEM_LENGTH) + "…";
    }

    /** 复杂内容标志位（列表页提示用）：1=有外链图 2=有公式 4=有代码 8=有表格 16=有内联图。 */
    private static Integer warningFlags(NewdbQuestionDO q) {
        Map<String, Object> assets = parseAssets(q.getAssetsJson());
        int flags = 0;
        if (!asList(assets.get("external_images")).isEmpty()) {
            flags |= 1;
        }
        if (asInt(assets.get("katex_spans")) > 0) {
            flags |= 2;
        }
        if (asInt(assets.get("code_blocks")) > 0) {
            flags |= 4;
        }
        if (asInt(assets.get("tables")) > 0) {
            flags |= 8;
        }
        if (asInt(assets.get("inline_svg")) > 0) {
            flags |= 16;
        }
        return flags;
    }

    private static Map<String, Object> parseAssets(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> asList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> warnings(NewdbPaperDO paper) {
        if (paper.getWarningsJson() == null) {
            return List.of();
        }
        try {
            return JSON.readValue(paper.getWarningsJson(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
