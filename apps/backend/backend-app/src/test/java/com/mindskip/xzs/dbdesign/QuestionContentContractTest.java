package com.mindskip.xzs.dbdesign;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1 —— 结构契约与 current 唯一性（design-test-plan.md 第 2 节）。
 *
 * <p>本测试只连接隔离库 {@code master408_design_test}，结构与数据由 Flyway V1..V5 建立；
 * 不连接也不修改共享开发库。写探针都在事务内执行并回滚，库中不留探针数据；
 * 候选 DDL 属 DDL 语句，MySQL 会隐式提交，因此由本类显式建立与清理。</p>
 *
 * <p>覆盖需求：DB-05（current 唯一性与并发冲突）、DB-07/DB-10（外键缺失的现状记录）、DB-11（结构契约）。</p>
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
class QuestionContentContractTest {

    private static final int PROBE_QUESTION_ID = 990001;
    private static final int PROBE_VERSION_A = 9001;
    private static final int PROBE_VERSION_B = 9002;
    private static final String CANDIDATE_INDEX = "uk_question_content_current";
    private static final String CANDIDATE_COLUMN = "current_question_id";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeAll
    void verifyIsolatedDatabaseAndCleanState() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(schema)
                .as("本测试只能运行在隔离库上，禁止连接共享开发库")
                .isEqualTo("master408_design_test");
        // 让本类可重复运行：清掉上一次可能残留的候选对象
        dropCandidateObjects();
    }

    @AfterAll
    void cleanUp() {
        dropCandidateObjects();
    }

    @Test
    @Order(1)
    @DisplayName("T1-1 隔离库结构：六张题目领域表存在，current 只有普通索引、没有唯一约束与外键")
    void baselineStructure() {
        for (String table : List.of("t_question", "t_text_content", "question_content",
                "question_source", "question_asset", "question_knowledge_point")) {
            assertThat(tableExists(table)).as("表 %s 应存在", table).isTrue();
        }

        assertThat(indexNonUnique("uk_question_content_version"))
                .as("(question_id, version) 必须唯一").isZero();
        assertThat(indexNonUnique("idx_question_content_current"))
                .as("(question_id, is_current) 只是普通索引").isEqualTo(1);
        assertThat(constraintCount(CANDIDATE_INDEX))
                .as("修复前不存在 current 唯一约束").isZero();
        assertThat(foreignKeyCount())
                .as("question_content 当前没有外键（A-04/A-12 的现状）").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("T1-2 同一 (question_id, version) 重复插入被 unique key 拒绝")
    void duplicateVersionRejected() {
        assertThatThrownBy(() -> inRolledBackTransaction(() -> {
            insertProbeQuestion();
            insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, true);
            insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, false);
        }))
                .as("版本号唯一性由 uk_question_content_version 保证")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_question_content_version");
    }

    @Test
    @Order(3)
    @DisplayName("T1-3 候选 A（生成列 + 唯一键）拒绝第二个 current，且不约束非 current 行")
    void candidateARejectsSecondCurrentRow() {
        applyCandidateA();
        try {
            assertThatThrownBy(() -> inRolledBackTransaction(() -> {
                insertProbeQuestion();
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, true);
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_B, true);
            }))
                    .as("两个不同 version 同时 is_current=b'1' 必须被拒绝")
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining(CANDIDATE_INDEX);

            assertThatCode(() -> inRolledBackTransaction(() -> {
                insertProbeQuestion();
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, false);
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_B, false);
            }))
                    .as("非 current 行生成列为 NULL，不应被唯一键约束")
                    .doesNotThrowAnyException();
        } finally {
            dropCandidateObjects();
        }
    }

    @Test
    @Order(4)
    @DisplayName("T1-4 候选 A 只保证“至多一个”，不保证“必须有一个”")
    void candidateAAllowsZeroCurrent() {
        applyCandidateA();
        try {
            assertThatCode(() -> inRolledBackTransaction(() -> {
                insertProbeQuestion();
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, false);
            }))
                    .as("没有 current 行的题目仍可写入：唯一性不等于存在性，需要另一条验证")
                    .doesNotThrowAnyException();
        } finally {
            dropCandidateObjects();
        }
    }

    @Test
    @Order(5)
    @DisplayName("T1-5 候选 B（函数索引）与候选 A 具备同等约束能力")
    void candidateBRejectsSecondCurrentRow() {
        applyCandidateB();
        try {
            assertThatThrownBy(() -> inRolledBackTransaction(() -> {
                insertProbeQuestion();
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, true);
                insertContent(PROBE_QUESTION_ID, PROBE_VERSION_B, true);
            }))
                    .as("函数索引同样必须拒绝第二个 current")
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining(CANDIDATE_INDEX);
        } finally {
            dropCandidateObjects();
        }
    }

    @Test
    @Order(6)
    @DisplayName("T1-6 缺外键的现状：删除被引用的题目会成功，并留下孤儿内容行")
    void deletingReferencedQuestionLeavesOrphanContent() {
        inRolledBackTransaction(() -> {
            insertProbeQuestion();
            insertContent(PROBE_QUESTION_ID, PROBE_VERSION_A, true);

            assertThatCode(() -> jdbcTemplate.update("DELETE FROM t_question WHERE id = ?", PROBE_QUESTION_ID))
                    .as("当前没有外键，删除被引用的题目不会被阻止（D-07 的现状依据）")
                    .doesNotThrowAnyException();

            assertThat(count("SELECT COUNT(*) FROM t_question WHERE id = ?", PROBE_QUESTION_ID)).isZero();
            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ?", PROBE_QUESTION_ID))
                    .as("删除题目后内容行成为孤儿，且不会被级联清理")
                    .isEqualTo(1);
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

    private void insertProbeQuestion() {
        jdbcTemplate.update(
                "INSERT INTO t_question (id, subject_id, question_type, title, deleted) VALUES (?, 1, 1, 'probe', b'0')",
                PROBE_QUESTION_ID);
    }

    private void insertContent(int questionId, int version, boolean current) {
        jdbcTemplate.update(
                "INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code, is_current)"
                        + " VALUES (?, ?, ?, 'plain', b'0', b'0', ?)",
                questionId, version, "probe-" + version, current);
    }

    private void applyCandidateA() {
        dropCandidateObjects();
        if (!columnExists(CANDIDATE_COLUMN)) {
            jdbcTemplate.execute("ALTER TABLE question_content ADD COLUMN " + CANDIDATE_COLUMN
                    + " INT GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL");
        }
        if (!indexExists(CANDIDATE_INDEX)) {
            jdbcTemplate.execute("ALTER TABLE question_content ADD UNIQUE KEY " + CANDIDATE_INDEX
                    + " (" + CANDIDATE_COLUMN + ")");
        }
    }

    private void applyCandidateB() {
        dropCandidateObjects();
        if (!indexExists(CANDIDATE_INDEX)) {
            jdbcTemplate.execute("ALTER TABLE question_content ADD UNIQUE KEY " + CANDIDATE_INDEX
                    + " ((IF(is_current = b'1', question_id, NULL)))");
        }
    }

    private void dropCandidateObjects() {
        if (indexExists(CANDIDATE_INDEX)) {
            jdbcTemplate.execute("ALTER TABLE question_content DROP INDEX " + CANDIDATE_INDEX);
        }
        if (columnExists(CANDIDATE_COLUMN)) {
            jdbcTemplate.execute("ALTER TABLE question_content DROP COLUMN " + CANDIDATE_COLUMN);
        }
    }

    // ------------------------------------------------------------------ 元数据查询

    private boolean tableExists(String table) {
        return count("SELECT COUNT(*) FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", table) > 0;
    }

    private boolean indexExists(String index) {
        return count("SELECT COUNT(*) FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content' AND INDEX_NAME = ?",
                index) > 0;
    }

    private Integer indexNonUnique(String index) {
        // information_schema.STATISTICS 对多列索引按列各给一行，因此必须去重统计索引个数
        return count("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'"
                + " AND INDEX_NAME = ? AND NON_UNIQUE = 1", index);
    }

    private boolean columnExists(String column) {
        return count("SELECT COUNT(*) FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content' AND COLUMN_NAME = ?",
                column) > 0;
    }

    private Integer constraintCount(String constraint) {
        return count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content' AND CONSTRAINT_NAME = ?",
                constraint);
    }

    private Integer foreignKeyCount() {
        return count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'"
                + " AND CONSTRAINT_TYPE = 'FOREIGN KEY'");
    }

    private Integer count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
