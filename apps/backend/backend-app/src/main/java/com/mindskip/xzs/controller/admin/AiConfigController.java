package com.mindskip.xzs.controller.admin;

import com.mindskip.xzs.base.BaseApiController;
import com.mindskip.xzs.base.RestResponse;
import com.mindskip.xzs.ai.RagService;
import com.mindskip.xzs.domain.ai.AiProviderConfig;
import com.mindskip.xzs.domain.rag.RagChunkRecord;
import com.mindskip.xzs.service.AiProviderConfigService;
import com.mindskip.xzs.service.RagDocumentService;
import com.mindskip.xzs.service.RagVectorStoreService;
import com.mindskip.xzs.service.impl.SpringAiRagVectorStoreServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController("AdminAiConfigController")
@RequestMapping("/api/admin/ai-config")
public class AiConfigController extends BaseApiController {

    private static final Logger logger = LoggerFactory.getLogger(AiConfigController.class);

    @Autowired
    private AiProviderConfigService aiProviderConfigService;

    @Autowired
    private RagDocumentService ragDocumentService;

    @Autowired
    private RagService ragService;

    @Autowired
    private RagVectorStoreService ragVectorStoreService;

    @PostMapping("/providers")
    public RestResponse<List<AiProviderConfig>> providers() {
        return RestResponse.ok(aiProviderConfigService.listSafe());
    }

    @PostMapping("/provider/save")
    public RestResponse<AiProviderConfig> saveProvider(@RequestBody Map<String, Object> request) {
        AiProviderConfig config = new AiProviderConfig();
        Object id = request.get("id");
        config.setId(id == null || "".equals(String.valueOf(id)) ? null : Integer.valueOf(String.valueOf(id)));
        config.setProviderCode(stringValue(request.get("providerCode")));
        config.setProviderName(stringValue(request.get("providerName")));
        config.setApiBaseUrl(stringValue(request.get("apiBaseUrl")));
        config.setChatModel(stringValue(request.get("chatModel")));
        config.setEmbeddingModel(stringValue(request.get("embeddingModel")));
        config.setEnabled(booleanValue(request.get("enabled")));
        Object priority = request.get("priority");
        config.setPriority(priority == null || "".equals(String.valueOf(priority)) ? 100 : Integer.valueOf(String.valueOf(priority)));
        String plainApiKey = stringValue(request.get("apiKey"));
        return RestResponse.ok(aiProviderConfigService.save(config, plainApiKey));
    }

    @PostMapping("/provider/{id}/test")
    public RestResponse<Map<String, Object>> testProvider(@PathVariable Integer id) {
        return RestResponse.ok(aiProviderConfigService.test(id));
    }

    @PostMapping("/provider/delete/{id}")
    public RestResponse deleteProvider(@PathVariable Integer id) {
        aiProviderConfigService.deleteById(id);
        return RestResponse.ok();
    }

    @PostMapping("/usage")
    public RestResponse<Map<String, Object>> usage(@RequestBody(required = false) Map<String, Object> request) {
        Integer days = 30;
        if (request != null && request.get("days") != null) {
            days = Integer.valueOf(String.valueOf(request.get("days")));
        }
        return RestResponse.ok(aiProviderConfigService.usage(days));
    }

    @PostMapping("/rag/index")
    public RestResponse<Map<String, Object>> ragIndex(@RequestBody(required = false) Map<String, Object> request) {
        if (!ragVectorStoreService.isEnabled()) {
            return RestResponse.fail(1, "Spring AI Qdrant VectorStore 未启用，请检查配置");
        }

        String source = request != null ? stringValue(String.valueOf(request.getOrDefault("source", "all"))) : "all";
        int backfilled = 0;

        if ("questions".equals(source) || "all".equals(source)) {
            int count = ragDocumentService.backfillFromQuestions();
            backfilled += count;
            logger.info("Backfilled {} question records into rag_document/rag_chunk", count);
        }
        if ("knowledge".equals(source) || "all".equals(source)) {
            int count = ragDocumentService.backfillFromLegacyKnowledgeBase();
            backfilled += count;
            logger.info("Backfilled {} knowledge base records into rag_document/rag_chunk", count);
        }
        int normalized = ragDocumentService.normalizeForIndexing(4000, 300);

        boolean force = request != null && booleanValue(request.get("force"));
        int indexed = 0;
        int failed = 0;
        String collectionName = environmentValue("AI_RAG_COLLECTION", "xzs_408_chunks_spring_v1");
        String model = environmentValue("AI_EMBEDDING_MODEL", "embedding-2");
        int batchSize = Integer.parseInt(environmentValue("AI_RAG_INDEX_BATCH_SIZE", "20"));
        int maxChunks = request != null && request.get("maxChunks") != null
                ? Math.max(1, Integer.parseInt(String.valueOf(request.get("maxChunks"))))
                : 200;
        long afterId = request != null && request.get("afterId") != null
                ? Math.max(0, Long.parseLong(String.valueOf(request.get("afterId")))) : 0;
        List<RagChunkRecord> chunks;
        while (indexed + failed < maxChunks
                && !(chunks = ragDocumentService.listIndexableChunks(
                model, collectionName, force, afterId,
                Math.min(batchSize, maxChunks - indexed - failed))).isEmpty()) {
            afterId = chunks.get(chunks.size() - 1).getId();
            try {
                ragVectorStoreService.add(chunks);
                int dimension = ragVectorStoreService.dimensions();
                for (RagChunkRecord chunk : chunks) {
                    ragDocumentService.markIndexed(chunk.getId(), model, dimension, collectionName,
                            SpringAiRagVectorStoreServiceImpl.vectorId(chunk.getId()));
                    indexed++;
                }
            } catch (Exception e) {
                failed += chunks.size();
                for (RagChunkRecord chunk : chunks) {
                    ragDocumentService.markIndexFailed(chunk.getId(), model, 0, collectionName, String.valueOf(chunk.getId()), e.getMessage());
                }
                logger.warn("Failed to index RAG batch ending at chunk {}: {}", afterId, e.getMessage());
            }
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("backfilled", backfilled);
        result.put("normalized", normalized);
        result.put("indexed", indexed);
        result.put("failed", failed);
        result.put("force", force);
        result.put("collection", collectionName);
        result.put("embeddingModel", model);
        result.put("maxChunks", maxChunks);
        result.put("nextAfterId", afterId);
        return RestResponse.ok(result);
    }

    @PostMapping("/rag/search")
    public RestResponse<List<RagService.RagDocument>> ragSearch(@RequestBody Map<String, Object> request)
            throws Exception {
        String query = stringValue(request.get("query"));
        if (query == null || query.isBlank()) {
            return RestResponse.fail(2, "query 不能为空");
        }
        int topK = request.get("topK") == null ? 5 : Integer.parseInt(String.valueOf(request.get("topK")));
        double threshold = request.get("threshold") == null
                ? 0.5 : Double.parseDouble(String.valueOf(request.get("threshold")));
        return RestResponse.ok(ragService.retrieve(
                query, Math.min(Math.max(topK, 1), 20), threshold));
    }

    private String environmentValue(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Boolean booleanValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }
}
