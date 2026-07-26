package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiPromptAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiPromptAuditLogMapper {

    int insert(AiPromptAuditLog log);

    List<AiPromptAuditLog> selectByDefinitionId(@Param("definitionId") Long definitionId,
                                                @Param("limit") int limit);
}
