package com.mindskip.xzs.newdb.repository;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * M2 学生端适配（新库消费点，真实学生身份）：
 * 卷列表/题面（隐藏答案解析）/整卷开始绑定（归属+重复请求保护）/恢复/交卷/
 * 回看。所有按 token/group 的读取均校验归属学生。仅可用于 m2 库。
 */
public interface NewdbM2StudentMapper {

    List<Map<String, Object>> listPapers();

    List<Map<String, Object>> selectPaperQuestions(@Param("paperId") long paperId);

    Map<String, Object> selectPaper(@Param("paperId") long paperId);

    Map<String, Object> selectQuestionStudentView(@Param("id") long id);

    List<Long> selectQuestionIdsByVersionIds(@Param("versionIds") List<Long> versionIds);

    Map<String, Object> selectActiveGroup(@Param("studentId") int studentId,
                                          @Param("paperId") long paperId);

    List<Map<String, Object>> selectActiveBinds(@Param("bindGroup") String bindGroup,
                                                @Param("studentId") int studentId);

    Map<String, Object> selectGroupStatus(@Param("bindGroup") String bindGroup,
                                          @Param("studentId") int studentId);

    int insertBind(@Param("b") Map<String, Object> b);

    List<Map<String, Object>> selectBindsForReview(@Param("bindGroup") String bindGroup,
                                                   @Param("studentId") int studentId);

    int submitBind(@Param("id") long id, @Param("answerText") String answerText,
                   @Param("pendingManual") boolean pendingManual);
}
