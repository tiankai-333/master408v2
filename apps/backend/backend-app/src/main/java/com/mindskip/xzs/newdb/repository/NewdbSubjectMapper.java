package com.mindskip.xzs.newdb.repository;

import org.apache.ibatis.annotations.Param;

/**
 * 新库 subject 表访问：按名幂等 upsert（uk_subject_name），再回查 id。
 */
public interface NewdbSubjectMapper {

    int upsertByName(@Param("name") String name);

    Integer idByName(@Param("name") String name);
}
