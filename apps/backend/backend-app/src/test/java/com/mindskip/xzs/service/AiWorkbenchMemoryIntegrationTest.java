package com.mindskip.xzs.service;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.ai.RagService;
import com.mindskip.xzs.ai.memory.ConversationMemoryIdFactory;
import com.mindskip.xzs.domain.User;
import com.mindskip.xzs.service.impl.AiOrchestratorServiceImpl;
import com.mindskip.xzs.viewmodel.student.ai.AiWorkbenchRequestVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiWorkbenchMemoryIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void bindsClientConversationIdToAuthenticatedUserBeforeCallingSpringAi() throws Exception {
        AiAnalysisGateway gateway = mock(AiAnalysisGateway.class);
        RagService ragService = mock(RagService.class);
        AiAgentPlannerService plannerService = mock(AiAgentPlannerService.class);
        ObjectProvider<AiToolOrchestrator> toolProvider = mock(ObjectProvider.class);
        when(toolProvider.getIfAvailable()).thenReturn(null);

        AiOrchestratorServiceImpl service = new AiOrchestratorServiceImpl(
                gateway,
                ragService,
                plannerService,
                new AiIntentRouter(),
                toolProvider,
                new ConversationMemoryIdFactory());

        doAnswer(invocation -> {
            Consumer<String> tokenConsumer = invocation.getArgument(6);
            tokenConsumer.accept("记住了");
            return null;
        }).when(gateway).analyzeStream(
                anyString(), anyString(), anyString(), nullable(String.class),
                anyString(), eq("user:42:conversation:client_12345678"), any());

        AiWorkbenchRequestVM request = new AiWorkbenchRequestVM();
        request.setIntent(AiIntentRouter.FREE_CHAT);
        request.setStyle("default");
        request.setUserMessage("暗号是 kk");
        request.setConversationId("client_12345678");
        User user = new User();
        user.setId(42);
        List<String> chunks = new ArrayList<>();

        service.handleStream(request, user, (event, data) -> {
            if ("chunk".equals(event)) {
                chunks.add(data);
            }
        });

        assertThat(chunks).containsExactly("记住了");
        verify(gateway).analyzeStream(
                anyString(), contains("暗号是 kk"), anyString(), nullable(String.class),
                anyString(), eq("user:42:conversation:client_12345678"), any());
    }
}
