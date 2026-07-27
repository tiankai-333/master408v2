package com.mindskip.xzs.ai.prompt;

import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import com.mindskip.xzs.domain.ai.AiPromptRelease;
import com.mindskip.xzs.domain.ai.AiPromptVersion;
import com.mindskip.xzs.repository.AiPromptDefinitionMapper;
import com.mindskip.xzs.repository.AiPromptReleaseMapper;
import com.mindskip.xzs.repository.AiPromptVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把散落在 JSON 文件与 Java 字面量里的 Prompt seed 进控制平面：
 * 已存在（按 prompt_key）则跳过，保证幂等。seed 完触发 registry.refresh()。
 * <p>
 * 之后 JSON 仅作为「数据库不可用时的只读兜底」（见 AnalysisService），
 * 真正的运行时来源是 DB 已发布版本。
 */
@Component
public class PromptSeedLoader {

    private static final Logger logger = LoggerFactory.getLogger(PromptSeedLoader.class);

    private static final String ENVIRONMENT = "default";

    private final AiPromptDefinitionMapper definitionMapper;
    private final AiPromptVersionMapper versionMapper;
    private final AiPromptReleaseMapper releaseMapper;
    private final DbPromptRegistry registry;

    public PromptSeedLoader(AiPromptDefinitionMapper definitionMapper,
                            AiPromptVersionMapper versionMapper,
                            AiPromptReleaseMapper releaseMapper,
                            DbPromptRegistry registry) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.releaseMapper = releaseMapper;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        int created = 0;
        try {
            created += seedAnalysisFromJson("default", "标准解析", "标准 408 解析风格（结构化讲解）。");
            created += seedAnalysisFromJson("feynman", "费曼风格", "费曼式：白话 + 类比。");
            created += seedAnalysisFromJson("first-principles", "第一性原理", "从定义与基本约束推导。");
            created += seedAnalysisFromJson("plato", "启发式", "苏格拉底式多轮追问。");
            created += seedWorkbench("default", "工作台-标准讲解");
            created += seedWorkbench("feynman", "工作台-费曼讲解");
            created += seedWorkbench("first-principles", "工作台-第一性原理");
            created += seedWorkbench("plato", "工作台-启发式讲解");
            created += seedLiteral("tool.intent-router", "意图路由器", "system",
                    IntentRouter.SYSTEM_PROMPT, null, variableNames());
            created += seedLiteral("chat.default", "默认对话", "system",
                    DefaultChat.SYSTEM_PROMPT, null, variableNames());
            created += seedLiteral("vision.ocr", "图片题目识别", "system",
                    VisionOcr.SYSTEM_PROMPT, VisionOcr.USER_PROMPT, variableNames());
        } catch (Exception e) {
            logger.warn("PromptSeedLoader partial failure: {}", e.getMessage(), e);
        }
        if (created > 0) {
            logger.info("PromptSeedLoader seeded {} new definitions, refreshing registry", created);
            registry.refresh();
        }
    }

    /** 读 ai/prompts/analysis/&lt;style&gt;.json 作为 analysis 类型的 seed。 */
    private int seedAnalysisFromJson(String style, String fallbackName, String description) {
        String promptKey = "analysis." + style;
        if (definitionMapper.selectByKey(promptKey) != null) {
            return 0;
        }
        String systemPrompt = null;
        String userPromptTemplate = null;
        List<String> variables = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("ai/prompts/analysis/" + style + ".json");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    byte[] bytes = is.readAllBytes();
                    String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    tools.jackson.databind.JsonNode node = new tools.jackson.databind.ObjectMapper().readTree(json);
                    systemPrompt = text(node, "systemPrompt");
                    userPromptTemplate = text(node, "userPromptTemplate");
                    if (node.has("variables") && node.get("variables").isArray()) {
                        for (tools.jackson.databind.JsonNode v : node.get("variables")) {
                            variables.add(v.asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read seed JSON for {}: {}", promptKey, e.getMessage());
        }
        if (systemPrompt == null) {
            return 0; // 兜底：JSON 缺失则不 seed，运行时由 AnalysisService 的 JSON 兜底处理
        }
        createActiveV1(promptKey, fallbackName, description, "analysis",
                systemPrompt, userPromptTemplate, variables);
        return 1;
    }

    private int seedLiteral(String promptKey, String name, String kind,
                            String systemPrompt, String userPromptTemplate, List<String> variables) {
        if (definitionMapper.selectByKey(promptKey) != null) {
            return 0;
        }
        createActiveV1(promptKey, name, null, kind, systemPrompt, userPromptTemplate, variables);
        return 1;
    }

    private int seedWorkbench(String style, String name) {
        return seedLiteral("workbench." + style, name, "workbench",
                WorkbenchPromptTemplates.SYSTEM_PROMPT,
                WorkbenchPromptTemplates.templateFor(style),
                WorkbenchPromptTemplates.variables());
    }

    private void createActiveV1(String promptKey, String name, String description, String kind,
                                String systemPrompt, String userPromptTemplate, List<String> variables) {
        AiPromptDefinition def = new AiPromptDefinition();
        def.setPromptKey(promptKey);
        def.setName(name);
        def.setDescription(description);
        def.setPromptKind(kind);
        def.setEnabled(Boolean.TRUE);
        def.setCreateUser(1);
        definitionMapper.insert(def);

        AiPromptVersion v = new AiPromptVersion();
        v.setDefinitionId(def.getId());
        v.setVersionNo(1);
        v.setSystemPrompt(systemPrompt);
        v.setUserPromptTemplate(userPromptTemplate);
        v.setVariablesJson(toVariablesJson(variables));
        v.setModelParamsJson("{\"temperature\":0.7,\"maxTokens\":4096}");
        v.setStatus("active");
        v.setChangeReason("seed from baseline");
        v.setCreatedBy(1);
        versionMapper.insert(v);

        AiPromptRelease rel = new AiPromptRelease();
        rel.setDefinitionId(def.getId());
        rel.setEnvironment(ENVIRONMENT);
        rel.setStableVersionId(v.getId());
        rel.setCanaryVersionId(null);
        rel.setCanaryPercent(0);
        rel.setStatus("active");
        rel.setReleasedBy(1);
        releaseMapper.insert(rel);

        logger.info("seeded prompt_key={} as v1 active (definition={}, version={}, release={})",
                promptKey, def.getId(), v.getId(), rel.getId());
    }

    private static List<String> variableNames() {
        return new ArrayList<>();
    }

    private static String toVariablesJson(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) sb.append(',');
            String n = variables.get(i);
            sb.append("{\"name\":\"").append(n).append("\",\"required\":")
                    .append("question".equals(n)).append('}');
        }
        return sb.append(']').toString();
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** 工具意图路由器 system prompt（拷贝自 SpringAiToolOrchestrator.SYSTEM_PROMPT，避免类依赖）。 */
    private static final class IntentRouter {
        private static final String SYSTEM_PROMPT = """
                你是 Master408 学习工作台的意图路由器。
                如果用户明确想要出题、练习、专项训练或学习安排，必须调用 plan_practice 工具。
                工具只能生成草案，绝不能声称已经创建试卷；真正建卷必须等待用户确认。
                如果用户只是咨询知识、闲聊或意图不清楚，不调用工具，直接简短回答或追问一个必要问题。
                """;
    }

    /** 默认对话 system prompt（拷贝自 ChatController.buildSystemPrompt）。 */
    private static final class DefaultChat {
        private static final String SYSTEM_PROMPT = "你是 408Master，一个专业的考研408智能导师。\n\n"
                + "【角色定位】\n"
                + "- 你精通计算机考研408的四门课程：数据结构、计算机组成原理、操作系统、计算机网络\n"
                + "- 你的目标是帮助学生理解知识点，而不仅仅是给出答案\n"
                + "- 你有记忆能力，会根据学生的学习历史提供个性化建议\n\n"
                + "【核心能力】\n"
                + "1. 题目解析：可以用四种风格讲解题目\n"
                + "2. 知识讲解：解释任何408相关知识点\n"
                + "3. 练习生成：根据知识点生成练习题\n"
                + "4. 学习建议：分析薄弱点，给出学习建议\n\n"
                + "【交互方式】\n"
                + "- 用友好、鼓励的语气说话\n"
                + "- 主动关注学生的薄弱点\n\n"
                + "【回答格式】\n"
                + "- 重要内容用 **粗体** 标注\n"
                + "- 列表项用 - 或 数字开头\n"
                + "- 代码或专业术语用 `反引号` 包裹\n"
                + "- 保持回复简洁但信息丰富";
    }

    /** 图片题目识别 system/user prompt（拷贝自 AnalysisService.analyzeImage 字面量）。 */
    private static final class VisionOcr {
        private static final String SYSTEM_PROMPT = "你是一个题目分析助手，需要识别图片中的所有题目内容。图片中可能包含多道题目，请逐一分析。对于每道题目，提取以下信息：题目类型（单选题、多选题、判断题、填空题、简答题）、题目内容（题干）、选项（如果有）、正确答案、解析（如果有）。请以JSON数组格式返回结果，数组中每个元素代表一道题目，不要包含任何多余的文字描述。";
        private static final String USER_PROMPT = "请分析这张图片中的所有题目，以JSON数组格式返回";
    }
}
