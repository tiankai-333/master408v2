package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.rag.RagLexicalHit;
import com.mindskip.xzs.domain.rag.RagRetrievalLogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RagRetrievalMapper {

    List<RagLexicalHit> selectLexicalCandidates(
            @Param("query") String query, @Param("limit") Integer limit);

    int insertRetrievalLog(RagRetrievalLogRecord record);

    int insertCitation(@Param("retrievalLogId") Long retrievalLogId,
                       @Param("chunkId") Long chunkId,
                       @Param("rankNo") Integer rankNo,
                       @Param("score") Double score,
                       @Param("citationText") String citationText);

    int markCitationUsed(@Param("retrievalLogId") Long retrievalLogId,
                         @Param("rankNo") Integer rankNo);
}
