-- 批次 4 隔离库实验 —— 物理结构候选与索引对照
--
-- 适用范围：仅隔离库 master408_design_test。**不修改共享库，不修改业务代码。**
-- 本脚本可重复执行：先清理上次残留，再生成合成样本、跑 EXPLAIN、应用候选结构。
-- 结论请写入 physical-design.md；EXPLAIN 只作为"计划选择"的证据，不得据此宣称实际性能提升。

-- ---------------------------------------------------------------------------
-- 0. 前置断言：必须连在隔离库上
-- ---------------------------------------------------------------------------
SELECT IF(DATABASE() = 'master408_design_test', 'OK: isolated database',
          CONCAT('ABORT: wrong database = ', DATABASE())) AS guard;

-- 生成 6613 行合成样本需要提高递归深度（MySQL 默认 cte_max_recursion_depth = 1000）
SET SESSION cte_max_recursion_depth = 20000;

-- ---------------------------------------------------------------------------
-- 1. 清理（可重复执行）
-- ---------------------------------------------------------------------------
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'
                 AND INDEX_NAME = 'idx_qc_question_current_version') > 0,
              'ALTER TABLE question_content DROP INDEX idx_qc_question_current_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'
                 AND INDEX_NAME = 'uk_question_content_current') > 0,
              'ALTER TABLE question_content DROP INDEX uk_question_content_current', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'
                 AND COLUMN_NAME = 'current_question_id') > 0,
              'ALTER TABLE question_content DROP COLUMN current_question_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_exam_paper_question_customer_answer'
                 AND COLUMN_NAME = 'question_content_id') > 0,
              'ALTER TABLE t_exam_paper_question_customer_answer DROP COLUMN question_content_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_knowledge_point'
                 AND COLUMN_NAME = 'source') > 0,
              'ALTER TABLE question_knowledge_point DROP COLUMN source, DROP COLUMN update_user, DROP COLUMN update_time',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question'
                 AND COLUMN_NAME = 'status_reason') > 0,
              'ALTER TABLE t_question DROP COLUMN status_reason, DROP COLUMN status_update_user, DROP COLUMN status_update_time',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DELETE FROM question_content WHERE question_id BETWEEN 900000 AND 999999;
DELETE FROM t_question WHERE id BETWEEN 900000 AND 999999;

-- ---------------------------------------------------------------------------
-- 2. 合成样本：与真实基数一致（6613 题，每题 1 版；另 200 题各有 3 版）
-- ---------------------------------------------------------------------------
INSERT INTO t_question (id, subject_id, question_type, title, correct, deleted)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 6613)
SELECT 900000 + n, 1, 1, CONCAT('synthetic-title-', n), 'A', b'0' FROM seq;

INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code, is_current)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 6613)
SELECT 900000 + n, 1, CONCAT('title-', n), 'plain', b'0', b'0', b'1' FROM seq;

-- 200 题每题三个版本，只有 v3 是 current（模拟"多次编辑"）
INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code, is_current)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT 900000 + n, 2, CONCAT('title-v2-', n), 'plain', b'0', b'0', b'0' FROM seq;

INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code, is_current)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT 900000 + n, 3, CONCAT('title-v3-', n), 'plain', b'0', b'0', b'1' FROM seq;

UPDATE question_content SET is_current = b'0'
WHERE question_id BETWEEN 900001 AND 900200 AND version = 1;

SELECT COUNT(*) AS synthetic_questions, (SELECT COUNT(*) FROM question_content
       WHERE question_id BETWEEN 900000 AND 999999) AS synthetic_content_rows FROM t_question;

-- ---------------------------------------------------------------------------
-- 3. EXPLAIN 对照：读取当前版本（应用真实读法：WHERE question_id=? AND is_current=b'1' ORDER BY version DESC LIMIT 1）
-- ---------------------------------------------------------------------------
SELECT '--- BEFORE: 现有索引 idx_question_content_current(question_id,is_current) ---' AS phase;
EXPLAIN SELECT * FROM question_content
WHERE question_id = 900100 AND is_current = b'1' ORDER BY version DESC LIMIT 1;

SELECT '--- BEFORE: 版本历史读取 ---' AS phase;
EXPLAIN SELECT * FROM question_content WHERE question_id = 900100 ORDER BY version DESC;

-- 3.1 候选：把版本纳入读索引，避免排序
CREATE INDEX idx_qc_question_current_version ON question_content (question_id, is_current, version);

SELECT '--- AFTER: 新增 idx_qc_question_current_version ---' AS phase;
EXPLAIN SELECT * FROM question_content
WHERE question_id = 900100 AND is_current = b'1' ORDER BY version DESC LIMIT 1;

SELECT '--- AFTER: 版本历史读取 ---' AS phase;
EXPLAIN SELECT * FROM question_content WHERE question_id = 900100 ORDER BY version DESC;

-- ---------------------------------------------------------------------------
-- 4. 候选结构 A（D-13）：生成列 + 唯一键，并复核读查询是否仍走索引
-- ---------------------------------------------------------------------------
ALTER TABLE question_content
  ADD COLUMN current_question_id INT
    GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL;

ALTER TABLE question_content
  ADD UNIQUE KEY uk_question_content_current (current_question_id);

SELECT '--- AFTER: 叠加候选 A（生成列 + 唯一键）后的读计划 ---' AS phase;
EXPLAIN SELECT * FROM question_content
WHERE question_id = 900100 AND is_current = b'1' ORDER BY version DESC LIMIT 1;

SELECT '--- 约束能力复核：探针见文件末尾附录（单独执行，预期 ERROR 1062） ---' AS phase;
SELECT COUNT(*) AS current_rows_for_900100 FROM question_content
WHERE question_id = 900100 AND is_current = b'1';

-- ---------------------------------------------------------------------------
-- 5. 候选结构 B（D-17）：开始作答时绑定不可变版本行
--    设计：在答题记录上新增 question_content_id，指向不可变版本行；保留原 question_text_content_id 作为历史证据
-- ---------------------------------------------------------------------------
ALTER TABLE t_exam_paper_question_customer_answer
  ADD COLUMN question_content_id INT NULL COMMENT 'D-17：开始作答时绑定的不可变内容版本行 id';

CREATE INDEX idx_epqca_question_content ON t_exam_paper_question_customer_answer (question_content_id);

SELECT '--- 候选 B：按绑定版本行回读题面（不走可变旧行） ---' AS phase;
SELECT a.id AS answer_id, a.question_id, qc.version, LEFT(qc.title, 24) AS bound_title
FROM t_exam_paper_question_customer_answer a
JOIN question_content qc ON qc.id = a.question_content_id
WHERE a.question_content_id IS NOT NULL
LIMIT 5;

-- 不变量：绑定列一旦写入，不得指向"当前版本之外"的行（由写入时固定，不由触发器保证）
SELECT '--- 候选 B：列已建立，等待实现阶段写入 ---' AS phase;
SELECT COUNT(*) AS bound_rows FROM t_exam_paper_question_customer_answer WHERE question_content_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. 候选结构 C（D-20）：人工标注留痕
-- ---------------------------------------------------------------------------
ALTER TABLE question_knowledge_point
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT 'D-20：auto=自动生成 / manual=人工标注',
  ADD COLUMN update_user INT NULL COMMENT 'D-20：最后修改人',
  ADD COLUMN update_time DATETIME NULL COMMENT 'D-20：最后修改时间';

CREATE INDEX idx_qkp_source ON question_knowledge_point (source);

SELECT '--- 候选 C：人工结果不被自动任务覆盖的最小判据 ---' AS phase;
SELECT 'UPDATE question_knowledge_point SET relevance = ? WHERE id = ? AND source <> ''manual''' AS guard_sql,
       COUNT(*) AS existing_rows FROM question_knowledge_point;

-- ---------------------------------------------------------------------------
-- 7. 候选结构 D（D-21）：停用语义与"紧急撤下"的边界
--    设计：复用 t_question.status 表达可用性（1=正常, 2=停用, 3=紧急撤下），并补留痕列；
--    不新增"审批"相关结构（用户明确排除完整审批系统）
-- ---------------------------------------------------------------------------
ALTER TABLE t_question
  ADD COLUMN status_reason VARCHAR(255) NULL COMMENT 'D-21：停用/撤下原因',
  ADD COLUMN status_update_user INT NULL COMMENT 'D-21：状态变更人',
  ADD COLUMN status_update_time DATETIME NULL COMMENT 'D-21：状态变更时间';

SELECT '--- 候选 D：按状态过滤的最小查询（新组卷必须排除非正常题） ---' AS phase;
EXPLAIN SELECT COUNT(*) FROM t_question WHERE deleted = b'0' AND status = 1;

SELECT '--- 候选 D：状态分布（真实语义将由实现阶段写入） ---' AS phase;
SELECT status, COUNT(*) AS questions FROM t_question GROUP BY status ORDER BY status;

-- ---------------------------------------------------------------------------
-- 8. 结束后清理候选对象，让隔离库回到 V5 基线（可重复执行）
-- ---------------------------------------------------------------------------
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_content'
                 AND INDEX_NAME = 'idx_qc_question_current_version') > 0,
              'ALTER TABLE question_content DROP INDEX idx_qc_question_current_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE question_content DROP INDEX uk_question_content_current;
ALTER TABLE question_content DROP COLUMN current_question_id;
ALTER TABLE t_exam_paper_question_customer_answer DROP INDEX idx_epqca_question_content;
ALTER TABLE t_exam_paper_question_customer_answer DROP COLUMN question_content_id;
ALTER TABLE question_knowledge_point DROP INDEX idx_qkp_source;
ALTER TABLE question_knowledge_point DROP COLUMN source, DROP COLUMN update_user, DROP COLUMN update_time;
ALTER TABLE t_question DROP COLUMN status_reason, DROP COLUMN status_update_user, DROP COLUMN status_update_time;

DELETE FROM question_content WHERE question_id BETWEEN 900000 AND 999999;
DELETE FROM t_question WHERE id BETWEEN 900000 AND 999999;

SELECT '--- 清理后残留检查（应全为 0） ---' AS phase;
SELECT (SELECT COUNT(*) FROM t_question) AS questions,
       (SELECT COUNT(*) FROM question_content) AS contents,
       (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'question_content' AND COLUMN_NAME = 'current_question_id') AS candidate_a_column;
