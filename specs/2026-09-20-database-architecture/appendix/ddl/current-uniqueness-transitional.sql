-- DDL 候选 B —— current 唯一性（过渡路径，不新增列）
--
-- 目的：与候选 A 达成同样的“每题至多一个当前版本”约束，但不给 question_content 增加列，
--       便于在旧写入路径尚未全部切换时先上约束。对应 DB-05。
-- 适用范围：仅隔离/演练数据库。本轮不把本文件应用为正式迁移。
-- 前置条件：与候选 A 相同（A-03 的 multiple_current 为 0、A-04 无孤儿内容行）。
--
-- 做法：用函数索引替代生成列。MySQL 8.0.13+ 支持按表达式建索引；
--       表达式对非当前行求值为 NULL，NULL 不参与唯一性比较。
--       若目标 MySQL 版本低于 8.0.13，本候选不可用，改用候选 A。
--
-- 幂等性：重复执行会因索引已存在而失败，属预期；回滚见文件末尾。

-- 1. 先解除一个易被误读的索引（可选，但建议在演练中对比读写表现）：
--    idx_question_content_current (question_id, is_current) 是普通索引，
--    本身无约束力。是否保留取决于 A-06/索引验证的实际执行计划，不要凭直觉删。
--    保留原样时，本文件不需要这条语句。
--
-- 2. 唯一函数索引：每题至多一个 current。
ALTER TABLE question_content
  ADD UNIQUE KEY uk_question_content_current ((IF(is_current = b'1', question_id, NULL)));

-- 3. 验证：与候选 A 相同——A-03b 返回空集，重复 current 探针被拒绝。
--    探针必须使用已存在的 question_id，否则报错可能来自其他约束。
-- INSERT INTO question_content
--   (question_id, version, title, content_format, has_image, has_code, is_current)
-- VALUES (:qid, 9001, 'probe-a', 'plain', b'0', b'0', b'1'),
--        (:qid, 9002, 'probe-b', 'plain', b'0', b'0', b'1');
-- 通过口径：ERROR 1062 且消息包含 'uk_question_content_current'。
-- 清理：DELETE FROM question_content WHERE version IN (9001, 9002);

-- 4. 对比记录（应用候选 A 或 B 后各测一次，写进 validation.md 运行记录）：
--    - 插入新版本（clearCurrent + insert）的耗时差异；
--    - 读取当前版本的执行计划是否仍走 idx_question_content_current；
--    - 批量回填 backfillFromLegacy 的耗时差异；
--    - 写入路径是否出现新的死锁或锁等待。
--
-- 回滚：
-- ALTER TABLE question_content DROP INDEX uk_question_content_current;
