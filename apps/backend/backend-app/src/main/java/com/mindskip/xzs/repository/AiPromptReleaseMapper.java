package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiPromptRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiPromptReleaseMapper {

    List<AiPromptRelease> selectAll();

    AiPromptRelease selectByDefinitionAndEnv(@Param("definitionId") Long definitionId,
                                             @Param("environment") String environment);

    int insert(AiPromptRelease release);

    /** 更新 stable/canary/percent/status；每次变更由 service 写 audit。 */
    int update(AiPromptRelease release);
}
