package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisClient;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
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

    public AiAnalysisGateway(AnalysisService legacyAnalysisService,
                             ObjectProvider<AiAnalysisClient> analysisClientProvider,
                             PlatoConversationPromptPolicy platoPromptPolicy) {
        this.legacyAnalysisService = legacyAnalysisService;
        this.analysisClientProvider = analysisClientProvider;
        this.platoPromptPolicy = platoPromptPolicy;
    }

    public String analyze(String style, String question, String knowledgePoints,
                          String referenceDocs, String taskType) throws Exception {
        return analyze(style, question, knowledgePoints, referenceDocs, taskType, null);
    }

    public String analyze(String style, String question, String knowledgePoints,
                          String referenceDocs, String taskType,
                          String conversationId) throws Exception {
        AiAnalysisClient springAiClient = analysisClientProvider.getIfAvailable();
        if (springAiClient == null) {
            return legacyAnalysisService.analyzeWithAI(
                    style, question, knowledgePoints, referenceDocs, taskType);
        }

        PromptTemplate template = legacyAnalysisService.getTemplate(style);
        String userPrompt = legacyAnalysisService.generatePrompt(
                style, question, knowledgePoints, referenceDocs, taskType);
        userPrompt = platoPromptPolicy.prepare(
                style, question, conversationId, userPrompt);
        return springAiClient.analyze(new AiAnalysisRequest(
                template.getSystemPrompt(), userPrompt, conversationId));
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
        AiAnalysisClient springAiClient = analysisClientProvider.getIfAvailable();
        if (springAiClient == null) {
            legacyAnalysisService.analyzeWithAIStream(
                    style, question, knowledgePoints, referenceDocs, taskType,
                    tokenConsumer::accept);
            return;
        }

        PromptTemplate template = legacyAnalysisService.getTemplate(style);
        String userPrompt = legacyAnalysisService.generatePrompt(
                style, question, knowledgePoints, referenceDocs, taskType);
        userPrompt = platoPromptPolicy.prepare(
                style, question, conversationId, userPrompt);
        springAiClient.analyzeStream(
                new AiAnalysisRequest(template.getSystemPrompt(), userPrompt, conversationId),
                tokenConsumer);
    }

    public String engineName() {
        return analysisClientProvider.getIfAvailable() == null ? "legacy" : "spring-ai";
    }
}
