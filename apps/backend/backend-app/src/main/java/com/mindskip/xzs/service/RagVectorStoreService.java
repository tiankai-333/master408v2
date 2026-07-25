package com.mindskip.xzs.service;

import com.mindskip.xzs.domain.rag.RagChunkRecord;

import java.util.List;

public interface RagVectorStoreService {

    boolean isEnabled();

    int dimensions();

    void add(List<RagChunkRecord> chunks);
}
