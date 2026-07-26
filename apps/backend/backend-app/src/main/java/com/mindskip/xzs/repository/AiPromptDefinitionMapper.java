package com.mindskip.xzs.repository;

import com.mindskip.xzs.domain.ai.AiPromptDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiPromptDefinitionMapper {

    List<AiPromptDefinition> selectAll();

    AiPromptDefinition selectByKey(@Param("promptKey") String promptKey);

    AiPromptDefinition selectById(@Param("id") Long id);

    int insert(AiPromptDefinition definition);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);
}
