-- ============================================================================
-- M2 候选库 master408_m2_candidate 自包含建库（题目编辑/不可变版本/作答版本绑定）
-- 派生：specs/2026-09-20 v0（question/paper/subject/import_batch）+ M2 新增：
--   question 增列：current_version_id/current_version_no/edited_manual
--   question_version：不可变版本历史（每题 append-only，不改历史行）
--   answer_bind：真实作答版本绑定（开始绑定→恢复→交卷→回看引用同一版本）
-- 幂等：CREATE DATABASE/TABLE IF NOT EXISTS，可重复执行；数据导入另由
--       tools/m2/import_from_candidate.py 执行（对 master408_candidate 只读）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `master408_m2_candidate`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `master408_m2_candidate`;

CREATE TABLE IF NOT EXISTS `import_batch` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `batch_tag`   VARCHAR(64) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL COMMENT 'crawler/paper_extract/manual_edit/m2_import',
  `started_at`  DATETIME NULL,
  `finished_at` DATETIME NULL,
  `note`        VARCHAR(255) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_batch_tag` (`batch_tag`)
) ENGINE = InnoDB COMMENT = '导入批次登记';

CREATE TABLE IF NOT EXISTS `subject` (
  `id`   INT         NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject_name` (`name`)
) ENGINE = InnoDB COMMENT = '学科';

CREATE TABLE IF NOT EXISTS `question` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `question_type`      TINYINT      NOT NULL DEFAULT 1,
  `subject_id`         INT          NULL,
  `score`              INT          NULL,
  `difficult`          TINYINT      NULL,
  `source_name`        VARCHAR(100) NULL,
  `source_year`        SMALLINT     NULL,
  `source_question_no` VARCHAR(50)  NULL,
  `source_ref`         VARCHAR(512) NULL,
  `stem_html`          LONGTEXT     NULL COMMENT '当前版本题干+选项（消毒后）',
  `analysis_html`      LONGTEXT     NULL COMMENT '当前版本解析（消毒后）',
  `stem_text`          TEXT         NULL,
  `correct`            VARCHAR(255) NULL,
  `answer_text`        TEXT         NULL,
  `status`             TINYINT      NOT NULL DEFAULT 1,
  `status_reason`      VARCHAR(255) NULL,
  `display_ready`      TINYINT      NOT NULL DEFAULT 0,
  `license_tag`        VARCHAR(32)  NOT NULL DEFAULT 'unknown',
  `batch_id`           BIGINT       NULL,
  `assets_json`        LONGTEXT     NULL,
  -- M2 新增：版本指针与人工编辑标记
  `current_version_id` BIGINT       NULL COMMENT '当前版本（question_version.id）',
  `current_version_no` INT          NULL COMMENT '当前版本号（乐观锁 expectedVersion）',
  `edited_manual`      TINYINT      NOT NULL DEFAULT 0
      COMMENT '1=有人工编辑历史，重导不得覆盖',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_source_ref` (`source_ref`),
  KEY `idx_question_filter` (`question_type`, `source_year`),
  KEY `idx_question_status` (`status`)
) ENGINE = InnoDB COMMENT = '题目（当前指针 + 不可变历史）';

CREATE TABLE IF NOT EXISTS `paper` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `name`             VARCHAR(255) NOT NULL,
  `subject_id`       INT         NULL,
  `paper_type`       TINYINT     NOT NULL DEFAULT 1,
  `source_year`      SMALLINT    NULL,
  `description`      TEXT        NULL,
  `question_ids`     JSON        NOT NULL,
  `question_count`   INT         NULL,
  `score`            INT         NULL,
  `suggest_time`     INT         NULL,
  `limit_start_time` DATETIME    NULL,
  `limit_end_time`   DATETIME    NULL,
  `batch_id`         BIGINT      NULL,
  `source_sha256`    VARCHAR(64) NULL,
  `source_url`       VARCHAR(255) NULL,
  `warning_count`    INT         NULL,
  `warnings_json`    LONGTEXT    NULL,
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_name` (`name`),
  KEY `idx_paper_year` (`source_year`)
) ENGINE = InnoDB COMMENT = '试卷';

CREATE TABLE IF NOT EXISTS `question_version` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `question_id`        BIGINT       NOT NULL,
  `version_no`         INT          NOT NULL COMMENT '从 1 递增；append-only 不改历史',
  `edit_source`        VARCHAR(16)  NOT NULL
      COMMENT 'import=初始导入 / manual_edit=人工编辑 / rollback=回滚生成',
  `stem_html`          LONGTEXT     NOT NULL,
  `analysis_html`      LONGTEXT     NULL,
  `correct`            VARCHAR(255) NULL,
  `answer_text`        TEXT         NULL,
  `assets_json`        LONGTEXT     NULL,
  `source_ref`         VARCHAR(512) NULL,
  `source_name`        VARCHAR(100) NULL,
  `source_year`        SMALLINT     NULL,
  `source_question_no` VARCHAR(50)  NULL,
  `content_sha256`     CHAR(64)     NOT NULL
      COMMENT 'stem+analysis+correct 的 sha256（不可变性可核）',
  `base_version_id`    BIGINT       NULL COMMENT 'manual_edit/rollback 的基底版本',
  `edit_note`          VARCHAR(255) NULL,
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version` (`question_id`, `version_no`),
  KEY `idx_version_question` (`question_id`)
) ENGINE = InnoDB COMMENT = '题目不可变版本历史（append-only）';

CREATE TABLE IF NOT EXISTS `answer_bind` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `bind_token`   CHAR(64)    NOT NULL COMMENT '开始作答令牌（恢复/交卷/回看键）',
  `question_id`  BIGINT      NOT NULL,
  `version_id`   BIGINT      NOT NULL COMMENT '开始作答时绑定的不可变版本',
  `version_no`   INT         NOT NULL,
  `status`       VARCHAR(16) NOT NULL DEFAULT 'active'
      COMMENT 'active=作答中 / submitted=已交卷',
  `answer_text`  TEXT        NULL COMMENT '学生作答内容',
  `student_id`   INT         NULL COMMENT '归属学生（xzs user.id），归属隔离',
  `paper_id`     BIGINT      NULL COMMENT '整卷绑定：所属卷',
  `bind_group`   CHAR(64)    NULL COMMENT '整卷绑定组令牌（同组同次开始作答）',
  `question_type` TINYINT    NULL,
  `pending_manual` TINYINT   NOT NULL DEFAULT 0
      COMMENT '1=复杂题待人工判分（不虚构自动评分）',
  `bound_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `submitted_at` DATETIME    NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bind_token` (`bind_token`),
  KEY `idx_bind_question` (`question_id`),
  KEY `idx_bind_group` (`bind_group`),
  KEY `idx_bind_student` (`student_id`)
) ENGINE = InnoDB COMMENT = '作答版本绑定（开始绑定后编辑不影响回看）';
