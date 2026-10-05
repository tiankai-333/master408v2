package com.mindskip.xzs.dbdesign;

import com.mindskip.xzs.domain.Question;
import com.mindskip.xzs.domain.canonical.QuestionContent;
import com.mindskip.xzs.domain.enums.QuestionTypeEnum;
import com.mindskip.xzs.service.QuestionContentService;
import com.mindskip.xzs.service.QuestionService;
import com.mindskip.xzs.viewmodel.admin.question.QuestionEditItemVM;
import com.mindskip.xzs.viewmodel.admin.question.QuestionEditRequestVM;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 —— 版本与题型（design-test-plan.md 第 2 节）。
 *
 * <p>调用真实服务 {@code QuestionContentServiceImpl.saveFromEdit} 与读取路径
 * {@code QuestionServiceImpl.getQuestionEditRequestVM}，不使用重写过的 SQL。
 * 只连接隔离库，全部写入在事务内回滚，库中不留数据。</p>
 *
 * <p>覆盖需求：DB-02（版本一致、不拼接不同版本）、DB-03（五种题型可无损表达）、
 * DB-04（原样保存 HTML、纯文本为投影）、DB-11（读取契约）。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/master408_design_test"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=root"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionVersioningTest {

    private static final int BASE_QUESTION_ID = 990401;
    private static final int BASE_TEXT_CONTENT_ID = 990501;

    @Autowired
    private QuestionContentService questionContentService;

    @Autowired
    private QuestionService questionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate rolledBack;

    @BeforeAll
    void setUp() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("本测试只能运行在隔离库上")
                .isEqualTo("master408_design_test");
        rolledBack = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("T3-1 五种题型的题干/选项/答案/解析往返后语义不变，选项顺序、前缀、分值、项标识保持")
    void fiveQuestionTypesRoundTrip() {
        inRolledBackTransaction(() -> {
            int offset = 0;
            for (QuestionTypeEnum type : QuestionTypeEnum.values()) {
                int questionId = BASE_QUESTION_ID + offset();
                int textContentId = BASE_TEXT_CONTENT_ID + offset();
                offset++;

                Question question = question(questionId, textContentId, type.getCode(), "A");
                QuestionEditRequestVM model = editModel("题干 " + type.getName(),
                        "<p>解析 " + type.getName() + "</p>", items("A", "B", "C"));

                questionContentService.saveFromEdit(question, model);
                QuestionContent stored = questionContentService.getCurrent(questionId);

                assertThat(stored).as("%s 应有当前版本", type.getName()).isNotNull();
                assertThat(stored.getTitle()).as("题干原样保存").isEqualTo(model.getTitle());
                assertThat(stored.getAnalysis()).isEqualTo(model.getAnalyze());
                assertThat(stored.getCorrectAnswer())
                        .as("答案取自 t_question.correct（由调用方先落库）")
                        .isEqualTo(question.getCorrect());
                assertThat(stored.getOptions()).as("选项 JSON 含全部三个项")
                        .contains("\"A\"").contains("\"B\"").contains("\"C\"");
                assertThat(stored.getOptions().indexOf("\"A\""))
                        .as("选项顺序必须保持").isLessThan(stored.getOptions().indexOf("\"B\""));
                assertThat(stored.getOptions()).as("分值必须保留").contains("\"score\":\"2\"");
                assertThat(stored.getOptions()).as("项标识必须保留").contains("uuid-A");
            }
        });
    }

    @Test
    @DisplayName("T3-2 连续三次编辑产生版本 1/2/3，且只有最后一版是 current")
    void threeEditsProduceSequentialVersions() {
        inRolledBackTransaction(() -> {
            int questionId = BASE_QUESTION_ID + offset();
            int textContentId = BASE_TEXT_CONTENT_ID + offset();
            Question question = question(questionId, textContentId, QuestionTypeEnum.SingleChoice.getCode(), "A");

            for (int round = 1; round <= 3; round++) {
                questionContentService.saveFromEdit(question,
                        editModel("题干 v" + round, "<p>解析 v" + round + "</p>", items("A", "B")));
            }

            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ?", questionId))
                    .isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM question_content WHERE question_id = ? AND is_current = b'1'",
                    questionId))
                    .as("只有最后一版是 current（DB-02）").isEqualTo(1);

            QuestionContent current = questionContentService.getCurrent(questionId);
            assertThat(current.getVersion()).isEqualTo(3);
            assertThat(current.getTitle()).as("读取必须来自同一版本行，不能拼接历史版本")
                    .isEqualTo("题干 v3");
            assertThat(current.getAnalysis()).isEqualTo("<p>解析 v3</p>");
        });
    }

    @Test
    @DisplayName("T3-3 纯文本投影按规则生成且可重复；has_image / has_code 由内容推导")
    void titleTextProjectionIsDeterministic() {
        inRolledBackTransaction(() -> {
            int questionId = BASE_QUESTION_ID + offset();
            int textContentId = BASE_TEXT_CONTENT_ID + offset();
            Question question = question(questionId, textContentId, QuestionTypeEnum.ShortAnswer.getCode(), "A");

            String html = "<div><p>第一行</p>\n <img src=\"a.png\"/> 第二行 </div>";
            QuestionEditRequestVM model = editModel(html, "<p>解析含 <code>code</code></p>", items("A"));

            questionContentService.saveFromEdit(question, model);
            QuestionContent first = questionContentService.getCurrent(questionId);
            questionContentService.saveFromEdit(question, model);
            QuestionContent second = questionContentService.getCurrent(questionId);

            assertThat(first.getTitle()).as("HTML 原样保存（DB-04）").isEqualTo(html);
            assertThat(first.getTitleText()).as("纯文本为去标签后的投影")
                    .isEqualTo("第一行 第二行");
            assertThat(second.getTitleText()).as("同一输入重复生成结果一致").isEqualTo(first.getTitleText());
            assertThat(first.getHasImage()).as("含 <img> 应置位").isTrue();
            assertThat(first.getHasCode()).as("含 <code> 应置位").isTrue();
            assertThat(first.getContentFormat()).isEqualTo("html");
        });
    }

    @Test
    @DisplayName("T3-4 记录现状：question_content.options 有值，但编辑读取路径不读它（字段所有权未定）")
    void editReadPathIgnoresCanonicalOptions() {
        inRolledBackTransaction(() -> {
            int questionId = BASE_QUESTION_ID + offset();
            int textContentId = BASE_TEXT_CONTENT_ID + offset();

            // 旧 JSON：选项只存在于 t_text_content.content
            jdbcTemplate.update("INSERT INTO t_text_content (id, content) VALUES (?, ?)", textContentId,
                    "{\"titleContent\":\"旧题干\",\"analyze\":\"旧解析\","
                            + "\"questionItemObjects\":[{\"prefix\":\"A\",\"content\":\"旧选项 A\"}],"
                            + "\"correct\":\"A\"}");
            jdbcTemplate.update("INSERT INTO t_question"
                            + " (id, subject_id, question_type, title, correct, info_text_content_id, deleted)"
                            + " VALUES (?, 1, 1, 'legacy-title', 'A', ?, b'0')",
                    questionId, textContentId);

            Question question = question(questionId, textContentId, QuestionTypeEnum.SingleChoice.getCode(), "A");
            questionContentService.saveFromEdit(question,
                    editModel("新题干", "<p>新解析</p>", items("Z")));

            QuestionContent stored = questionContentService.getCurrent(questionId);
            assertThat(stored.getOptions()).as("新表确实保存了新的选项 JSON").contains("新选项 Z");

            QuestionEditRequestVM readBack = questionService.getQuestionEditRequestVM(questionId);
            assertThat(readBack.getTitle()).as("题干读自新表").isEqualTo("新题干");
            assertThat(readBack.getItems()).as("选项读自旧 JSON（新表的 options 无读者）")
                    .hasSize(1);
            assertThat(readBack.getItems().get(0).getContent())
                    .as("因此 question_content.options 目前是只写字段，权威位置尚未确定（DB-01）")
                    .isEqualTo("旧选项 A");
        });
    }

    // ------------------------------------------------------------------ 夹具

    private int offset() {
        return (int) (System.nanoTime() % 50) + 1;
    }

    private void inRolledBackTransaction(Runnable action) {
        rolledBack.execute(status -> {
            action.run();
            status.setRollbackOnly();
            return null;
        });
    }

    private Question question(int questionId, int textContentId, int typeCode, String correct) {
        Question question = new Question();
        question.setId(questionId);
        question.setQuestionType(typeCode);
        question.setCorrect(correct);
        question.setInfoTextContentId(textContentId);
        return question;
    }

    private QuestionEditRequestVM editModel(String title, String analyze, List<QuestionEditItemVM> items) {
        QuestionEditRequestVM model = new QuestionEditRequestVM();
        model.setTitle(title);
        model.setAnalyze(analyze);
        model.setItems(items);
        return model;
    }

    private List<QuestionEditItemVM> items(String... prefixes) {
        List<QuestionEditItemVM> items = new ArrayList<>();
        for (String prefix : prefixes) {
            QuestionEditItemVM item = new QuestionEditItemVM();
            item.setPrefix(prefix);
            item.setContent("新选项 " + prefix);
            item.setScore("2");
            item.setItemUuid("uuid-" + prefix);
            items.add(item);
        }
        return items;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
