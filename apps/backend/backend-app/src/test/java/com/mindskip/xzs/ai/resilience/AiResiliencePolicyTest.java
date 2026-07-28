package com.mindskip.xzs.ai.resilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResiliencePolicyTest {

    @Test
    void schedulesAndRecoversA429WithBoundedRetries() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AiResiliencePolicy policy = policy(3, 5, meters);
        AtomicInteger attempts = new AtomicInteger();

        String value = policy.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("HTTP 429 too many requests");
            }
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(meters.counter(
                "master408.ai.resilience.events", "event", "429_scheduled").count())
                .isEqualTo(2);
    }

    @Test
    void opensCircuitAfterConfiguredConsecutiveFailures() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AiResiliencePolicy policy = policy(1, 2, meters);

        assertThatThrownBy(() -> policy.execute(() -> {
            throw new IllegalStateException("HTTP 500");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.execute(() -> {
            throw new IllegalStateException("HTTP 500");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.execute(() -> "not called"))
                .isInstanceOf(AiResiliencePolicy.AiCircuitOpenException.class);
    }

    @Test
    void doesNotReplayPartialStreamingCalls() {
        AiResiliencePolicy policy = policy(3, 5, new SimpleMeterRegistry());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> policy.executeStreaming(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("HTTP 429");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }

    private AiResiliencePolicy policy(
            int attempts, int threshold, SimpleMeterRegistry meters) {
        return new AiResiliencePolicy(
                2, 100, attempts, Duration.ofMillis(1),
                threshold, Duration.ofSeconds(30), meters);
    }
}
