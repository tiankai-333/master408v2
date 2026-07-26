package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiPromptVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AiPromptVersionMapper {

    List<AiPromptVersion> selectByDefinitionId(@Param("definitionId") Long definitionId);

    /** Bulk load for registry cache: stable/canary versions referenced by releases. */
    List<AiPromptVersion> selectByIds(@Param("ids") Collection<Long> ids);

    AiPromptVersion selectById(@Param("id") Long id);

    Integer selectMaxVersionNo(@Param("definitionId") Long definitionId);

    int insert(AiPromptVersion version);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 仅 draft/testing/rejected 状态可改内容（由 service 层守卫）。 */
    int updateContent(AiPromptVersion version);

    int updateApproval(@Param("id") Long id, @Param("status") String status,
                       @Param("approvedBy") Integer approvedBy,
                       @Param("approveComment") String approveComment);
}
