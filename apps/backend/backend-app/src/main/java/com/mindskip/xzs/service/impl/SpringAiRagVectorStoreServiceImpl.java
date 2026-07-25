package com.mindskip.xzs.service.impl;

import com.mindskip.xzs.domain.rag.RagChunkRecord;
import com.mindskip.xzs.service.RagVectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SpringAiRagVectorStoreServiceImpl implements RagVectorStoreService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final boolean enabled;
    private final int embeddingDimension;

    public SpringAiRagVectorStoreServiceImpl(
            ObjectProvider<VectorStore> vectorStoreProvider,
            @Value("${ai.rag.vector.enabled:false}") boolean enabled,
            @Value("${ai.embedding.dimension:1024}") int embeddingDimension) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.enabled = enabled;
        this.embeddingDimension = embeddingDimension;
    }

    @Override
    public boolean isEnabled() {
        return enabled && vectorStoreProvider.getIfAvailable() != null;
    }

    @Override
    public int dimensions() {
        return embeddingDimension;
    }

    @Override
    public void add(List<RagChunkRecord> chunks) {
        if (!isEnabled()) {
            throw new IllegalStateException("Spring AI Qdrant VectorStore is not enabled");
        }
        List<Document> documents = new ArrayList<>(chunks.size());
        for (RagChunkRecord chunk : chunks) {
            String text = chunk.getContentText() != null ? chunk.getContentText() : chunk.getContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            documents.add(Document.builder()
                    .id(vectorId(chunk.getId()))
                    .text(text)
                    .metadata("chunk_id", chunk.getId())
                    .metadata("document_id", chunk.getDocumentId())
                    .metadata("chunk_index", chunk.getChunkIndex())
                    .metadata("title", safe(chunk.getTitle()))
                    .metadata("citation_label", safe(chunk.getCitationLabel()))
                    .metadata("source_position", safe(chunk.getSourcePosition()))
                    .metadata("subject_id", chunk.getSubjectId() == null ? -1 : chunk.getSubjectId())
                    .metadata("knowledge_point_id",
                            chunk.getKnowledgePointId() == null ? -1 : chunk.getKnowledgePointId())
                    .build());
        }
        if (!documents.isEmpty()) {
            vectorStoreProvider.getObject().add(documents);
        }
    }

    public static String vectorId(Long chunkId) {
        return UUID.nameUUIDFromBytes(("rag-chunk:" + chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
