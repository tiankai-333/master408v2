package com.mindskip.xzs.ai;

import com.mindskip.xzs.domain.rag.RagLexicalHit;
import com.mindskip.xzs.domain.rag.RagRetrievalLogRecord;
import com.mindskip.xzs.repository.RagRetrievalMapper;
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
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private static final ThreadLocal<Integer> currentUser = new ThreadLocal<>();

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RagRetrievalMapper retrievalMapper;
    private final boolean vectorStoreEnabled;
    private final double similarityThreshold;
    private final int candidateMultiplier;

    public RagService(
            ObjectProvider<VectorStore> vectorStoreProvider,
            RagRetrievalMapper retrievalMapper,
            @Value("${ai.rag.vector.enabled:false}") boolean vectorStoreEnabled,
            @Value("${ai.rag.vector.similarity-threshold:0.5}") double similarityThreshold,
            @Value("${ai.rag.hybrid.candidate-multiplier:3}") int candidateMultiplier) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.retrievalMapper = retrievalMapper;
        this.vectorStoreEnabled = vectorStoreEnabled;
        this.similarityThreshold = similarityThreshold;
        this.candidateMultiplier = Math.max(2, candidateMultiplier);
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
        int safeTopK = Math.max(1, Math.min(20, topK));
        int candidateLimit = safeTopK * candidateMultiplier;
        long startedAt = System.nanoTime();
        List<RagDocument> vectorHits = vectorCandidates(query, candidateLimit, threshold);
        List<RagDocument> lexicalHits = lexicalCandidates(query, candidateLimit);
        List<RagDocument> results = fuseAndRerank(query, vectorHits, lexicalHits, safeTopK);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        persistTrace(query, safeTopK, vectorHits.size(), lexicalHits.size(), results, latencyMs);
        logger.info("Hybrid RAG retrieved {} documents (vector={}, lexical={}, topK={}, userId={})",
                results.size(), vectorHits.size(), lexicalHits.size(), safeTopK, currentUser.get());
        return results;
    }

    private List<RagDocument> vectorCandidates(String query, int limit, double threshold) {
        if (!vectorStoreEnabled) {
            return Collections.emptyList();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            logger.warn("Vector recall unavailable; continuing with lexical recall");
            return Collections.emptyList();
        }
        try {
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(Math.min(Math.max(threshold, 0), 1))
                    .build());
            List<RagDocument> values = new ArrayList<>(hits.size());
            for (Document hit : hits) {
                Map<String, Object> metadata = hit.getMetadata();
                RagDocument value = new RagDocument(
                        stringMetadata(metadata, "citation_label", "未知"),
                        hit.getText(), hit.getScore() == null ? 0 : hit.getScore(),
                        longMetadata(metadata.get("chunk_id")));
                value.setVectorScore(value.getSimilarity());
                value.setSourcePosition(stringMetadata(metadata, "source_position", null));
                values.add(value);
            }
            return values;
        } catch (RuntimeException error) {
            logger.warn("Vector recall failed; continuing with lexical recall: {}", error.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RagDocument> lexicalCandidates(String query, int limit) {
        try {
            List<RagLexicalHit> hits = retrievalMapper.selectLexicalCandidates(query, limit);
            List<RagDocument> values = new ArrayList<>(hits.size());
            double maxScore = hits.stream()
                    .map(RagLexicalHit::getLexicalScore)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue).max().orElse(1D);
            for (RagLexicalHit hit : hits) {
                RagDocument value = new RagDocument(hit.getTitle(), hit.getContent(), 0, hit.getId());
                value.setLexicalScore(maxScore <= 0 || hit.getLexicalScore() == null
                        ? 0 : hit.getLexicalScore() / maxScore);
                value.setSourcePosition(hit.getSourcePosition());
                values.add(value);
            }
            return values;
        } catch (RuntimeException error) {
            logger.warn("Lexical recall failed; continuing with vector recall: {}", error.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RagDocument> fuseAndRerank(
            String query, List<RagDocument> vectorHits,
            List<RagDocument> lexicalHits, int topK) {
        Map<Long, RagDocument> candidates = new LinkedHashMap<>();
        Map<Long, Integer> vectorRanks = new LinkedHashMap<>();
        Map<Long, Integer> lexicalRanks = new LinkedHashMap<>();
        mergeCandidates(candidates, vectorRanks, vectorHits, true);
        mergeCandidates(candidates, lexicalRanks, lexicalHits, false);
        Set<String> terms = queryTerms(query);
        for (RagDocument candidate : candidates.values()) {
            Integer vectorRank = vectorRanks.get(candidate.getId());
            Integer lexicalRank = lexicalRanks.get(candidate.getId());
            double rrf = (vectorRank == null ? 0 : 0.6D / (60 + vectorRank))
                    + (lexicalRank == null ? 0 : 0.4D / (60 + lexicalRank));
            double overlap = overlap(terms, candidate.getTitle() + " " + candidate.getContent());
            double normalizedRrf = Math.min(1D, rrf * 61D);
            double evidenceScore = Math.max(candidate.getVectorScore(), candidate.getLexicalScore());
            candidate.setRerankScore(Math.min(1D,
                    0.55D * normalizedRrf + 0.25D * overlap + 0.20D * evidenceScore));
            candidate.setSimilarity(candidate.getRerankScore());
        }
        List<RagDocument> ranked = new ArrayList<>(candidates.values());
        ranked.sort(Comparator.comparingDouble(RagDocument::getRerankScore).reversed()
                .thenComparing(document -> document.getId() == null ? Long.MAX_VALUE : document.getId()));
        if (ranked.size() > topK) {
            ranked = new ArrayList<>(ranked.subList(0, topK));
        }
        for (int index = 0; index < ranked.size(); index++) {
            ranked.get(index).setRankNo(index + 1);
        }
        return ranked;
    }

    private void mergeCandidates(Map<Long, RagDocument> candidates, Map<Long, Integer> ranks,
                                 List<RagDocument> hits, boolean vector) {
        for (int index = 0; index < hits.size(); index++) {
            RagDocument hit = hits.get(index);
            if (hit.getId() == null) {
                continue;
            }
            ranks.putIfAbsent(hit.getId(), index + 1);
            RagDocument existing = candidates.get(hit.getId());
            if (existing == null) {
                candidates.put(hit.getId(), hit);
            } else if (vector) {
                existing.setVectorScore(hit.getVectorScore());
            } else {
                existing.setLexicalScore(hit.getLexicalScore());
                if (existing.getSourcePosition() == null) {
                    existing.setSourcePosition(hit.getSourcePosition());
                }
            }
        }
    }

    private Set<String> queryTerms(String query) {
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String value : normalized.split("[^a-z0-9_]+")) {
            if (value.length() >= 2) {
                terms.add(value);
            }
        }
        String chinese = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 1 < chinese.length(); index++) {
            terms.add(chinese.substring(index, index + 2));
        }
        return terms;
    }

    private double overlap(Set<String> terms, String content) {
        if (terms.isEmpty() || content == null) {
            return 0;
        }
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        long hits = terms.stream().filter(normalized::contains).count();
        return (double) hits / terms.size();
    }

    private void persistTrace(String query, int topK, int vectorCount, int lexicalCount,
                              List<RagDocument> results, long latencyMs) {
        try {
            RagRetrievalLogRecord log = new RagRetrievalLogRecord();
            log.setUserId(currentUser.get());
            log.setQueryText(query);
            log.setQueryHash(sha256(query));
            log.setTopK(topK);
            log.setResultCount(results.size());
            log.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
            log.setMetadata("{\"strategy\":\"hybrid-rrf-local-rerank-v1\","
                    + "\"vectorCandidates\":" + vectorCount + ","
                    + "\"lexicalCandidates\":" + lexicalCount + "}");
            log.setCreateTime(new Date());
            retrievalMapper.insertRetrievalLog(log);
            for (RagDocument result : results) {
                result.setRetrievalLogId(log.getId());
                retrievalMapper.insertCitation(log.getId(), result.getId(), result.getRankNo(),
                        result.getRerankScore(), limitCitation(result.getTitle()));
            }
        } catch (RuntimeException error) {
            logger.warn("Failed to persist RAG retrieval trace: {}", error.getMessage());
        }
    }

    public void markCitationsUsed(List<RagDocument> docs, String answer) {
        if (docs == null || answer == null) {
            return;
        }
        for (RagDocument doc : docs) {
            String marker = "【参考" + doc.getRankNo() + "】";
            if (answer.contains(marker)
                    || (doc.getTitle() != null && answer.contains(doc.getTitle()))) {
                retrievalMapper.markCitationUsed(doc.getRetrievalLogId(), doc.getRankNo());
            }
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String limitCitation(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    public String formatReferenceDocs(List<RagDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder value = new StringBuilder(
                "\n\n## 可引用参考资料\n回答使用资料时必须保留对应的【参考N】标记；"
                        + "资料不足时明确说明，不得编造引用。\n\n");
        for (int i = 0; i < docs.size(); i++) {
            RagDocument document = docs.get(i);
            value.append("【参考").append(i + 1).append("】")
                    .append(document.getTitle()).append("\n");
            if (document.getSourcePosition() != null) {
                value.append("来源位置：").append(document.getSourcePosition()).append("\n");
            }
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

    private Long longMetadata(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static class RagDocument {
        private String title;
        private String content;
        private double similarity;
        private Long id;
        private double vectorScore;
        private double lexicalScore;
        private double rerankScore;
        private Integer rankNo;
        private Long retrievalLogId;
        private String sourcePosition;

        public RagDocument() {
        }

        public RagDocument(String title, String content, double similarity, Long id) {
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
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public double getVectorScore() { return vectorScore; }
        public void setVectorScore(double vectorScore) { this.vectorScore = vectorScore; }
        public double getLexicalScore() { return lexicalScore; }
        public void setLexicalScore(double lexicalScore) { this.lexicalScore = lexicalScore; }
        public double getRerankScore() { return rerankScore; }
        public void setRerankScore(double rerankScore) { this.rerankScore = rerankScore; }
        public Integer getRankNo() { return rankNo; }
        public void setRankNo(Integer rankNo) { this.rankNo = rankNo; }
        public Long getRetrievalLogId() { return retrievalLogId; }
        public void setRetrievalLogId(Long retrievalLogId) { this.retrievalLogId = retrievalLogId; }
        public String getSourcePosition() { return sourcePosition; }
        public void setSourcePosition(String sourcePosition) { this.sourcePosition = sourcePosition; }
    }
}
