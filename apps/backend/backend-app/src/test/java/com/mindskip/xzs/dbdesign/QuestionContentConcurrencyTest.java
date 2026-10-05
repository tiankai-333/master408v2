package com.mindskip.xzs.dbdesign;

import com.mindskip.xzs.domain.Question;
import com.mindskip.xzs.domain.canonical.QuestionContent;
import com.mindskip.xzs.domain.enums.QuestionTypeEnum;
import com.mindskip.xzs.repository.QuestionContentMapper;
import com.mindskip.xzs.service.QuestionContentService;
import com.mindskip.xzs.viewmodel.admin.question.QuestionEditItemVM;
import com.mindskip.xzs.viewmodel.admin.question.QuestionEditRequestVM;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 —— 并发与冲突（design-test-plan.md 第 2 节，本方案的核心证据）。
 *
 * <p>目标：证明「唯一键把竞态从静默数据损坏变成显式失败」。写探针会在隔离库中提交，
 * 因此每个用例前后都清理探针行；库中不留探针数据。</p>
 *
 * <p>设计取舍：并发用例只断言**最终不变量**（每题至多一个 current），不断言"必须有一个线程失败"——
 * 因为 InnoDB 的行锁会让两个编辑在 {@code clearCurrent} 处相互阻塞，实际交错可能被串行化。
 * 两个失败模式本身已由确定性用例覆盖：版本重号见 T2-1，双 current 见 T4-3。</p>
 *
 * <p>覆盖需求：DB-05、DB-10。T2-4（重复请求幂等）按 design-test-plan 在幂等方案确认前不实现。</p>
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
class QuestionContentConcurrencyTest {

    private static final int PROBE_QUESTION_ID = 990301;
    private static final String CANDIDATE_INDEX = "uk_question_content_current";
    private static final String CANDIDATE_COLUMN = "current_question_id";

    @Autowired
    private QuestionContentMapper questionContentMapper;

    @Autowired
    private QuestionContentService questionContentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate committed;
    private TransactionTemplate rolledBack;

    @BeforeAll
    void setUp() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("本测试只能运行在隔离库上")
                .isEqualTo("master408_design_test");
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        rolledBack = new TransactionTemplate(transactionManager);
        rolledBack.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        deleteProbeRows();
    }

    @AfterAll
    void cleanUp() {
        dropCandidateObjects();
        deleteProbeRows();
    }

    @Test
    @Order(1)
    @DisplayName("T2-1 顺序重试：同一 (question_id, version) 第二次插入失败，最终只有一行")
    void sequentialRetryFailsOnSecondInsert() {
        deleteProbeRows();
        committed.executeWithoutResult(status -> insertQuestionAndFirstVersion());

        assertThatThrownBy(() -> committed.executeWithoutResult(status ->
                insertContent(PROBE_QUESTION_ID, 1, "duplicate-version")))
                .as("唯一键必须把重号插入变成显式失败，而不是静默产生两行")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_question_content_version");

        assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ? AND version = 1",
                PROBE_QUESTION_ID)).isEqualTo(1);
        assertThat(currentRows()).isEqualTo(1);
        deleteProbeRows();
    }

    @Test
    @Order(2)
    @DisplayName("T2-2 并发版本竞争：两个线程同时 selectMaxVersion→clearCurrent→insert，最终 current 恒为一行")
    void concurrentVersionRaceKeepsSingleCurrent() throws Exception {
        deleteProbeRows();
        applyCandidateA();
        try {
            committed.executeWithoutResult(status -> insertQuestionAndFirstVersion());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger insertedVersions = new AtomicInteger();
            List<Throwable> failures = new ArrayList<>();
            List<Integer> versions = new ArrayList<>();

            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            Integer version = committed.execute(status -> {
                                int next = questionContentMapper.selectMaxVersion(PROBE_QUESTION_ID) + 1;
                                sleepQuietly(30); // 放大 selectMaxVersion 与 insert 之间的窗口
                                questionContentMapper.clearCurrent(PROBE_QUESTION_ID);
                                QuestionContent content = new QuestionContent();
                                content.setQuestionId(PROBE_QUESTION_ID);
                                content.setVersion(next);
                                content.setTitle("concurrent-" + next);
                                content.setContentFormat("plain");
                                content.setHasImage(false);
                                content.setHasCode(false);
                                content.setCurrent(true);
                                questionContentMapper.insert(content);
                                return next;
                            });
                            synchronized (versions) {
                                versions.add(version);
                            }
                            insertedVersions.incrementAndGet();
                        } catch (Throwable error) {
                            synchronized (failures) {
                                failures.add(error);
                            }
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            System.out.println("[T2-2 观察] 成功写入的版本=" + versions
                    + "，失败明细=" + describeFailures(failures)
                    + "，最终 current 行数=" + currentRows());

            assertThat(insertedVersions.get())
                    .as("至少有一个线程写入成功；失败明细=" + describeFailures(failures) + "；写入的版本=" + versions)
                    .isGreaterThanOrEqualTo(1);
            assertThat(currentRows())
                    .as("不变量：无论交错如何，最终每题至多一个 current（这是本方案要证明的核心）")
                    .isEqualTo(1);
            assertThat(count("SELECT MAX(version) FROM question_content WHERE question_id = ?", PROBE_QUESTION_ID))
                    .as("current 行必须是最大版本")
                    .isEqualTo(currentVersion());
            for (Throwable failure : failures) {
                assertThat(failure)
                        .as("竞争失败必须是显式的唯一键冲突，而不是别的错误")
                        .isInstanceOf(DuplicateKeyException.class);
            }
        } finally {
            deleteProbeRows();
            dropCandidateObjects();
        }
    }

    @Test
    @Order(3)
    @DisplayName("T2-3 clearCurrent 之后失败：事务回滚，原 current 行仍然存在且版本不变")
    void failedTransactionKeepsOriginalCurrent() {
        deleteProbeRows();
        committed.executeWithoutResult(status -> insertQuestionAndFirstVersion());
        Integer originalVersion = currentVersion();

        assertThatThrownBy(() -> committed.executeWithoutResult(status -> {
            questionContentMapper.clearCurrent(PROBE_QUESTION_ID);
            throw new IllegalStateException("模拟 clearCurrent 之后写入失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(currentRows())
                .as("clearCurrent 被回滚，不能留下 0 个 current 的半成品状态")
                .isEqualTo(1);
        assertThat(currentVersion()).isEqualTo(originalVersion);
        deleteProbeRows();
    }

    @Test
    @Order(4)
    @DisplayName("T2-5 真实写入流程并发：两个线程同时调用 saveFromEdit，最终仍只有一个 current")
    void concurrentRealWriteFlowKeepsSingleCurrent() throws Exception {
        deleteProbeRows();
        committed.executeWithoutResult(status -> {
            insertProbeQuestion();
            questionContentService.saveFromEdit(probeQuestion(), editModel("v1"));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();

        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        QuestionContent saved = questionContentService.saveFromEdit(
                                probeQuestion(), editModel("concurrent-real-flow"));
                        synchronized (versions) {
                            versions.add(saved.getVersion());
                        }
                        succeeded.incrementAndGet();
                    } catch (Throwable error) {
                        synchronized (failures) {
                            failures.add(error);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        System.out.println("[T2-5 观察] 真实写入流程（saveFromEdit）：成功版本=" + versions
                + "，失败明细=" + describeFailures(failures)
                + "，最终 current 行数=" + currentRows()
                + "，最终 current 版本=" + currentVersion());

        assertThat(succeeded.get())
                .as("至少有一个线程写入成功；失败明细=" + describeFailures(failures))
                .isGreaterThanOrEqualTo(1);
        assertThat(currentRows())
                .as("不变量：真实写入流程并发后仍只有一行 current")
                .isEqualTo(1);
        assertThat(currentVersion())
                .as("current 行必须是最大版本")
                .isEqualTo(count("SELECT MAX(version) FROM question_content WHERE question_id = ?",
                        PROBE_QUESTION_ID));
        for (Throwable failure : failures) {
            assertThat(failure)
                    .as("真实流程下的竞争失败同样必须是显式唯一键冲突")
                    .isInstanceOf(DuplicateKeyException.class);
        }
        deleteProbeRows();
    }

    // ------------------------------------------------------------------ 夹具

    private void insertQuestionAndFirstVersion() {
        insertProbeQuestion();
        insertContent(PROBE_QUESTION_ID, 1, "baseline");
    }

    private void insertProbeQuestion() {
        jdbcTemplate.update("INSERT INTO t_question (id, subject_id, question_type, title, correct, deleted)"
                + " VALUES (?, 1, 1, 'concurrency-probe', 'A', b'0')", PROBE_QUESTION_ID);
    }

    private Question probeQuestion() {
        Question question = new Question();
        question.setId(PROBE_QUESTION_ID);
        question.setQuestionType(QuestionTypeEnum.SingleChoice.getCode());
        question.setCorrect("A");
        question.setInfoTextContentId(990601);
        return question;
    }

    private QuestionEditRequestVM editModel(String title) {
        QuestionEditItemVM item = new QuestionEditItemVM();
        item.setPrefix("A");
        item.setContent("option A");
        item.setScore("2");
        item.setItemUuid("uuid-A");
        QuestionEditRequestVM model = new QuestionEditRequestVM();
        model.setTitle(title);
        model.setAnalyze("<p>analysis</p>");
        model.setItems(List.of(item));
        return model;
    }

    private void insertContent(int questionId, int version, String title) {
        jdbcTemplate.update("INSERT INTO question_content"
                        + " (question_id, version, title, content_format, has_image, has_code, is_current)"
                        + " VALUES (?, ?, ?, 'plain', b'0', b'0', b'1')",
                questionId, version, title);
    }

    private void applyCandidateA() {
        dropCandidateObjects();
        int columnExists = count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'question_content' AND COLUMN_NAME = ?", CANDIDATE_COLUMN);
        if (columnExists == 0) {
            jdbcTemplate.execute("ALTER TABLE question_content ADD COLUMN " + CANDIDATE_COLUMN
                    + " INT GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL");
        }
        jdbcTemplate.execute("ALTER TABLE question_content ADD UNIQUE KEY " + CANDIDATE_INDEX
                + " (" + CANDIDATE_COLUMN + ")");
    }

    private void dropCandidateObjects() {
        if (count("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'question_content' AND INDEX_NAME = ?", CANDIDATE_INDEX) > 0) {
            jdbcTemplate.execute("ALTER TABLE question_content DROP INDEX " + CANDIDATE_INDEX);
        }
        if (count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'question_content' AND COLUMN_NAME = ?", CANDIDATE_COLUMN) > 0) {
            jdbcTemplate.execute("ALTER TABLE question_content DROP COLUMN " + CANDIDATE_COLUMN);
        }
    }

    private void deleteProbeRows() {
        jdbcTemplate.update("DELETE FROM question_content WHERE question_id = ?", PROBE_QUESTION_ID);
        jdbcTemplate.update("DELETE FROM t_question WHERE id = ?", PROBE_QUESTION_ID);
    }

    private int currentRows() {
        return count("SELECT COUNT(*) FROM question_content WHERE question_id = ? AND is_current = b'1'",
                PROBE_QUESTION_ID);
    }

    private Integer currentVersion() {
        return count("SELECT version FROM question_content WHERE question_id = ? AND is_current = b'1'",
                PROBE_QUESTION_ID);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String describeFailures(List<Throwable> failures) {
        StringBuilder description = new StringBuilder("[");
        for (Throwable failure : failures) {
            description.append(failure.getClass().getSimpleName()).append(": ")
                    .append(String.valueOf(failure.getMessage()).replaceAll("\\s+", " ")).append(" | ");
        }
        return description.append(']').toString();
    }
}
