package com.mindskip.xzs.ai.evaluation;

import com.mindskip.xzs.ai.AiAnalysisGateway;
import com.mindskip.xzs.ai.AnalysisService;
import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.prompt.PromptRef;
import com.mindskip.xzs.domain.ai.AiEvaluationCaseResultRecord;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.repository.AiEvaluationMapper;
import com.mindskip.xzs.service.AiProviderConfigService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Service
public class AiEvaluationWorker {

    private static final int MAX_STORED_RESPONSE_CHARS = 12_000;
    private static final int MAX_STORED_ERROR_CHARS = 1_000;

    private final AiEvaluationMapper mapper;
    private final AiAnalysisGateway gateway;
    private final AnalysisService analysisService;
    private final AiProviderConfigService providerConfigService;
    private final ObjectMapper objectMapper;
    private final DeterministicAiResponseEvaluator evaluator =
            new DeterministicAiResponseEvaluator();

    public AiEvaluationWorker(AiEvaluationMapper mapper,
                              AiAnalysisGateway gateway,
                              AnalysisService analysisService,
                              AiProviderConfigService providerConfigService,
                              ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.analysisService = analysisService;
        this.providerConfigService = providerConfigService;
        this.objectMapper = objectMapper;
    }

    @Async("aiEvaluationExecutor")
    public void execute(Long runId, List<AiEvaluationCase> cases) {
        try {
            if (mapper.markRunStarted(runId) != 1) {
                return;
            }
            String model = resolveModel();
            List<AiEvaluationResult> results = new ArrayList<>();
            for (AiEvaluationCase evaluationCase : cases) {
                results.add(executeCase(runId, evaluationCase, model));
            }
            complete(runId, results);
        } catch (Exception exception) {
            mapper.failRun(runId, safeError(exception));
        }
    }

    private AiEvaluationResult executeCase(Long runId, AiEvaluationCase evaluationCase,
                                           String model) {
        AiAnalysisRequest prepared = analysisService.buildAnalysisRequest(
                evaluationCase.style(),
                evaluationCase.question(),
                evaluationCase.knowledgePoints(),
                evaluationCase.referenceDocs(),
                evaluationCase.taskType(),
                null);
        long startNanos = System.nanoTime();
        try {
            // Evaluation calls deliberately use no conversation ID, so ChatMemory is disabled.
            String response = gateway.analyze(
                    evaluationCase.style(),
                    evaluationCase.question(),
                    evaluationCase.knowledgePoints(),
                    evaluationCase.referenceDocs(),
                    evaluationCase.taskType(),
                    null);
            long latencyMs = elapsedMs(startNanos);
            AiEvaluationResult result = evaluator.evaluate(
                    evaluationCase,
                    prepared.systemPrompt(),
                    prepared.userPrompt(),
                    response,
                    model,
                    latencyMs);
            mapper.insertCaseResult(toRecord(runId, evaluationCase, prepared.promptRef(),
                    response, result, null));
            return result;
        } catch (Exception exception) {
            long latencyMs = elapsedMs(startNanos);
            AiEvaluationResult result = evaluator.evaluate(
                    evaluationCase,
                    prepared.systemPrompt(),
                    prepared.userPrompt(),
                    "",
                    model,
                    latencyMs);
            mapper.insertCaseResult(toRecord(runId, evaluationCase, prepared.promptRef(),
                    null, result, safeError(exception)));
            return result;
        }
    }

    private AiEvaluationCaseResultRecord toRecord(Long runId,
                                                   AiEvaluationCase evaluationCase,
                                                   PromptRef promptRef,
                                                   String response,
                                                   AiEvaluationResult result,
                                                   String error) {
        AiEvaluationCaseResultRecord record = new AiEvaluationCaseResultRecord();
        record.setRunId(runId);
        record.setCaseId(evaluationCase.id());
        record.setCategory(evaluationCase.category());
        if (promptRef != null) {
            record.setPromptKey(promptRef.promptKey());
            record.setPromptVersionId(promptRef.versionId());
            record.setPromptReleaseId(promptRef.releaseId());
        }
        record.setPassed(result.passed() && error == null);
        record.setQualityScore(BigDecimal.valueOf(result.qualityScore()));
        record.setConceptCoverage(BigDecimal.valueOf(result.conceptCoverage()));
        record.setMissingConceptsJson(json(result.missingConcepts()));
        record.setForbiddenHitsJson(json(result.forbiddenPhraseHits()));
        record.setResponseText(limit(response, MAX_STORED_RESPONSE_CHARS));
        record.setResponseChars(result.responseChars());
        record.setEstimatedInputTokens(result.estimatedInputTokens());
        record.setEstimatedOutputTokens(result.estimatedOutputTokens());
        record.setEstimatedCost(BigDecimal.valueOf(result.estimatedCost()));
        record.setEndToEndLatencyMs(toInteger(result.endToEndLatencyMs()));
        record.setFailureReason(limit(result.failureReason(), 500));
        record.setErrorMessage(error);
        record.setCreateTime(new Date());
        return record;
    }

    private void complete(Long runId, List<AiEvaluationResult> results) {
        AiEvaluationRun run = new AiEvaluationRun();
        run.setId(runId);
        run.setPassedCount((int) results.stream().filter(AiEvaluationResult::passed).count());
        run.setAverageQualityScore(averageQuality(results));
        run.setEstimatedInputTokens(results.stream()
                .mapToInt(AiEvaluationResult::estimatedInputTokens).sum());
        run.setEstimatedOutputTokens(results.stream()
                .mapToInt(AiEvaluationResult::estimatedOutputTokens).sum());
        run.setEstimatedCost(results.stream()
                .map(value -> BigDecimal.valueOf(value.estimatedCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<Long> latencies = results.stream()
                .map(AiEvaluationResult::endToEndLatencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        run.setAverageLatencyMs(latencies.isEmpty() ? 0 : toInteger(Math.round(
                latencies.stream().mapToLong(Long::longValue).average().orElse(0))));
        run.setP95LatencyMs(percentile95(latencies));
        mapper.completeRun(run);
    }

    private BigDecimal averageQuality(List<AiEvaluationResult> results) {
        if (results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double average = results.stream()
                .mapToDouble(AiEvaluationResult::qualityScore)
                .average()
                .orElse(0);
        return BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP);
    }

    private int percentile95(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * 0.95) - 1);
        return toInteger(sortedValues.get(index));
    }

    private String resolveModel() {
        try {
            AiProviderConfig provider = providerConfigService.getFirstEnabled();
            return provider == null || provider.getChatModel() == null
                    ? "unknown"
                    : provider.getChatModel();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private int toInteger(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        value = value.replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        return limit(value, MAX_STORED_ERROR_CHARS);
    }

    private String limit(String value, int maximumChars) {
        if (value == null || value.length() <= maximumChars) {
            return value;
        }
        return value.substring(0, maximumChars);
    }
}
