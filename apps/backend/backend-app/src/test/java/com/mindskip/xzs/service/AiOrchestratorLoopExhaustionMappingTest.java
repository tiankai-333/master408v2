package com.mindskip.xzs.service;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.ai.RagService;
import com.mindskip.xzs.ai.memory.ConversationMemoryIdFactory;
import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.service.AiOrchestratorService.WorkbenchEventConsumer;
import com.mindskip.xzs.service.impl.AiOrchestratorServiceImpl;
import com.mindskip.xzs.viewmodel.student.ai.AiAgentPlanResponseVM;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B路回归：循环耗尽显式终止的消费方映射（AiOrchestratorServiceImpl）。
 * 耗尽时给调用方可读 status 终止事件；不落入普通对话网关（不产生额外模型调用）。
 * 全部边界 mock，不开应用、不写库、不调用真实模型。
 */
class AiOrchestratorLoopExhaustionMappingTest {

    private AiAnalysisGateway aiAnalysisGateway;
    private RagService ragService;
    private AiAgentPlannerService plannerService;
    private AiToolOrchestrator toolOrchestrator;
    private ConversationMemoryIdFactory conversationMemoryIdFactory;
    private AiOrchestratorServiceImpl orchestratorService;
    private final User user = new User();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        aiAnalysisGateway = mock(AiAnalysisGateway.class);
        ragService = mock(RagService.class);
        plannerService = mock(AiAgentPlannerService.class);
        toolOrchestrator = mock(AiToolOrchestrator.class);
        conversationMemoryIdFactory = mock(ConversationMemoryIdFactory.class);
        when(conversationMemoryIdFactory.create(any(), any())).thenReturn("conv-1");
        ObjectProvider<AiToolOrchestrator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(toolOrchestrator);
        orchestratorService = new AiOrchestratorServiceImpl(aiAnalysisGateway, ragService,
                plannerService, new AiIntentRouter(), provider, conversationMemoryIdFactory);
    }

    /** free_chat 回退路径：无显式 intent、消息不命中规则、无上下文 → 进入模型工具路由。 */
    private AiWorkbenchRequestVM request() {
        AiWorkbenchRequestVM request = new AiWorkbenchRequestVM();
        request.setUserMessage("帮我想想办法");
        return request;
    }

    @Test
    void loopExhaustionSendsReadableStatusAndSkipsChatGateway() throws Exception {
        List<String[]> sent = new ArrayList<>();
        when(toolOrchestrator.route(any(), any()))
                .thenReturn(new AiToolOrchestrator.Outcome(null, null, false, true));

        orchestratorService.handleStream(request(), user, (name, data) -> sent.add(new String[]{name, data}));

        assertThat(sent).anySatisfy(event -> {
            assertThat(event[0]).isEqualTo("status");
            assertThat(event[1]).contains("上限").contains("终止");
        });
        assertThat(sent).extracting(event -> event[0])
                .doesNotContain("chunk", "agentDraft");
        // 终止后不落入普通对话网关：不再额外调用一次模型
        verifyNoInteractions(aiAnalysisGateway);
    }

    @Test
    void loopExhaustionWithDraftStillDeliversDraftAfterStatus() throws Exception {
        List<String[]> sent = new ArrayList<>();
        AiAgentPlanResponseVM draft = new AiAgentPlanResponseVM();
        draft.setStatus("draft");
        when(toolOrchestrator.route(any(), any()))
                .thenReturn(new AiToolOrchestrator.Outcome(draft, null, true, true));

        orchestratorService.handleStream(request(), user, (name, data) -> sent.add(new String[]{name, data}));

        // 先有路由前 status，再有终止 status + 草案
        assertThat(sent).extracting(event -> event[0]).containsExactly("status", "status", "agentDraft");
        assertThat(sent.get(1)[1]).contains("上限").contains("终止");
        verifyNoInteractions(aiAnalysisGateway);
    }

    @Test
    void normalAnswerStillStreamsChunkWithoutTerminationStatus() throws Exception {
        List<String[]> sent = new ArrayList<>();
        when(toolOrchestrator.route(any(), any()))
                .thenReturn(new AiToolOrchestrator.Outcome(null, "进程调度是操作系统的核心概念。", false, false));

        orchestratorService.handleStream(request(), user, (name, data) -> sent.add(new String[]{name, data}));

        // 路由前 status + 正常 chunk；无终止 status
        assertThat(sent).extracting(event -> event[0]).containsExactly("status", "chunk");
        assertThat(sent.get(0)[1]).doesNotContain("上限");
        assertThat(sent.get(1)[1]).contains("进程调度");
        verifyNoInteractions(aiAnalysisGateway);
        verifyNoInteractions(plannerService);
    }
}
