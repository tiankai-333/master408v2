package com.mindskip.xzs.newdb.repository;

import com.mindskip.xzs.newdb.domain.NewdbCountItem;
import com.mindskip.xzs.newdb.domain.NewdbPageQuery;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 新库 question 表访问。不注册为 Spring bean、不参与 @MapperScan：
 * 经 NewdbDbClient 的 SqlSession getMapper 获取（plan D1）。
 */
public interface NewdbQuestionMapper {

    NewdbQuestionDO selectBySourceRef(@Param("sourceRef") String sourceRef);

    NewdbQuestionDO selectDetail(@Param("id") long id);

    int insert(NewdbQuestionDO row);

    long countAll();

    long countPage(NewdbPageQuery query);

    List<NewdbQuestionDO> page(NewdbPageQuery query);

    List<NewdbCountItem> countByYear();

    List<NewdbCountItem> countByType();

    List<NewdbCountItem> countByStatus();
}
