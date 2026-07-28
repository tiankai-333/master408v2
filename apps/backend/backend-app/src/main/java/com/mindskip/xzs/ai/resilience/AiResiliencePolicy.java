package com.mindskip.xzs.ai.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small, dependency-free guard around the configured primary model.
 * It bounds concurrency, admission rate, retries transient failures, and opens
 * a circuit after consecutive provider failures.
 */
@Component
public class AiResiliencePolicy {

    private final Semaphore concurrentCalls;
    private final int requestsPerSecond;
    private final int maxAttempts;
    private final int failureThreshold;
    private final long openNanos;
    private final long baseDelayMs;
    private final MeterRegistry meters;
    private final AtomicLong windowStarted = new AtomicLong(System.nanoTime());
    private final AtomicInteger windowCount = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenedAt = new AtomicLong();

    public AiResiliencePolicy(
            @Value("${ai.resilience.max-concurrent:16}") int maxConcurrent,
            @Value("${ai.resilience.requests-per-second:8}") int requestsPerSecond,
            @Value("${ai.resilience.max-attempts:3}") int maxAttempts,
            @Value("${ai.resilience.base-delay:250ms}") Duration baseDelay,
            @Value("${ai.resilience.circuit-failure-threshold:5}") int failureThreshold,
            @Value("${ai.resilience.circuit-open-duration:30s}") Duration openDuration,
            MeterRegistry meters) {
        this.concurrentCalls = new Semaphore(Math.max(1, maxConcurrent), true);
        this.requestsPerSecond = Math.max(1, requestsPerSecond);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(1L, baseDelay.toMillis());
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openNanos = Math.max(1L, openDuration.toNanos());
        this.meters = meters;
    }

    public <T> T execute(CheckedSupplier<T> call) throws Exception {
        if (!concurrentCalls.tryAcquire()) {
            count("bulkhead_rejected");
            throw new AiCapacityException("AI concurrency limit reached");
        }
        try {
            admit();
            ensureCircuitAllowsRequest();
            Exception last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    T result = call.get();
                    consecutiveFailures.set(0);
                    circuitOpenedAt.set(0L);
                    if (attempt > 1) {
                        count("retry_recovered");
                    }
                    return result;
                } catch (Exception error) {
                    last = error;
                    AiFailureType failureType = AiFailureClassifier.classify(error);
                    count("classified_" + failureType.metricTag());
                    if (!failureType.retryable() || attempt == maxAttempts) {
                        recordFailure();
                        throw error;
                    }
                    count(failureType == AiFailureType.RATE_LIMIT
                            ? "429_scheduled" : "retry_scheduled");
                    Thread.sleep(backoffMs(attempt, error));
                }
            }
            throw last == null ? new IllegalStateException("AI call did not run") : last;
        } finally {
            concurrentCalls.release();
        }
    }

    /**
     * Streaming calls are never replayed after a token is emitted; retrying a
     * partial stream would duplicate content for the user.
     */
    public <T> T executeStreaming(CheckedSupplier<T> call) throws Exception {
        if (!concurrentCalls.tryAcquire()) {
            count("bulkhead_rejected");
            throw new AiCapacityException("AI concurrency limit reached");
        }
        try {
            admit();
            ensureCircuitAllowsRequest();
            try {
                T result = call.get();
                consecutiveFailures.set(0);
                circuitOpenedAt.set(0L);
                return result;
            } catch (Exception error) {
                count("classified_" + AiFailureClassifier.classify(error).metricTag());
                recordFailure();
                throw error;
            }
        } finally {
            concurrentCalls.release();
        }
    }

    private synchronized void admit() throws InterruptedException {
        long now = System.nanoTime();
        long elapsed = now - windowStarted.get();
        if (elapsed >= 1_000_000_000L) {
            windowStarted.set(now);
            windowCount.set(0);
        }
        if (windowCount.incrementAndGet() > requestsPerSecond) {
            long waitNanos = Math.max(0L, 1_000_000_000L - elapsed);
            count("rate_scheduled");
            Thread.sleep(Math.min(1000L, waitNanos / 1_000_000L));
            windowStarted.set(System.nanoTime());
            windowCount.set(1);
        }
    }

    private void ensureCircuitAllowsRequest() {
        long openedAt = circuitOpenedAt.get();
        if (openedAt == 0L) {
            return;
        }
        if (System.nanoTime() - openedAt < openNanos) {
            count("circuit_rejected");
            throw new AiCircuitOpenException("Primary AI provider circuit is open");
        }
        circuitOpenedAt.compareAndSet(openedAt, 0L);
        consecutiveFailures.set(0);
        count("half_open_probe");
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            circuitOpenedAt.compareAndSet(0L, System.nanoTime());
            count("circuit_opened");
        }
    }

    private long backoffMs(int attempt, Exception error) {
        long retryAfter = retryAfterMs(error);
        long exponential = baseDelayMs * (1L << Math.min(6, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseDelayMs));
        return Math.min(10_000L, Math.max(retryAfter, exponential + jitter));
    }

    private long retryAfterMs(Exception error) {
        String message = fullMessage(error);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)retry[- ]after[^0-9]*(\\d+)")
                .matcher(message);
        return matcher.find() ? Math.min(10_000L, Long.parseLong(matcher.group(1)) * 1000L) : 0L;
    }

    private String fullMessage(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                value.append(' ').append(current.getMessage());
            }
        }
        return value.toString();
    }

    private void count(String event) {
        meters.counter("master408.ai.resilience.events", "event", event).increment();
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static class AiCapacityException extends RuntimeException {
        public AiCapacityException(String message) { super(message); }
    }

    public static class AiCircuitOpenException extends RuntimeException {
        public AiCircuitOpenException(String message) { super(message); }
    }
}
