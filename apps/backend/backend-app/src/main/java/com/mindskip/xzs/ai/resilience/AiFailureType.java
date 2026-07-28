package com.mindskip.xzs.ai.resilience;

/**
 * Provider-call failures classified by handling semantics rather than by
 * provider-specific exception names.
 */
public enum AiFailureType {
    RATE_LIMIT(true),
    TIMEOUT(true),
    SERVER_ERROR(true),
    CONNECTION_ERROR(true),
    AUTHENTICATION(false),
    BAD_REQUEST(false),
    CAPACITY(false),
    CIRCUIT_OPEN(false),
    UNKNOWN(false);

    private final boolean retryable;

    AiFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public String metricTag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
