package com.mindskip.xzs.newdb.controller;

import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.newdb.config.NewdbDbClient;
import com.mindskip.xzs.newdb.importing.HtmlSanitizer;
import com.mindskip.xzs.newdb.importing.NewdbM2ContentGuard;
import com.mindskip.xzs.newdb.repository.NewdbM2Mapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M2 候选库（master408_m2_candidate）：管理端编辑 / 不可变版本 / 回滚 /
 * 作答版本绑定（开始→恢复→交卷→回看）。写路径全部走事务 + expectedVersion
 * 乐观锁（FOR UPDATE 行锁），冲突 409、失败整事务回滚不留半个版本。
 * 历史版本行 append-only 永不修改；作答绑定开始后指向不可变版本，
 * 后续编辑不影响回看。仅可用于 m2 库（NEWDB_DB_URL 指向 master408_m2_candidate）。
 */
@RestController
@RequestMapping("/api/newdb/m2")
@Profile("newdb")
public class NewdbM2Controller {

    private final NewdbDbClient dbClient;
    private final SecureRandom random = new SecureRandom();

    public NewdbM2Controller(NewdbDbClient dbClient) {
        this.dbClient = dbClient;
    }

    // ------------------------------------------------------------- 题目读取

    @GetMapping("/question/{id}")
    public ResponseEntity<RestResponse<Object>> question(@PathVariable long id) {
        Map<String, Object> q = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectQuestionCurrent(id));
        if (q == null) {
            return notFound("题目不存在");
        }
        List<Map<String, Object>> versions = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectVersions(id));
        Map<String, Object> body = new HashMap<>(q);
        body.put("expectedVersion", q.get("current_version_no"));
        body.put("versions", versions);
        return ResponseEntity.ok(RestResponse.ok(body));
    }

    @GetMapping("/question/by-ref")
    public ResponseEntity<RestResponse<Object>> byRef(
            @org.springframework.web.bind.annotation.RequestParam("ref") String ref) {
        Long id = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectQuestionIdBySourceRef(ref));
        if (id == null) {
            return notFound("题目不存在（source_ref）");
        }
        return ResponseEntity.ok(RestResponse.ok(Map.of("id", id)));
    }

    @GetMapping("/question/{id}/versions")
    public ResponseEntity<RestResponse<Object>> versions(@PathVariable long id) {
        List<Map<String, Object>> versions = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectVersions(id));
        if (versions.isEmpty()) {
            return notFound("题目不存在或无版本");
        }
        return ResponseEntity.ok(RestResponse.ok(versions));
    }

    @GetMapping("/question/{id}/version/{vno}")
    public ResponseEntity<RestResponse<Object>> version(@PathVariable long id,
                                                        @PathVariable int vno) {
        Map<String, Object> v = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectVersion(id, vno));
        if (v == null) {
            return notFound("版本不存在");
        }
        return ResponseEntity.ok(RestResponse.ok(v));
    }

    // ------------------------------------------------------------- 编辑（乐观锁）

    public record EditRequest(Integer expectedVersion, String stemHtml,
                              String analysisHtml, String correct,
                              String editNote) {
    }

    @PutMapping("/question/{id}")
    public ResponseEntity<RestResponse<Object>> edit(@PathVariable long id,
                                                     @RequestBody EditRequest req) {
        if (req.expectedVersion() == null || req.expectedVersion() < 1) {
            return bad("expectedVersion 必须为正整数");
        }
        final String stem = req.stemHtml();
        final String analysis = req.analysisHtml();
        if (stem == null || stem.isBlank()) {
            return bad("题干不能为空");
        }
        // 题干/解析分别独立清洗（修：题干清洗结果误写解析）
        final String cleanStem;
        final String cleanAnalysis;
        try {
            cleanStem = NewdbM2ContentGuard.cleanAndVerify("题干", stem);
            cleanAnalysis = NewdbM2ContentGuard.cleanAndVerify("解析", analysis);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
        if (req.correct() != null && req.correct().length() > 255) {
            return bad("correct 超长（≤255）");
        }
        try {
            return dbClient.inSession(session -> {
                NewdbM2Mapper mapper = session.getMapper(NewdbM2Mapper.class);
                Map<String, Object> q = mapper.selectQuestionCurrent(id);
                Integer currentNo = mapper.selectCurrentVersionNoForUpdate(id);
                if (currentNo == null) {
                    return notFound("题目不存在");
                }
                // 用 int 值比较（修 Integer != 引用比较在 >127 时误冲突）
                if (currentNo.intValue() != req.expectedVersion().intValue()) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body((RestResponse<Object>) RestResponse.fail(
                                    409, "版本冲突：当前 " + currentNo + "，提交基于 "
                                            + req.expectedVersion() + "（原子保护，未写入）"));
                }
                Map<String, Object> v = new HashMap<>();
                v.put("question_id", id);
                v.put("version_no", currentNo + 1);
                v.put("edit_source", "manual_edit");
                v.put("stem_html", cleanStem);
                v.put("analysis_html", cleanAnalysis);
                v.put("correct", req.correct());
                v.put("answer_text", null);
                v.put("content_sha256", NewdbM2ContentGuard.contentSha256(
                        cleanStem, cleanAnalysis, req.correct()));
                v.put("base_version_id", null);
                v.put("edit_note", req.editNote() == null ? "manual_edit" : req.editNote());
                fillSource(mapper, id, v);
                mapper.insertVersion(v);
                long versionId = ((Number) v.get("version_id")).longValue();
                // 保留该题既有的 answer_text 与 assets_json（修：编辑清空两字段）
                mapper.updateQuestionContent(id, versionId, currentNo + 1,
                        cleanStem, cleanAnalysis, req.correct(),
                        (String) q.get("answer_text"),
                        (String) q.get("assets_json"),
                        (String) v.get("content_sha256"));
                Map<String, Object> body = Map.of(
                        "version_no", currentNo + 1,
                        "expectedVersion", currentNo + 1);
                return ResponseEntity.ok(RestResponse.ok(body));
            });
        } catch (RuntimeException e) {
            // 事务已回滚（不留半个版本）；向上返回 500 由全局处理，这里不吞
            throw e;
        }
    }

    // ------------------------------------------------------------- 回滚

    public record RollbackRequest(Integer expectedVersion, Integer toVersion,
                                  String editNote) {
    }

    @PostMapping("/question/{id}/rollback")
    public ResponseEntity<RestResponse<Object>> rollback(@PathVariable long id,
                                                         @RequestBody RollbackRequest req) {
        if (req.expectedVersion() == null || req.expectedVersion() < 1
                || req.toVersion() == null || req.toVersion() < 1) {
            return bad("expectedVersion/toVersion 必须为正整数");
        }
        return dbClient.inSession(session -> {
            NewdbM2Mapper mapper = session.getMapper(NewdbM2Mapper.class);
            Integer currentNo = mapper.selectCurrentVersionNoForUpdate(id);
            if (currentNo == null) {
                return notFound("题目不存在");
            }
            if (currentNo.intValue() != req.expectedVersion()) {
                return conflict(currentNo, req.expectedVersion());
            }
            Map<String, Object> target = mapper.selectVersion(id, req.toVersion());
            if (target == null) {
                return notFound("回滚目标版本不存在：" + req.toVersion());
            }
            Map<String, Object> v = new HashMap<>();
            v.put("question_id", id);
            v.put("version_no", currentNo + 1);
            v.put("edit_source", "rollback");
            v.put("stem_html", target.get("stem_html"));
            v.put("analysis_html", target.get("analysis_html"));
            v.put("correct", target.get("correct"));
            v.put("answer_text", target.get("answer_text"));
            v.put("assets_json", target.get("assets_json"));
            v.put("source_ref", target.get("source_ref"));
            v.put("source_name", target.get("source_name"));
            v.put("source_year", target.get("source_year"));
            v.put("source_question_no", target.get("source_question_no"));
            v.put("content_sha256", target.get("content_sha256"));
            v.put("base_version_id", target.get("version_id"));
            v.put("edit_note", "rollback to v" + req.toVersion());
            mapper.insertVersion(v);
            long versionId = ((Number) v.get("version_id")).longValue();
            mapper.updateQuestionContent(id, versionId, currentNo + 1,
                    (String) target.get("stem_html"),
                    (String) target.get("analysis_html"),
                    (String) target.get("correct"),
                    (String) target.get("answer_text"),
                    (String) target.get("assets_json"),
                    (String) target.get("content_sha256"));
            return ResponseEntity.ok(RestResponse.ok(Map.of(
                    "version_no", currentNo + 1,
                    "rolled_back_to", req.toVersion())));
        });
    }

    // ------------------------------------------------------------- 作答绑定

    public record StartRequest(Long questionId) {
    }

    @PostMapping("/answer/start")
    public ResponseEntity<RestResponse<Object>> start(@RequestBody StartRequest req) {
        if (req.questionId() == null) {
            return bad("questionId 必填");
        }
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        return dbClient.inSession(session -> {
            NewdbM2Mapper mapper = session.getMapper(NewdbM2Mapper.class);
            Integer currentNo = mapper.selectCurrentVersionNoForUpdate(req.questionId());
            if (currentNo == null) {
                return notFound("题目不存在");
            }
            Map<String, Object> v = mapper.selectVersion(req.questionId(), currentNo);
            if (v == null) {
                return notFound("当前版本不存在");
            }
            mapper.insertAnswerBind(token, req.questionId(),
                    ((Number) v.get("version_id")).longValue(), currentNo);
            Map<String, Object> body = new HashMap<>();
            body.put("bind_token", token);
            body.put("bound_version_no", currentNo);
            body.put("stem_html", v.get("stem_html"));
            body.put("analysis_html", v.get("analysis_html"));
            body.put("correct", v.get("correct"));
            return ResponseEntity.ok(RestResponse.ok(body));
        });
    }

    /** 恢复/回看共用：返回开始作答时**绑定的不可变版本**内容（编辑后不变）。 */
    @GetMapping("/answer/{token}")
    public ResponseEntity<RestResponse<Object>> restore(@PathVariable String token) {
        Map<String, Object> bind = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.selectAnswerBind(token));
        if (bind == null) {
            return notFound("绑定不存在");
        }
        return ResponseEntity.ok(RestResponse.ok(bind));
    }

    public record SubmitRequest(String answerText) {
    }

    @PostMapping("/answer/{token}/submit")
    public ResponseEntity<RestResponse<Object>> submit(@PathVariable String token,
                                                       @RequestBody SubmitRequest req) {
        int updated = dbClient.withMapper(NewdbM2Mapper.class,
                m -> m.submitAnswerBind(token,
                        req.answerText() == null ? "" : req.answerText()));
        if (updated == 0) {
            return notFound("绑定不存在或已交卷");
        }
        return ResponseEntity.ok(RestResponse.ok(Map.of("status", "submitted")));
    }

    // ---------------------------------------------------------------- helpers

    private static void fillSource(NewdbM2Mapper mapper, long id, Map<String, Object> v) {
        Map<String, Object> q = mapper.selectQuestionCurrent(id);
        if (q != null) {
            v.put("source_ref", q.get("source_ref"));
            v.put("source_name", q.get("source_name"));
            v.put("source_year", q.get("source_year"));
            v.put("source_question_no", q.get("source_question_no"));
            v.put("assets_json", q.get("assets_json"));
        }
    }

    private static ResponseEntity<RestResponse<Object>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new RestResponse<>(404, message));
    }

    private static ResponseEntity<RestResponse<Object>> bad(String message) {
        return ResponseEntity.badRequest()
                .body(new RestResponse<>(400, message));
    }

    private static ResponseEntity<RestResponse<Object>> conflict(Integer current,
                                                                 Integer expected) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body((RestResponse<Object>) RestResponse.fail(409,
                        "版本冲突：当前 " + current + "，提交基于 " + expected
                                + "（原子保护，未写入）"));
    }
}
