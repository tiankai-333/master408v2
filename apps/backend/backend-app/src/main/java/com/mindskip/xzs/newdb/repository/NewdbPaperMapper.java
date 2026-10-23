package com.mindskip.xzs.newdb.repository;

import com.mindskip.xzs.newdb.domain.NewdbPaperDO;
import com.mindskip.xzs.newdb.domain.NewdbQuestionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 候选库 paper 表访问（M1 整卷）。与 NewdbQuestionMapper 同规约：
 * 不注册为 Spring bean，经 NewdbDbClient 的 SqlSession 获取。
 * 本 Mapper 的 SQL 含候选库扩展列（assets_json / source_sha256 等），
 * 仅可用于 master408_candidate；v0 库（master408）无这些列。
 */
public interface NewdbPaperMapper {

    NewdbPaperDO selectByName(@Param("name") String name);

    NewdbPaperDO selectById(@Param("id") long id);

    List<NewdbPaperDO> listAll();

    int insert(NewdbPaperDO row);

    int updateContent(@Param("id") long id, @Param("row") NewdbPaperDO row);

    /**
     * 候选库专用：题目带 assets_json 列（候选库扩展），含学科/批次联查。
     */
    List<NewdbQuestionDO> selectQuestionsByIds(@Param("ids") List<Long> ids);

    NewdbQuestionDO selectQuestionDetail(@Param("id") long id);

    /**
     * 候选库专用：按 source_ref 取行（含 assets_json 扩展列），供重放内容修复检测。
     */
    NewdbQuestionDO selectQuestionBySourceRef(@Param("sourceRef") String sourceRef);

    /**
     * 候选库专用插入：与 v0 insert 相同字段 + assets_json 扩展列。
     */
    int insertWithAssets(NewdbQuestionDO row);

    /**
     * 候选库专用：重放时内容修复（缺图恢复等）——同一 source_ref 行按新批次整行更新
     * （D-33 纪律 3 的候选库实现：内容更新 = 整行内容替换 + 新 batch_id，id 不变以保持
     * paper.question_ids 稳定）。仅当内容确有变化时由导入服务调用。
     */
    int updateQuestionContent(NewdbQuestionDO row);
}
