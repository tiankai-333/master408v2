package com.mindskip.xzs.ai.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A diagnostic micro-benchmark for local comparison only.
 *
 * <p>It deliberately excludes network and model inference. The numbers describe
 * this JVM run and must not be presented as production latency or a JMH result.</p>
 */
@Tag("benchmark")
class SpringAiIsolatedOverheadTest {

    private static final int WARMUP_ITERATIONS = 200;
    private static final int MEASURED_ITERATIONS = 1_000;

    @Test
    void measuresChatClientDispatchOverheadWithoutARealProvider() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("mocked analysis"))));
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(response);

        SpringAiAnalysisClient springClient = new SpringAiAnalysisClient(
                ChatClient.builder(model), new SimpleMeterRegistry());
        AiAnalysisRequest request = new AiAnalysisRequest("system", "user");
        Prompt directPrompt = new Prompt("user");

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            model.call(directPrompt);
            springClient.analyze(request);
        }

        long[] directNanos = measure(() -> model.call(directPrompt));
        long[] springNanos = measure(() -> springClient.analyze(request));

        String result = springClient.analyze(request);
        assertThat(result).isEqualTo("mocked analysis");

        System.out.printf(
                "ISOLATED_AI_OVERHEAD direct_mock[p50=%dµs,p95=%dµs] "
                        + "spring_chat_client[p50=%dµs,p95=%dµs]%n",
                percentileMicros(directNanos, 50),
                percentileMicros(directNanos, 95),
                percentileMicros(springNanos, 50),
                percentileMicros(springNanos, 95));
    }

    private long[] measure(Runnable operation) {
        long[] durations = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            operation.run();
            durations[i] = System.nanoTime() - start;
        }
        Arrays.sort(durations);
        return durations;
    }

    private long percentileMicros(long[] sortedNanos, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, index)] / 1_000;
    }
}
