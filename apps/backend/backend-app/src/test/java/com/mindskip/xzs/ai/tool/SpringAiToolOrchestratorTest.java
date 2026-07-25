package com.mindskip.xzs.ai.tool;

import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.repository.AgentRuntimeMapper;
import com.mindskip.xzs.service.AiAgentPlannerService;
import com.mindskip.xzs.service.AiToolOrchestrator;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanResponseVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiToolOrchestratorTest {

    @Test
    void executesFrameworkControlledToolLoopAndReturnsCapturedDraft() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        AssistantMessage toolRequest = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "plan_practice",
                        """
                        {"knowledgePoint":"二叉树","questionCount":3,"minutes":10,"preferMistakes":true}
                        """)))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(
                response(toolRequest), response(new AssistantMessage("草案已生成，请确认。")));

        AiAgentPlannerService planner = mock(AiAgentPlannerService.class);
        AiAgentPlanResponseVM draft = new AiAgentPlanResponseVM();
        draft.setStatus("draft");
        draft.setKnowledgePoint("二叉树");
        when(planner.plan(any(), any())).thenReturn(draft);
        AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
        SpringAiToolOrchestrator orchestrator = new SpringAiToolOrchestrator(
                model, planner, runtimeMapper);
        AiWorkbenchRequestVM request = new AiWorkbenchRequestVM();
        request.setUserMessage("我二叉树不太行，帮我安排一下");

        AiToolOrchestrator.Outcome outcome = orchestrator.route(request, new User());

        assertThat(outcome.agentDraft()).as("outcome=%s", outcome).isSameAs(draft);
        assertThat(outcome.toolCalled()).isTrue();
        assertThat(draft.getToolCallJson()).contains("\"realToolCall\":true")
                .contains("\"tool\":\"plan_practice\"")
                .contains("\"knowledgePoint\":\"二叉树\"");
        verify(model, times(2)).call(any(Prompt.class));
        verify(planner).plan(any(), any());
        verify(runtimeMapper).insertRunLog(any());
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
