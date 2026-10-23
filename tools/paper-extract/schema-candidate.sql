-- ============================================================================
-- M1 候选库 master408_candidate 引导 schema
-- 派生：specs/2026-09-20-database-architecture/appendix/ddl/newdb-v0.sql @53a6f630
-- 差异（候选库专用，均为加列，不改 v0 语义）：
--   question.assets_json  —— 复杂内容盘点（外链图引用/内联SVG/公式/代码块/表格计数），
--                            支撑"图片/表格/公式不静默丢失"的对账口径；
--   索引/约束与 v0 逐字一致。
-- 执行：mysql -u<user> -p < schema-candidate.sql（一次性引导，重复执行到建库行为止安全，
--       表建后重放会因表已存在失败——导入幂等性由 source_ref 业务键保证，不靠本脚本）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `master408_candidate` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `master408_candidate`;

CREATE TABLE `import_batch` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `batch_tag`   VARCHAR(64)  NOT NULL COMMENT '批次标识',
  `source_type` VARCHAR(32)  NOT NULL COMMENT 'crawler / manual_fix / seed / paper_extract',
  `started_at`  DATETIME     NULL,
  `finished_at` DATETIME     NULL,
  `note`        VARCHAR(255) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_batch_tag` (`batch_tag`)
) ENGINE = InnoDB COMMENT = '导入批次登记';

CREATE TABLE `question` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `question_type`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1单选 2多选 3判断 4填空 5简答',
  `subject_id`         INT          NULL,
  `score`              INT          NULL COMMENT '分值×10；来源无分值事实则 NULL',
  `difficult`          TINYINT      NULL,
  `source_name`        VARCHAR(100) NULL,
  `source_year`        SMALLINT     NULL,
  `source_question_no` VARCHAR(50)  NULL,
  `source_ref`         VARCHAR(512) NULL COMMENT '原始页面锚点 URL（导入幂等业务键）',
  `stem_html`          LONGTEXT     NULL COMMENT '题干+选项 HTML，消毒后入库，raw 不可变',
  `analysis_html`      LONGTEXT     NULL COMMENT '解析 HTML（综合题参考答案+解析；单选来源缺失为 NULL）',
  `stem_text`          TEXT         NULL COMMENT '派生投影，可从 raw 重建',
  `correct`            VARCHAR(255) NULL COMMENT '选择=字母；简答 NULL 为合法状态',
  `answer_text`        TEXT         NULL,
  `status`             TINYINT      NOT NULL DEFAULT 1,
  `status_reason`      VARCHAR(255) NULL,
  `display_ready`      TINYINT      NOT NULL DEFAULT 0,
  `license_tag`        VARCHAR(32)  NOT NULL DEFAULT 'unknown',
  `batch_id`           BIGINT       NULL,
  `assets_json`        LONGTEXT     NULL COMMENT '候选库扩展：复杂内容盘点 JSON（不参与判分）',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_source_ref` (`source_ref`),
  KEY `idx_question_filter` (`question_type`, `source_year`),
  KEY `idx_question_batch` (`batch_id`),
  KEY `idx_question_status` (`status`)
) ENGINE = InnoDB COMMENT = '题目（候选库：整卷 408 真题）';

CREATE TABLE `paper` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `name`             VARCHAR(255) NOT NULL,
  `subject_id`       INT         NULL,
  `paper_type`       TINYINT     NOT NULL DEFAULT 1 COMMENT '1 真题 / 2 模拟',
  `source_year`      SMALLINT    NULL,
  `description`      TEXT        NULL,
  `question_ids`     JSON        NOT NULL COMMENT '有序题目 id 数组，顺序即卷面题号顺序',
  `question_count`   INT         NULL,
  `score`            INT         NULL,
  `suggest_time`     INT         NULL,
  `limit_start_time` DATETIME    NULL,
  `limit_end_time`   DATETIME    NULL,
  `batch_id`         BIGINT      NULL,
  `source_sha256`    VARCHAR(64) NULL COMMENT '候选库扩展：源 HTML 文件指纹（对账）',
  `source_url`       VARCHAR(255) NULL COMMENT '候选库扩展：来源页 URL（provenance）',
  `warning_count`    INT         NULL COMMENT '候选库扩展：导入告警条数（缺失盘点入口）',
  `warnings_json`    LONGTEXT    NULL COMMENT '候选库扩展：逐条告警 JSON（缺图/缺解析等）',
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_name` (`name`),
  KEY `idx_paper_year` (`source_year`),
  KEY `idx_paper_batch` (`batch_id`)
) ENGINE = InnoDB COMMENT = '试卷（整卷对账单元）';

CREATE TABLE `subject` (
  `id`   INT         NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject_name` (`name`)
) ENGINE = InnoDB COMMENT = '学科';
