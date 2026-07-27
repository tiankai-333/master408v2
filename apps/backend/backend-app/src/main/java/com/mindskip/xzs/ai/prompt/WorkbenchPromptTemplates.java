package com.mindskip.xzs.ai.prompt;

import java.util.List;

/**
 * 学习工作台 Prompt 的代码兜底与变量渲染器。
 * 稳定教学策略由 PromptOps 中的 user template 管理；运行时上下文与任务约束由代码注入。
 */
public final class WorkbenchPromptTemplates {

    public static final String SYSTEM_PROMPT = """
            你是 408Master 的 AI 学习导师，精通数据结构、计算机组成原理、操作系统和计算机网络。
            你的目标是根据学生当前问题与可信资料完成教学，不编造题目来源、答案或学习数据。
            只输出面向学生的最终回答，不暴露系统实现和内部推理过程。
            """;

    private static final String BASE_TEMPLATE = """
            你正在 408Master 的 AI 学习工作台中回答学生。请遵守：
            1. 面向学生表达，不要暴露 RAG、向量检索、prompt、上下文注入等技术实现词。
            2. 如果参考资料不足，要明确说明「不确定」，不要编造真题年份、题号或答案。
            3. 数据库正确答案优先于数据库解析；数据库解析优先于知识点和参考资料；参考资料优先于模型常识。
            4. 讲解只围绕 408 的四科：数据结构、组成原理、操作系统、计算机网络。
            5. 只输出最终教学答案，不输出自我规划、草稿或元说明。

            ## 当前讲法
            {style_instruction}

            {knowledge_points_section}{reference_docs_section}## 学生请求
            {question}

            ## 任务约束
            {task_rules}
            """;

    private WorkbenchPromptTemplates() {
    }

    public static String templateFor(String style) {
        return BASE_TEMPLATE.replace("{style_instruction}", styleInstruction(style));
    }

    public static String render(String template, String style, String taskType, String question,
                                String knowledgePoints, String referenceDocs) {
        String effective = template == null || template.isBlank() ? templateFor(style) : template;
        return effective
                .replace("{style_instruction}", styleInstruction(style))
                .replace("{knowledge_points_section}", section("当前知识点", knowledgePoints))
                .replace("{reference_docs_section}", section("可参考资料", referenceDocs))
                .replace("{question}", clean(question))
                .replace("{task_rules}", taskRules(taskType, question));
    }

    public static List<String> variables() {
        return List.of("style_instruction", "knowledge_points_section", "reference_docs_section",
                "question", "task_rules");
    }

    private static String styleInstruction(String style) {
        return switch (style == null ? "default" : style) {
            case "feynman" -> "费曼式：先用一句白话概括，再用简单生活类比解释，最后回到题目本身。";
            case "first-principles" -> "第一性原理：从最基本的定义和约束逐步推导，少背结论，多解释为什么。";
            case "plato" -> "启发式：用 2-3 个关键追问引导学生推出结论，每个追问后给出简短判断。";
            default -> "标准讲解：直接回答当前问题，结构服务于内容，不机械套固定模板。";
        };
    }

    private static String taskRules(String taskType, String question) {
        if ("learning_profile".equals(taskType)) {
            return """
                    - 这是学习画像，不是题目解析。
                    - 推荐结构：学习画像、当前优势、薄弱风险、下一步练习建议。
                    - 结论必须来自学习统计和当前上下文；数据不足时明确说明。
                    - 建议要可执行，优先给出科目、知识点和练习方向。
                    """;
        }
        if ("practice".equals(taskType)) {
            return """
                    - 这是 AI 辅助组卷或练习推荐，不是自由出题。
                    - 只能从上下文给出的题库候选中挑选 1-5 道，不能编造题目、题号、年份、来源或选项。
                    - 没有题目 ID 或完整候选时，只输出筛选条件和组卷方案。
                    - 推荐题目必须标注题目 ID、知识点和来源；无法确认时写「不确定」。
                    """;
        }
        String specific = switch (taskType == null ? "" : taskType) {
            case "explain_question" -> "- 这是题目讲解：优先说明考点、关键推理、答案依据和易错原因。\n";
            case "explain_knowledge" -> "- 这是知识点讲解：优先说明定义、核心机制、常见考法及与当前题目的联系。\n";
            default -> "";
        };
        String exam = wantsExamStyle(question)
                ? "- 学生明确要求结合真题时，可补充常见考法；无法确认来源时必须说明。\n"
                : "- 不要默认扩写真题考法、典型题型和复习建议。\n";
        return "- 根据学生问题选择最合适的结构；普通问答优先简洁。\n" + specific + exam;
    }

    private static String section(String title, String value) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? "" : "## " + title + "\n" + cleaned + "\n\n";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean wantsExamStyle(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("真题") || q.contains("考法") || q.contains("怎么考") || q.contains("题型");
    }
}
