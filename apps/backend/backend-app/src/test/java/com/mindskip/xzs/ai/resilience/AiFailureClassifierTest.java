package com.mindskip.xzs.ai.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class AiFailureClassifierTest {

    @Test
    void classifiesStructuredHttpFailuresBeforeMessages() {
        assertThat(AiFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)))
                .isEqualTo(AiFailureType.RATE_LIMIT);
        assertThat(AiFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED)))
                .isEqualTo(AiFailureType.AUTHENTICATION);
        assertThat(AiFailureClassifier.classify(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
                .isEqualTo(AiFailureType.BAD_REQUEST);
        assertThat(AiFailureClassifier.classify(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
                .isEqualTo(AiFailureType.SERVER_ERROR);
    }

    @Test
    void classifiesTypedNetworkFailuresThroughCauseChain() {
        assertThat(AiFailureClassifier.classify(
                new IllegalStateException("wrapped", new SocketTimeoutException())))
                .isEqualTo(AiFailureType.TIMEOUT);
        assertThat(AiFailureClassifier.classify(
                new IllegalStateException("wrapped", new ConnectException())))
                .isEqualTo(AiFailureType.CONNECTION_ERROR);
    }

    @Test
    void classifiesLocalProtectionAndUnknownFailures() {
        assertThat(AiFailureClassifier.classify(
                new AiResiliencePolicy.AiCapacityException("full")))
                .isEqualTo(AiFailureType.CAPACITY);
        assertThat(AiFailureClassifier.classify(
                new AiResiliencePolicy.AiCircuitOpenException("open")))
                .isEqualTo(AiFailureType.CIRCUIT_OPEN);
        assertThat(AiFailureClassifier.classify(
                new IllegalArgumentException("unrecognised provider failure")))
                .isEqualTo(AiFailureType.UNKNOWN);
    }

    @Test
    void onlyTransientProviderFailuresAreRetryable() {
        assertThat(AiFailureType.RATE_LIMIT.retryable()).isTrue();
        assertThat(AiFailureType.TIMEOUT.retryable()).isTrue();
        assertThat(AiFailureType.SERVER_ERROR.retryable()).isTrue();
        assertThat(AiFailureType.CONNECTION_ERROR.retryable()).isTrue();
        assertThat(AiFailureType.AUTHENTICATION.retryable()).isFalse();
        assertThat(AiFailureType.BAD_REQUEST.retryable()).isFalse();
        assertThat(AiFailureType.CAPACITY.retryable()).isFalse();
        assertThat(AiFailureType.CIRCUIT_OPEN.retryable()).isFalse();
        assertThat(AiFailureType.UNKNOWN.retryable()).isFalse();
    }
}
