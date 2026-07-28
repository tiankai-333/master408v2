package com.mindskip.xzs.ai;

import com.mindskip.xzs.ai.client.AiAnalysisRequest;
import com.mindskip.xzs.ai.client.AiAnalysisResult;
import com.mindskip.xzs.ai.prompt.PromptRef;
import com.mindskip.xzs.domain.ai.AiUsageLog;
import com.mindskip.xzs.repository.AiUsageLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Service
public class AiUsageObservationService {

    private static final Logger logger = LoggerFactory.getLogger(AiUsageObservationService.class);
    private final AiUsageLogMapper usageLogMapper;

    public AiUsageObservationService(AiUsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    public Observation observe(String mode, String style, String question,
                               String knowledgePoints, String referenceDocs,
                               AiAnalysisRequest request, AiAnalysisResult result,
                               long durationMs, Long firstTokenLatencyMs,
                               boolean success, Throwable error) {
        String requestId = UUID.randomUUID().toString();
        try {
            AiUsageLog log = new AiUsageLog();
            log.setRequestId(requestId);
            log.setEngine("spring-ai");
            log.setMode(mode);
            log.setUsageSource(result.usageSource());
            log.setConversationId(request.conversationId());
            log.setFirstTokenLatencyMs(firstTokenLatencyMs == null ? null : safeInt(firstTokenLatencyMs));
            log.setUserId(AnalysisService.getCurrentUserId());
            log.setKeySource(AnalysisService.getCurrentKeySource());
            log.setStyle(style);
            log.setAiType("spring-ai");
            log.setModel(result.model());
            log.setQuestion(limit(question, 6000));
            log.setKnowledgePoints(limit(knowledgePoints, 6000));
            log.setKnowledgeBaseIds(referenceDocs == null || referenceDocs.isBlank() ? null : "rag");
            log.setPrompt(limit(request.systemPrompt() + "\n\n" + request.userPrompt(), 6000));
            log.setResponse(limit(result.content(), 6000));
            log.setResponseLength(result.content().length());
            log.setTokensUsed(result.totalTokens());
            log.setInputTokens(result.inputTokens());
            log.setOutputTokens(result.outputTokens());
            log.setCacheHitTokens(result.cacheHitTokens());
            log.setCost(AiPricing.calculateCost(result.model(), result.inputTokens(),
                    result.outputTokens(), result.cacheHitTokens()));
            log.setDurationMs(safeInt(durationMs));
            log.setSuccess(success);
            log.setErrorMessage(error == null ? null : limit(error.getMessage(), 1000));
            PromptRef ref = request.promptRef();
            if (ref != null) {
                log.setPromptKey(ref.promptKey());
                log.setPromptVersionId(ref.versionId());
                log.setPromptReleaseId(ref.releaseId());
            }
            log.setCreateTime(new Date());
            usageLogMapper.insert(log);
            return new Observation(log.getId(), requestId);
        } catch (RuntimeException logError) {
            logger.warn("Failed to persist Spring AI observation requestId={}: {}",
                    requestId, logError.getMessage());
            return new Observation(null, requestId);
        }
    }

    private int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record Observation(Integer usageLogId, String requestId) {
    }
}
