-- 只读数据审计 —— 题目领域
--
-- 用途：执行 plan.md 第 1 组的运行时审计，为 requirements.md 的 DB-01..DB-11 提供事实依据。
-- 性质：全部为只读 SELECT。本文件不含 INSERT / UPDATE / DELETE / DDL，可以在本地库直接执行。
-- 库名：默认 schema 为 `xzs`（见 application.yml 的 spring.datasource.url 默认值）；
--       数据库契约测试使用 `master408_v2`（见 DatabaseSchemaContractTest 的 datasource 覆盖）。
--       审计前必须确认本次连接的是哪一个库，并把结果记进报告。
--
-- 执行方式（示例，按实际库名替换）。PowerShell 不支持 `<` 重定向，使用管道：
--   Get-Content specs\2026-09-20-database-architecture\audit-queries.sql |
--     mysql -u root -p xzs
-- 或逐条在客户端执行、逐条粘贴结果。报告必须包含：代码提交、库名、结构版本、
-- 采样时间、每条 SQL 原文和真实结果。不得填写估算值。
-- 若客户端启用 ONLY_FULL_GROUP_BY 且个别聚合报错，先记录错误本身，不要改写口径来绕过。
--
-- 未执行前，本文件中任何数字都不存在；设计文档也不得引用未执行的结果。

-- ---------------------------------------------------------------------------
-- A-01  六张表规模与最近写入
-- 对应：DB-01、DB-07；事实源清单第 7 节“是否仍有活跃写入”
-- ---------------------------------------------------------------------------
SELECT 't_question' AS table_name,
       COUNT(*) AS total_rows,
       SUM(deleted = b'0') AS active_rows,
       SUM(deleted <> b'0') AS deleted_rows,
       MAX(create_time) AS last_create_time,
       NULL AS last_update_time
FROM t_question
UNION ALL
SELECT 'question_content',
       COUNT(*),
       SUM(is_current = b'1'),
       SUM(is_current <> b'1'),
       MAX(create_time),
       MAX(update_time)
FROM question_content
UNION ALL
SELECT 'question_source', COUNT(*), NULL, NULL, MAX(create_time), NULL FROM question_source
UNION ALL
SELECT 'question_asset',  COUNT(*), NULL, NULL, MAX(create_time), NULL FROM question_asset
UNION ALL
SELECT 'question_knowledge_point', COUNT(*), NULL, NULL, NULL, NULL
FROM question_knowledge_point
UNION ALL
SELECT 't_text_content', COUNT(*), NULL, NULL, MAX(create_time), NULL FROM t_text_content;

-- ---------------------------------------------------------------------------
-- A-02  旧主链完整性：有效题目是否都有可解析的旧内容
-- 对应：DB-01、DB-10；事实源清单“每张 t_question 是否存在 t_text_content”
-- 说明：t_question.info_text_content_id 可为 NULL，也可指向不存在的 t_text_content.id。
-- ---------------------------------------------------------------------------
SELECT COUNT(*)                                                        AS active_questions,
       SUM(q.info_text_content_id IS NULL)                             AS missing_legacy_id,
       SUM(q.info_text_content_id IS NOT NULL AND tc.id IS NULL)       AS dangling_legacy_id,
       SUM(tc.content IS NULL OR tc.content = '')                      AS empty_legacy_content,
       SUM(tc.id IS NOT NULL
           AND tc.content IS NOT NULL
           AND JSON_VALID(tc.content) = 0)                             AS legacy_not_json
FROM t_question q
LEFT JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0';

-- ---------------------------------------------------------------------------
-- A-03  current 完整性：有效题目按 current 数量分桶
-- 对应：DB-05、DB-11；“每题唯一当前版本”是否被数据库强制
-- 结论口径：cur = 0 表示无当前内容；cur > 1 表示唯一性只由应用保证。
-- ---------------------------------------------------------------------------
SELECT bucket, COUNT(*) AS questions FROM (
  SELECT q.id,
         CASE (SELECT COUNT(*) FROM question_content qc
                WHERE qc.question_id = q.id AND qc.is_current = b'1')
              WHEN 0 THEN '0_current'
              WHEN 1 THEN '1_current'
              ELSE 'multiple_current'
         END AS bucket
  FROM t_question q
  WHERE q.deleted = b'0'
) t
GROUP BY bucket
ORDER BY bucket;

-- A-03b  多 current 明细（供人工核对，限制 50 条）
SELECT qc.question_id, COUNT(*) AS current_rows, GROUP_CONCAT(qc.id ORDER BY qc.id) AS content_ids
FROM question_content qc
JOIN t_question q ON q.id = qc.question_id AND q.deleted = b'0'
WHERE qc.is_current = b'1'
GROUP BY qc.question_id
HAVING COUNT(*) > 1
ORDER BY current_rows DESC, qc.question_id
LIMIT 50;

-- ---------------------------------------------------------------------------
-- A-04  孤儿与版本异常
-- 对应：DB-05、DB-10；事实源清单 DB-TD-Q05
-- 说明：question_content 没有外键；question_knowledge_point 与 question_source
--       同样只靠普通索引和应用约定。
-- ---------------------------------------------------------------------------
SELECT 'content_without_question' AS anomaly, COUNT(*) AS rows_found
FROM question_content qc
LEFT JOIN t_question q ON q.id = qc.question_id
WHERE q.id IS NULL
UNION ALL
SELECT 'content_version_not_positive', COUNT(*)
FROM question_content WHERE version < 1
UNION ALL
SELECT 'content_version_duplicated', COUNT(*) FROM (
  SELECT question_id, version FROM question_content
  GROUP BY question_id, version HAVING COUNT(*) > 1
) d
UNION ALL
SELECT 'source_without_question', COUNT(*)
FROM question_source s LEFT JOIN t_question q ON q.id = s.question_id
WHERE q.id IS NULL
UNION ALL
SELECT 'source_duplicated_per_question', COUNT(*) FROM (
  SELECT question_id FROM question_source GROUP BY question_id HAVING COUNT(*) > 1
) d
UNION ALL
SELECT 'kp_relation_without_question', COUNT(*)
FROM question_knowledge_point kp LEFT JOIN t_question q ON q.id = kp.question_id
WHERE q.id IS NULL
UNION ALL
SELECT 'kp_relation_without_point', COUNT(*)
FROM question_knowledge_point kp LEFT JOIN knowledge_point k ON k.id = kp.knowledge_point_id
WHERE k.id IS NULL
UNION ALL
SELECT 'asset_without_question', COUNT(*)
FROM question_asset a LEFT JOIN t_question q ON q.id = a.question_id
WHERE q.id IS NULL;

-- ---------------------------------------------------------------------------
-- A-05  版本连续性：断号与最大版本和 current 的关系
-- 对应：DB-02、DB-05
-- 说明：断号本身不一定是缺陷（可能来自历史回填或删除），必须逐例分析原因。
-- ---------------------------------------------------------------------------
SELECT question_id,
       COUNT(*)      AS versions,
       MIN(version)  AS min_version,
       MAX(version)  AS max_version,
       MAX(version) - COUNT(*) AS missing_numbers
FROM question_content
GROUP BY question_id
HAVING MIN(version) <> 1 OR MAX(version) <> COUNT(*)
ORDER BY missing_numbers DESC, question_id
LIMIT 50;

-- A-05b  当前版本号是否等于该题最大版本号
SELECT COUNT(*) AS current_not_latest FROM (
  SELECT qc.question_id
  FROM question_content qc
  WHERE qc.is_current = b'1'
    AND qc.version <> (SELECT MAX(x.version) FROM question_content x
                       WHERE x.question_id = qc.question_id)
) t;

-- ---------------------------------------------------------------------------
-- A-06  新旧内容一致性（分层统计）
-- 对应：DB-02、DB-03、DB-04
-- 说明：禁止直接比较 question_content.source_hash 与旧 JSON，两者生成算法不同
--       （回填用 SHA2(CONCAT_WS(...))，标准编辑从 items 序列化）。
--       本查询先统计可比对的分层规模，再在报告中按样本给出规范化对照结论。
-- ---------------------------------------------------------------------------
SELECT q.question_type,
       COUNT(*)                                                        AS compared_questions,
       SUM(qc.title IS NOT NULL AND qc.title_text IS NULL)             AS missing_title_text,
       SUM(qc.analysis IS NOT NULL AND qc.analysis_text IS NULL)       AS missing_analysis_text,
       SUM(qc.options IS NULL OR qc.options = '')                      AS missing_options,
       SUM(qc.correct_answer IS NULL OR qc.correct_answer = '')        AS missing_correct_answer
FROM t_question q
JOIN question_content qc ON qc.question_id = q.id AND qc.is_current = b'1'
WHERE q.deleted = b'0'
GROUP BY q.question_type
ORDER BY q.question_type;

-- A-06b  指定题目的新旧同屏抽样（把 :question_id 换成 A-06 里选出的真实题目）
-- SELECT q.id, q.question_type,
--        q.title        AS legacy_title_col,
--        q.options      AS legacy_options_col,
--        q.correct_answer AS legacy_correct_col,
--        q.analysis     AS legacy_analysis_col,
--        qc.version, qc.title, qc.options, qc.correct_answer, qc.analysis,
--        qc.source_hash, qc.legacy_text_content_id
-- FROM t_question q
-- JOIN question_content qc ON qc.question_id = q.id AND qc.is_current = b'1'
-- WHERE q.id = :question_id;

-- ---------------------------------------------------------------------------
-- A-07  富文本与资源保真（分层样本，供人工对照）
-- 对应：DB-04、DB-07
-- ---------------------------------------------------------------------------
SELECT 'has_image_flag' AS layer, COUNT(*) AS rows_found
FROM question_content WHERE is_current = b'1' AND has_image = b'1'
UNION ALL
SELECT 'has_code_flag', COUNT(*)
FROM question_content WHERE is_current = b'1' AND has_code = b'1'
UNION ALL
SELECT 'title_contains_img_tag', COUNT(*)
FROM question_content WHERE is_current = b'1' AND title LIKE '%<img%'
UNION ALL
SELECT 'title_contains_table', COUNT(*)
FROM question_content WHERE is_current = b'1' AND title LIKE '%<table%'
UNION ALL
SELECT 'title_contains_katex', COUNT(*)
FROM question_content WHERE is_current = b'1'
  AND (title LIKE '%katex%' OR title LIKE '%\\\\(' OR title LIKE '%$$%')
UNION ALL
SELECT 'legacy_images_column_not_null', COUNT(*)
FROM t_question WHERE deleted = b'0' AND images IS NOT NULL AND images <> ''
UNION ALL
SELECT 'question_asset_rows', COUNT(*) FROM question_asset;

-- A-07b  待人工比对的富文本样本（限制 20 条）
SELECT qc.question_id, qc.has_image, qc.has_code,
       LEFT(COALESCE(qc.title, ''), 200)      AS title_head,
       LEFT(COALESCE(qc.title_text, ''), 200) AS title_text_head
FROM question_content qc
WHERE qc.is_current = b'1'
  AND (qc.has_image = b'1' OR qc.has_code = b'1'
       OR qc.title LIKE '%<table%' OR qc.title LIKE '%<img%')
ORDER BY qc.question_id
LIMIT 20;

-- ---------------------------------------------------------------------------
-- A-08  来源独有信息量：question_source 是可重建投影还是独有事实
-- 对应：DB-07
-- 说明：source_year / source_question_no 可从 t_question 同名旧列派生；
--       URL、页码、批次和 metadata 不可派生。覆盖率决定该表能否降级。
-- ---------------------------------------------------------------------------
SELECT COUNT(*)                                                        AS source_rows,
       COUNT(DISTINCT question_id)                                     AS questions_covered,
       SUM(source_year IS NOT NULL)                                    AS with_year,
       SUM(source_question_no IS NOT NULL)                             AS with_question_no,
       SUM(raw_ref IS NOT NULL AND raw_ref <> '')                      AS with_raw_ref,
       SUM(page_no IS NOT NULL AND page_no <> '')                      AS with_page_no,
       SUM(crawler_batch IS NOT NULL AND crawler_batch <> '')          AS with_crawler_batch,
       SUM(ocr_batch IS NOT NULL AND ocr_batch <> '')                  AS with_ocr_batch,
       SUM(metadata IS NOT NULL AND metadata <> '')                    AS with_metadata
FROM question_source;

-- A-08b  旧列仍有值但没有来源记录的题目
SELECT COUNT(*) AS active_questions_without_source_row
FROM t_question q
LEFT JOIN question_source s ON s.question_id = q.id
WHERE q.deleted = b'0' AND s.id IS NULL;

-- ---------------------------------------------------------------------------
-- A-09  软删除题目的下游泄漏面
-- 对应：DB-08、DB-11；事实源清单 DB-TD-Q03
-- ---------------------------------------------------------------------------
SELECT 'deleted_questions' AS metric, COUNT(*) AS rows_found
FROM t_question WHERE deleted <> b'0'
UNION ALL
SELECT 'deleted_but_has_current_content', COUNT(DISTINCT qc.question_id)
FROM question_content qc
JOIN t_question q ON q.id = qc.question_id
WHERE q.deleted <> b'0' AND qc.is_current = b'1'
UNION ALL
-- 注意：下面这一条依赖 rag_document.source_ref 与题目 ID 的可比性，执行前必须先看 A-11b。
-- 若 source_ref 不是纯数字题目 ID，本行结果无意义，应改为按实际格式连接或标记为「不适用」。
SELECT 'deleted_but_indexed_as_rag_document', COUNT(*)
FROM rag_document d
WHERE d.document_type = 'question'
  AND d.status <> 'disabled'
  AND EXISTS (SELECT 1 FROM t_question q
              WHERE q.deleted <> b'0'
                AND CAST(d.source_ref AS CHAR) = CAST(q.id AS CHAR))
UNION ALL
SELECT 'deleted_but_has_kp_relation', COUNT(DISTINCT kp.question_id)
FROM question_knowledge_point kp
JOIN t_question q ON q.id = kp.question_id
WHERE q.deleted <> b'0'
UNION ALL
SELECT 'deleted_but_referenced_by_exam_answer', COUNT(DISTINCT a.question_id)
FROM t_exam_paper_question_customer_answer a
JOIN t_question q ON q.id = a.question_id
WHERE q.deleted <> b'0';

-- ---------------------------------------------------------------------------
-- A-10  历史考试的内容引用面
-- 对应：DB-06、DB-11；事实源清单“历史考试”检查项
-- 说明：答题记录同时保存 question_id、question_text_content_id 和 text_content_id，
--       当前读取路径按 QuestionServiceImpl.getQuestionEditRequestVM 先读旧 JSON、
--       再用 question_content.current 覆盖题干与解析。是否有答案/评分从新表读取，
--       必须在代码走查中确认，不能由本查询推断。
-- ---------------------------------------------------------------------------
SELECT COUNT(*)                                                          AS answer_rows,
       COUNT(DISTINCT question_id)                                       AS distinct_questions,
       SUM(question_text_content_id IS NULL)                             AS missing_question_tc_id,
       SUM(text_content_id IS NULL)                                      AS missing_answer_tc_id,
       SUM(NOT EXISTS (SELECT 1 FROM t_text_content tc
                       WHERE tc.id = a.question_text_content_id))        AS dangling_question_tc_id,
       SUM(NOT EXISTS (SELECT 1 FROM question_content qc
                       WHERE qc.question_id = a.question_id
                         AND qc.legacy_text_content_id = a.question_text_content_id)) AS no_content_row_by_legacy_link
FROM t_exam_paper_question_customer_answer a;

-- A-10b  已被修改过的题目出现在考试记录里（潜在“历史答案漂移”候选）
SELECT a.question_id,
       COUNT(*)                       AS answer_rows,
       (SELECT COUNT(*) FROM question_content qc WHERE qc.question_id = a.question_id) AS content_versions
FROM t_exam_paper_question_customer_answer a
GROUP BY a.question_id
HAVING content_versions > 1
ORDER BY content_versions DESC, a.question_id
LIMIT 50;

-- ---------------------------------------------------------------------------
-- A-11  RAG 投影的同步状态
-- 对应：DB-08、DB-09
-- 说明：题目类文档的 source_ref 与 question_content 的连接方式必须先用
--       SELECT id, title, source_ref, content_hash, status FROM rag_document
--       WHERE document_type = 'question' LIMIT 20; 确认，再写对账查询。
-- ---------------------------------------------------------------------------
SELECT d.status, COUNT(*) AS documents, SUM(d.content_hash IS NULL) AS missing_content_hash
FROM rag_document d
WHERE d.document_type = 'question'
GROUP BY d.status
ORDER BY d.status;

-- A-11b  题目类文档样本：确认 source_ref 到底存什么
SELECT d.id, d.title, d.source_type, d.source_name, d.source_ref,
       d.content_hash, d.version, d.status, d.update_time
FROM rag_document d
WHERE d.document_type = 'question'
ORDER BY d.id
LIMIT 20;

-- A-11c  切片与向量写入状态
SELECT e.status, COUNT(*) AS embeddings,
       MIN(e.update_time) AS oldest_update, MAX(e.update_time) AS newest_update
FROM rag_embedding e
JOIN rag_chunk c ON c.id = e.chunk_id
JOIN rag_document d ON d.id = c.document_id
WHERE d.document_type = 'question'
GROUP BY e.status
ORDER BY e.status;

-- ---------------------------------------------------------------------------
-- A-12  并发写入观察（只读，不改数据）
-- 对应：DB-05；配合 design-test-plan.md 的 T4/T5 在隔离库执行
-- 说明：以下两条必须在显式事务中按顺序手动执行，观察第二条的结果，然后 ROLLBACK。
--       不要在共享库上执行任何 COMMIT。
-- ---------------------------------------------------------------------------
-- 会话 1：START TRANSACTION;
--         INSERT INTO question_content
--           (question_id, version, title, options, correct_answer, analysis,
--            title_text, analysis_text, content_format, has_image, has_code,
--            is_current)
--         VALUES (:qid, :v, 'probe-1', NULL, NULL, NULL, NULL, NULL, 'plain', b'0', b'0', b'0');
-- 会话 2：START TRANSACTION;
--         INSERT INTO question_content (...) VALUES (:qid, :v, 'probe-2', ...);
--         -- 观察：第二条报 ERROR 1062 还是插入成功。
--         --   ERROR 1062 → 唯一键是当前唯一防线，应用必须先读版本再重试。
--         --   插入成功   → uk_question_content_version 未生效，必须立即停止并报告。
-- 两个会话都 ROLLBACK;
SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = database()
  AND TABLE_NAME = 'question_content';

SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = database()
  AND TABLE_NAME = 'question_content'
GROUP BY INDEX_NAME, NON_UNIQUE
ORDER BY INDEX_NAME;

-- ---------------------------------------------------------------------------
-- A-13  隔离库 current 唯一性约束验证（不在生产/共享库执行）
-- 对应：DB-05，DDL 变体见 ddl/
-- 说明：先应用 ddl/current-uniqueness-strict.sql 或 -transitional.sql，
--       再执行下面两条，确认重复 current 真的被数据库拒绝。
-- ---------------------------------------------------------------------------
-- INSERT INTO question_content
--   (question_id, version, title, content_format, has_image, has_code, is_current)
-- VALUES (:qid, 9001, 'probe-a', 'plain', b'0', b'0', b'1'),
--        (:qid, 9002, 'probe-b', 'plain', b'0', b'0', b'1');
-- 预期：ERROR 1062（Duplicate entry ... for key 'uk_question_content_current'）
-- 随后删除探针行：DELETE FROM question_content WHERE version IN (9001, 9002);
