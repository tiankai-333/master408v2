package com.mindskip.xzs.newdb.repository;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * M2 候选库（master408_m2_candidate）专用访问：题目编辑/不可变版本/作答版本绑定。
 * SQL 含 M2 扩展列（current_version_id/question_version/answer_bind），
 * 仅可用于 m2 库，与 v0 库 Mapper 严格分离。结果用 Map（自包含实验切片，
 * 行结构以 XML 别名为准，不引入新领域模型）。
 */
public interface NewdbM2Mapper {

    Map<String, Object> selectQuestionCurrent(@Param("id") long id);

    Long selectQuestionIdBySourceRef(@Param("sourceRef") String sourceRef);

    Integer selectCurrentVersionNoForUpdate(@Param("id") long id);

    Map<String, Object> selectVersion(@Param("questionId") long questionId,
                                      @Param("versionNo") int versionNo);

    List<Map<String, Object>> selectVersions(@Param("questionId") long questionId);

    int insertVersion(@Param("v") Map<String, Object> v);

    int updateQuestionContent(@Param("id") long id,
                              @Param("versionId") long versionId,
                              @Param("versionNo") int versionNo,
                              @Param("stemHtml") String stemHtml,
                              @Param("analysisHtml") String analysisHtml,
                              @Param("correct") String correct,
                              @Param("answerText") String answerText,
                              @Param("assetsJson") String assetsJson,
                              @Param("contentSha256") String contentSha256);

    int insertAnswerBind(@Param("token") String token,
                         @Param("questionId") long questionId,
                         @Param("versionId") long versionId,
                         @Param("versionNo") int versionNo);

    Map<String, Object> selectAnswerBind(@Param("token") String token);

    int submitAnswerBind(@Param("token") String token,
                         @Param("answerText") String answerText);
}
