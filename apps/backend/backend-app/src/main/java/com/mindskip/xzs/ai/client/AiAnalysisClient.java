package com.mindskip.xzs.ai.client;

import java.util.function.Consumer;

/**
 * Stable application boundary for a single model interaction.
 *
 * <p>The legacy HTTP implementation and Spring AI implementation can coexist behind this
 * contract, allowing controlled rollout without changing the public controller API.</p>
 */
public interface AiAnalysisClient {

    String analyze(AiAnalysisRequest request);

    void analyzeStream(AiAnalysisRequest request, Consumer<String> tokenConsumer);
}
