package com.mindskip.xzs.ai;

/**
 * Application response metadata used by the UI to submit feedback against the
 * exact observed model call.
 */
public record AiGatewayResult(String content, Integer usageLogId, String requestId, String engine) {

    public AiGatewayResult(String content, Integer usageLogId, String requestId) {
        this(content, usageLogId, requestId, "spring-ai");
    }
}
