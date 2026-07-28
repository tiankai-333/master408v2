package com.mindskip.xzs.ai.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Spring AI 2.0 implementation of the provider-neutral analysis boundary.
 */
@Component
@ConditionalOnProperty(name = "ai.engine", havingValue = "spring")
public class SpringAiAnalysisClient implements AiAnalysisClient {

    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;
    private final MessageChatMemoryAdvisor memoryAdvisor;

    @Autowired
    public SpringAiAnalysisClient(ChatClient.Builder chatClientBuilder,
                                  MeterRegistry meterRegistry,
                                  ObjectProvider<MessageChatMemoryAdvisor> memoryAdvisorProvider) {
        this.memoryAdvisor = memoryAdvisorProvider.getIfAvailable();
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    SpringAiAnalysisClient(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.memoryAdvisor = null;
    }

    @Override
    public String analyze(AiAnalysisRequest request) {
        return analyzeResult(request).content();
    }

    @Override
    public AiAnalysisResult analyzeResult(AiAnalysisRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ChatResponse response = chatClient.prompt()
                    .system(request.systemPrompt())
                    .user(request.userPrompt())
                    .advisors(advisor -> addConversationMemory(advisor, request))
                    .call()
                    .chatResponse();
            count("sync", "success");
            return toResult(request, response);
        } catch (RuntimeException exception) {
            count("sync", "failure");
            throw exception;
        } finally {
            sample.stop(timer("sync"));
        }
    }

    private AiAnalysisResult toResult(AiAnalysisRequest request, ChatResponse response) {
        if (response == null) {
            return AiAnalysisResult.estimated(request, "");
        }
        String content = response.getResult() == null
                || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null || usage.getTotalTokens() == null) {
            return AiAnalysisResult.estimated(request, content);
        }
        int input = value(usage.getPromptTokens());
        int output = value(usage.getCompletionTokens());
        int cacheRead = value(usage.getCacheReadInputTokens());
        return new AiAnalysisResult(content, metadata.getModel(), input, output,
                value(usage.getTotalTokens()), cacheRead, "provider");
    }

    private int value(Long value) {
        return value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    @Override
    public void analyzeStream(AiAnalysisRequest request, Consumer<String> tokenConsumer) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            chatClient.prompt()
                    .system(request.systemPrompt())
                    .user(request.userPrompt())
                    .advisors(advisor -> addConversationMemory(advisor, request))
                    .stream()
                    .content()
                    .doOnNext(tokenConsumer)
                    .blockLast();
            count("stream", "success");
        } catch (RuntimeException exception) {
            count("stream", "failure");
            throw exception;
        } finally {
            sample.stop(timer("stream"));
        }
    }

    private void addConversationMemory(ChatClient.AdvisorSpec advisor,
                                       AiAnalysisRequest request) {
        if (memoryAdvisor != null && request.conversationId() != null) {
            advisor.advisors(memoryAdvisor)
                    .param(ChatMemory.CONVERSATION_ID, request.conversationId());
        }
    }

    private Timer timer(String mode) {
        return Timer.builder("master408.ai.call.duration")
                .description("End-to-end model call duration at the application AI boundary")
                .tag("engine", "spring-ai")
                .tag("mode", mode)
                .register(meterRegistry);
    }

    private void count(String mode, String outcome) {
        Counter.builder("master408.ai.calls")
                .description("Model calls at the application AI boundary")
                .tag("engine", "spring-ai")
                .tag("mode", mode)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
