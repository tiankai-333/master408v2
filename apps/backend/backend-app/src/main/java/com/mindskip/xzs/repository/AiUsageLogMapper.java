package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiUsageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiUsageLogMapper {

    List<AiUsageLog> selectByTemplateId(@Param("templateId") Integer templateId, @Param("limit") int limit);

    AiUsageLog selectById(@Param("id") Integer id);

    int insert(AiUsageLog usageLog);

    int update(AiUsageLog usageLog);

    int updateFeedbackForUser(@Param("id") Integer id, @Param("userId") Integer userId,
                              @Param("rating") Integer rating, @Param("feedback") String feedback);

    int deleteById(@Param("id") Integer id);

    int countTotal();

    double countSuccessRate();

    List<Map<String, Object>> getTopStyles(@Param("limit") int limit);

    Map<String, Object> selectUserUsageSummary(@Param("userId") Integer userId, @Param("days") int days);

    List<Map<String, Object>> selectUserUsageSummaryByKeySource(@Param("userId") Integer userId, @Param("days") int days);

    List<Map<String, Object>> selectUserUsageByProvider(@Param("userId") Integer userId, @Param("days") int days);

    List<Map<String, Object>> selectUserRecentLogs(@Param("userId") Integer userId, @Param("days") int days, @Param("limit") int limit);
}
