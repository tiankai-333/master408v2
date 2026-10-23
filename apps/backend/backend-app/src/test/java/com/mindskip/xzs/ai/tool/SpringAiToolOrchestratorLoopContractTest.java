package com.mindskip.xzs.ai.tool;

import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.domain.ai.AgentRunRecord;
import com.mindskip.xzs.repository.AgentRuntimeMapper;
import com.mindskip.xzs.service.AiAgentPlannerService;
import com.mindskip.xzs.service.AiToolOrchestrator;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanResponseVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B路确定性契约用例：工具循环边界（SpringAiToolOrchestrator）。
 * ChatModel 与 AiAgentPlannerService / AgentRuntimeMapper 全部 mock，
 * 模型输出固定脚本，不调用真实模型、不写库。
 */
class SpringAiToolOrchestratorLoopContractTest {

    private ChatModel model;
    private AiAgentPlannerService planner;
    private AgentRuntimeMapper runtimeMapper;
    private final User user = new User();

    private SpringAiToolOrchestrator orchestrator() {
        model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        planner = mock(AiAgentPlannerService.class);
        runtimeMapper = mock(AgentRuntimeMapper.class);
        when(runtimeMapper.insertRunLog(any())).thenReturn(1);
        return new SpringAiToolOrchestrator(model, planner, runtimeMapper);
    }

    private AiWorkbenchRequestVM request(String text) {
        AiWorkbenchRequestVM request = new AiWorkbenchRequestVM();
        request.setUserMessage(text);
        return request;
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private AssistantMessage toolCallMessage() {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "plan_practice",
                        """
                        {"knowledgePoint":"进程调度","questionCount":3,"minutes":10,"preferMistakes":true}
                        """)))
                .build();
    }

    // ---- 正常：直答，不触发工具 ----

    @Test
    void plainAnswerDoesNotInvokeToolOrPlanner() {
        SpringAiToolOrchestrator orchestrator = orchestrator();
        when(model.call(any(Prompt.class)))
                .thenReturn(response(new AssistantMessage("进程调度是操作系统的核心概念……")));

        AiToolOrchestrator.Outcome outcome =
                orchestrator.route(request("什么是进程调度？"), user);

        assertThat(outcome.toolCalled()).isFalse();
        assertThat(outcome.terminatedByLimit()).isFalse();
        assertThat(outcome.agentDraft()).isNull();
        assertThat(outcome.answer()).contains("进程调度");
        verify(planner, never()).plan(any(), any());
        verify(runtimeMapper, never()).insertRunLog(any());
    }

    // ---- 循环耗尽：达到 4 轮上限且模型仍请求工具 → 显式终止并留痕 ----

    @Test
    void loopExhaustionTerminatesExplicitlyWithoutFakeAnswer() {
        // 目标契约（D-03/D-08）：耗尽时不得把未消费 tool-call 消息的空文本当最终回答；
        // 调用方收到 terminatedByLimit 终止状态；run log 留下可检查的耗尽原因；
        // 不额外调用模型伪造终止总结（模型调用恰为初始 1 次 + 每轮工具后 1 次共 5 次）。
        SpringAiToolOrchestrator orchestrator = orchestrator();
        AiAgentPlanResponseVM draft = new AiAgentPlanResponseVM();
        draft.setStatus("draft");
        when(planner.plan(any(), any())).thenReturn(draft);
        when(model.call(any(Prompt.class))).thenReturn(response(toolCallMessage()));

        AiToolOrchestrator.Outcome outcome = orchestrator.route(request("安排进程调度练习"), user);

        verify(model, times(5)).call(any(Prompt.class));
        verify(planner, times(4)).plan(any(), any());
        assertThat(outcome.toolCalled()).isTrue();
        assertThat(outcome.terminatedByLimit()).isTrue();
        assertThat(outcome.agentDraft()).isSameAs(draft);
        // 未消费 tool-call 消息的文本不再被当作最终回答
        assertThat(outcome.answer()).isNull();

        // 耗尽留痕：4 次工具执行成功日志 + 1 条 loop_exhausted 终止日志
        ArgumentCaptor<AgentRunRecord> logCaptor = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(runtimeMapper, times(5)).insertRunLog(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .anySatisfy(log -> {
                    assertThat(log.getStatus()).isEqualTo("loop_exhausted");
                    assertThat(log.getErrorMessage()).contains("4 轮上限");
                    assertThat(log.getToolCallJson())
                            .contains("\"status\":\"loop_exhausted\"")
                            .contains("\"rounds\":4");
                });
    }

    // ---- 工具失败：异常被工具框架吞掉转为模型反馈，失败留痕写入 run log ----

    @Test
    void plannerFailureIsSwallowedByToolFrameworkAndLeavesFailureTrace() {
        // 契约要求（discussion 第3轮"复用"清单）：失败降级。
        // 实际可观察行为：plan 抛出的异常不会从 route() 向上传播——Spring AI 默认
        // ToolExecutionExceptionProcessor 把异常消息作为工具结果回灌模型，循环继续；
        // 但 planPractice 自身写入了 status=failure 的 run log，失败未被伪装成成功。
        SpringAiToolOrchestrator orchestrator = orchestrator();
        when(planner.plan(any(), any())).thenThrow(new IllegalStateException("题库查询失败"));
        when(model.call(any(Prompt.class))).thenReturn(
                response(toolCallMessage()),
                response(new AssistantMessage("出错了，请稍后再试。")));

        AiToolOrchestrator.Outcome outcome = orchestrator.route(request("安排练习"), user);

        // 异常未传播：route 正常返回，draft 为空、无草案可确认；失败路径不误标为耗尽
        assertThat(outcome.toolCalled()).isFalse();
        assertThat(outcome.terminatedByLimit()).isFalse();
        assertThat(outcome.agentDraft()).isNull();

        // 失败留痕：status=failure，错误消息保留，不会伪装成成功
        ArgumentCaptor<AgentRunRecord> logCaptor = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(runtimeMapper).insertRunLog(logCaptor.capture());
        AgentRunRecord log = logCaptor.getValue();
        assertThat(log.getStatus()).isEqualTo("failure");
        assertThat(log.getErrorMessage()).contains("题库查询失败");
        assertThat(log.getToolCallJson())
                .contains("\"realToolCall\":true")
                .contains("\"status\":\"failure\"");
    }
}
