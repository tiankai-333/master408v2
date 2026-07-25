package com.mindskip.xzs.service.impl;

import com.mindskip.xzs.domain.rag.RagChunkRecord;
import com.mindskip.xzs.repository.RagDocumentMapper;
import com.mindskip.xzs.service.RagDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class RagDocumentServiceImpl implements RagDocumentService {

    @Autowired
    private RagDocumentMapper ragDocumentMapper;

    @Override
    public int backfillFromLegacyKnowledgeBase() {
        int documents = ragDocumentMapper.backfillDocumentsFromLegacyKnowledgeBase();
        int chunks = ragDocumentMapper.backfillChunksFromLegacyKnowledgeBase();
        return documents + chunks;
    }

    @Override
    public int backfillFromQuestions() {
        int documents = ragDocumentMapper.backfillDocumentsFromQuestions();
        int chunks = ragDocumentMapper.backfillChunksFromQuestions();
        return documents + chunks;
    }

    @Override
    @Transactional
    public int normalizeForIndexing(int maxChars, int overlapChars) {
        int safeMax = Math.min(Math.max(maxChars, 1000), 6000);
        int safeOverlap = Math.min(Math.max(overlapChars, 0), safeMax / 4);
        int changed = ragDocumentMapper.refreshQuestionChunkText();
        List<RagChunkRecord> oversized = ragDocumentMapper.selectOversizedKnowledgeChunks(safeMax);
        for (RagChunkRecord source : oversized) {
            String text = source.getContentText() != null ? source.getContentText() : source.getContent();
            List<String> segments = splitText(text, safeMax, safeOverlap);
            if (segments.size() <= 1) {
                continue;
            }
            String first = segments.get(0);
            ragDocumentMapper.updateChunkContent(
                    source.getId(), first, sha256(first), Math.max(1, first.length() / 3));
            changed++;
            for (int i = 1; i < segments.size(); i++) {
                RagChunkRecord split = new RagChunkRecord();
                split.setDocumentId(source.getDocumentId());
                split.setChunkIndex(source.getChunkIndex() + i);
                split.setContent(segments.get(i));
                split.setContentText(segments.get(i));
                split.setSubjectId(source.getSubjectId());
                split.setKnowledgePointId(source.getKnowledgePointId());
                String label = source.getCitationLabel() == null ? source.getTitle() : source.getCitationLabel();
                split.setCitationLabel(label + "（" + (i + 1) + "/" + segments.size() + "）");
                split.setSourcePosition(source.getSourcePosition());
                ragDocumentMapper.insertSplitChunk(split);
                changed++;
            }
        }
        return changed;
    }

    private List<String> splitText(String text, int maxChars, int overlapChars) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int boundary = Math.max(
                        Math.max(text.lastIndexOf('\n', end), text.lastIndexOf('。', end)),
                        text.lastIndexOf('；', end));
                if (boundary > start + maxChars / 2) {
                    end = boundary + 1;
                }
            }
            result.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlapChars, start + 1);
        }
        return result;
    }

    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : hash) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash RAG chunk", e);
        }
    }

    @Override
    public List<RagChunkRecord> listIndexableChunks(String embeddingModel, String collectionName,
                                                    boolean force, long afterId, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        return ragDocumentMapper.selectIndexableChunks(
                embeddingModel, collectionName, force, Math.max(afterId, 0), safeLimit);
    }

    @Override
    public void markIndexed(Long chunkId, String model, Integer dimension, String collectionName, String vectorId) {
        ragDocumentMapper.upsertEmbeddingMetadata(chunkId, model, dimension, "qdrant", collectionName, vectorId, "indexed", null);
    }

    @Override
    public void markIndexFailed(Long chunkId, String model, Integer dimension, String collectionName, String vectorId, String errorMessage) {
        String message = errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 1000));
        ragDocumentMapper.upsertEmbeddingMetadata(chunkId, model, dimension, "qdrant", collectionName, vectorId, "failed", message);
    }
}
