-- DDL 候选 A —— current 唯一性（标准路径）
--
-- 目的：把“每题至多一个当前版本”从应用约定升级为数据库约束，对应 DB-05。
-- 适用范围：仅隔离/演练数据库。本轮不把本文件应用为正式迁移。
-- 前置条件（任一不满足则停止，不要先建约束）：
--   1. audit-queries.sql 的 A-03 结果中 multiple_current 为 0；
--   2. A-04 的 content_without_question 为 0（否则该题内容行会让生成列与索引行为难以解释）；
--   3. 已记录当前 question_content 的索引结构（A-12 的第二条查询）。
--
-- 关键点：现有 uk_question_content_version (question_id, version) 只保证版本号不重复，
--         它不能阻止两个不同 version 的行同时 is_current = b'1'。
--         idx_question_content_current (question_id, is_current) 是普通索引，也不阻止。
--         Mapper 的 clearCurrent + insert 之间没有数据库级互斥，因此并发下会出现
--         “0 个 current”或（在缺少唯一键时）“多个 current”。
--
-- 幂等性：重复执行会因列/索引已存在而失败，属预期；回滚见文件末尾。

-- 1. 生成列：只对当前行取 question_id，非当前行为 NULL。
--    MySQL 唯一索引允许多个 NULL，因此非当前行不受约束。
ALTER TABLE question_content
  ADD COLUMN current_question_id INT
    GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL
    COMMENT '生成列：仅为当前版本填充 question_id，用于强制每题至多一个 current';

-- 2. 唯一约束：每题至多一个 current。
ALTER TABLE question_content
  ADD UNIQUE KEY uk_question_content_current (current_question_id);

-- 3. 验证：应在 A-03b 无结果的基础上继续返回空集。
-- SELECT question_id, COUNT(*) FROM question_content
-- WHERE is_current = b'1' GROUP BY question_id HAVING COUNT(*) > 1;

-- 4. 验证：重复 current 必须被拒绝。
--    探针必须使用**已存在**的 question_id（先 `SELECT id FROM t_question WHERE deleted = b'0' LIMIT 1;`），
--    否则报错可能来自其他约束而不是本唯一键。
-- INSERT INTO question_content
--   (question_id, version, title, content_format, has_image, has_code, is_current)
-- VALUES (:qid, 9001, 'probe-a', 'plain', b'0', b'0', b'1'),
--        (:qid, 9002, 'probe-b', 'plain', b'0', b'0', b'1');
-- 通过口径：错误为 ERROR 1062，且消息包含 'uk_question_content_current'。
--           同一语句内第一条插入会成功、第二条失败，因此该 statement 整体报错。
--           如果错误是其他约束或两条都成功，本候选不成立，记录原始错误后停止。
-- 清理：DELETE FROM question_content WHERE version IN (9001, 9002);

-- 5. 应用侧仍需配合（不能只靠约束）：
--    - clearCurrent 与 insert 必须在同一事务内，且失败后整体回滚；
--    - 并发编辑竞争 uk_question_content_version 或本唯一键时，服务层应把
--      DuplicateKeyException 翻译为“该题已被他人更新，请刷新后重试”，而不是返回 500；
--    - 版本号生成仍为 SELECT MAX(version) + 1，本约束不解决它的竞态，只让竞态可见。
--
-- 回滚：
-- ALTER TABLE question_content DROP INDEX uk_question_content_current;
-- ALTER TABLE question_content DROP COLUMN current_question_id;
