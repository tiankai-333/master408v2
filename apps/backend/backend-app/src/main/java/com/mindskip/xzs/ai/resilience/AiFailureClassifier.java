package com.mindskip.xzs.ai.resilience;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central failure classifier used by retry, metrics and operator diagnostics.
 * Structured exception/status information wins; message matching is only a
 * compatibility fallback for OpenAI-compatible clients that discard status.
 */
public final class AiFailureClassifier {

    private static final Pattern HTTP_STATUS =
            Pattern.compile("(?<!\\d)([45]\\d\\d)(?!\\d)");

    private AiFailureClassifier() {
    }

    public static AiFailureType classify(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AiResiliencePolicy.AiCapacityException) {
                return AiFailureType.CAPACITY;
            }
            if (current instanceof AiResiliencePolicy.AiCircuitOpenException) {
                return AiFailureType.CIRCUIT_OPEN;
            }
            if (current instanceof RestClientResponseException responseException) {
                return fromStatus(responseException.getStatusCode());
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return AiFailureType.TIMEOUT;
            }
            if (current instanceof ConnectException || current instanceof SocketException) {
                return AiFailureType.CONNECTION_ERROR;
            }
        }

        String message = fullMessage(error).toLowerCase(Locale.ROOT);
        if (message.contains("too many requests") || message.contains("rate limit")) {
            return AiFailureType.RATE_LIMIT;
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return AiFailureType.TIMEOUT;
        }
        if (message.contains("connection reset")
                || message.contains("connection refused")
                || message.contains("temporarily unavailable")) {
            return AiFailureType.CONNECTION_ERROR;
        }
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            return fromStatus(HttpStatusCode.valueOf(Integer.parseInt(status.group(1))));
        }
        return AiFailureType.UNKNOWN;
    }

    private static AiFailureType fromStatus(HttpStatusCode status) {
        int value = status.value();
        if (value == 429) {
            return AiFailureType.RATE_LIMIT;
        }
        if (value == 401 || value == 403) {
            return AiFailureType.AUTHENTICATION;
        }
        if (status.is5xxServerError()) {
            return AiFailureType.SERVER_ERROR;
        }
        if (status.is4xxClientError()) {
            return AiFailureType.BAD_REQUEST;
        }
        return AiFailureType.UNKNOWN;
    }

    private static String fullMessage(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                value.append(' ').append(current.getMessage());
            }
        }
        return value.toString();
    }
}
