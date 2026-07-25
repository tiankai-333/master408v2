package com.mindskip.xzs.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private static final ThreadLocal<Integer> currentUser = new ThreadLocal<>();

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final boolean vectorStoreEnabled;
    private final double similarityThreshold;

    public RagService(
            ObjectProvider<VectorStore> vectorStoreProvider,
            @Value("${ai.rag.vector.enabled:false}") boolean vectorStoreEnabled,
            @Value("${ai.rag.vector.similarity-threshold:0.5}") double similarityThreshold) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.vectorStoreEnabled = vectorStoreEnabled;
        this.similarityThreshold = similarityThreshold;
    }

    public static void setCurrentUserId(Integer userId) {
        currentUser.set(userId);
    }

    public static void clearCurrentUserId() {
        currentUser.remove();
    }

    public List<RagDocument> retrieve(String query, int topK) {
        return retrieve(query, topK, similarityThreshold);
    }

    public List<RagDocument> retrieve(String query, int topK, double threshold) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        if (!vectorStoreEnabled) {
            logger.warn("RAG retrieval skipped because Spring AI VectorStore is disabled");
            return Collections.emptyList();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("RAG is enabled but Spring AI VectorStore is unavailable");
        }
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, topK))
                .similarityThreshold(Math.min(Math.max(threshold, 0), 1))
                .build());
        List<RagDocument> results = new ArrayList<>(hits.size());
        for (Document hit : hits) {
            Map<String, Object> metadata = hit.getMetadata();
            String title = stringMetadata(metadata, "citation_label", "未知");
            Integer id = integerMetadata(metadata.get("chunk_id"));
            results.add(new RagDocument(title, hit.getText(),
                    hit.getScore() == null ? 0 : hit.getScore(), id));
        }
        logger.info("Spring AI VectorStore RAG retrieved {} documents (topK={}, userId={})",
                results.size(), topK, currentUser.get());
        return results;
    }

    public String formatReferenceDocs(List<RagDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder value = new StringBuilder("\n\n## 参考资料（来自题库，供辅助参考）\n\n");
        for (int i = 0; i < docs.size(); i++) {
            RagDocument document = docs.get(i);
            value.append("【参考").append(i + 1).append("】")
                    .append(document.getTitle()).append("\n");
            if (document.getContent() != null && !document.getContent().isEmpty()) {
                value.append(document.getContent()).append("\n\n");
            }
        }
        return value.toString();
    }

    private String stringMetadata(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private Integer integerMetadata(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static class RagDocument {
        private String title;
        private String content;
        private double similarity;
        private Integer id;

        public RagDocument() {
        }

        public RagDocument(String title, String content, double similarity, Integer id) {
            this.title = title;
            this.content = content;
            this.similarity = similarity;
            this.id = id;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
    }
}
