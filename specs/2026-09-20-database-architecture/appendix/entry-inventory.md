# 题目领域数据契约 — 读写入口盘点

- **状态**：走查完成（第 1 组「补齐剩余读写入口」交付物）。六张表全覆盖；两个 dump 型文件按结构而非逐行枚举，已在「未能确认」一节显式说明。
- **条目总数**：102 条编号条目 = 批量导入 6（I）、后台任务/事件 7（B）、知识图谱 11（K）、RAG 9（R）、直接 SQL 脚本 15（S）、写入口 26（W）、读入口 21（Q）、未能确认 7（U）。
- **走查日期**：2026-09-21
- **本轮性质**：只读走查。未修改任何业务代码，未连接数据库，未执行任何 SQL。
- **库/范围**：MySQL，库名 `xzs`；题目领域六张表 = `t_question`、`t_text_content`、`question_content`、`question_source`、`question_asset`、`question_knowledge_point`。

## 使用的检索方式

| 方式 | 具体用法 | 用途 |
| --- | --- | --- |
| `grep`（ripgrep 工具） | `INSERT INTO <表名>`、`UPDATE <表名> SET`、`DELETE FROM <表名>`；`@Scheduled`/`@EventListener`/`ApplicationReadyEvent`/`@Async`/`@Transactional`；mapper 方法名全局引用 | 定位 Mapper statement、Java 调用点、事务注解 |
| PowerShell `Select-String` | 对 `v1-reference/**/*.sql` 按表名正则逐文件扫描，按文件大小分流（<3MB / ≥3MB） | 直接 SQL 脚本盘点（避免 69MB/161MB dump 撑爆单次输出） |
| `read`（带行号） | 逐个打开被命中的 Mapper XML、Service、Controller、配置类 | 取证具体行号与列清单 |
| `glob` | `**/plan.md`、`**/controller/**/QuestionController.java`、`**/event/*.java` | 确认交付物位置与同名类分布 |
| 交叉比对 | 用 `specs/2026-09-20-database-architecture/audit-report.md` 的 A-11b 结果校准 `source_ref` 口径 | 避免假阴性 |

检索命令未命中即等同于「该模式在扫描范围内不存在」，本文件中所有「无写入者 / 无实现」结论均据此给出。

---

## 一、批量导入

### 1.1 已确认的入口

| # | 入口 | 具体位置 | 写入表/列 | 证据 |
| --- | --- | --- | --- | --- |
| I-1 | `POST /api/admin/question/upload/txt` | `QuestionController.uploadTxt`（admin） | `t_text_content`(content, create_time) + `t_question` + `question_content` | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/QuestionController.java:87-111` |
| I-1a | ↳ 编排者 | `QuestionServiceImpl.uploadAndAnalyzeTxt` → `parseAndInsertQuestions` → 循环调用 `insertFullQuestion` | 见 I-2 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/QuestionServiceImpl.java:230-247`；循环体 `:320-361`；插入调用 `:359` |
| I-2 | 单题落库（被 I-1 调用） | `QuestionServiceImpl.insertFullQuestion` | `t_text_content`(content,create_time) → `t_question`(全业务列) → `question_content`(v1) | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/QuestionServiceImpl.java:79-105` |
| I-3 | `POST /api/admin/question/upload`（JSON 批量） | `QuestionController.uploadQuestion` | `t_question` 单表，且**要求调用方自带 `infoTextContentId`** | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/QuestionController.java:113-151`（校验 `:126-128`，逐条 `questionService.insert` `:147`） |
| I-4 | 前端批量菜单 | **不存在**。admin 端 `api/question.js` 只暴露 page/edit/select/delete | — | 证据：`apps/frontend/admin/src/api/question.js:4-7` |
| I-5 | 脚本级批量导入（主要通道） | 见第五节 S-1…S-5（实际承担了绝大部分题目的入库） | 见第五节 | 见第五节 |

### 1.2 I-1 的关键事实

- AI 返回体直接 `mapper.readValue(analysisResult, List.class)`，无 schema 校验：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/QuestionServiceImpl.java:317`。
- `QuestionEditRequestVM` 只有 `questionType/subjectId/title/items/analyze/correct/correctArray/score/difficult`，**没有** `source`/`sourceYear`/`sourceQuestionNo`/`tags`/`knowledge_point`/`has_image`/`has_code` 字段：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/viewmodel/admin/question/QuestionEditRequestVM.java:13-37`。
- 因此 AI 导入产生的题目**只能**写 `t_question` 的传统列 + `question_content`，`question_source` 与 `question_asset` 永远拿不到数据（`question_source` 的来源字段在脚本里才写）。
- 失败语义：整批 `@Transactional`，`parseAndInsertQuestions` 抛异常即整批回滚，但 AI 调用（HTTP）发生在事务内且超时 600s：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/QuestionServiceImpl.java:231`（`@Transactional`）、`:408`（readTimeout 600000）。

### 1.3 I-3 的关键事实

- 该端点绕过 `insertFullQuestion`，直接用 `QuestionService.insert` → `BaseMapper.insert`（全列 INSERT，含自增主键位）：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/BaseServiceImpl.java:20-22`；XML `apps/backend/backend-app/src/main/resources/mapper/QuestionMapper.xml:32-43`。
- 它**不**创建 `question_content` 版本行，其写入的题目在 `question_content` 中无 current，只有 legacy 路径可读。
- 无事务：`uploadQuestion` 无 `@Transactional`，`QuestionServiceImpl` 未覆盖 `insert`，`BaseServiceImpl` 无注解：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/QuestionController.java:113`；`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/BaseServiceImpl.java:6-43`。

---

## 二、后台任务 / 定时任务 / 事件监听

### 2.1 已确认

| # | 类型 | 具体位置 | 是否触碰六张表 | 证据 |
| --- | --- | --- | --- | --- |
| B-1 | `@Scheduled` | **全仓库 main 代码零命中**（`@Scheduled`/`EnableScheduling`/quartz/xxl-job 均无） | 否 | 检索方式见文首；证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/AiProviderConfigServiceImpl.java:10` 仅为 `ApplicationReadyEvent` 导入，无调度配置 |
| B-2 | `@EventListener(ApplicationReadyEvent)` | `AiProviderConfigServiceImpl.syncLegacyConfig` | 否，只写 `ai_provider_config` | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/AiProviderConfigServiceImpl.java:48-68`（`mapper.update(config)` `:66`） |
| B-3 | `@EventListener(ApplicationReadyEvent)` | `PromptSeedLoader.seed` | 否，只写 `ai_prompt_definition`/`_version`/`_release` | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/ai/prompt/PromptSeedLoader.java:49-74`（三类 insert `:137`、`:149`、`:159`） |
| B-4 | `@PostConstruct` | `DbPromptRegistry.init` → `refresh()` | 否 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/ai/prompt/DbPromptRegistry.java:41-43` |
| B-5 | `@Async("aiEvaluationExecutor")` | `AiEvaluationWorker.execute(Long, List<AiEvaluationCase>)` | 否，`AiEvaluationCase` 来自 JSON 数据集，不查六张表 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/ai/evaluation/AiEvaluationWorker.java:49-64`；`:68-74`（用 `evaluationCase.question()`）；`apps/backend/backend-app/src/main/java/com/mindskip/xzs/ai/evaluation/FixedAiEvaluationDatasetLoader.java:53`（从 JSON 读 question）；`apps/backend/backend-app/src/main/java/com/mindskip/xzs/ai/evaluation/AiEvaluationAsyncConfiguration.java:18`（`@EnableAsync`） |
| B-6 | 事件监听（答题交卷） | `CalculateExamPaperAnswerListener.onApplicationEvent` | **间接写 `t_text_content`**（主观题答案文本），不写其余五张 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/listener/CalculateExamPaperAnswerListener.java:26`、`:50`（`@Transactional`）、`:60-65`（`textContentService.insertByFilter`） |
| B-7 | 事件定义 | `CalculateExamPaperAnswerCompleteEvent` / `OnRegistrationCompleteEvent` / `UserEvent` | 无直接 SQL | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/event/CalculateExamPaperAnswerCompleteEvent.java`、`OnRegistrationCompleteEvent.java`、`UserEvent.java` |

### 2.2 结论

**没有任何定时任务、启动任务或异步线程会写入题目领域六张表，也不会自动重建 RAG 索引。** 题目领域数据只由「请求-响应」同步路径与手工 SQL 脚本产生。这一点直接约束了 RAG 一致性设计：不存在后台对账者。

---

## 三、知识图谱 SQL 与读取路径

### 3.1 写入口

| # | 入口 | 具体位置 | 写哪张表 | 事务 | 证据 |
| --- | --- | --- | --- | --- | --- |
| K-1 | `QuestionKnowledgePointMapper.insert` | statement id = `insert` | `question_knowledge_point`(question_id, knowledge_point_id, relevance) | **未定义**（无 `@Transactional`，且无调用者） | XML 证据：`apps/backend/backend-app/src/main/resources/mapper/QuestionKnowledgePointMapper.xml:20-23` |
| K-2 | `QuestionKnowledgePointMapper.deleteByQuestionId` | statement id = `deleteByQuestionId` | `question_knowledge_point` | 同上 | 证据：`apps/backend/backend-app/src/main/resources/mapper/QuestionKnowledgePointMapper.xml:25-27` |
| K-3 | `QuestionKnowledgePointMapper.deleteByKnowledgePointId` | statement id = `deleteByKnowledgePointId` | `question_knowledge_point` | 同上 | 证据：`apps/backend/backend-app/src/main/resources/mapper/QuestionKnowledgePointMapper.xml:29-31` |
| K-4 | `KnowledgePointMapper.insert / update / deleteById` | statement id = `insert`、`update`、`deleteById` | `knowledge_point`（非六张表，但是关系表的父表） | 无注解，且 **Service 层未暴露** | 证据：`apps/backend/backend-app/src/main/resources/mapper/KnowledgePointMapper.xml:33-51` |

**K-1/K-2/K-3 无任何 Java 调用者（已确认）**：全仓库对 `questionKnowledgePointMapper` 的引用只有三处，全部是 `findByKnowledgePointId` / `findByQuestionId` 读取：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:217`、`:238`、`:257`。
因此 **`question_knowledge_point` 在运行期是只读表**，其内容 100% 由脚本写入（见 S-2、S-4）。

### 3.2 读入口

| # | 入口 | 具体位置 | 读哪张表/列 | 「题干/选项/答案来自不同来源」 | 证据 |
| --- | --- | --- | --- | --- | --- |
| K-5 | 图谱节点/边 | `KnowledgeGraphServiceImpl.getKnowledgeGraph` | `knowledge_point`(*) + `t_subject`(id,name)；读法 `SELECT *` | 不涉及题目内容 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:46-161`；mapper `.../mapper/KnowledgePointMapper.xml:17-31` |
| K-6 | 知识点详情 | `KnowledgeGraphServiceImpl.getKnowledgePointDetail` | `knowledge_point` + `knowledge_content`(html_ref,summary_text,source_url,asset_dir) + `question_knowledge_point` + `t_question` | **K-7 兜底时是拼接读** | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:164-232`（`findCurrentByKnowledgePointId` `:177`，`findByKnowledgePointId` `:217`） |
| K-7 | 原生 SQL：题目摘要投影 | `KnowledgeGraphServiceImpl.loadQuestionDisplayFields` | `t_question`(title_text, source, source_year, source_question_no, subject_id) + `t_text_content`(JSON_EXTRACT `$.titleContent`) + `t_subject`(name) | **是**。标题取 `COALESCE(title_text, JSON 提取的 titleContent)`；来源取 `source_year` 优先、回落 `source` 列 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:292-299`；标题合成 `:300-304`；来源合成 `:305-311` |
| K-8 | 原生 SQL：关键词兜底召回 | `KnowledgeGraphServiceImpl.findFallbackQuestions` | `t_question`(id,question_type,difficult,subject_id,title_text,source,source_year,source_question_no,tags,knowledge_point,analysis_text) + `t_text_content`(JSON_EXTRACT) + `t_subject` | **是**。召回条件跨 `q.tags / q.knowledge_point / q.title_text / q.analysis_text / tc.content` 五列；展示标题再走 K-7 的双来源拼接 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:320-382`；LIKE 五列条件 `:332`；主查询 `:341-348`；次级兜底查询 `:350-358` |
| K-9 | 题→知识点 | `KnowledgeGraphServiceImpl.getQuestionKnowledgePoints` | `question_knowledge_point`(*) + `knowledge_point`(*) | 否 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:235-251` |
| K-10 | 知识点→题 | `KnowledgeGraphServiceImpl.getKnowledgePointQuestions` | `question_knowledge_point` + `t_question` + K-7 | 是（同 K-7） | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java:253-276`（`:270` 合并 K-7 字段） |
| K-11 | 知识点物理删除 | `KnowledgePointMapper.deleteById`（软删 `deleted=TRUE`） | `knowledge_point.deleted` | 否 | 证据：`apps/backend/backend-app/src/main/resources/mapper/KnowledgePointMapper.xml:49-51` |

### 3.3 事务边界

`KnowledgeGraphServiceImpl` **整个类没有任何 `@Transactional`**：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/KnowledgeGraphServiceImpl.java`（全文件检索 `Transactional` 零命中）。
`getKnowledgePointDetail` 会做 N+1 次 `questionMapper.selectByPrimaryKey`（每个关系一行一次）：证据：`:219-225`。K-8 内部所有异常被 `catch` 吞掉并返回空列表：证据：`:378-380`；K-5 同理 `:151-154`。

---

## 四、RAG 回填与刷新

### 4.1 写入口

| # | 入口 | statement id / 方法 | 写哪张表哪些列 | 事务 | 证据 |
| --- | --- | --- | --- | --- | --- |
| R-1 | 知识库回填（文档） | `RagDocumentMapper.backfillDocumentsFromLegacyKnowledgeBase` | `rag_document`(document_type,title,summary,subject_id,knowledge_point_id,source_type,source_name,source_ref,permission_scope,version,content_hash,status,legacy_knowledge_base_id,create_user) | **无** | XML 证据：`apps/backend/backend-app/src/main/resources/mapper/RagDocumentMapper.xml:19-55`；调用证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/RagDocumentServiceImpl.java:22-26` |
| R-2 | 知识库回填（切片） | `RagDocumentMapper.backfillChunksFromLegacyKnowledgeBase` | `rag_chunk`(document_id,chunk_index,content,content_text,token_count,subject_id,knowledge_point_id,citation_label,source_position,content_hash,enabled) | **无** | XML：`.../mapper/RagDocumentMapper.xml:57-83`；调用：`.../RagDocumentServiceImpl.java:24` |
| R-3 | 题目回填（文档） | `RagDocumentMapper.backfillDocumentsFromQuestions` | `rag_document`(同上 14 列，`document_type='question'`) | **无** | XML：`.../mapper/RagDocumentMapper.xml:158-185`；调用：`.../RagDocumentServiceImpl.java:29-33` |
| R-4 | 题目回填（切片） | `RagDocumentMapper.backfillChunksFromQuestions` | `rag_chunk`(同上 11 列) | **无** | XML：`.../mapper/RagDocumentMapper.xml:187-213`；调用：`.../RagDocumentServiceImpl.java:31` |
| R-5 | 题目切片正文刷新 | `RagDocumentMapper.refreshQuestionChunkText` | `rag_chunk.content_text / token_count / content_hash`，源 = `question_content`(title_text,correct_answer,analysis_text,source_hash) | ✅ `@Transactional`（REQUIRED，默认传播） | XML：`.../mapper/RagDocumentMapper.xml:106-121`；调用与事务：`.../RagDocumentServiceImpl.java:36-40` |
| R-6 | 知识切片超长拆分 | `RagDocumentMapper.updateChunkContent` + `insertSplitChunk` | `rag_chunk`(content,content_text,token_count,content_hash) / 新行 | ✅ 同 R-5 事务内 | XML：`.../mapper/RagDocumentMapper.xml:135-156`；调用：`.../RagDocumentServiceImpl.java:41-66` |
| R-7 | 向量元数据 upsert | `RagDocumentMapper.upsertEmbeddingMetadata` | `rag_embedding`(chunk_id,embedding_model,embedding_dimension,vector_store,collection_name,vector_id,payload_hash,indexed_at,status,error_message,update_time)，`ON DUPLICATE KEY UPDATE` | **无** | XML：`.../mapper/RagDocumentMapper.xml:215-235`；调用：`.../RagDocumentServiceImpl.java:115-123` |
| R-8 | 向量库写入（Qdrant） | `SpringAiRagVectorStoreServiceImpl.add` | 外部向量库，metadata 含 chunk_id/document_id/chunk_index/title/citation_label/source_position/subject_id/knowledge_point_id | 无（外部系统） | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/SpringAiRagVectorStoreServiceImpl.java:42-70`；稳定向量 ID `:72-74`（`UUID.nameUUIDFromBytes("rag-chunk:"+chunkId)`） |

### 4.2 唯一触发点（无后台触发器）

| # | 触发 | 位置 | 说明 | 证据 |
| --- | --- | --- | --- | --- |
| R-9 | `POST /api/admin/ai-config/rag/index` | `AiConfigController.ragIndex` | **唯一**触发 R-1…R-8 的入口；由前端「开发者 API 清单」页列出，admin 配置页并未调用 | 证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/AiConfigController.java:80-145`；前端清单 `apps/frontend/admin/src/views/developer/index.vue:517`；`apps/frontend/admin/src/views/ai/config.vue:198-345` 无 RAG 调用 |

参数语义（决定幂等与停产窗口）：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/AiConfigController.java:86`（`source` ∈ questions/knowledge/all）、`:99`（固定 `normalizeForIndexing(4000,300)`）、`:101`（`force`）、`:107-111`（`maxChunks` 默认 200、`afterId` 断点）、`:104-106`（collection 默认 `xzs_408_chunks_spring_v1`、model 默认 `embedding-2`、batch 默认 20）、`:113-133`（游标循环 + 逐批 markIndexed/markIndexFailed）。

### 4.3 `source_ref` 格式约定（已确认）

- 题目类文档：`CONCAT('t_question:', q.id)`，例如 `t_question:7417`。写入证据：`apps/backend/backend-app/src/main/resources/mapper/RagDocumentMapper.xml:172`（document）与 `:202`（chunk 的 `source_position`）与 `:206`（回连条件）。
- 知识库类文档：`CONCAT('t_ai_knowledge_base:', kb.id)`。证据：`.../mapper/RagDocumentMapper.xml:33`。
- 回连用 `SUBSTRING_INDEX(rd.source_ref, ':', -1)` 取尾部数字：证据：`.../mapper/RagDocumentMapper.xml:110`。
- 与审计结论一致（A-11b 样本 `t_question:7417`）：证据：`specs/2026-09-20-database-architecture/audit-report.md:399-402`。

### 4.4 题目触发的重建索引：**不存在**

题目编辑（`insertFullQuestion`/`updateFullQuestion`）不会写任何 `rag_*` 表：证据：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/service/impl/QuestionServiceImpl.java:79-126`（全方法体内无 Rag 相关调用）。
所以题目内容变更与 RAG 投影之间没有触发器，只有下次人工调用 R-9 时才由 R-5 增量补齐。而 R-5 的「变更判定」本身是坏的：

- R-5 的判据是 `rc.content_hash <> SHA2(CONCAT_WS('|', q.id, qc.source_hash), 256)`：证据：`apps/backend/backend-app/src/main/resources/mapper/RagDocumentMapper.xml:119-120`。
- R-4 写入的 `content_hash` 却是 `SHA2(CONCAT_WS('|', q.id, tc.content), 256)`：证据：`.../mapper/RagDocumentMapper.xml:203`。
- 两者基准不同（`qc.source_hash` vs `tc.content`），因此 R-5 对 R-4 产出的行**恒不相等** → 每次调用都会整表重写 `rag_chunk`（并把 `content_hash` 换成另一种算法），而 `rag_embedding.payload_hash` 未随之失效 → 向量与正文长期不一致。这是需要写进契约的投影失效缺陷。

---

## 五、直接 SQL 脚本

范围：仓库内 `.sql` 文件中对六张表的 `INSERT`/`UPDATE`/`DELETE`。已按要求排除 Flyway 迁移 `apps/backend/backend-app/src/main/resources/db/migration/V1..V5*.sql`（V1 仅 `CREATE TABLE`，V5 仅 294 字节的检索结构）与审计脚本 `specs/2026-09-20-database-architecture/audit-queries.sql`。

| # | 脚本（相对路径） | 对六张表的操作 | 事务 | 关键行号 |
| --- | --- | --- | --- | --- |
| S-1 | `v1-reference/database/archive/13_backfill_canonical_ai_data.sql` | `question_content` INSERT…SELECT（`NOT EXISTS(qc.version=1)` 幂等）、`question_source` INSERT…SELECT（按 type+year+no 幂等）、`question_knowledge_point` INSERT…SELECT（名称/标签精确匹配）、`rag_document`/`rag_chunk`/`rag_embedding` INSERT…SELECT | 无 `START TRANSACTION` | `question_content` `:11-43`；`question_source` `:48-68`；`question_knowledge_point` `:74-84`；RAG `:90-172` |
| S-2 | `v1-reference/database/archive/12_canonical_ai_architecture.sql` | 仅建表（含 `question_asset` DDL `:34-48`） | — | `:34-48` |
| S-3 | `v1-reference/database/archive/16_import_2026_html_mock_exam.sql` | 完整重建：先 DELETE 后 INSERT。DELETE `question_source` `:11`、`question_content` `:12`、`t_question` `:14`、`t_text_content`(按 info_text_content_id) `:15`；随后逐题 INSERT `t_text_content` `:20` / `t_question` `:23` / `question_content` `:33`（**手写 `source_hash` 字面量**） / `question_source` `:38`（`source_type='crawler_html'`） | ✅ `START TRANSACTION` `:6` | `:6-16`；样例题 `:19-42`；共 48 组（`t_question` INSERT 48 次） |
| S-4 | `v1-reference/database/archive/17_import_csgraduates_html_exams.sql` | 完整重建 + 大批量导入。DELETE 段 `:279`(question_asset) `:280`(question_source) `:281`(question_content) `:283`(t_question) `:284-285`(t_text_content)；INSERT：`t_question` 6613 次、`t_text_content` 6841 次、`question_content` 6613 次、`question_source` 6613 次；`question_asset` 仅 DELETE 无 INSERT | 无 `START TRANSACTION`；用 `CREATE TEMPORARY TABLE tmp_csg_*` 做定位（`:256`、`:272`） | DELETE 段 `:279-285`；临时表 `:256-277`；INSERT 样例 `:288-295`、`question_source` `:152814` 起 |
| S-5 | `apps/backend/backend-app/src/main/resources/db/demo/01_demo_seed.sql` | `t_question` INSERT（`WHERE NOT EXISTS` 幂等）`：64-94`；`question_knowledge_point` INSERT（幂等）`:106-117`。**不写 `t_text_content`，也不写 `question_content`/`question_source`** | 无 | `:17-25`(t_subject)、`:35-52`(knowledge_point)、`:64-94`、`:106-117` |
| S-6 | `v1-reference/database/archive/18_generate_408_subject_papers.sql` | `t_text_content` DELETE `:41` + INSERT(试卷框架) `:98` | 待确认（未逐行读全） | `:41`、`:98` |
| S-7 | `v1-reference/database/current/03_questions_and_exams.sql`（与 `v1-reference/deploy/sql/03_questions_and_exams.sql` 内容一致） | `question_source` INSERT `:69-72`（`crawler_html`）、`t_question` INSERT `:112` 起（约 64 条，`INSERT INTO t_question VALUES` 全列形式）。**`question_asset` 段为空**（只有 DISABLE/ENABLE KEYS 包裹，无数据行） | 无 | asset 空段 `:19-23`；source `:69-72`；question `:112` 起 |
| S-8 | `v1-reference/database/current/06_schema_fix.sql`（与 `deploy/sql/06_schema_fix.sql` 同构） | `t_text_content` DELETE 孤儿行 `:115`，并 DELETE 关联试卷 `:116` | 无 | `:115-116` |
| S-9 | `v1-reference/database/archive/10_knowledge_points_data.sql` | `question_knowledge_point` **全表 DELETE** `:7`（TRUNCATE 语义，后续由 S-1 重建） | 无 | `:7` |
| S-10 | `v1-reference/database/tests/test_npe_data.sql` | `t_text_content` INSERT(id=99001) `:59`、DELETE `:199` | 无 | `:59`、`:199` |
| S-11 | `v1-reference/database/archive/04_exam_data.sql` | `t_text_content` INSERT `:31`、`t_question` INSERT `:708` | 无 | `:31`、`:708` |
| S-12 | `v1-reference/database/archive/source.sql` | 全量 dump：`question_content` `:365`、`question_knowledge_point` `:393`、`question_source` `:430`、`t_question` `:1179`、`t_text_content` `:1289`；`question_asset` 只有空表声明 `:319-324` 无数据 | 无 | `:294-324`、`:365`、`:393`、`:430`、`:1179`、`:1289` |
| S-13 | `v1-reference/database/archive/backups/xzs-20260515.sql` | 备份：`t_question` `:434`、`t_text_content` `:542` | 无 | `:434`、`:542` |
| S-14 | `v1-reference/database/backup/xzs_local_system.sql` | 本地全量备份：`question_source` `:528-531`、`t_question` `:1373` 起、`t_text_content` 大量；`question_asset` 为空表 `:369-399` | 无 | `:369-399`、`:528-531`、`:1373` |
| S-15 | 其余 `.sql` 文件（清单见下） | 对六张表**零命中** | — | 见下 |

**对六张表零写入命中的 SQL 文件（已确认）**：`00_init_database_with_seed.sql`、`01_init_structure.sql`、`02_extend_fields.sql`、`05_rag_embeddings.sql`、`06_ai_knowledge_rag.sql`、`07_demo_student_learning_data.sql`、`08_clean_knowledge_display_noise.sql`、`11_default_user_level.sql`、`14_ai_provider_config.sql`、`15_embed_question_images.sql`、`21_fix_english_cloze_options.sql`、`22_fix_embedding_usage_logs.sql`、`23_non408_virtual_knowledge_points.sql`、`legacy-knowledge/*.sql`（5 个）、`current/01_schema.sql`、`current/02_seed.sql`、`current/04_knowledge_and_rag.sql`、`current/05_student_data.sql`、`deploy/sql/01,02,04,05`。

**`question_asset` 的脚本层结论（已确认）**：17 个候选文件中，只有 S-4 的 `DELETE qa FROM question_asset qa JOIN tmp_...`（证据：`v1-reference/database/archive/17_import_csgraduates_html_exams.sql:279`）；**没有任何脚本 INSERT 过 `question_asset`**，`source.sql`（`:319-324`）、`current/03_questions_and_exams.sql`（`:19-23`）、`xzs_local_system.sql`（`:394-399`）三处的 asset 数据段均为空。

---

## 六、写入口清单

约定：事务边界一列中，「—」表示该路径上不存在 `@Transactional`（含被调用方法），传播行为栏写「无」；REQUIRED 为 Spring 默认传播。

| # | 写入口 | 表 / 列 | 事务注解 | 传播 | 证据 |
| --- | --- | --- | --- | --- | --- |
| W-1 | `QuestionServiceImpl.insertFullQuestion` | `t_text_content`(content,create_time) + `t_question`(question_type,subject_id,grade_level,create_time,status,correct,score,difficult,info_text_content_id,create_user,deleted) + `question_content`(见 W-2) | `@Transactional` | REQUIRED | `.../service/impl/QuestionServiceImpl.java:79`、`:85-103` |
| W-2 | `QuestionContentServiceImpl.saveFromEdit` | `question_content`(question_id,version,title,options,correct_answer,analysis,title_text,analysis_text,content_format,has_image,has_code,legacy_text_content_id,source_hash,is_current) + `UPDATE question_content SET is_current=b'0'` | `@Transactional` | REQUIRED（嵌套加入 W-1/W-3） | `.../service/impl/QuestionContentServiceImpl.java:31-56`；clearCurrent `:53` |
| W-3 | `QuestionServiceImpl.updateFullQuestion` | `t_question`(subject_id,grade_level,score,difficult,correct；`info_text_content_id` 仅在非 null 时) + `t_text_content`(content,create_time,embedding) + W-2 | `@Transactional` | REQUIRED | `.../service/impl/QuestionServiceImpl.java:107-126`；XML `.../mapper/QuestionMapper.xml:123-161` |
| W-4 | `QuestionServiceImpl.uploadAndAnalyzeTxt` | 循环 W-1 | `@Transactional` | REQUIRED（整批同一事务） | `.../service/impl/QuestionServiceImpl.java:230-247` |
| W-5 | `QuestionController.uploadQuestion` → `QuestionService.insert` | `t_question` 全列（含 info_text_content_id 必填校验） | — | 无 | `.../controller/admin/QuestionController.java:113-151`；`.../service/impl/BaseServiceImpl.java:20-22`；XML `.../mapper/QuestionMapper.xml:32-43` |
| W-6 | `QuestionController.delete` → `updateByIdFilter` | `t_question.deleted=true`（软删） | — | 无 | `.../controller/admin/QuestionController.java:79-85`；XML `.../mapper/QuestionMapper.xml:123-161` |
| W-7 | `TextContentServiceImpl.insertByFilter`（通用被调） | `t_text_content`(id?,content,create_time,embedding) | — | 无（由调用方决定） | `.../service/impl/BaseServiceImpl.java:24-27`；XML `.../mapper/TextContentMapper.xml:38-68` |
| W-8 | `QuestionServiceImpl.setQuestionInfoFromVM` + W-7 | `t_text_content.content` = JSON{questionItemObjects,analyze,titleContent,correct} | 继承 W-1/W-3 | REQUIRED | `.../service/impl/QuestionServiceImpl.java:186-204` |
| W-9 | `CalculateExamPaperAnswerListener` | `t_text_content`(content,create_time)（主观题答案快照） | `@Transactional` | REQUIRED | `.../listener/CalculateExamPaperAnswerListener.java:50`、`:60-65` |
| W-10 | `ExamPaperServiceImpl.savePaperFromVM` | `t_text_content`(试卷框架 content) | `@Transactional` | REQUIRED | `.../service/impl/ExamPaperServiceImpl.java:84`、`:96`；`.../service/impl/ExamPaperServiceImpl.java:107`（更新） |
| W-11 | `TaskExamServiceImpl` | `t_text_content`(任务框架 content) | `@Transactional` | REQUIRED | `.../service/impl/TaskExamServiceImpl.java:54`、`:73`、`:96` |
| W-12 | `TaskExamCustomerAnswerImpl` | `t_text_content`(任务作答 content) | — | 无 | `.../service/impl/TaskExamCustomerAnswerImpl.java:43-44`、`:51-52` |
| W-13 | `ExamPaperAnswerServiceImpl`（任务分支） | `t_text_content`(content,create_time,embedding) | `@Transactional`（方法级，仅覆盖部分路径） | REQUIRED | `.../service/impl/ExamPaperAnswerServiceImpl.java:111`、`:144-150` |
| W-14 | `AiPaperComposeServiceImpl.createPersonalTask` | `t_text_content`(content,create_time) | `@Transactional` | REQUIRED | `.../service/impl/AiPaperComposeServiceImpl.java:66`、`:266-269` |
| W-15 | `QuestionContentServiceImpl.backfillFromLegacy` → `QuestionContentMapper.backfillFromLegacy` | `question_content`(version=1 全列，`source_hash` 用 `CONCAT_WS('|',q.id,q.title,q.options,q.correct_answer/q.correct,q.analysis,tc.content)` 的 MySQL `SHA2`)，扫 `t_question` + `t_text_content` | **—** | 无 | `.../service/impl/QuestionContentServiceImpl.java:58-61`；XML `.../mapper/QuestionContentMapper.xml:59-87`（hash `:78`） |
| W-16 | `RagDocumentServiceImpl.backfillFromLegacyKnowledgeBase` | `rag_document`(14 列) + `rag_chunk`(11 列)，源 `t_ai_knowledge_base` + `knowledge_point` | **—** | 无 | `.../service/impl/RagDocumentServiceImpl.java:21-26`；XML `.../mapper/RagDocumentMapper.xml:19-55`、`:57-83` |
| W-17 | `RagDocumentServiceImpl.backfillFromQuestions` | `rag_document`(14 列,`source_ref='t_question:'+id`) + `rag_chunk`(11 列)，源 `t_question` JOIN `t_text_content` | **—** | 无 | `.../service/impl/RagDocumentServiceImpl.java:28-33`；XML `.../mapper/RagDocumentMapper.xml:158-185`、`:187-213` |
| W-18 | `RagDocumentServiceImpl.normalizeForIndexing` → `refreshQuestionChunkText` | `rag_chunk.content_text/token_count/content_hash`；源 `question_content`(is_current=1) | `@Transactional` | REQUIRED | `.../service/impl/RagDocumentServiceImpl.java:35-40`；XML `.../mapper/RagDocumentMapper.xml:106-121` |
| W-19 | `RagDocumentServiceImpl.normalizeForIndexing` → `updateChunkContent` + `insertSplitChunk` | `rag_chunk`(content,content_text,token_count,content_hash) / 新行(document_id,chunk_index,…) | 同 W-18 | REQUIRED | `.../service/impl/RagDocumentServiceImpl.java:41-66`；XML `.../mapper/RagDocumentMapper.xml:135-156` |
| W-20 | `RagDocumentServiceImpl.markIndexed` / `markIndexFailed` → `upsertEmbeddingMetadata` | `rag_embedding`(chunk_id,embedding_model,embedding_dimension,vector_store,collection_name,vector_id,payload_hash,indexed_at,status,error_message) | **—** | 无 | `.../service/impl/RagDocumentServiceImpl.java:114-123`；XML `.../mapper/RagDocumentMapper.xml:215-235` |
| W-21 | `KnowledgeGraphServiceImpl`（整类） | **无任何写操作** | — | — | `.../service/impl/KnowledgeGraphServiceImpl.java`（写语句零命中） |
| W-22 | `QuestionKnowledgePointMapper.insert / deleteByQuestionId / deleteByKnowledgePointId` | `question_knowledge_point` | — | 无 | XML `.../mapper/QuestionKnowledgePointMapper.xml:20-31`；**无 Java 调用者**（见 3.1） |
| W-23 | `KnowledgePointMapper.insert / update / deleteById` | `knowledge_point`(name,subject_id,parent_id,description,level,sort_order / deleted) | — | 无 | XML `.../mapper/KnowledgePointMapper.xml:33-51`；**Service 层未暴露** |
| W-24 | `QuestionMapper.deleteByPrimaryKey`（硬删）/ `TextContentMapper.deleteByPrimaryKey`（硬删） | `t_question` / `t_text_content` 整行 | — | 无 | XML `.../mapper/QuestionMapper.xml:28-31`、`.../mapper/TextContentMapper.xml:28-31`；`BaseServiceImpl.java:15-17`；**全仓库无调用者** |
| W-25 | 脚本 S-1（canonical backfill） | `question_content` / `question_source` / `question_knowledge_point` / `rag_*` | 无显式事务 | 脚本内隐式 | `v1-reference/database/archive/13_backfill_canonical_ai_data.sql:11,48,74,90,125,151` |
| W-26 | 脚本 S-3 / S-4 / S-5（导入与种子） | 见第五节 | S-3 有事务；S-4/S-5 无 | — | `.../16_import_2026_html_mock_exam.sql:6`；`.../17_import_csgraduates_html_exams.sql:279-285`；`apps/backend/backend-app/src/main/resources/db/demo/01_demo_seed.sql:64,106` |

### 6.1 无事务保护的风险点（汇总）

1. **W-5**：HTTP 批量 JSON 导入逐条插入、无事务，中途失败留下半批数据。
2. **W-15**：`question_content` 全量回填的调用方无事务（`QuestionContentServiceImpl.backfillFromLegacy` 未加注解，与 W-2 的 `@Transactional` 形成不对称）。单条 INSERT…SELECT 语句本身原子，但「回填」与后续读切换之间没有一致点。
3. **W-16 / W-17**：RAG 回填两条语句（document + chunk）非原子，第一条成功第二条失败会留下无切片的可用文档（`status='ready'` 但无 `rag_chunk`），检索侧永不命中。
4. **W-20**：`rag_embedding` 状态写入无事务，且与 W-18/W-19 的正文刷新不在同一事务，正文换了 hash 而向量元数据仍是 `indexed`。
5. **W-6**：软删题目的下游失效（`rag_document.status`、`question_content.is_current`、图关系）全部无事务、无级联，删题后 RAG 仍能用旧正文召回。
6. **W-12 / W-13 / W-10 / W-11 / W-09**：`t_text_content` 的多来源写入者之间无统一事务边界；同一张表同时承载题目内容、试卷框架、任务框架、主观题答案四种语义。

---

## 七、读入口清单

| # | 读入口 | 表 / 列 | 是否拼接不同来源 | 证据 |
| --- | --- | --- | --- | --- |
| Q-1 | `QuestionMapper.page` | `t_question`(id,question_type,subject_id,score,grade_level,difficult,correct,info_text_content_id,create_user,status,create_time,deleted) + 子查询 `t_text_content.content LIKE` | 是（列表关键词来自 `t_text_content`，展示字段来自 `t_question`） | XML `.../mapper/QuestionMapper.xml:181-203`；调用 `.../service/impl/QuestionServiceImpl.java:71-75` |
| Q-2 | `QuestionMapper.selectByPrimaryKey` | `t_question`(Base_Column_List) | 否 | XML `.../mapper/QuestionMapper.xml:22-27` |
| Q-3 | **题目编辑回显** `QuestionServiceImpl.getQuestionEditRequestVM(Question)` | `t_question`(question_type,correct,score) + `t_text_content`(JSON：titleContent/analyze/questionItemObjects) + `question_content`(title,analysis,title_text) | **是，且是覆盖式拼接** | `.../service/impl/QuestionServiceImpl.java:138-184` |
| Q-4 | 同上：题干 | `questionObject.titleContent` → 被 `currentContent.title` 覆盖（仅当非 null） | 是 | `.../service/impl/QuestionServiceImpl.java:144-148` |
| Q-5 | 同上：解析 | `currentContent.analysis` 非 null 时用 `question_content`，否则回落 `questionObject.analyze` | 是 | `.../service/impl/QuestionServiceImpl.java:171` |
| Q-6 | 同上：答案 | **四路分流**：单选/判断 `t_question.correct`；多选 `ExamUtil.contentToArray(t_question.correct)`；填空 `questionObject.questionItemObjects[].content`（来自 `t_text_content`）；简答 `questionObject.correct`（来自 `t_text_content`） | 是 | `.../service/impl/QuestionServiceImpl.java:151-169` |
| Q-7 | 同上：选项 | `questionObject.questionItemObjects` → `t_text_content` JSON，**完全不读 `question_content.options`** | 是（读侧忽略新表 options） | `.../service/impl/QuestionServiceImpl.java:175-182` |
| Q-8 | 学生答题列表 `QuestionAnswerController.pageList` | `question_content`(title_text,title) 优先 → `t_text_content`(JSON titleContent) 兜底 → `HtmlUtil.clear` 再兜底 | 是（三级回落） | `.../controller/student/QuestionAnswerController.java:58-78`；`firstNotBlank` `:84-97`；`data-fallback` 提取 `:99-115` |
| Q-9 | 学生题目详情 `QuestionController.select` → Q-3 | 同 Q-3 | 是 | `.../controller/student/QuestionController.java:38-45` |
| Q-10 | 后台列表 `AdminQuestionController.pageList` | `t_question` + `t_text_content`(`selectById(infoTextContentId)` → JSON titleContent → `HtmlUtil.clear`) | 是；且 `textContent` 为 null 时第 48 行会 NPE | `.../controller/admin/QuestionController.java:40-54` |
| Q-11 | 试卷回显 `ExamPaperServiceImpl.examPaperToVM` | `t_exam_paper` + `t_text_content`(框架 JSON) + `t_question`(selectByIds) + 逐题 Q-3 | 是（框架来自 `t_text_content`，题目内容走 Q-3 多来源） | `.../service/impl/ExamPaperServiceImpl.java:115-145`；逐题 `:131`；`questions.stream()...findFirst().get()` `:130`（缺失题目即 `NoSuchElementException`，见 `v1-reference/database/current/06_schema_fix.sql:112-114` 的记录） |
| Q-12 | 答题记录详情 | `exam_paper_question_customer_answer`(question_text_content_id) → Q-3 | 是（答题行冻结的是 `t_text_content` id） | `.../controller/student/QuestionAnswerController.java:118-135`；`.../controller/wx/student/QuestionAnswerController.java:82` |
| Q-13 | 答题行写入时冻结的来源 | `ExamPaperAnswerServiceImpl.ExamPaperQuestionCustomerAnswerFromVM`：`questionTextContentId = question.getInfoTextContentId()` | 是（冻结 legacy 指针用于列表回显，与 Q-8 的 current 优先叠加） | `.../service/impl/ExamPaperAnswerServiceImpl.java:212` |
| Q-14 | 判分来源 | `ExamPaperAnswerServiceImpl.setSpecialFromVM`：单选/判断/多选/填空全部用 `question.getCorrect()`（`t_question.correct`） | 是（判分读 `t_question`，回显读 Q-3/Q-6 分流） | `.../service/impl/ExamPaperAnswerServiceImpl.java:228-244`（`:234`、`:240`） |
| Q-15 | AI 组卷候选 | `QuestionMapper.selectForAiPaper` / `selectMistakesForAiPaper` | 是（同一 WHERE 内跨 `knowledge_point`/`tags`/`title_text`/`analysis_text` 四列 LIKE） | XML `.../mapper/QuestionMapper.xml:217-282`（`:222-229`、`:261`、`:280`） |
| Q-16 | AI 组卷编排 | `AiAgentPlannerServiceImpl.selectCandidates` / `AiPaperComposeServiceImpl.compose` | 是（先按知识点精确后回落全科） | `.../service/impl/AiAgentPlannerServiceImpl.java:65-78`；`.../service/impl/AiPaperComposeServiceImpl.java:76-113` |
| Q-17 | AI 分析入参 | `AIAnalysisController.analyzeWithAI`：`question` 来自**请求体**，不查库；`knowledgePoints` 亦来自请求体 | 否（但意味着前端可传任意来源的题干） | `.../controller/student/AIAnalysisController.java:130-166`（`:137-138`、`:166` RAG 检索） |
| Q-18 | RAG 检索（向量） | 外部向量库 metadata(chunk_id/citation_label/source_position) | 是（向量命中与词法命中在 `fuseAndRerank` 融合） | `.../ai/RagService.java:61-80`、`:82-113` |
| Q-19 | RAG 检索（词法） | `rag_chunk`(id,citation_label,content_text,content,source_position) JOIN `rag_document`(id,title,status) | 是（`COALESCE(content_text, content)` 与 `COALESCE(citation_label, rd.title)`） | XML `.../mapper/RagRetrievalMapper.xml:5-20` |
| Q-20 | 知识点详情读题目 | K-7 原生 SQL（见第三节） | 是 | `.../service/impl/KnowledgeGraphServiceImpl.java:288-318` |
| Q-21 | 图谱兜底召回 | K-8 原生 SQL（见第三节） | 是 | `.../service/impl/KnowledgeGraphServiceImpl.java:320-382` |

### 7.1 「题干/选项/答案来自不同来源」总判定

**成立，而且集中在同一条编辑回显路径（Q-3…Q-7）上。** 归纳成四种组合：

| 语义字段 | 首选来源 | 回落/次选来源 | 证据 |
| --- | --- | --- | --- |
| 题干 | `question_content.title`（仅覆盖 title，不覆盖 items） | `t_text_content` JSON `$.titleContent` | `.../service/impl/QuestionServiceImpl.java:144-148` |
| 选项 | `t_text_content` JSON `$.questionItemObjects` | 无（**从不读 `question_content.options`**） | `.../service/impl/QuestionServiceImpl.java:175-182` |
| 解析 | `question_content.analysis` | `t_text_content` JSON `$.analyze` | `.../service/impl/QuestionServiceImpl.java:171` |
| 答案 | 单选/判断/多选：`t_question.correct`；填空/简答：`t_text_content` JSON | — | `.../service/impl/QuestionServiceImpl.java:151-169` |
| 列表短标题 | `question_content.title_text` → `question_content.title`（data-fallback 提取）→ `HtmlUtil.clear(title)` | 再回落 `t_text_content` JSON `$.titleContent` | `.../controller/student/QuestionAnswerController.java:58-78` |
| 判分答案 | `t_question.correct` | 无 | `.../service/impl/ExamPaperAnswerServiceImpl.java:234,240` |

由此产生一个可验证的契约缺口：**一次编辑写入 `question_content.title/analysis` 后，回显题干与解析变了，选项与填空/简答答案却仍来自 `t_text_content`**，即同一道题在界面上可能呈现「新题干 + 旧选项」。这是字段所有权必须先定的直接理由。

---

## 八、未能确认（及原因）

| # | 项 | 原因 |
| --- | --- | --- |
| U-1 | `v1-reference/database/archive/17_import_csgraduates_html_exams.sql` 的 6613×4 条语句无法逐行枚举 | 文件 69MB，逐行结果超出单次工具输出上限；已用 `Select-String` 得到各表命中计数与起止样例行号（`:279-285`、`:288-295`、`:152814` 起），并确认 `question_asset` 无 INSERT。第 4 节与第 5 节的数字均为实测计数，非估计 |
| U-2 | `v1-reference/database/backup/xzs_local_system.sql`（161MB）与 `v1-reference/database/archive/source.sql`（6.8MB）的完整行号 | 同上，按结构定性（dump 文件），仅给出代表性行号 |
| U-3 | `v1-reference/database/archive/18_generate_408_subject_papers.sql` 是否存在事务包裹 | 只按命中读取了 `:41`、`:98` 两处，未通读全文 |
| U-4 | `v1-reference/database/archive/17_...sql` 全文是否含 `START TRANSACTION` | 未通读；已确认 S-3（16 号文件）有事务，S-4 未见 |
| U-5 | `QuestionContentMapper.backfillFromLegacy` 是否会破坏「至多一个 current」 | 已有隔离测试存在（`apps/backend/backend-app/src/test/java/com/mindskip/xzs/dbdesign/QuestionBackfillIdempotencyTest.java:118` 名为 `backfillCanCreateASecondCurrentRow`），但本轮只读走查不执行测试，故不落结论 |
| U-6 | 生产机上是否有人工/外部调度调用 `POST /api/admin/ai-config/rag/index` | 代码内无调度者；外部（cron、运维脚本、Postman）无法从仓库确认 |
| U-7 | `AiConfigController` 等 admin 端点的鉴权配置是否真的拦截了 RAG/导入端点 | 本轮未走查 Spring Security 配置，不推断 |

---

## 九、对数据契约设计的影响

### 9.1 哪些入口决定了字段所有权

| 字段族 | 决定性入口 | 契约含义 |
| --- | --- | --- |
| 题干（HTML/纯文本） | W-1/W-3 写 `t_text_content` + `question_content`；Q-4 读时 `question_content` 优先 | `question_content.title/title_text` 是唯一权威；`t_text_content.content.$.titleContent` 必须降级为 legacy 投影。证据：`.../QuestionServiceImpl.java:144-148` |
| 选项 | W-1/W-3 只写 `t_text_content` JSON；`question_content.options` 仅在 W-2 写入、读路径不消费 | 必须先决定 `question_content.options` 是否为权威，否则 Q-7 的忽略是无主字段。证据：`.../QuestionServiceImpl.java:175-182` vs `.../QuestionContentServiceImpl.java:41` |
| 答案 | W-1/W-3 写 `t_question.correct`；W-2 复制进 `question_content.correct_answer`；Q-6 分流读 `t_question.correct` 与 `t_text_content` JSON；Q-14 判分只读 `t_question.correct` | **答案目前有两个权威候选且判分只用旧的**。`question_content.correct_answer` 若要成为权威，Q-6/Q-14 必须一起改 |
| 来源/年份/题号 | 唯一写入者 = 脚本 S-1/S-3/S-4（`question_source`）+ `t_question.source/source_year/source_question_no`；应用层零写入 | `question_source` 是**纯脚本投影**，应用不可维护；Q-15/K-7/K-8 仍全部读 `t_question` 的三列。这支持 Q-04「可重建投影」的判定方向 |
| 图片/附件 | **零写入者**（应用与脚本都没有），只有 DDL 与测试断言 | `question_asset` 确认为休眠表；图片信息目前藏在 `t_question.images` 文本与 `t_text_content` 的 `data-src` HTML 里（证据：`v1-reference/database/archive/16_import_2026_html_mock_exam.sql:29-31` 的 `question-html-ref` + `tags='html,external_html'`） |
| 知识点关系 | 唯一写入者 = 脚本 S-1/S-5；K-1/K-2/K-3 有代码但无调用者；Q-3 读路径完全不消费 | `question_knowledge_point` 是**只读投影**；Q-06「是否升级为权威关系表」当前答案是「否，且需要先补上写路径与失效路径」 |
| RAG 投影版本 | W-17 写 `source_ref='t_question:'+id` 与 `content_hash=SHA2(q.id|tc.content)`；W-18 用 `SHA2(q.id|qc.source_hash)` 判定 | 投影契约必须统一 hash 基准，并把「源版本号」明确成 `question_content.version` 而非 hash，否则失效判定无法闭环 |

### 9.2 哪些入口存在没有事务保护的风险

（按影响面排序，明细见 6.1）

1. **W-5**（JSON 批量导入）：无事务，半批可入库。
2. **W-16 + W-17**（RAG 回填双写）：document 与 chunk 非原子，可产生 `status='ready'` 但无切片的孤儿文档；这类文档因没有切片，**词法召回与向量召回都无对象可用**（措辞限定：这不等于整条检索链路不可用，见 `audit-report.md` 第 12 节的口径说明）。
3. **W-20**（`rag_embedding` upsert）：与正文刷新不同事务，`status='indexed'` 可与已变更正文并存。
4. **W-15**（`question_content` 回填）：无事务，且其 hash 与 W-2 的 Java `sha256` 算法不同（`SHA2(CONCAT_WS(...))` vs `String.format("%02x")`），同一题经两条路径会产生两个不同的 `source_hash`。
5. **W-6 + 下游**（软删除）：删除题目既不置 `question_content.is_current`，也不置 `rag_document.status`，也不清 `question_knowledge_point`；无事务、无级联。
6. **W-9/W-12**（`t_text_content` 在无事务路径写入主观题答案/任务作答）：与 W-7 的通用写入共享同一张表。

### 9.3 迁移时需要一起改的入口

| 迁移动作 | 必须同步修改的入口 | 证据 |
| --- | --- | --- |
| 让 `question_content` 成为读权威 | Q-3/Q-4/Q-5/Q-6/Q-7（`getQuestionEditRequestVM`）、Q-8（学生答题列表）、Q-10（后台列表）、Q-11（试卷回显，间接经 Q-3） | `.../QuestionServiceImpl.java:138-184`；`.../controller/student/QuestionAnswerController.java:58-78`；`.../controller/admin/QuestionController.java:40-54` |
| 让 `question_content.correct_answer` 成为判分权威 | Q-14 判分（`setSpecialFromVM`）、Q-6 答案分流、W-2 的写入来源 | `.../ExamPaperAnswerServiceImpl.java:228-244`；`.../QuestionServiceImpl.java:151-169`；`.../QuestionContentServiceImpl.java:42` |
| 统一 `source_hash` 算法 | W-2（Java `sha256`）、W-15（XML `SHA2`）、W-17（XML `SHA2(q.id|tc.content)`）、W-18（XML `SHA2(q.id|qc.source_hash)`）、以及脚本 S-3/S-4 里手写的 `source_hash` 字面量 | `.../QuestionContentServiceImpl.java:79-91`；`.../mapper/QuestionContentMapper.xml:78`；`.../mapper/RagDocumentMapper.xml:119,175,203`；`v1-reference/database/archive/16_import_2026_html_mock_exam.sql:37,62` |
| 引入题目变更触发器/RAG 失效 | 目前**必须新建**：W-1/W-3（题目写入后）、W-6（软删后）都没有下游调用；同时要改 R-9 的收口点 `AiConfigController.ragIndex` | `.../QuestionServiceImpl.java:79-126`；`.../AiConfigController.java:80-145` |
| 启用 `question_asset` | 需要新建：写入者（当前零）、读路径（Q-3/Q-11 需带上 asset）、以及 S-3/S-4 的 HTML 引用解析（`data-src` → `question-html/...`） | `v1-reference/database/archive/16_import_2026_html_mock_exam.sql:29-31`；`.../QuestionServiceImpl.java:138-184`（无 asset 输出） |
| 启用 `question_source` 为权威 | 需要补写路径（当前 0 个）并改 Q-15/K-7/K-8 从 `t_question.source*` 切到 `question_source` | XML `.../mapper/QuestionMapper.xml:222-231`；`.../KnowledgeGraphServiceImpl.java:294,341-347` |
| 把 `question_knowledge_point` 升级为权威关系表 | 需要启用 K-1/K-2/K-3（当前无调用者）并在 Q-3 回显与判分链路中消费；同时要接上 K-11 的软删失效 | XML `.../mapper/QuestionKnowledgePointMapper.xml:20-31`；`.../KnowledgeGraphServiceImpl.java:217-257` |
| 提供非「纯脚本」的导入通道 | W-5 需要补齐 `question_content` 版本行与 `question_source`；W-1 需要补齐来源字段（`QuestionEditRequestVM` 当前无这些字段） | `.../viewmodel/admin/question/QuestionRequestVM`→`QuestionEditRequestVM.java:13-37`；`.../QuestionController.java:113-151` |

### 9.4 给下一组（第 2-4 组）的直接输入

1. **Q-04（`question_source` 是否可重建投影）**：可重建——应用层零写入、`t_question` 三列仍在被读、脚本可幂等重建。证据密集在 `RagDocumentMapper.xml` 之外的 `13_backfill_canonical_ai_data.sql:48-68`。
2. **Q-05（`question_asset` 启用还是休眠）**：休眠——零写入者、ddl 存在、三处 dump 的数据段为空、仅被测试断言引用。
3. **Q-06（`question_knowledge_point` 是否升级为权威）**：当前是只读投影；升级前必须先解决 K-1/K-2/K-3 无调用者与无失效路径两个前置条件。
4. **DB-09（RAG 投影契约）**：必须写死「源版本号 = `question_content.version`」并统一 `content_hash` 的基准表达式，否则 W-18 的 `WHERE rc.content_hash <> ...` 恒真。
5. **DB-05（事务边界）**：W-15/W-16/W-17/W-20 与 W-6 的下游失效是四处必须补齐的边界。
