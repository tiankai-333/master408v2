package com.mindskip.xzs.ai.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String content = chatClient.prompt()
                    .system(request.systemPrompt())
                    .user(request.userPrompt())
                    .advisors(advisor -> addConversationMemory(advisor, request))
                    .call()
                    .content();
            count("sync", "success");
            return content;
        } catch (RuntimeException exception) {
            count("sync", "failure");
            throw exception;
        } finally {
            sample.stop(timer("sync"));
        }
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
