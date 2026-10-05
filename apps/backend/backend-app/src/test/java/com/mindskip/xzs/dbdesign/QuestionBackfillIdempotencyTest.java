package com.mindskip.xzs.dbdesign;

import com.mindskip.xzs.repository.QuestionContentMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 —— 回填可重复性（design-test-plan.md 第 2 节）。
 *
 * <p>直接调用真实语句 {@code QuestionContentMapper.backfillFromLegacy}，不使用重写过的 SQL。
 * 只连接隔离库 {@code master408_design_test}，所有探针写入都在事务内回滚，库中不留数据。</p>
 *
 * <p>覆盖需求：DB-10（迁移可重复、可对账、冲突隔离）、DB-08（软删除题目不进入回填）、
 * DB-01（旧 JSON 仍是回填输入）。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/master408_design_test"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=root"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuestionBackfillIdempotencyTest {

    private static final int QID_ACTIVE = 990101;
    private static final int QID_DELETED = 990102;
    private static final int QID_MISSING_VERSION_ONE = 990103;
    private static final int QID_LEGACY_JSON = 990104;
    private static final int LEGACY_TEXT_CONTENT_ID = 990201;

    @Autowired
    private QuestionContentMapper questionContentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeAll
    void verifyIsolatedDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("本测试只能运行在隔离库上")
                .isEqualTo("master408_design_test");
        assertThat(count("SELECT COUNT(*) FROM t_question"))
                .as("隔离库必须没有业务题目；否则全局回填的计数断言失去意义")
                .isZero();
    }

    @AfterAll
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM question_content WHERE question_id BETWEEN 990100 AND 990199");
        jdbcTemplate.update("DELETE FROM t_question WHERE id BETWEEN 990100 AND 990199");
        jdbcTemplate.update("DELETE FROM t_text_content WHERE id = ?", LEGACY_TEXT_CONTENT_ID);
    }

    @Test
    @Order(1)
    @DisplayName("T4-1 首次回填为缺 version=1 的有效题生成一行 current；软删除题不参与")
    void firstBackfillInsertsOnlyActiveQuestions() {
        inRolledBackTransaction(() -> {
            insertQuestion(QID_ACTIVE, "题干 A", false, null);
            insertQuestion(QID_DELETED, "题干 D", true, null);

            assertThat(questionContentMapper.backfillFromLegacy())
                    .as("只有未删除且缺 version=1 的题目被回填")
                    .isEqualTo(1);

            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ?", QID_ACTIVE))
                    .isEqualTo(1);
            assertThat(currentRows(QID_ACTIVE)).as("回填行默认为 current").isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ?", QID_DELETED))
                    .as("软删除题目不产生内容行（DB-08）")
                    .isZero();
        });
    }

    @Test
    @Order(2)
    @DisplayName("T4-2 重复回填不新增行，source_hash 与行数保持不变")
    void secondBackfillIsIdempotent() {
        inRolledBackTransaction(() -> {
            insertQuestion(QID_ACTIVE, "题干 A", false, null);

            assertThat(questionContentMapper.backfillFromLegacy()).isEqualTo(1);
            String hashAfterFirst = sourceHash(QID_ACTIVE);

            assertThat(questionContentMapper.backfillFromLegacy())
                    .as("NOT EXISTS (version = 1) 保证第二次不新增")
                    .isZero();
            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ?", QID_ACTIVE))
                    .isEqualTo(1);
            assertThat(sourceHash(QID_ACTIVE)).isEqualTo(hashAfterFirst);
        });
    }

    @Test
    @Order(3)
    @DisplayName("T4-3 陷阱记录：缺 version=1 但已有 current 的题目，回填会造出第二个 current")
    void backfillCanCreateASecondCurrentRow() {
        inRolledBackTransaction(() -> {
            insertQuestion(QID_MISSING_VERSION_ONE, "题干 B", false, null);
            // 模拟异常历史状态：只有 version=2 且它是 current
            jdbcTemplate.update("INSERT INTO question_content"
                            + " (question_id, version, title, content_format, is_current)"
                            + " VALUES (?, 2, 'v2', 'plain', b'1')", QID_MISSING_VERSION_ONE);
            assertThat(currentRows(QID_MISSING_VERSION_ONE)).isEqualTo(1);

            assertThat(questionContentMapper.backfillFromLegacy())
                    .as("该题缺 version=1，因此仍会被回填")
                    .isEqualTo(1);

            assertThat(currentRows(QID_MISSING_VERSION_ONE))
                    .as("回填无条件写 is_current=b'1'，于是出现两个 current；"
                            + "候选唯一键会把这个静默损坏变成显式 1062 失败")
                    .isEqualTo(2);
        });
    }

    @Test
    @Order(4)
    @DisplayName("T4-4 回填确实以旧 JSON 为输入：题干与解析来自 t_text_content.content")
    void backfillReadsLegacyJson() {
        inRolledBackTransaction(() -> {
            jdbcTemplate.update("INSERT INTO t_text_content (id, content) VALUES (?, ?)",
                    LEGACY_TEXT_CONTENT_ID,
                    "{\"titleContent\":\"JSON 题干\",\"analyze\":\"JSON 解析\"}");
            insertQuestion(QID_LEGACY_JSON, null, false, LEGACY_TEXT_CONTENT_ID);

            assertThat(questionContentMapper.backfillFromLegacy()).isEqualTo(1);

            assertThat(string("SELECT title FROM question_content WHERE question_id = ?", QID_LEGACY_JSON))
                    .as("题干来自旧 JSON 的 titleContent（DB-01）")
                    .isEqualTo("JSON 题干");
            assertThat(string("SELECT analysis FROM question_content WHERE question_id = ?", QID_LEGACY_JSON))
                    .isEqualTo("JSON 解析");
        });
    }

    // ------------------------------------------------------------------ 夹具

    private void inRolledBackTransaction(Runnable action) {
        transactionTemplate.execute(status -> {
            action.run();
            status.setRollbackOnly();
            return null;
        });
    }

    private void insertQuestion(int id, String title, boolean deleted, Integer legacyTextContentId) {
        jdbcTemplate.update("INSERT INTO t_question"
                        + " (id, subject_id, question_type, title, info_text_content_id, deleted)"
                        + " VALUES (?, 1, 1, ?, ?, ?)",
                id, title, legacyTextContentId, deleted);
    }

    private int currentRows(int questionId) {
        return count("SELECT COUNT(*) FROM question_content WHERE question_id = ? AND is_current = b'1'", questionId);
    }

    private String sourceHash(int questionId) {
        return string("SELECT source_hash FROM question_content WHERE question_id = ?", questionId);
    }

    private String string(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
