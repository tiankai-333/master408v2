package com.mindskip.xzs.ai.client;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAnalysisClientTest {

    @Test
    void returnsProviderUsageWhenTheModelSuppliesIt() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("deepseek-chat")
                .usage(new DefaultUsage(120, 30, 150, null, 20L, 0L))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("measured answer"))), metadata));
        SpringAiAnalysisClient client = new SpringAiAnalysisClient(
                ChatClient.builder(model), new SimpleMeterRegistry());

        AiAnalysisResult result = client.analyzeResult(
                new AiAnalysisRequest("system", "user"));

        assertThat(result.model()).isEqualTo("deepseek-chat");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.totalTokens()).isEqualTo(150);
        assertThat(result.cacheHitTokens()).isEqualTo(20);
        assertThat(result.usageSource()).isEqualTo("provider");
    }

    @Test
    void delegatesThroughSpringAiWithoutCallingARealProvider() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("mocked analysis")))));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiAnalysisClient client = new SpringAiAnalysisClient(
                ChatClient.builder(model), meterRegistry);

        String result = client.analyze(new AiAnalysisRequest(
                "You are an interview coach.",
                "Explain the difference between Java 17 and 21."));

        assertThat(result).isEqualTo("mocked analysis");
        verify(model).call(any(Prompt.class));
        assertThat(meterRegistry.counter(
                "master408.ai.calls", "engine", "spring-ai", "mode", "sync",
                "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void streamsThroughSpringAiWithoutCallingARealProvider() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("first"), response(" second")));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiAnalysisClient client = new SpringAiAnalysisClient(
                ChatClient.builder(model), meterRegistry);
        List<String> tokens = new ArrayList<>();

        client.analyzeStream(new AiAnalysisRequest("system", "user"), tokens::add);

        assertThat(tokens).containsExactly("first", " second");
        verify(model).stream(any(Prompt.class));
        assertThat(meterRegistry.counter(
                "master408.ai.calls", "engine", "spring-ai", "mode", "stream",
                "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void recordsFailedCallsWithoutLeakingThroughMetrics() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider failed"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiAnalysisClient client = new SpringAiAnalysisClient(
                ChatClient.builder(model), meterRegistry);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        client.analyze(new AiAnalysisRequest("system", "user")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meterRegistry.counter(
                "master408.ai.calls", "engine", "spring-ai", "mode", "sync",
                "outcome", "failure").count()).isEqualTo(1);
    }

    @Test
    void keepsConversationMemoryIsolatedAndLeavesLegacyCallsMemoryFree() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                response("answer-a"), response("answer-b"), response("legacy-answer"));
        InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(12)
                .build();
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(memory).build();
        @SuppressWarnings("unchecked")
        ObjectProvider<MessageChatMemoryAdvisor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memoryAdvisor);
        SpringAiAnalysisClient client = new SpringAiAnalysisClient(
                ChatClient.builder(model), new SimpleMeterRegistry(), provider);

        client.analyze(new AiAnalysisRequest("system", "secret-a", "user:7:conversation:conversation-a"));
        client.analyze(new AiAnalysisRequest("system", "secret-b", "user:8:conversation:conversation-a"));
        client.analyze(new AiAnalysisRequest("system", "legacy call"));

        assertThat(memory.get("user:7:conversation:conversation-a"))
                .extracting(message -> message.getText())
                .contains("secret-a", "answer-a")
                .doesNotContain("secret-b");
        assertThat(memory.get("user:8:conversation:conversation-a"))
                .extracting(message -> message.getText())
                .contains("secret-b", "answer-b")
                .doesNotContain("secret-a");
        assertThat(repository.findConversationIds()).containsExactlyInAnyOrder(
                "user:7:conversation:conversation-a",
                "user:8:conversation:conversation-a");
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
