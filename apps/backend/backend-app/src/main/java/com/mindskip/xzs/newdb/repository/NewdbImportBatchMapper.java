package com.mindskip.xzs.newdb.repository;

import com.mindskip.xzs.newdb.domain.NewdbImportBatchDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 新库 import_batch 表访问（血缘头）。
 */
public interface NewdbImportBatchMapper {

    NewdbImportBatchDO selectByTag(@Param("batchTag") String batchTag);

    int insert(NewdbImportBatchDO batch);

    int markFinished(@Param("id") long id, @Param("finishedAt") LocalDateTime finishedAt,
                     @Param("note") String note);

    long countAll();

    LocalDateTime lastStartedAt();
}
