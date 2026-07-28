package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisClient;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.client.AiAnalysisResult;
import com.mindskip.xzs.ai.resilience.AiResiliencePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Incremental migration gateway: legacy remains the safe default, while Spring AI can be
 * enabled for the server-configured model without changing the controller contract.
 */
@Service
public class AiAnalysisGateway {

    private final AnalysisService legacyAnalysisService;
    private final ObjectProvider<AiAnalysisClient> analysisClientProvider;
    private final PlatoConversationPromptPolicy platoPromptPolicy;
    private final AiUsageObservationService observationService;
    private final AiResiliencePolicy resiliencePolicy;

    public AiAnalysisGateway(AnalysisService legacyAnalysisService,
                             ObjectProvider<AiAnalysisClient> analysisClientProvider,
                             PlatoConversationPromptPolicy platoPromptPolicy,
                             AiUsageObservationService observationService,
                             AiResiliencePolicy resiliencePolicy) {
        this.legacyAnalysisService = legacyAnalysisService;
        this.analysisClientProvider = analysisClientProvider;
        this.platoPromptPolicy = platoPromptPolicy;
        this.observationService = observationService;
        this.resiliencePolicy = resiliencePolicy;
    }

    public String analyze(String style, String question, String knowledgePoints,
                          String referenceDocs, String taskType) throws Exception {
        return analyze(style, question, knowledgePoints, referenceDocs, taskType, null);
    }

    public String analyze(String style, String question, String knowledgePoints,
                          String referenceDocs, String taskType,
                          String conversationId) throws Exception {
        return analyzeDetailed(style, question, knowledgePoints, referenceDocs,
                taskType, conversationId).content();
    }

    public AiGatewayResult analyzeDetailed(String style, String question, String knowledgePoints,
                                           String referenceDocs, String taskType,
                                           String conversationId) throws Exception {
        AiAnalysisClient springAiClient = analysisClientProvider.getIfAvailable();
        if (springAiClient == null) {
            return new AiGatewayResult(legacyAnalysisService.analyzeWithAI(
                    style, question, knowledgePoints, referenceDocs, taskType),
                    null, null, "legacy");
        }

        AiAnalysisRequest base = legacyAnalysisService.buildAnalysisRequest(
                style, question, knowledgePoints, referenceDocs, taskType, conversationId);
        String userPrompt = platoPromptPolicy.prepare(
                style, question, conversationId, base.userPrompt());
        AiAnalysisRequest request = new AiAnalysisRequest(
                base.systemPrompt(), userPrompt, conversationId, base.promptRef());
        long startedAt = System.nanoTime();
        try {
            AiAnalysisResult result = resiliencePolicy.execute(
                    () -> springAiClient.analyzeResult(request));
            AiUsageObservationService.Observation observation = observationService.observe(
                    "sync", style, question, knowledgePoints, referenceDocs,
                    request, result, elapsedMs(startedAt), null, true, null);
            return new AiGatewayResult(result.content(), observation.usageLogId(),
                    observation.requestId());
        } catch (Exception error) {
            AiAnalysisResult estimated = AiAnalysisResult.estimated(request, "");
            observationService.observe("sync", style, question, knowledgePoints, referenceDocs,
                    request, estimated, elapsedMs(startedAt), null, false, error);
            try {
                String fallback = legacyAnalysisService.analyzeWithFallbackProviders(
                        style, question, knowledgePoints, referenceDocs, taskType);
                return new AiGatewayResult(fallback, null, null, "legacy-fallback");
            } catch (Exception fallbackError) {
                fallbackError.addSuppressed(error);
                throw fallbackError;
            }
        }
    }

    public void analyzeStream(String style, String question, String knowledgePoints,
                              String referenceDocs, String taskType,
                              Consumer<String> tokenConsumer) throws Exception {
        analyzeStream(style, question, knowledgePoints, referenceDocs, taskType,
                null, tokenConsumer);
    }

    public void analyzeStream(String style, String question, String knowledgePoints,
                              String referenceDocs, String taskType,
                              String conversationId,
                              Consumer<String> tokenConsumer) throws Exception {
        analyzeStreamDetailed(style, question, knowledgePoints, referenceDocs, taskType,
                conversationId, tokenConsumer);
    }

    public AiGatewayResult analyzeStreamDetailed(
            String style, String question, String knowledgePoints,
            String referenceDocs, String taskType, String conversationId,
            Consumer<String> tokenConsumer) throws Exception {
        AiAnalysisClient springAiClient = analysisClientProvider.getIfAvailable();
        if (springAiClient == null) {
            legacyAnalysisService.analyzeWithAIStream(
                    style, question, knowledgePoints, referenceDocs, taskType,
                    tokenConsumer::accept);
            return new AiGatewayResult("", null, null, "legacy");
        }

        AiAnalysisRequest base = legacyAnalysisService.buildAnalysisRequest(
                style, question, knowledgePoints, referenceDocs, taskType, conversationId);
        String userPrompt = platoPromptPolicy.prepare(
                style, question, conversationId, base.userPrompt());
        AiAnalysisRequest request = new AiAnalysisRequest(
                base.systemPrompt(), userPrompt, conversationId, base.promptRef());
        StringBuilder response = new StringBuilder();
        long startedAt = System.nanoTime();
        long[] firstTokenAt = {0L};
        try {
            resiliencePolicy.executeStreaming(() -> {
                springAiClient.analyzeStream(request, token -> {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.nanoTime();
                    }
                    response.append(token);
                    tokenConsumer.accept(token);
                });
                return null;
            });
            AiAnalysisResult estimated = AiAnalysisResult.estimated(request, response.toString());
            AiUsageObservationService.Observation observation = observationService.observe(
                    "stream", style, question, knowledgePoints, referenceDocs,
                    request, estimated, elapsedMs(startedAt),
                    firstTokenAt[0] == 0L ? null : elapsedMs(startedAt, firstTokenAt[0]),
                    true, null);
            return new AiGatewayResult(response.toString(), observation.usageLogId(),
                    observation.requestId());
        } catch (Exception error) {
            AiAnalysisResult estimated = AiAnalysisResult.estimated(request, response.toString());
            observationService.observe("stream", style, question, knowledgePoints, referenceDocs,
                    request, estimated, elapsedMs(startedAt),
                    firstTokenAt[0] == 0L ? null : elapsedMs(startedAt, firstTokenAt[0]),
                    false, error);
            if (response.isEmpty()) {
                try {
                    String fallback = legacyAnalysisService.analyzeStreamWithFallbackProviders(
                            style, question, knowledgePoints, referenceDocs, taskType,
                            tokenConsumer::accept);
                    return new AiGatewayResult(fallback, null, null, "legacy-fallback");
                } catch (Exception fallbackError) {
                    fallbackError.addSuppressed(error);
                    throw fallbackError;
                }
            }
            throw error;
        }
    }

    public String engineName() {
        return analysisClientProvider.getIfAvailable() == null ? "legacy" : "spring-ai";
    }

    private long elapsedMs(long startedAt) {
        return elapsedMs(startedAt, System.nanoTime());
    }

    private long elapsedMs(long startedAt, long endedAt) {
        return Math.max(0L, (endedAt - startedAt) / 1_000_000L);
    }
}
