-- M2 answer_bind 归属/整卷扩展列（对既有库的一次性限定升级；幂等由过程判断）
ALTER TABLE master408_m2_candidate.answer_bind
  ADD COLUMN student_id INT NULL COMMENT '归属学生',
  ADD COLUMN paper_id BIGINT NULL,
  ADD COLUMN bind_group CHAR(64) NULL,
  ADD COLUMN question_type TINYINT NULL,
  ADD COLUMN pending_manual TINYINT NOT NULL DEFAULT 0;
ALTER TABLE master408_m2_candidate.answer_bind
  ADD KEY idx_bind_group (bind_group),
  ADD KEY idx_bind_student (student_id);
