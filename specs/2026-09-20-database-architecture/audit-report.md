# 只读数据审计报告 —— 题目领域事实源

状态：**第一轮只读审计已执行**（A-01 至 A-11c 及 A-12 的结构查询）。A-12 的手工并发写入探针、
A-13 的隔离库约束验证、A-06/A-07 的规范化人工对照**未执行**，见第 14 节。

本报告只写库名、结构与采样信息，不写连接口令。每个数字都能追溯到 A 编号与本节内的 SQL 原文。

## 0. 报告头

| 项 | 值 |
| --- | --- |
| 执行日期 | 2026-09-21 |
| 采样时刻 | 2026-09-21 20:15 (+08:00) |
| 代码提交 | `c2c0a84`（工作区另有 19 项未提交改动；本次审计**未修改任何数据**） |
| **审计库** | **`xzs`** |
| 结构版本 | Flyway V5（`flyway_schema_history` 最新成功版本 = 5，安装于 2026-07-28 09:57:32） |
| 对照库 | `master408_v2`：结构与 `xzs` 同为 V5，但题目领域 8 张表**全部 0 行**，故不作为审计目标 |
| 执行方式 | `mysql --default-character-set=utf8mb4 --table -e "source specs/2026-09-20-database-architecture/audit-queries.sql" xzs` |
| 原始输出 | `.data/audit-raw-xzs.txt`（158 行，未删改） |
| 只读性 | `audit-queries.sql` 中全部可执行语句为 `SELECT`；A-12/A-13 的写入探针在原文件中是注释，本次未执行 |

库名选择的依据（A-01 对照）：

| 表 | `xzs` | `master408_v2` |
| --- | ---: | ---: |
| `t_question` | 6613 | 0 |
| `question_content` | 6613 | 0 |
| `question_source` | 6613 | 0 |
| `question_asset` | 0 | 0 |
| `question_knowledge_point` | 6033 | 0 |
| `t_text_content` | 6903 | 0 |
| `rag_document` | 6732 | 0 |
| `t_exam_paper_question_customer_answer` | 108 | 0 |

## 1. 结论摘要（先看这一节）

1. **旧链路（`t_question` + `t_text_content`）"结构链接"完整，但内容完整性未验证**：6613 道有效题
   全部有可解析的旧 JSON，无缺 id、无悬空 id、无空内容、无非 JSON（A-02）。
   ⚠️ **2026-09-21 修正**：A-02 只验证了 **id 链接与 JSON 合法性**，**没有验证内容是否完整**。
   样本核对（`sample-2024-q43.md`）发现 `t_text_content.id=107082` 的 `titleContent` 在词中间截断
   （300 字符，尾部 `rd 000 0011 add 指令 sll`），且该截断值**同时存在于 `t_question.title` 与
   `question_content.title`**——即"结构完整"不等于"内容完整"，且截断会传导到新表。
   全库还发现旧 JSON 的键集只有 `["analyze","titleContent","questionItemObjects"]`
   （6613/6613），**没有 images 键、没有答案键**。详见 TD-017。
2. **新链路（`question_content`）与旧链路一一对应**：6613 行，每题恰好 1 个 `is_current`，
   无 0 个、无多个、无断号、`current` 恒为最大版本（A-03/A-05/A-05b）。
3. **但"至多一个 current"没有任何数据库级保障**：`question_content` 上只有主键与
   `uk_question_content_version (question_id, version)`；`idx_question_content_current` 是**非唯一**索引
   （A-12）。当前数据正确，靠的是应用逻辑，不是约束。
4. **`question_source` 含不可派生的独有事实**：`raw_ref` 与 `metadata` 覆盖率 100%，
   `source_year` 94.3%；而 `page_no`/`crawler_batch`/`ocr_batch` 全空（A-08）。不能简单降级为投影。
5. **资源事实不在库内**：`question_asset` 空表、`legacy images` 列全空、题干里没有内嵌
   `<img>`/`<table>`/KaTeX；图文题以 `<div class="question-html-ref" data-src="...">` **外链 + 降级文本**
   的形式存在（A-07/A-07b）。DB-04/DB-07 的"保真"必须回答"外部 HTML 文件才是事实源"这件事。
6. **软删除语义无法用真实数据验证**：`t_question.deleted` 非 0 的行数为 **0**，因此 A-09 的整组
   "软删除下游泄漏"检查全部返回 0——那是**无样本**，不是"没有问题"。
7. **存在 643 条孤儿知识点关系**：`question_knowledge_point` 指向不存在的 `t_question.id`（A-04）。
8. **真实数据只有两种题型**：单选 3780、简答 2833；多选/判断/填空**零行**（A-06 及补充查询）。
   简答题的 `correct_answer` 100% 为空。
9. **RAG 投影：`ready` 不等于可检索**：6613 个题目文档全部 `ready` 且都有切片，但只有 3774 个题目文档
   具备**已记录的** `indexed` 向量，**2839 个（42.9%）没有已记录的向量索引**；另有 40 条 `failed`
   向量写入（A-11/A-11c 及补充）。
   **措辞限定（2026-09-21 修正）**：本项只证明"缺少已记录的向量索引"，**不能**据此称这些题目
   "完全不可检索"——词法召回（`rag_chunk.content_text` + ngram）、向量召回（`rag_embedding`）与
   最终返回过滤（`status`/`enabled`/停用题）是三条不同链路，必须分别验证；`ready` 也不能作为
   这三种能力的统一证明。详见第 12 节的口径说明。
10. **历史考试暂无漂移候选**：108 条答题、67 道题，`question_text_content_id` 全部能通过
    `legacy_text_content_id` 找到内容行；没有任何"多版本题目"出现在答题记录里（A-10/A-10b）。

## 2. A-01 六张表规模与最近写入

SQL（原文）：

```sql
SELECT 't_question' AS table_name, COUNT(*) AS total_rows, SUM(deleted = b'0') AS active_rows,
       SUM(deleted <> b'0') AS deleted_rows, MAX(create_time) AS last_create_time, NULL AS last_update_time
FROM t_question
UNION ALL SELECT 'question_content', COUNT(*), SUM(is_current = b'1'), SUM(is_current <> b'1'),
       MAX(create_time), MAX(update_time) FROM question_content
UNION ALL SELECT 'question_source', COUNT(*), NULL, NULL, MAX(create_time), NULL FROM question_source
UNION ALL SELECT 'question_asset', COUNT(*), NULL, NULL, MAX(create_time), NULL FROM question_asset
UNION ALL SELECT 'question_knowledge_point', COUNT(*), NULL, NULL, NULL, NULL FROM question_knowledge_point
UNION ALL SELECT 't_text_content', COUNT(*), NULL, NULL, MAX(create_time), NULL FROM t_text_content;
```

真实结果：

| table_name | total_rows | active_rows | deleted_rows | last_create_time | last_update_time |
| --- | ---: | ---: | ---: | --- | --- |
| t_question | 6613 | 6613 | **0** | 2026-05-27 20:41:30 | — |
| question_content | 6613 | 6613 (`is_current`) | 0 | 2026-05-27 20:41:30 | 2026-05-27 20:41:30 |
| question_source | 6613 | — | — | 2026-05-27 20:41:30 | — |
| question_asset | **0** | — | — | NULL | — |
| question_knowledge_point | 6033 | — | — | NULL | — |
| t_text_content | 6903 | — | — | 2026-07-24 19:05:13 | — |

结论：题目领域最后一次写入是 **2026-05-27**（约 4 个月前）；`t_text_content` 在 2026-07-24 还有写入。
`question_asset` 从未有数据。**软删除从未被使用（deleted 行数 0）**，这是后面多项检查无样本的根因。

## 3. A-02 旧主链完整性

SQL（原文）：

```sql
SELECT COUNT(*) AS active_questions,
       SUM(q.info_text_content_id IS NULL) AS missing_legacy_id,
       SUM(q.info_text_content_id IS NOT NULL AND tc.id IS NULL) AS dangling_legacy_id,
       SUM(tc.content IS NULL OR tc.content = '') AS empty_legacy_content,
       SUM(tc.id IS NOT NULL AND tc.content IS NOT NULL AND JSON_VALID(tc.content) = 0) AS legacy_not_json
FROM t_question q LEFT JOIN t_text_content tc ON tc.id = q.info_text_content_id
WHERE q.deleted = b'0';
```

真实结果：

| active_questions | missing_legacy_id | dangling_legacy_id | empty_legacy_content | legacy_not_json |
| ---: | ---: | ---: | ---: | ---: |
| 6613 | 0 | 0 | 0 | 0 |

结论：旧 JSON 链路**无破链**（id 链接、空值、JSON 合法性三项均通过）。

⚠️ **但"无破链"不等于"内容完整"（2026-09-21 修正）**：本查询检查的是**结构链接**——
`info_text_content_id` 是否有对应行、`content` 是否为空、是否为合法 JSON。
它**不检查**内容是否被截断。样本核对已确认：`t_text_content.id=107082` 的 `titleContent`
在词中间截断（300 字符），且同一截断值同时出现在 `t_question.title` 与 `question_content.title`。

因此 DB-01/DB-10 的表述应改为：**旧 JSON 可以作为迁移输入的"结构来源"，但其内容完整性必须先抽样核对**
（见 `pending-verification.md` 的新增项与 TD-017）。

## 4. A-03 / A-03b current 完整性

SQL（原文）：

```sql
SELECT bucket, COUNT(*) AS questions FROM (
  SELECT q.id,
         CASE (SELECT COUNT(*) FROM question_content qc
                WHERE qc.question_id = q.id AND qc.is_current = b'1')
              WHEN 0 THEN '0_current' WHEN 1 THEN '1_current' ELSE 'multiple_current' END AS bucket
  FROM t_question q WHERE q.deleted = b'0') t
GROUP BY bucket ORDER BY bucket;
```

真实结果：

| bucket | questions |
| --- | ---: |
| 1_current | 6613 |

`0_current` 与 `multiple_current` 均为 0 行。A-03b（多 current 明细）**返回空集**。

结论：数据层面"每题恰好一个当前版本"成立。**这不等于数据库强制了唯一性**，见 A-12。

## 5. A-04 孤儿与版本异常

SQL（原文）：

```sql
SELECT 'content_without_question' AS anomaly, COUNT(*) AS rows_found
FROM question_content qc LEFT JOIN t_question q ON q.id = qc.question_id WHERE q.id IS NULL
UNION ALL SELECT 'content_version_not_positive', COUNT(*) FROM question_content WHERE version < 1
UNION ALL SELECT 'content_version_duplicated', COUNT(*) FROM (
  SELECT question_id, version FROM question_content GROUP BY question_id, version HAVING COUNT(*) > 1) d
UNION ALL SELECT 'source_without_question', COUNT(*) FROM question_source s
  LEFT JOIN t_question q ON q.id = s.question_id WHERE q.id IS NULL
UNION ALL SELECT 'source_duplicated_per_question', COUNT(*) FROM (
  SELECT question_id FROM question_source GROUP BY question_id HAVING COUNT(*) > 1) d
UNION ALL SELECT 'kp_relation_without_question', COUNT(*) FROM question_knowledge_point kp
  LEFT JOIN t_question q ON q.id = kp.question_id WHERE q.id IS NULL
UNION ALL SELECT 'kp_relation_without_point', COUNT(*) FROM question_knowledge_point kp
  LEFT JOIN knowledge_point k ON k.id = kp.knowledge_point_id WHERE k.id IS NULL
UNION ALL SELECT 'asset_without_question', COUNT(*) FROM question_asset a
  LEFT JOIN t_question q ON q.id = a.question_id WHERE q.id IS NULL;
```

真实结果：

| anomaly | rows_found |
| --- | ---: |
| content_without_question | 0 |
| content_version_not_positive | 0 |
| content_version_duplicated | 0 |
| source_without_question | 0 |
| source_duplicated_per_question | 0 |
| **kp_relation_without_question** | **643** |
| kp_relation_without_point | 0 |
| asset_without_question | 0 |

结论：唯一真实异常是 **643 条知识点关系指向不存在的题目**。由于 `deleted <> 0` 的题目为 0 行，
这 643 条**不是软删除残留**，而是硬删除或从未存在过的题目留下的孤儿关系。需要业务决策（DB-06/DB-08）。

## 6. A-05 / A-05b 版本连续性

SQL（A-05 原文）：

```sql
SELECT question_id, COUNT(*) AS versions, MIN(version) AS min_version, MAX(version) AS max_version,
       MAX(version) - COUNT(*) AS missing_numbers
FROM question_content GROUP BY question_id
HAVING MIN(version) <> 1 OR MAX(version) <> COUNT(*)
ORDER BY missing_numbers DESC, question_id LIMIT 50;
```

真实结果：**返回空集** → 所有题目的版本号都是 `1..n` 连续、且从 1 开始。

SQL（A-05b 原文）：

```sql
SELECT COUNT(*) AS current_not_latest FROM (
  SELECT qc.question_id FROM question_content qc WHERE qc.is_current = b'1'
    AND qc.version <> (SELECT MAX(x.version) FROM question_content x WHERE x.question_id = qc.question_id)) t;
```

| current_not_latest |
| ---: |
| 0 |

结论：真实数据里 `current` 恒为最大版本。**但每题都只有 1 个版本**（见 A-06 的 compared_questions 与
A-10b 空集），所以"多次编辑后 current 是否仍指向最新"这条**没有真实样本**，只能靠隔离库测试 T3 验证。

## 7. A-06 新旧内容一致性（分层规模）

SQL（原文）：

```sql
SELECT q.question_type, COUNT(*) AS compared_questions,
       SUM(qc.title IS NOT NULL AND qc.title_text IS NULL) AS missing_title_text,
       SUM(qc.analysis IS NOT NULL AND qc.analysis_text IS NULL) AS missing_analysis_text,
       SUM(qc.options IS NULL OR qc.options = '') AS missing_options,
       SUM(qc.correct_answer IS NULL OR qc.correct_answer = '') AS missing_correct_answer
FROM t_question q JOIN question_content qc ON qc.question_id = q.id AND qc.is_current = b'1'
WHERE q.deleted = b'0' GROUP BY q.question_type ORDER BY q.question_type;
```

真实结果：

| question_type | compared_questions | missing_title_text | missing_analysis_text | missing_options | missing_correct_answer |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1（单选） | 3780 | 0 | 0 | 0 | 0 |
| 5（简答） | 2833 | 0 | 0 | 0 | **2833** |

结论：

- 投影列（`title_text`/`analysis_text`/`options`）**无不一致样本**；
- **题型 5（简答）的 `correct_answer` 100% 为空**——需在契约里明确"简答题无标准答案"是正常
  还是缺失（DB-03）；
- 真实数据只有题型 1 和 5；题型 2/3/4（多选/判断/填空）**零行**，其契约没有真实样本可对照；
- `source_hash` 与旧 JSON 不可直接比较（生成算法不同），语义一致性仍需人工对照（未执行，见第 14 节）。

## 8. A-07 / A-07b 富文本与资源保真

SQL（A-07 原文，分层计数）：

```sql
SELECT 'has_image_flag' AS layer, COUNT(*) AS rows_found FROM question_content WHERE is_current = b'1' AND has_image = b'1'
UNION ALL SELECT 'has_code_flag', COUNT(*) FROM question_content WHERE is_current = b'1' AND has_code = b'1'
UNION ALL SELECT 'title_contains_img_tag', COUNT(*) FROM question_content WHERE is_current = b'1' AND title LIKE '%<img%'
UNION ALL SELECT 'title_contains_table', COUNT(*) FROM question_content WHERE is_current = b'1' AND title LIKE '%<table%'
UNION ALL SELECT 'title_contains_katex', COUNT(*) FROM question_content WHERE is_current = b'1'
  AND (title LIKE '%katex%' OR title LIKE '%\\\\(' OR title LIKE '%$$%')
UNION ALL SELECT 'legacy_images_column_not_null', COUNT(*) FROM t_question
  WHERE deleted = b'0' AND images IS NOT NULL AND images <> ''
UNION ALL SELECT 'question_asset_rows', COUNT(*) FROM question_asset;
```

真实结果：

| layer | rows_found |
| --- | ---: |
| has_image_flag | 137 |
| has_code_flag | 185 |
| title_contains_img_tag | 0 |
| title_contains_table | 0 |
| title_contains_katex | 0 |
| legacy_images_column_not_null | **0** |
| question_asset_rows | **0** |

A-07b 样本（原文查询取 20 条，此处摘 1 条代表）：

| question_id | title_head（截断） | title_text_head（截断） |
| --- | --- | --- |
| 7421 | `<div class="question-html-ref" data-src="question-html/csgraduates/408-mock-01/q5-title.html" data-fallback="分别以下列序列构造二叉排序树，与用其他三个序列所构造的结果不同的是（ ）。"></div>` | `分别以下列序列构造二叉排序树，与用其他三个序列所构造的结果不同的是（ ）。` |

结论（对 DB-04/DB-07 影响最大的一条）：

- 题干里的图文**不是内嵌 HTML**，而是 **`<div class="question-html-ref" data-src="…" data-fallback="…">`**
  外链引用 + 降级纯文本；`title_text` 正是从 `data-fallback` 提取的投影；
- `t_question.images` 列**全空**、`question_asset` **空表** → 图片/表格/公式的**事实不在数据库里**，
  在仓库外的 `question-html/**` 文件里；
- 因此 DB-04 的"保真"与 DB-07 的"资源关系"必须显式回答：**数据库只保存引用与降级文本**，
  丢失外部文件即不可恢复；`title_contains_*` 全为 0 也说明**不能用题干 HTML 判断富文本**。

## 9. A-08 / A-08b 来源独有信息量

SQL（A-08 原文）：

```sql
SELECT COUNT(*) AS source_rows, COUNT(DISTINCT question_id) AS questions_covered,
       SUM(source_year IS NOT NULL) AS with_year, SUM(source_question_no IS NOT NULL) AS with_question_no,
       SUM(raw_ref IS NOT NULL AND raw_ref <> '') AS with_raw_ref,
       SUM(page_no IS NOT NULL AND page_no <> '') AS with_page_no,
       SUM(crawler_batch IS NOT NULL AND crawler_batch <> '') AS with_crawler_batch,
       SUM(ocr_batch IS NOT NULL AND ocr_batch <> '') AS with_ocr_batch,
       SUM(metadata IS NOT NULL AND metadata <> '') AS with_metadata
FROM question_source;
```

真实结果：

| source_rows | questions_covered | with_year | with_question_no | with_raw_ref | with_page_no | with_crawler_batch | with_ocr_batch | with_metadata |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 6613 | 6613 | 6236 | 6613 | **6613** | 0 | 0 | 0 | **6613** |

A-08b：

| active_questions_without_source_row |
| ---: |
| 0 |

结论：`raw_ref` 与 `metadata` **100% 有值且不可从 `t_question` 派生** → `question_source` 是
**独有事实**，不能降级为可重建投影（影响 Q-04）；`page_no`/`crawler_batch`/`ocr_batch` 是**休眠列**。

## 10. A-09 软删除题目的下游泄漏面（含口径修正）

SQL（原文）：

```sql
SELECT 'deleted_questions' AS metric, COUNT(*) AS rows_found FROM t_question WHERE deleted <> b'0'
UNION ALL SELECT 'deleted_but_has_current_content', COUNT(DISTINCT qc.question_id)
  FROM question_content qc JOIN t_question q ON q.id = qc.question_id
  WHERE q.deleted <> b'0' AND qc.is_current = b'1'
UNION ALL SELECT 'deleted_but_indexed_as_rag_document', COUNT(*) FROM rag_document d
  WHERE d.document_type = 'question' AND d.status <> 'disabled'
    AND EXISTS (SELECT 1 FROM t_question q WHERE q.deleted <> b'0'
                AND CAST(d.source_ref AS CHAR) = CAST(q.id AS CHAR))
UNION ALL SELECT 'deleted_but_has_kp_relation', COUNT(DISTINCT kp.question_id)
  FROM question_knowledge_point kp JOIN t_question q ON q.id = kp.question_id WHERE q.deleted <> b'0'
UNION ALL SELECT 'deleted_but_referenced_by_exam_answer', COUNT(DISTINCT a.question_id)
  FROM t_exam_paper_question_customer_answer a JOIN t_question q ON q.id = a.question_id
  WHERE q.deleted <> b'0';
```

真实结果：

| metric | rows_found |
| --- | ---: |
| deleted_questions | **0** |
| deleted_but_has_current_content | 0 |
| deleted_but_indexed_as_rag_document | 0（**口径无效，见下**） |
| deleted_but_has_kp_relation | 0 |
| deleted_but_referenced_by_exam_answer | 0 |

**口径修正（必须先修再引用）**：A-11b 显示 `rag_document.source_ref` 的实际格式是
`t_question:7417`，**不是纯数字题目 ID**（原 SQL 自己也在 §A-09 注释里要求先看 A-11b）。
因此原查询的 `CAST(d.source_ref AS CHAR) = CAST(q.id AS CHAR)` 永远不匹配，那一行的 0 是**假阴性**。
按真实格式重跑（补充查询）：

```sql
SELECT COUNT(*) AS deleted_but_indexed_as_rag_document_fixed
FROM rag_document d
JOIN t_question q ON d.source_ref = CONCAT('t_question:', q.id)
WHERE d.document_type = 'question' AND d.status <> 'disabled' AND q.deleted <> b'0';
```

| deleted_but_indexed_as_rag_document_fixed |
| ---: |
| 0 |

结论：修正后仍为 0，但**根本原因是 `deleted` 行数为 0——整组检查无样本**。
DB-08 的软删除失效语义在当前数据下**不可验证**；要验证必须在隔离库或合成样本上做。

## 11. A-10 / A-10b 历史考试的内容引用面

SQL（A-10 原文）：

```sql
SELECT COUNT(*) AS answer_rows, COUNT(DISTINCT question_id) AS distinct_questions,
       SUM(question_text_content_id IS NULL) AS missing_question_tc_id,
       SUM(text_content_id IS NULL) AS missing_answer_tc_id,
       SUM(NOT EXISTS (SELECT 1 FROM t_text_content tc WHERE tc.id = a.question_text_content_id)) AS dangling_question_tc_id,
       SUM(NOT EXISTS (SELECT 1 FROM question_content qc WHERE qc.question_id = a.question_id
                       AND qc.legacy_text_content_id = a.question_text_content_id)) AS no_content_row_by_legacy_link
FROM t_exam_paper_question_customer_answer a;
```

真实结果：

| answer_rows | distinct_questions | missing_question_tc_id | missing_answer_tc_id | dangling_question_tc_id | no_content_row_by_legacy_link |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 108 | 67 | 0 | **96** | 0 | **0** |

A-10b（已被修改过的题目出现在考试记录里）：**返回空集**（没有任何 `content_versions > 1` 的题目
出现在答题记录中）。

结论：

- 题目侧引用完整（108 条答题都能定位到内容行，`no_content_row_by_legacy_link = 0`）；
- 108 条中有 **96 条没有答案侧 `text_content_id`**（学生未作答或未保存作答文本），需要在 DB-06 里
  明确这是正常业务状态还是数据问题；
- **当前不存在历史答案漂移候选**，因为每题只有 1 个版本；DB-06 的"内容绑定时机"仍要靠设计推演
  （无真实多版本样本）。

## 12. A-11 / A-11b / A-11c RAG 投影同步状态

SQL（A-11 原文）：

```sql
SELECT d.status, COUNT(*) AS documents, SUM(d.content_hash IS NULL) AS missing_content_hash
FROM rag_document d WHERE d.document_type = 'question' GROUP BY d.status ORDER BY d.status;
```

| status | documents | missing_content_hash |
| --- | ---: | ---: |
| ready | 6613 | 0 |

A-11b 样本（20 条，摘 2 条）——**决定 `source_ref` 口径的关键证据**：

| id | title | source_type | source_name | source_ref | version | status |
| ---: | --- | --- | --- | --- | ---: | --- |
| 128 | 题目 #7417 | exam | subject_5 | **t_question:7417** | 1 | ready |
| 129 | 题目 #7418 | exam | subject_5 | **t_question:7418** | 1 | ready |

A-11c（原文）：

```sql
SELECT e.status, COUNT(*) AS embeddings, MIN(e.update_time) AS oldest_update, MAX(e.update_time) AS newest_update
FROM rag_embedding e JOIN rag_chunk c ON c.id = e.chunk_id JOIN rag_document d ON d.id = c.document_id
WHERE d.document_type = 'question' GROUP BY e.status ORDER BY e.status;
```

| status | embeddings | oldest_update | newest_update |
| --- | ---: | --- | --- |
| failed | 40 | 2026-05-29 06:08:07 | 2026-05-29 06:11:48 |
| indexed | 4473 | 2026-05-29 05:53:19 | 2026-07-25 19:33:14 |

补充查询（投影覆盖，A-11b 后才可写）：

```sql
SELECT d.document_type, COUNT(*) AS docs, SUM(d.status='ready') AS ready,
       SUM(EXISTS(SELECT 1 FROM rag_chunk c WHERE c.document_id = d.id)) AS docs_with_chunk,
       SUM(EXISTS(SELECT 1 FROM rag_chunk c JOIN rag_embedding e ON e.chunk_id = c.id
                  WHERE c.document_id = d.id AND e.status='indexed')) AS docs_with_indexed_chunk
FROM rag_document d GROUP BY d.document_type ORDER BY d.document_type;

SELECT COUNT(*) AS question_docs_without_indexed_chunk
FROM rag_document d WHERE d.document_type='question' AND d.status='ready'
  AND NOT EXISTS (SELECT 1 FROM rag_chunk c JOIN rag_embedding e ON e.chunk_id=c.id
                  WHERE c.document_id=d.id AND e.status='indexed');
```

| document_type | docs | ready | docs_with_chunk | docs_with_indexed_chunk |
| --- | ---: | ---: | ---: | ---: |
| knowledge_base | 119 | 119 | 119 | 119 |
| question | 6613 | 6613 | 6613 | **3774** |

| question_docs_without_indexed_chunk |
| ---: |
| **2839** |

结论（对 DB-09 最关键的一条）：

- 文档与切片层**完整**（6613/6613 有 chunk）；
- **向量层缺 2839 个题目文档（42.9%）**，另有 **40 条 failed**（集中在 2026-05-29 06:08–06:11，
  是一次失败批次的痕迹）；
- 因此 `rag_document.status = 'ready'` **不能**作为"可被检索"的证明。**口径限定（2026-09-21 修正）**：
  本次只证明这 2839 个题目文档**没有已记录的 `indexed` 向量**，即**向量召回**这一条链路对它们不可用；
  以下三条必须分别验证，不能合并成一句"不可检索"：
  1. **词法召回**：`rag_chunk.content_text` 是否存在、能否被 ngram 全文索引命中；
  2. **向量召回**：`rag_embedding.status='indexed'` 且 payload/collection 与当前配置一致；
  3. **最终返回过滤**：`rag_document.status`、`rag_chunk.enabled`、以及停用题的下游过滤是否真的生效。
  这也是 DB-09 投影契约需要解释的状态：`ready` 的定义是否应当包含"向量已就绪"，还是应把
  "展示就绪"与"检索就绪"拆成两个可分别表达的状态（与 DB-14 一致）。

## 13. A-12 question_content 的约束与索引现状

SQL（原文）：

```sql
SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = database() AND TABLE_NAME = 'question_content';

SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = database() AND TABLE_NAME = 'question_content'
GROUP BY INDEX_NAME, NON_UNIQUE ORDER BY INDEX_NAME;
```

真实结果：

| CONSTRAINT_NAME | CONSTRAINT_TYPE |
| --- | --- |
| PRIMARY | PRIMARY KEY |
| uk_question_content_version | UNIQUE |

| INDEX_NAME | NON_UNIQUE | columns_in_index |
| --- | ---: | --- |
| idx_question_content_current | **1** | question_id,is_current |
| idx_question_content_legacy | 1 | legacy_text_content_id |
| PRIMARY | 0 | id |
| uk_question_content_version | 0 | question_id,version |

结论：**"至多一个 current"没有数据库约束**——`(question_id, is_current)` 只是普通索引。
`question_content` 上**没有任何外键**（与 A-04 的注释一致）。
手工并发探针（同一 `(question_id, version)` 双插入）**未执行**，见第 14 节。

## 14. 未执行项（不得当作已验证）

| 项 | 为什么未执行 | 归属 |
| --- | --- | --- |
| A-12 手工并发写入探针（两会话 INSERT + ROLLBACK） | 属于写操作，按 plan 工作规则应在隔离库执行；本次只做只读结构查询 | 隔离库 + `design-test-plan.md` T2 |
| A-13 隔离库 current 唯一性验证 | 需先建 `master408_design_test` 并应用候选 DDL | `design-test-plan.md` T1/T2 |
| A-06 规范化人工对照（新旧题干/选项/答案/解析逐题比对） | 需要按题型抽样并人工判断语义等价 | 第 8 节已给出分层规模，样本对照待做 |
| A-07 富文本样本的保真人工对照 | 需外部 `question-html/**` 文件与渲染结果 | DB-04 |
| A-06b 指定题目同屏抽样 | 原文件中为注释，需先选定 `:question_id` | 可与人工对照合并 |
| `question_asset` 相关结论 | 表为空，无样本 | DB-07 维持休眠判定 |

## 15. 对 Q-01 至 Q-07 的直接影响（供第 2 组定稿，本节不做决策）

| 待决策 | 现在有的事实依据 | 仍缺什么 |
| --- | --- | --- |
| Q-01 current 唯一性 | 数据层面 6613/6613 恰好一个 current、无断号、current=最大版本；**数据库无唯一约束**（A-03/A-05/A-12） | 隔离库验证候选 DDL 的约束能力（A-13/T1/T2）；另一方案的代价对比 |
| Q-02 版本号方案 | 同上；真实数据每题仅 1 版，无多版本样本 | T3 合成样本 |
| Q-03 历史考试绑定时机 | 108 条答题、题目侧引用完整、无漂移候选；96 条缺答案侧文本（A-10） | 读取路径走查（DB-06 不入库验证） |
| Q-04 `question_source` 是否可降级 | `raw_ref`/`metadata` 100%、`source_year` 94.3% → **不可派生**（A-08） | 无 |
| Q-05 `question_asset` 启用或休眠 | 空表、`t_question.images` 全空、资源以外部 HTML 引用存在（A-07） | 外部文件的可获得性与长期保管方案 |
| Q-06 知识点关系是否升为权威 | 6033 条关系、**643 条孤儿**（A-04） | 孤儿关系的业务处理决策 |
| Q-07 索引与查询形态 | 现有 `idx_question_content_current(question_id,is_current)` 非唯一、`uk_..._version` 唯一（A-12） | 隔离库 `EXPLAIN` 对比（第 2 组） |

## 16. 复现方式

```powershell
# 只读审计（不修改任何数据）
mysql -h 127.0.0.1 -u root -p --default-character-set=utf8mb4 --table `
  -e "source specs/2026-09-20-database-architecture/audit-queries.sql" xzs
```

原始输出：`.data/audit-raw-xzs.txt`。补充查询（第 10、12 节）已在本报告中给出 SQL 原文，
可逐条重跑核对。
