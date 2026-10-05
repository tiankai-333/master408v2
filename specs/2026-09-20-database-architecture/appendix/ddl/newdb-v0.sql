-- ============================================================================
-- Master408 新库引导 schema v0（D-33：raw 极简，raw + 派生）
-- 依据：specs/2026-09-20-database-architecture/plan.md 重开定向（2026-09-30）
--       requirements.md D-33 / D-30 / D-22 / D-28；ER 说明见 appendix/newdb-er.md
--
-- 三纪律（D-33）：
--   1. XSS 消毒强制：stem_html / analysis_html 必须经导入器消毒（剥 <script>/事件属性）后入库；
--   2. answer 校验：question_type IN (1,2,3) 时 correct 不得为空，否则 display_ready 必须为 0；
--   3. raw 不可变：stem_html / analysis_html 写入后不修改——内容更新 = 整行替换 + 新 batch_id；
--      一切派生物（stem_text、RAG 切片、向量）必须可从 raw 重建。
--
-- 范围（v0）：题目域 + 试卷域 + 导入血缘。
--   作答域、用户域、知识库域、RAG 投影表**刻意缺席**，见 newdb-er.md「刻意缺席」节。
-- 执行：mysql -u<user> -p < newdb-v0.sql（脚本自带 CREATE DATABASE，可重复执行会失败——
--       它是一次性引导脚本，不是幂等迁移）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `master408` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `master408`;

-- ----------------------------------------------------------------------------
-- 导入批次（D-30③ 血缘头）：每个爬取/修复批次登记一行；
-- question.batch_id / paper.batch_id 指向本表——每行可回答"哪次爬取写的"。
-- ----------------------------------------------------------------------------
CREATE TABLE `import_batch` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `batch_tag`   VARCHAR(64)  NOT NULL COMMENT '批次标识，如 crawl-2026w40 / repair-td017',
  `source_type` VARCHAR(32)  NOT NULL COMMENT 'crawler / manual_fix / seed',
  `started_at`  DATETIME     NULL,
  `finished_at` DATETIME     NULL,
  `note`        VARCHAR(255) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_import_batch_tag` (`batch_tag`)
) ENGINE = InnoDB COMMENT = '导入批次登记（D-30③：AI 批量写入的血缘头）';

-- ----------------------------------------------------------------------------
-- 题目（D-33 raw 极简）：一行 = 一道题的全部事实。
-- 对照旧 t_question：删 8 个应用层不可达的内容列（title/options/correct_answer/analysis/
--   content_format/has_image/has_code/images，见 data-contract 3.2 实测）+ difficulty（零引用）
--   + info_text_content_id 间接层（内容直存本表，消除双源根源）；
-- 新增：stem_html/analysis_html（内容事实源）、stem_text（派生投影）、
--   source_ref/batch_id/license_tag/display_ready（治理三件套）。
-- 不设 deleted 列：状态由 status 单列表达（1 正常 / 2 停用 / 3 紧急撤下，D-21）。
-- ----------------------------------------------------------------------------
CREATE TABLE `question` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `question_type`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1单选 2多选 3判断 4填空 5简答（后端枚举为准，D-22）',
  `subject_id`         INT          NULL COMMENT '学科（逻辑引用 subject.id）',
  `score`              INT          NULL COMMENT '分值，×10 存整数（ExamUtil 惯例）',
  `difficult`          TINYINT      NULL COMMENT '难度 1/2/3',
  `source_name`        VARCHAR(100) NULL COMMENT '来源名，如 2024年408真题 / 2026 408 模拟卷（D-18 展示项）',
  `source_year`        SMALLINT     NULL COMMENT '来源年份',
  `source_question_no` VARCHAR(50)  NULL COMMENT '原始题号（varchar 修复旧 int 无法存非数字题号的缺口）',
  `source_ref`         VARCHAR(512) NULL COMMENT '原始页面 URL（provenance；导入幂等业务键）',
  `stem_html`          LONGTEXT     NULL COMMENT '题干 HTML 原文——显示事实源；XSS 消毒后入库；raw 不可变（纪律1/3）',
  `analysis_html`      LONGTEXT     NULL COMMENT '解析 HTML 原文——同上',
  `stem_text`          TEXT         NULL COMMENT '题干纯文本投影（导入器从 stem_html 生成；RAG 切片直用，D-33 派生）',
  `correct`            VARCHAR(255) NULL COMMENT '判分答案：选择=字母 / 判断=1或0 / 多选=排序逗号串；填空简答 NULL（D-28）',
  `answer_text`        TEXT         NULL COMMENT '主观题参考答案长文本（NULL 为合法状态，D-28）',
  `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 2 停用 / 3 紧急撤下（D-21）',
  `status_reason`      VARCHAR(255) NULL COMMENT '停用/撤下原因',
  `display_ready`      TINYINT      NOT NULL DEFAULT 0 COMMENT '上线阀（纪律2）：0=不上线',
  `license_tag`        VARCHAR(32)  NOT NULL DEFAULT 'unknown' COMMENT '授权标记（D-32 遗留：unknown/self/crawler-review…）',
  `batch_id`           BIGINT       NULL COMMENT '写入批次（import_batch.id）',
  `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_source_ref` (`source_ref`),
  KEY `idx_question_filter` (`question_type`, `source_year`),
  KEY `idx_question_batch` (`batch_id`),
  KEY `idx_question_status` (`status`),
  FULLTEXT KEY `ft_question_stem_text` (`stem_text`) WITH PARSER ngram
) ENGINE = InnoDB COMMENT = '题目（D-33 raw 极简：一行 = 一道题的全部事实）';

-- ----------------------------------------------------------------------------
-- 试卷（作者设计："试卷名 + 有序题目 id"）。
-- 对照旧 t_exam_paper + t_text_content 试卷 Frame：question_ids 直存本表，
--   消除 frame_text_content_id 间接层；丢弃 grade_level/task_exam_id（无使用证据）。
-- ----------------------------------------------------------------------------
CREATE TABLE `paper` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `name`             VARCHAR(255) NOT NULL COMMENT '试卷名（幂等业务键）',
  `subject_id`       INT         NULL,
  `paper_type`       TINYINT     NOT NULL DEFAULT 1 COMMENT '1 真题 / 2 模拟 / 3 原创…（与 question.license_tag 口径独立）',
  `source_year`      SMALLINT    NULL,
  `description`      TEXT        NULL,
  `question_ids`     JSON        NOT NULL COMMENT '有序题目 id 数组，如 [101,102,103]——顺序即出题顺序',
  `question_count`   INT         NULL COMMENT '题目数（导入时写入的冗余计数，便于筛选）',
  `score`            INT         NULL COMMENT '总分（×10）',
  `suggest_time`     INT         NULL COMMENT '建议时长（分钟）',
  `limit_start_time` DATETIME    NULL,
  `limit_end_time`   DATETIME    NULL,
  `batch_id`         BIGINT      NULL COMMENT '写入批次（import_batch.id）',
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_name` (`name`),
  KEY `idx_paper_year` (`source_year`),
  KEY `idx_paper_batch` (`batch_id`)
) ENGINE = InnoDB COMMENT = '试卷（名字 + 有序题目 id；作者 2026-09-27 设计）';

-- ----------------------------------------------------------------------------
-- 学科（修剪版 t_subject：id/name 之外的三列均无使用证据，不建）
-- ----------------------------------------------------------------------------
CREATE TABLE `subject` (
  `id`   INT         NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject_name` (`name`)
) ENGINE = InnoDB COMMENT = '学科';
