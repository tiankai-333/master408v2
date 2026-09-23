-- 批次 5 迁移演练 —— 隔离库代表性样本 + 分批 + 幂等 + 故障注入 + 对账
--
-- 适用范围：仅 master408_design_test；**不修改共享库、不修改业务代码**。
-- 演练用分批复刻：真实语句 `QuestionContentMapper.xml:59-87` 没有批次参数（全局回填），
--   本脚本的批次副本与它语义一致，只额外加 `AND q.id BETWEEN ... AND ...`；
--   真实实现若采用批次，应把该条件做进 SQL 或按主键分页。
-- 结尾自动清理并回到 V5 基线；预期失败的"故障注入"语句见文件末尾附录（单独执行）。

-- ---------------------------------------------------------------------------
-- 0. 守卫与清理
-- ---------------------------------------------------------------------------
SELECT IF(DATABASE() = 'master408_design_test', 'OK: isolated database',
          CONCAT('ABORT: wrong database = ', DATABASE())) AS guard;

DELETE FROM question_content WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM question_knowledge_point WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM question_source WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM t_text_content WHERE id BETWEEN 910100 AND 910199;
DELETE FROM t_question WHERE id BETWEEN 910000 AND 910999;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'question_content' AND COLUMN_NAME = 'current_question_id') > 0,
              'ALTER TABLE question_content DROP COLUMN current_question_id', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 1. 代表性样本（覆盖审计发现的各种形态）
-- ---------------------------------------------------------------------------
INSERT INTO t_text_content (id, content)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 21)
SELECT 910100 + n, CONCAT('{"titleContent":"题干 ', n, '","analyze":"解析 ', n, '","questionItemObjects":[{"prefix":"A","content":"选项 A"}],"correct":"A"}')
FROM seq;

INSERT INTO t_question (id, subject_id, question_type, title, correct, info_text_content_id, deleted)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 21)
SELECT 910000 + n, 1, 1, CONCAT('legacy-title-', n), 'A', 910100 + n, IF(n = 21, b'1', b'0') FROM seq;

-- 已有一版的题目（幂等检查）
INSERT INTO question_content (question_id, version, title, content_format, is_current)
VALUES (910019, 1, 'preexisting-v1', 'plain', b'1');

-- 陷阱题：缺 version=1，但已有 version=2 且为 current（T4-3 的形态）
INSERT INTO question_content (question_id, version, title, content_format, is_current)
VALUES (910020, 2, 'trap-v2-current', 'plain', b'1');

-- 孤儿知识点关系（指向样本区间内并不存在的题目，便于被对账查询命中）
INSERT INTO question_knowledge_point (question_id, knowledge_point_id) VALUES (910099, 1);

SELECT '样本就绪' AS phase,
       (SELECT COUNT(*) FROM t_question WHERE id BETWEEN 910000 AND 910999) AS questions,
       (SELECT COUNT(*) FROM t_question WHERE id BETWEEN 910000 AND 910999 AND deleted = b'0') AS active,
       (SELECT COUNT(*) FROM question_content WHERE question_id BETWEEN 910000 AND 910999) AS content_rows;

-- ---------------------------------------------------------------------------
-- 2. 阶段 1：扩展结构（候选 A）
-- ---------------------------------------------------------------------------
ALTER TABLE question_content
  ADD COLUMN current_question_id INT GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL;
ALTER TABLE question_content ADD UNIQUE KEY uk_question_content_current (current_question_id);
SELECT '阶段 1 完成：候选 A 已应用' AS phase;

-- ---------------------------------------------------------------------------
-- 3. 演练前对账（基线）
-- ---------------------------------------------------------------------------
SELECT '基线对账' AS phase;
SELECT bucket, COUNT(*) AS questions FROM (
  SELECT q.id, CASE (SELECT COUNT(*) FROM question_content qc
                     WHERE qc.question_id = q.id AND qc.is_current = b'1')
                 WHEN 0 THEN '0_current' WHEN 1 THEN '1_current' ELSE 'multiple_current' END AS bucket
  FROM t_question q WHERE q.deleted = b'0' AND q.id BETWEEN 910000 AND 910999) t
GROUP BY bucket ORDER BY bucket;
SELECT COUNT(*) AS missing_version_one_but_has_current FROM t_question q
WHERE q.deleted = b'0' AND q.id BETWEEN 910000 AND 910999
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1)
  AND EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.is_current = b'1');

-- ---------------------------------------------------------------------------
-- 4. 批次 1：干净题目（910001-910010）回填
-- ---------------------------------------------------------------------------
INSERT INTO question_content (question_id, version, title, options, correct_answer, analysis,
                              title_text, analysis_text, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1,
       COALESCE(q.title, IF(JSON_VALID(tc.content), JSON_UNQUOTE(JSON_EXTRACT(tc.content, '$.titleContent')), NULL), tc.content),
       q.options, COALESCE(q.correct_answer, q.correct),
       COALESCE(q.analysis, IF(JSON_VALID(tc.content), JSON_UNQUOTE(JSON_EXTRACT(tc.content, '$.analyze')), NULL)),
       q.title_text, q.analysis_text, COALESCE(q.content_format, 'html'),
       COALESCE(q.has_image, b'0'), COALESCE(q.has_code, b'0'), q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(q.title, ''), COALESCE(q.options, ''),
                      COALESCE(q.correct_answer, q.correct, ''), COALESCE(q.analysis, ''),
                      COALESCE(tc.content, '')), 256),
       b'1'
FROM t_question q LEFT JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910001 AND 910010
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
SELECT ROW_COUNT() AS batch1_inserted;
SELECT COUNT(*) AS batch1_rows_now FROM question_content WHERE question_id BETWEEN 910001 AND 910010;

-- 4.1 幂等：重跑同一批次
INSERT INTO question_content (question_id, version, title, options, correct_answer, analysis,
                              title_text, analysis_text, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1,
       COALESCE(q.title, IF(JSON_VALID(tc.content), JSON_UNQUOTE(JSON_EXTRACT(tc.content, '$.titleContent')), NULL), tc.content),
       q.options, COALESCE(q.correct_answer, q.correct),
       COALESCE(q.analysis, IF(JSON_VALID(tc.content), JSON_UNQUOTE(JSON_EXTRACT(tc.content, '$.analyze')), NULL)),
       q.title_text, q.analysis_text, COALESCE(q.content_format, 'html'),
       COALESCE(q.has_image, b'0'), COALESCE(q.has_code, b'0'), q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(q.title, ''), COALESCE(q.options, ''),
                      COALESCE(q.correct_answer, q.correct, ''), COALESCE(q.analysis, ''),
                      COALESCE(tc.content, '')), 256),
       b'1'
FROM t_question q LEFT JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910001 AND 910010
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
SELECT ROW_COUNT() AS batch1_rerun_inserted;

-- ---------------------------------------------------------------------------
-- 5. 批次 2 中断与断点续跑（910011-910018 分两段）
-- ---------------------------------------------------------------------------
INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1, tc.content, 'html', b'0', b'0', q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(tc.content, '')), 256), b'1'
FROM t_question q JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910011 AND 910014
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
SELECT ROW_COUNT() AS segment_a_inserted;
-- 模拟中断后重跑"最后一批"：断点记录=910014，因此从 910011 重跑也应无新增
INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1, tc.content, 'html', b'0', b'0', q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(tc.content, '')), 256), b'1'
FROM t_question q JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910011 AND 910014
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
SELECT ROW_COUNT() AS segment_a_resume_inserted;
INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1, tc.content, 'html', b'0', b'0', q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(tc.content, '')), 256), b'1'
FROM t_question q JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910015 AND 910018
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
SELECT ROW_COUNT() AS segment_b_inserted;

-- ---------------------------------------------------------------------------
-- 6. 缓解措施：跳过"缺 v1 但已有 current"的题目，并把它们登记进人工清单
--    （不加这一步，回填会因 uk_question_content_current 报 1062，见文件末尾附录）
-- ---------------------------------------------------------------------------
SELECT q.id AS trap_question_id, 'skip: missing version=1 but has current' AS reason
FROM t_question q
WHERE q.deleted = b'0' AND q.id BETWEEN 910000 AND 910999
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1)
  AND EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.is_current = b'1');

INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code,
                              legacy_text_content_id, source_hash, is_current)
SELECT q.id, 1, tc.content, 'html', b'0', b'0', q.info_text_content_id,
       SHA2(CONCAT_WS('|', q.id, COALESCE(tc.content, '')), 256), b'1'
FROM t_question q JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0' AND q.id BETWEEN 910001 AND 910018
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1)
  AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.is_current = b'1');
SELECT ROW_COUNT() AS mitigated_inserted;

-- ---------------------------------------------------------------------------
-- 7. 最终对账
-- ---------------------------------------------------------------------------
SELECT '最终对账' AS phase;
SELECT bucket, COUNT(*) AS questions FROM (
  SELECT q.id, CASE (SELECT COUNT(*) FROM question_content qc
                     WHERE qc.question_id = q.id AND qc.is_current = b'1')
                 WHEN 0 THEN '0_current' WHEN 1 THEN '1_current' ELSE 'multiple_current' END AS bucket
  FROM t_question q WHERE q.deleted = b'0' AND q.id BETWEEN 910000 AND 910999) t
GROUP BY bucket ORDER BY bucket;
SELECT (SELECT COUNT(*) FROM t_question WHERE deleted = b'0' AND id BETWEEN 910000 AND 910999) AS active_questions,
       (SELECT COUNT(DISTINCT question_id) FROM question_content WHERE question_id BETWEEN 910000 AND 910999) AS covered,
       (SELECT COUNT(*) FROM question_content WHERE question_id BETWEEN 910000 AND 910999) AS content_rows;
SELECT COUNT(*) AS version_gaps FROM (
  SELECT question_id FROM question_content WHERE question_id BETWEEN 910000 AND 910999
  GROUP BY question_id HAVING MIN(version) <> 1 OR MAX(version) <> COUNT(*)) t;
SELECT COUNT(*) AS soft_deleted_got_content FROM question_content qc
  JOIN t_question q ON q.id = qc.question_id WHERE q.deleted <> b'0' AND q.id BETWEEN 910000 AND 910999;
SELECT COUNT(*) AS orphan_kp_relations FROM question_knowledge_point kp
  LEFT JOIN t_question q ON q.id = kp.question_id
  WHERE q.id IS NULL AND kp.question_id BETWEEN 910000 AND 910999;

-- ---------------------------------------------------------------------------
-- 8. 清理，回到 V5 基线
-- ---------------------------------------------------------------------------
DELETE FROM question_knowledge_point WHERE question_id = 910099;
DELETE FROM question_content WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM question_knowledge_point WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM question_source WHERE question_id BETWEEN 910000 AND 910999;
DELETE FROM t_text_content WHERE id BETWEEN 910100 AND 910199;
DELETE FROM t_question WHERE id BETWEEN 910000 AND 910999;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'question_content' AND INDEX_NAME = 'uk_question_content_current') > 0,
              'ALTER TABLE question_content DROP INDEX uk_question_content_current', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'question_content' AND COLUMN_NAME = 'current_question_id') > 0,
              'ALTER TABLE question_content DROP COLUMN current_question_id', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SELECT '清理完成（应全为 0）' AS phase,
       (SELECT COUNT(*) FROM t_question) AS questions,
       (SELECT COUNT(*) FROM question_content) AS contents;

-- ---------------------------------------------------------------------------
-- 附录：故障注入（单独执行，**预期 ERROR 1062**）
--   不先跳过"缺 v1 但已有 current"的题目时，回填会撞 uk_question_content_current：
--   INSERT INTO question_content (question_id, version, title, content_format, has_image, has_code, is_current)
--   SELECT q.id, 1, tc.content, 'html', b'0', b'0', b'1'
--   FROM t_question q JOIN t_text_content tc ON tc.id = q.info_text_content_id
--   WHERE q.deleted = b'0' AND q.id = 910020
--     AND NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = q.id AND qc.version = 1);
--   含义：批次内的这一条会让**整个语句**失败并被回滚 → 必须先普查并跳过，或改为逐题事务。
-- ---------------------------------------------------------------------------
