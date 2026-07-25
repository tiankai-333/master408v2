package com.mindskip.xzs.ai.tool;

import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.domain.ai.AgentRunRecord;
import com.mindskip.xzs.repository.AgentRuntimeMapper;
import com.mindskip.xzs.service.AiAgentPlannerService;
import com.mindskip.xzs.service.AiToolOrchestrator;
import com.mindskip.xzs.utility.JsonUtil;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanRequestVM;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanResponseVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchContextVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "ai.engine", havingValue = "spring")
public class SpringAiToolOrchestrator implements AiToolOrchestrator {

    private static final String SYSTEM_PROMPT = """
            你是 Master408 学习工作台的意图路由器。
            如果用户明确想要出题、练习、专项训练或学习安排，必须调用 plan_practice 工具。
            工具只能生成草案，绝不能声称已经创建试卷；真正建卷必须等待用户确认。
            如果用户只是咨询知识、闲聊或意图不清楚，不调用工具，直接简短回答或追问一个必要问题。
            """;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AiAgentPlannerService plannerService;
    private final AgentRuntimeMapper runtimeMapper;

    public SpringAiToolOrchestrator(ChatModel chatModel,
                                    AiAgentPlannerService plannerService,
                                    AgentRuntimeMapper runtimeMapper) {
        this.chatModel = chatModel;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.plannerService = plannerService;
        this.runtimeMapper = runtimeMapper;
    }

    @Override
    public Outcome route(AiWorkbenchRequestVM request, User user) {
        PracticePlanningTool tool = new PracticePlanningTool(
                plannerService, runtimeMapper, request, user);
        ToolCallback[] callbacks = ToolCallbacks.from(tool);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .build();
        List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(request == null || request.getUserMessage() == null
                        ? "" : request.getUserMessage()));
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
        int rounds = 0;
        while (response != null && response.hasToolCalls() && rounds++ < 4) {
            ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(execution.conversationHistory(), options);
            response = chatModel.call(prompt);
        }
        String answer = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        return new Outcome(tool.draft, answer, tool.draft != null);
    }

    static final class PracticePlanningTool {

        private final AiAgentPlannerService plannerService;
        private final AgentRuntimeMapper runtimeMapper;
        private final AiWorkbenchRequestVM workbenchRequest;
        private final User user;
        private AiAgentPlanResponseVM draft;

        PracticePlanningTool(AiAgentPlannerService plannerService,
                             AgentRuntimeMapper runtimeMapper,
                             AiWorkbenchRequestVM workbenchRequest,
                             User user) {
            this.plannerService = plannerService;
            this.runtimeMapper = runtimeMapper;
            this.workbenchRequest = workbenchRequest;
            this.user = user;
        }

        @Tool(name = "plan_practice",
                description = "根据学生要求查询题库并生成练习草案。只读，不创建试卷，返回后仍需学生确认。")
        public String planPractice(
                @ToolParam(required = false, description = "知识点，例如二叉树、进程调度；不明确时留空") String knowledgePoint,
                @ToolParam(required = false, description = "题目数量，范围1到5") Integer questionCount,
                @ToolParam(required = false, description = "建议练习分钟数") Integer minutes,
                @ToolParam(required = false, description = "是否优先选择该学生的错题") Boolean preferMistakes) {
            long start = System.currentTimeMillis();
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("knowledgePoint", knowledgePoint);
            arguments.put("questionCount", questionCount);
            arguments.put("minutes", minutes);
            arguments.put("preferMistakes", preferMistakes);
            try {
                AiAgentPlanRequestVM planRequest = new AiAgentPlanRequestVM();
                planRequest.setMessage(workbenchRequest == null ? null : workbenchRequest.getUserMessage());
                planRequest.setMode("compose_paper");
                planRequest.setQuestionCount(questionCount);
                planRequest.setMinutes(minutes);
                planRequest.setPreferMistakes(preferMistakes);
                planRequest.setContextKnowledgePoint(firstNonBlank(
                        knowledgePoint, contextKnowledgePoint(workbenchRequest)));
                AiWorkbenchContextVM context = workbenchRequest == null ? null : workbenchRequest.getContext();
                planRequest.setSubjectId(context == null ? null : context.getSubjectId());

                draft = plannerService.plan(planRequest, user);
                Map<String, Object> trace = trace(arguments, draft, start, "success", null);
                draft.setToolCallJson(JsonUtil.toJsonStr(trace));
                draft.setRunLogId(insertLog(trace, start, "success", null));
                return JsonUtil.toJsonStr(draft);
            } catch (RuntimeException exception) {
                Map<String, Object> trace = trace(arguments, null, start, "failure", exception.getMessage());
                insertLog(trace, start, "failure", exception.getMessage());
                throw exception;
            }
        }

        private Map<String, Object> trace(Map<String, Object> arguments,
                                          AiAgentPlanResponseVM result,
                                          long start, String status, String error) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("realToolCall", true);
            trace.put("tool", "plan_practice");
            trace.put("arguments", arguments);
            trace.put("result", result);
            trace.put("latencyMs", (int) (System.currentTimeMillis() - start));
            trace.put("status", status);
            if (error != null) {
                trace.put("error", error);
            }
            return trace;
        }

        private Long insertLog(Map<String, Object> trace, long start, String status, String error) {
            try {
                AgentRunRecord record = new AgentRunRecord();
                record.setUserId(user == null ? null : user.getId());
                record.setRequestText(workbenchRequest == null ? null : workbenchRequest.getUserMessage());
                record.setResponseText(draft == null ? null : JsonUtil.toJsonStr(draft));
                record.setToolCallJson(JsonUtil.toJsonStr(trace));
                record.setModelName("spring-ai-tool-calling");
                record.setLatencyMs((int) (System.currentTimeMillis() - start));
                record.setStatus(status);
                record.setErrorMessage(error);
                runtimeMapper.insertRunLog(record);
                return record.getId();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static String contextKnowledgePoint(AiWorkbenchRequestVM request) {
            if (request == null || request.getContext() == null
                    || request.getContext().getKnowledgePoint() == null) {
                return null;
            }
            return request.getContext().getKnowledgePoint().getName();
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first.trim()
                    : second != null && !second.isBlank() ? second.trim() : null;
        }
    }
}
