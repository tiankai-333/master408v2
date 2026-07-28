package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiEvaluationCaseResultRecord;
import com.mindskip.xzs.domain.ai.AiEvaluationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiEvaluationMapper {

    int insertRun(AiEvaluationRun run);

    int markRunStarted(@Param("id") Long id);

    int completeRun(AiEvaluationRun run);

    int failRun(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int insertCaseResult(AiEvaluationCaseResultRecord result);

    AiEvaluationRun selectRun(@Param("id") Long id);

    List<AiEvaluationRun> selectRecentRuns(@Param("limit") int limit);

    List<AiEvaluationCaseResultRecord> selectCaseResults(@Param("runId") Long runId);
}
