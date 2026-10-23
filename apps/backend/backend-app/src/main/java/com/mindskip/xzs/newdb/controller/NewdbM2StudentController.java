package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.base.BaseApiController;
import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.repository.NewdbM2StudentMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M2 学生端适配（新库消费点，真实学生身份）：
 * 归属 = 主数据源 xzs 学生体系（BaseApiController.getCurrentUser，非管理 token）；
 * 整卷开始绑定（每题绑不可变版本 + 归属 student_id + 重复请求保护返回已有绑定）；
 * 恢复/回看引用绑定版本（管理端再编辑不漂移）；题面接口一律不含答案/解析
 * （未交卷隐藏，交卷后回看经 review 按绑定版本给出，单选给答案+解析、
 * 综合题显示作答并标待人工判分，不虚构自动评分）。
 * 仅可用于 m2 库（NEWDB_DB_URL 指向 master408_m2_candidate）。
 */
@RestController
@RequestMapping("/api/student/m2")
@Profile("newdb")
public class NewdbM2StudentController extends BaseApiController {

    private final NewdbDbClient dbClient;
    private final SecureRandom random = new SecureRandom();

    public NewdbM2StudentController(NewdbDbClient dbClient) {
        this.dbClient = dbClient;
    }

    @GetMapping("/papers")
    public RestResponse<Object> papers() {
        return RestResponse.ok(dbClient.withMapper(NewdbM2StudentMapper.class,
                NewdbM2StudentMapper::listPapers));
    }

    /** 学生视角题面：无 correct/analysis/answer_text。 */
    @GetMapping("/paper/{paperId}")
    public ResponseEntity<RestResponse<Object>> paper(@PathVariable long paperId) {
        Map<String, Object> paper = dbClient.withMapper(NewdbM2StudentMapper.class,
                m -> m.selectPaper(paperId));
        if (paper == null) {
            return notFound("试卷不存在");
        }
        List<Map<String, Object>> questions = dbClient.withMapper(
                NewdbM2StudentMapper.class, m -> m.selectPaperQuestions(paperId));
        Map<String, Object> body = new LinkedHashMap<>(paper);
        body.put("questions", questions);
        return ResponseEntity.ok(RestResponse.ok(body));
    }

    public record StartRequest(Long paperId) {
    }

    /** 开始作答：整卷绑定当前不可变版本；重复请求（同学生同卷 active）返回已有绑定。 */
    @PostMapping("/answer/start")
    public ResponseEntity<RestResponse<Object>> start(@RequestBody StartRequest req) {
        int studentId = getCurrentUser().getId();
        if (req.paperId() == null) {
            return bad("paperId 必填");
        }
        return dbClient.inSession(session -> {
            NewdbM2StudentMapper mapper = session.getMapper(NewdbM2StudentMapper.class);
            // 重复请求保护：已有 active 整卷绑定 → 直接恢复
            Map<String, Object> existing = mapper.selectActiveGroup(studentId,
                    req.paperId());
            if (existing != null) {
                String group = (String) existing.get("bind_group");
                List<Map<String, Object>> binds = mapper.selectActiveBinds(group,
                        studentId);
                return ResponseEntity.ok(RestResponse.ok(Map.of(
                        "bind_group", group,
                        "resumed", true,
                        "questions", binds)));
            }
            List<Map<String, Object>> questions = mapper.selectPaperQuestions(
                    req.paperId());
            if (questions.isEmpty()) {
                return notFound("试卷无题目");
            }
            String group = newToken();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> qRow : questions) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("bind_token", newToken());
                b.put("question_id", ((Number) qRow.get("question_id")).longValue());
                Object vid = qRow.get("version_id");
                b.put("version_id", vid == null ? null : ((Number) vid).longValue());
                Object vno = qRow.get("current_version_no");
                b.put("version_no", vno == null ? 1 : ((Number) vno).intValue());
                b.put("student_id", studentId);
                b.put("paper_id", req.paperId());
                b.put("bind_group", group);
                Object qt = qRow.get("question_type");
                b.put("question_type", qt == null ? null : ((Number) qt).intValue());
                b.put("pending_manual", 0);
                mapper.insertBind(b);
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("question_id", b.get("question_id"));
                view.put("bind_token", b.get("bind_token"));
                view.put("stem_html", qRow.get("stem_html"));
                view.put("assets_json", qRow.get("assets_json"));
                view.put("question_type", b.get("question_type"));
                view.put("subject_name", qRow.get("subject_name"));
                view.put("source_question_no", qRow.get("source_question_no"));
                out.add(view);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bind_group", group);
            body.put("resumed", false);
            body.put("questions", out);
            return ResponseEntity.ok(RestResponse.ok(body));
        });
    }

    /** 恢复：整卷当前作答态（绑定版本题面，无答案/解析；归属校验）。 */
    @GetMapping("/answer/group/{group}")
    public ResponseEntity<RestResponse<Object>> restore(@PathVariable String group) {
        int studentId = getCurrentUser().getId();
        List<Map<String, Object>> binds = dbClient.withMapper(NewdbM2StudentMapper.class,
                m -> m.selectActiveBinds(group, studentId));
        if (binds.isEmpty()) {
            return notFound("作答绑定不存在（或不属于当前学生）");
        }
        return ResponseEntity.ok(RestResponse.ok(Map.of(
                "bind_group", group, "questions", binds)));
    }

    public record SubmitRequest(String answerText) {
    }

    /** 交卷：整卷各题提交（按 bind id 逐题归属+状态保护）；复杂题标待人工。 */
    @PostMapping("/answer/group/{group}/submit")
    public ResponseEntity<RestResponse<Object>> submit(
            @PathVariable String group,
            @RequestBody Map<String, SubmitRequest> answers) {
        int studentId = getCurrentUser().getId();
        return dbClient.inSession(session -> {
            NewdbM2StudentMapper mapper = session.getMapper(NewdbM2StudentMapper.class);
            List<Map<String, Object>> binds = mapper.selectBindsForReview(group,
                    studentId);
            if (binds.isEmpty()) {
                return notFound("作答绑定不存在（或不属于当前学生）");
            }
            int pending = 0;
            for (Map<String, Object> b : binds) {
                long id = ((Number) b.get("id")).longValue();
                String qid = String.valueOf(b.get("question_id"));
                String answerText = answers.get(qid) == null ? ""
                        : answers.get(qid).answerText();
                boolean manual = Objects.equals(b.get("question_type"), 5);
                if (manual) {
                    pending++;
                }
                mapper.submitBind(id, answerText, manual);
            }
            return ResponseEntity.ok(RestResponse.ok(Map.of(
                    "submitted", binds.size(), "pending_manual", pending)));
        });
    }

    /** 回看（交卷前后均可；单选给答案+解析，综合题标待人工；归属隔离）。 */
    @GetMapping("/review/group/{group}")
    public ResponseEntity<RestResponse<Object>> review(@PathVariable String group) {
        int studentId = getCurrentUser().getId();
        List<Map<String, Object>> binds = dbClient.withMapper(NewdbM2StudentMapper.class,
                m -> m.selectBindsForReview(group, studentId));
        if (binds.isEmpty()) {
            return notFound("作答绑定不存在（或不属于当前学生）");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int submitted = 0;
        for (Map<String, Object> b : binds) {
            boolean isSubmitted = "submitted".equals(b.get("status"));
            if (isSubmitted) {
                submitted++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question_id", b.get("question_id"));
            row.put("version_no", b.get("version_no"));
            row.put("question_type", b.get("question_type"));
            row.put("subject_name", b.get("subject_name"));
            row.put("source_question_no", b.get("source_question_no"));
            row.put("answer_text", b.get("answer_text"));
            row.put("status", b.get("status"));
            row.put("stem_html", b.get("stem_html"));
            row.put("assets_json", b.get("assets_json"));
            // 未交卷隐藏答案与解析；交卷后单选给答案，综合题给解析
            if (isSubmitted) {
                if (Objects.equals(b.get("question_type"), 1)) {
                    row.put("correct", b.get("correct"));
                }
                row.put("analysis_html", b.get("analysis_html"));
                row.put("pending_manual", b.get("pending_manual"));
            }
            out.add(row);
        }
        return ResponseEntity.ok(RestResponse.ok(Map.of(
                "bind_group", group, "submitted_count", submitted,
                "questions", out)));
    }

    // ---------------------------------------------------------------- helpers

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static ResponseEntity<RestResponse<Object>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new RestResponse<>(404, message));
    }

    private static ResponseEntity<RestResponse<Object>> bad(String message) {
        return ResponseEntity.badRequest().body(new RestResponse<>(400, message));
    }
}
