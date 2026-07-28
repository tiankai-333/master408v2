package com.mindskip.xzs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/master408_v2"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "spring.datasource.username=root"
        })
class DatabaseSchemaContractTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "ai_agent", "ai_agent_skill", "ai_provider_config", "ai_run_log", "ai_skill",
            "ai_tool", "ai_user_key", "knowledge_content", "knowledge_point",
            "knowledge_point_relation", "question_asset", "question_content",
            "question_knowledge_point", "question_source", "rag_answer_citation", "rag_chunk",
            "rag_document", "rag_embedding", "rag_retrieval_log", "student_knowledge_state",
            "student_learning_event", "student_mistake_book", "t_ai_adjustment_log",
            "t_ai_knowledge_base", "t_ai_prompt_template", "t_ai_usage_log", "t_essay_question",
            "t_exam_paper", "t_exam_paper_answer", "t_exam_paper_question_customer_answer",
            "t_message", "t_message_user", "t_question", "t_subject", "t_task_exam",
            "t_task_exam_customer_answer", "t_text_content", "t_user", "t_user_event_log",
            "t_user_learning_event", "t_user_learning_profile", "t_user_skill_feedback",
            "t_user_token",
            // M6.5 Prompt 控制平面（Flyway V2）
            "ai_prompt_definition", "ai_prompt_version", "ai_prompt_release", "ai_prompt_audit_log",
            // 固定评测运行与逐条证据（Flyway V3）
            "ai_evaluation_run", "ai_evaluation_case_result",
            "flyway_schema_history"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void tableSetMatchesCanonicalSchema() {
        Set<String> actualTables = new TreeSet<>(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = database()", String.class));

        assertThat(actualTables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void postSchemaFixColumnsExist() {
        assertThat(columnsOf("t_ai_usage_log"))
                .contains("user_id", "key_source", "cache_hit_tokens", "input_tokens",
                        "output_tokens", "request_id", "engine", "mode", "usage_source",
                        "conversation_id", "first_token_latency_ms");
        assertThat(columnsOf("ai_provider_config")).contains("vision_model");
        assertThat(columnsOf("ai_user_key"))
                .contains("user_id", "provider_code", "api_key_cipher", "vision_model");
        assertThat(columnsOf("ai_evaluation_run"))
                .contains("dataset_version", "candidate_label", "average_quality_score",
                        "estimated_input_tokens", "estimated_output_tokens",
                        "estimated_cost", "average_latency_ms", "p95_latency_ms");
        assertThat(columnsOf("ai_evaluation_case_result"))
                .contains("run_id", "case_id", "prompt_key", "prompt_version_id",
                        "prompt_release_id", "quality_score", "concept_coverage",
                        "estimated_input_tokens", "estimated_output_tokens",
                        "estimated_cost", "end_to_end_latency_ms");
    }

    private Set<String> columnsOf(String tableName) {
        return new TreeSet<>(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = database() and table_name = ?",
                String.class, tableName));
    }
}
