package com.mindskip.xzs.service;

import com.mindskip.xzs.domain.rag.RagChunkRecord;

import java.util.List;

public interface RagDocumentService {

    int backfillFromLegacyKnowledgeBase();

    int backfillFromQuestions();

    int normalizeForIndexing(int maxChars, int overlapChars);

    List<RagChunkRecord> listIndexableChunks(String embeddingModel, String collectionName,
                                             boolean force, long afterId, int limit);

    void markIndexed(Long chunkId, String model, Integer dimension, String collectionName, String vectorId);

    void markIndexFailed(Long chunkId, String model, Integer dimension, String collectionName, String vectorId, String errorMessage);
}
