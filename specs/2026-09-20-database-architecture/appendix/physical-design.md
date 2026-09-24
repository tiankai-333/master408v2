# 物理结构设计 —— 题目领域（批次 4 + 2026-09-23 定稿附录）

状态：**设计与隔离验证完成，未实施**。本批次只做设计与隔离库实验：**未修改共享库结构，未修改任何业务代码**。
依据：`requirements.md` D-13～D-28、`data-contract.md`（3.9/4.6/6.4 定稿）、`audit-report.md`、
`design-test-plan.md` 第 5 节、`entry-inventory.md`、`sample-2024-q43.md`。
日期 2026-09-21（批次 4）、2026-09-23（第 10～13 节：块/发布/资源/就绪状态的物理定稿），代码提交 `c2c0a84`。

> **EXPLAIN 口径声明（重要）**：本文中所有 `EXPLAIN` 结果只说明**优化器在给定数据与语句下选择了哪条计划**，
> **不能**据此宣称实际性能提升。真实收益必须在实施阶段用固定数据、固定参数、多轮测量另外验证。

## 1. 实验环境与样本

| 项 | 值 |
| --- | --- |
| 库 | `master408_design_test`（隔离库，结构来自 V5 导出，Flyway V0–V5） |
| MySQL | 8.0.36（候选 B 的函数索引需 ≥ 8.0.13） |
| 合成样本 | 6613 道题 + 7013 行内容（6613 题各 1 版；另 200 题各 3 版，仅 v3 为 current），与真实基数一致 |
| 脚本 | [`ddl/batch4-experiment.sql`](ddl/batch4-experiment.sql)（可重复执行，跑完自动清理） |
| 原始日志 | `.data/batch4-experiment.log` |
| 清理验证 | 实验后 `t_question = 0`、`question_content = 0`、候选列/索引均已移除 |

## 2. 候选 1：current 唯一性（D-13，生成列 + 唯一键）

DDL（与 `ddl/current-uniqueness-strict.sql` 一致）：

```sql
ALTER TABLE question_content
  ADD COLUMN current_question_id INT
    GENERATED ALWAYS AS (IF(is_current = b'1', question_id, NULL)) VIRTUAL;
ALTER TABLE question_content
  ADD UNIQUE KEY uk_question_content_current (current_question_id);
```

**约束能力（隔离库实测，独立于 JUnit）**：

```text
ERROR 1062 (23000): Duplicate entry '999001' for key 'question_content.uk_question_content_current'
```

即：同一题插入第二个 `is_current=b'1'` 被数据库拒绝。与 T1-3/T1-5 的结论一致。
仍然只保证「**至多一个**」——没有 current 行的题目可以照常写入（T1-4），所以 D-14 的存在性保证
必须在事务与业务层实现（见第 5 节）。

## 3. 候选 2：读路径 —— 不一定需要新增索引

真实读法（`QuestionContentMapper.xml:25-32`）：

```sql
SELECT * FROM question_content WHERE question_id = #{questionId} AND is_current = b'1'
ORDER BY version DESC LIMIT 1
```

| 读法 | type | key | rows | Extra |
| --- | --- | --- | --- | --- |
| 现状：`question_id + is_current` | ref | `idx_question_content_current` | 2 | **Using filesort** |
| 现状 + 新增 `idx_qc_question_current_version(question_id,is_current,version)` | ref | `idx_qc_question_current_version` | 1 | Backward index scan |
| **候选 A 的列：`WHERE current_question_id = ?`** | **const** | `uk_question_content_current` | 1 | **无排序** |
| 版本历史：`WHERE question_id = ? ORDER BY version DESC` | ref | `uk_question_content_version` | 3 | Backward index scan（现状已足够） |

**结论与建议**：

- 若采用候选 A，**优先把读取语句改写为 `WHERE current_question_id = ?`**：一条唯一索引即
  `const` 命中，且不需要排序，**无需新增索引**（新增索引方案的收益与之重叠）。
- `idx_question_content_current(question_id,is_current)` **是否保留**取决于其他消费者：
  A-03 的"每题 current 计数"相关子查询、以及"取某题所有 current 行"的语义仍用它。
  **不凭本次实验删除任何现有索引**（实验只覆盖了两条语句）。
- 版本历史读取无需新索引，现有 `uk_question_content_version` 已经给出 Backward index scan。

## 4. 候选 3：D-17 开始作答时绑定不可变版本

**现状与缺口**：

- 现有绑定发生在**交卷时**：`ExamPaperAnswerServiceImpl:202`（"用户提交答案的转化存储对象"）
  在 `:212` 写入 `questionTextContentId = question.getInfoTextContentId()`；
  而该 `t_text_content` 行会被 `QuestionServiceImpl:120-122` **就地改写**。
- **未发现**独立的"开始作答"落库点（`entry-inventory.md` 的写入口清单里没有该路径；实现阶段需再确认）。

**候选字段**：

```sql
ALTER TABLE t_exam_paper_question_customer_answer
  ADD COLUMN question_content_id INT NULL COMMENT 'D-17：开始作答时绑定的不可变内容版本行 id';
CREATE INDEX idx_epqca_question_content ON t_exam_paper_question_customer_answer (question_content_id);
```

绑定对象是 `question_content.id`（**追加式版本行**：`saveFromEdit` 只插入新版本、只翻转 `is_current`，
`backfillFromLegacy` 只在缺 `version=1` 时插入），因此一旦绑定即不可变。

**落点选择（两个候选，实现阶段定）**：

| 候选 | 做法 | 代价 |
| --- | --- | --- |
| 3a 答题记录骨架 | 开始作答时先为每道题建一行答题记录，写入绑定 | 改动现有"交卷才建记录"的流程，影响统计口径 |
| **3b 版本快照映射（推荐）** | 开始作答时生成"本次作答 → 各题绑定版本"的映射（挂在作答实例上或独立关联表），交卷时答题记录引用该映射 | 新增一张关系表或一个实例级结构；不改变答题记录的现有语义 |

**读取规则（契约要求）**：展示题面与解析、自动判分、历史回看**全部按绑定版本读取**，
不得回退到 `is_current`。现状判分读 `question.getCorrect()`（当前位置），与 D-17 冲突，实施时必须一并改。

**失败行为**：绑定那一刻找不到可用版本 → **拒绝开始作答并明确报错**（D-14），不得静默用旧版或跳过。

## 5. 候选 4：D-16 乐观并发校验必须与写入同事务

**不能这样做**（存在 check-then-act 窗口）：

```text
事务1: SELECT version → 与 expectedVersion 比较 → 通过
事务2: clearCurrent + insert          ← 两个请求都可能通过比较，然后依次覆盖
```

**锁顺序（2026-09-21 修正）：先锁稳定的题目主记录，再读当前版本。必须同一受保护事务内完成。**

```sql
-- 同一受保护事务内，按固定顺序执行
SELECT id FROM t_question WHERE id = ? FOR UPDATE;            -- ① 先锁主记录（身份稳定）
SELECT id, version FROM question_content
  WHERE question_id = ? AND is_current = b'1';                -- ② 再读当前版本（普通读即可）
-- ③ 校验 expectedVersion 与该行 version 一致；不符 → 抛业务冲突异常，整体回滚
UPDATE question_content SET is_current = b'0' WHERE question_id = ? AND is_current = b'1';
INSERT INTO question_content (...) VALUES (...);               -- ④ 新版本 + 切换 current
```

为什么锁主记录而不是锁当前版本行：

- `t_question.id` 的**身份稳定**，不随版本切换而改变；锁当前版本行会在 `clearCurrent` 之后失去锁对象；
- 锁主记录**同时覆盖"该题尚无 current 行"**的情形（此时没有版本行可锁）；
- 版本号生成（`SELECT MAX(version)+1`）与 `clearCurrent`/`insert` 都在同一把锁保护下，
  竞态从"两个请求读到同一版本号"变成"必然串行"。

配套约束：

- **所有相关写入入口必须遵守同一锁顺序**（先 `t_question`、后 `question_content`），否则形成交叉加锁
  顺序、引发死锁；`entry-inventory.md` 的写入口清单需逐一核对；
- `expectedVersion` 由前端在**打开编辑页时**记录并在提交时回传（D-16）；
- 唯一键（`uk_question_content_version` 与候选 A 的 `uk_question_content_current`）**保留为最后一道防线**，
  用于覆盖绕过乐观校验的旁路写入；
- 冲突响应：业务错误码 + "该题已被他人更新，请刷新后重试"，**前端必须保留用户未保存的输入**（D-15）；
- 失败整体回滚，不得留下 0 个 current 的半成品（T2-3 已验证当前实现能满足）。

**实验要求（2026-09-21 追加）**：隔离库的并发验证**不能只跑简化 Mapper 序列**
（`selectMaxVersion → clearCurrent → insert`），必须覆盖**真实写入流程**，即经由
`QuestionContentServiceImpl.saveFromEdit` 及其上游 `QuestionServiceImpl.updateFullQuestion` 的并发调用。
本批次已补该实验，见 `design-test-plan.md` 第 5 节 T2-5。

## 6. 候选 5：D-20 人工标注留痕

```sql
ALTER TABLE question_knowledge_point
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT 'auto=自动生成 / manual=人工标注',
  ADD COLUMN update_user INT NULL,
  ADD COLUMN update_time DATETIME NULL;
CREATE INDEX idx_qkp_source ON question_knowledge_point (source);
```

**自动任务不得覆盖人工结果**的最小判据（写语句自带条件）：

```sql
UPDATE question_knowledge_point SET relevance = ?, update_time = NOW()
WHERE id = ? AND source <> 'manual';
```

另按 D-20：643 条孤儿关系（A-04）**先隔离查明原因**，不凭题意猜测对应关系；人工添加/移除要记录
修改人与时间（由上面的列承载）。

## 7. 候选 6：D-21 停用语义与"紧急撤下"的边界

设计选择：**复用 `t_question.status` 表达可用性**（1=正常，2=停用，3=紧急撤下），并补留痕列，
不新增审批相关结构（用户明确排除完整审批系统）：

```sql
ALTER TABLE t_question
  ADD COLUMN status_reason VARCHAR(255) NULL COMMENT '停用/撤下原因',
  ADD COLUMN status_update_user INT NULL,
  ADD COLUMN status_update_time DATETIME NULL;
```

- 逐场景行为见 `requirements.md` D-21 与 `data-contract.md` 第 7 节；
- **边界**：停用 = 阻止新使用、保留历史；紧急撤下 = 因版权/安全需要立即撤下内容，是**另一种操作**，
  本文只定义其边界（独立状态值 + 原因 + 留痕 + 是否允许历史可读需产品确认），不扩展为审批流程。
- 新组卷/新入卷必须排除非正常题：`WHERE deleted = b'0' AND status = 1`。
  在本次合成样本中**所有题目 status=1**，优化器对该条件选择全表扫描（`possible_keys=idx_status`, `key=NULL`）。
  **该结果既不证明索引无益、也不证明有益**——它只说明"在 100% 行都匹配的数据分布下，全表扫描是合理选择"；
  是否需要 `(deleted, status)` 复合索引必须在实施阶段用真实分布再测。

## 8. 108 条历史答卷的绑定迁移（D-06）

**证据（只读查询，真实库 `xzs`）**：

| 事实 | 值 |
| --- | --- |
| `question_content` 创建时间 | 2026-05-27 20:41:17 – 20:41:30（回填） |
| 答题记录创建时间 | 2026-05-28 15:00:58 – 2026-07-24 19:05:13 |
| 落在"版本行创建之后"的答题记录 | **108 / 108**（67 道题） |
| 拥有多个版本的题目 | **0** |
| 通过 `legacy_text_content_id` 找不到内容行的答题记录 | 0（A-10） |

**统一采用三态口径（2026-09-21 修正）**：

| 状态 | 判据 | 允许的动作 |
| --- | --- | --- |
| **已证实** | 有足够证据支持准确恢复 | 可写入权威历史绑定 |
| **候选匹配、未证实** | 只能记录候选版本与依据，缺少对历史写入/读取路径的证明 | **只记录候选，不写入权威历史绑定** |
| **不可恢复** | 缺少必要内容或证据 | 按 D-06 显式标记，不用当前版本补齐 |

**这 108 条的判定：全部归入「候选匹配、未证实」，暂不执行绑定。**

理由（修正原先"可恢复"的结论）：

- "作答晚于 v1 创建、且现在只有一个版本"只能说明 **v1 是一个候选来源**，**不能证明**当时读取到的内容
  就是这一行；还可能有旧写入入口、直接修改、或版本记录缺失；
- 当时题面来自**多处数据**（题干/解析来自 `question_content`，选项来自 `t_text_content` 的 JSON，
  答案来自 `t_question.correct`），**不能把部分字段的可能一致推导为整题一致**——必须按字段分别判定；
- **"没有发现变化"不等于"证明没有变化"**：`t_text_content` 没有 `update_time`，无法证伪就地改写。

因此：

- **题面、解析**：候选版本 = 该题当前 `question_content` 行；依据 = 作答时点晚于该行创建、且当前无多版本。
  状态仍为「候选匹配、未证实」。
- **选项**：候选来源 = `t_text_content.content`；状态「候选匹配、未证实」（就地可变、无 `update_time`）。
- **答案**：候选来源 = `t_question.correct`（可变列，无留痕）；状态「候选匹配、未证实」。

**迁移执行约束**：本 Feature 只登记候选与依据，**不写入任何权威历史绑定**；要升级为「已证实」，
必须先补齐对历史写入/读取路径的证明（例如找到当时的写入日志、备份或快照），这在当前证据下不成立。
实施阶段若要绑定，只能绑定**新发生**的作答（D-17 生效之后）。

## 9. 迁移影响汇总（设计层）

| 结构变化 | 影响的写入路径 | 影响的读取路径 | 回滚动作 |
| --- | --- | --- | --- |
| `question_content.current_question_id` + `uk_question_content_current` | 所有 `question_content` 插入（生成列自动维护） | 可改写 `selectCurrentByQuestionId` 为按列查 | 删索引 → 删列（顺序不可颠倒） |
| `t_exam_paper_question_customer_answer.question_content_id` | 新增"开始作答"绑定写入（待实现） | 题面/判分/历史回看改为按绑定版本读 | 删索引 → 删列；旧读法仍可用 |
| `question_knowledge_point.source/update_user/update_time` | 人工标注写入 | 管理端展示来源与留痕 | 删索引 → 删列 |
| `t_question.status_reason/status_update_user/status_update_time` | 停用/撤下写入 | 组卷与检索过滤 | 删列（`status` 语义回退需同时回滚代码） |
| `question_content.content_blocks`（候选 7） | 转换器/编辑路径写入块 JSON | 渲染按块读；`*_text` 重算 | 删列（旧列语义不受影响） |
| `question_content.published/published_at`（候选 8） | 保存事务内对 current 行置位 | 管理端区分"曾发布版本" | 删列（默认值向后兼容） |
| `question_asset.block_id/content_hash/size_bytes/availability/last_checked_at`（候选 9） | 资源写入者（TD-016，待实现） | 资源可得性展示、块级归属查询 | 删列（表当前 0 行） |
| `rag_document.source_content_version/display_ready/retrieval_ready/gen_rule_version`、`rag_chunk.source_block_id`、`rag_embedding.source_content_version`（候选 10） | RAG 投影刷新路径（TD 登记的改造） | 检索降级判断（4.6 矩阵）、对账 | 删列；`status` 语义回退需同时回滚代码 |

**共性风险**：所有新增列都允许为 NULL 或有默认值，因此**旧代码在迁移后仍可运行**（向前兼容）；
但"读路径是否切换到新列"必须与写入路径同批发布，否则会出现新的"只写不读"字段。

## 10. 候选 7：D-23 内容块的物理落库形态（2026-09-23 定稿，回应 U-11）

**选择：版本行内 JSON 块列**——`question_content` 新增 `content_blocks JSON NULL`；**不建块表，不复用 `t_essay_question`**。

理由：

- 版本行已不可变、追加式（`data-contract.md` 3.4；T3-2 实测）→ 块随版本**原子读写**，渲染一次取整行，无额外 JOIN；
- 块不作为独立查询主体：检索走 `*_text` 投影与 `rag_chunk`（chunk 携带块标识，候选 10），管理端编辑按版本取整行；
- 块表会把行数放大（题数 × 块数 × 版本数），而当前没有"按块独立索引查询"的需求；
- 资源归属（D-26）用 `(question_content_id, block_id)` 二元组表达，不依赖块表存在。

块 JSON 逻辑 schema（实施时可加 JSON Schema 校验）：

```json
{
  "blocks": [
    {"id": "b01", "type": "title",        "seq": 1, "html": "…", "assetRefs": []},
    {"id": "b02", "type": "material",     "seq": 2, "label": "图a", "html": "…", "assetRefs": ["a1"]},
    {"id": "b03", "type": "sub_question", "seq": 3, "score": 5, "html": "…", "assetRefs": ["a1", "a2"]},
    {"id": "b04", "type": "answer",       "seq": 4, "subOf": "b03", "text": "…"},
    {"id": "b05", "type": "analysis",     "seq": 5, "subOf": "b03", "html": "…", "assetRefs": ["a3"]}
  ]
}
```

约束（对应 `data-contract.md` 3.9）：`id` 块内唯一、`seq` 严格递增、
`type ∈ {title, material, sub_question, answer, analysis}`；`subOf` 只允许指向 `sub_question` 块；
`answer.text` 允许为空（D-28，空 = 合法状态）；单块降级 = 整个 `blocks` 数组只有一块 `title`。

**U-11 结论：`t_essay_question` 不复用**。依据：98 行中 `sub_questions` 0 行有值、`answer` 仅 3 行、
`analysis` 仅 4 行，2024/43 行 `total_score=10` 与正文 13 分不符（`sample-2024-q43.md` + 只读复核）；
且该表**每题一行、无版本概念**，与"版本行内多块"的模型冲突。处置：保持休眠——不登记为事实源、
不迁移、不删除（D-04 同类处理）；未来若做简答题结构化再另行论证。

**与既有列的关系（防"第三份内容源"）**：过渡期 `content_blocks` 由转换器从现有列**派生**
（`title` → title 块、`analysis` → analysis 块、旧 JSON → 选项不变仍走 `options` 列），
块列与既有列的一致性由转换器 + 迁移对账保证；何时把块列升级为权威、既有列转入只读兼容，
按 D-27 的"停写 → 切读 → 确认 → 观察"节奏在实施 Feature 中定，本设计不提前切换。

```sql
ALTER TABLE question_content
  ADD COLUMN content_blocks JSON NULL COMMENT 'D-23：结构化内容块（3.9 schema）；NULL=未转换';
```

## 11. 候选 8：D-24 发布标记（2026-09-23 定稿）

```sql
ALTER TABLE question_content
  ADD COLUMN published BIT NOT NULL DEFAULT b'0' COMMENT 'D-24：该版本曾对外使用',
  ADD COLUMN published_at DATETIME NULL COMMENT '首次置 1 的时间';
```

- 语义：保存产生新版本默认 `0`；版本成为 current 且对外可用时置 `1`（保存事务内，见 `data-contract.md` 6.4）；**置 1 后不回退**（"曾经对外"是不可逆事实）；
- 历史绑定（D-17）绑版本行，与 `published` 无关——该标记只服务管理端区分"曾发布的历史版本"与"仅保存过的版本"；
- **不表达草稿箱/审校流**（D-24 明确不做完整草稿工作流；将来扩展按 D-20 留痕模式）。

## 12. 候选 9：D-26 资源引用落库形态（二选一定稿：启用 `question_asset` 补列）

**选择：启用 `question_asset` 并补列**，不另建资源引用表。

理由：现有列已覆盖 D-26"归属 + 定位"骨架（`question_id`/`question_content_id`/`asset_type`/
`asset_url`/`sort_order`/`alt_text`），另建新表将重复约 90% 列定义；且该表全库 0 行、零写入者
（A-07，3.6）→ 启用**无历史包袱、无双表迁移**；已有索引 `idx_question_asset_question(question_id, sort_order)`。

```sql
ALTER TABLE question_asset
  ADD COLUMN block_id VARCHAR(64) NULL COMMENT 'D-23/D-26：归属内容块（content_blocks.blocks[].id）',
  ADD COLUMN content_hash CHAR(64) NULL COMMENT '文件内容 sha256；与 size_bytes 组合校验',
  ADD COLUMN size_bytes BIGINT NULL,
  ADD COLUMN availability TINYINT NOT NULL DEFAULT 0 COMMENT '0=未校验 1=存在 2=缺失 3=不可用',
  ADD COLUMN last_checked_at DATETIME NULL;
```

口径约束：

- 归属 = `(question_content_id, block_id)`，解析资源与题干资源必须可区分（DB-12），经其挂载块的 `type` 表达；
- `storage_key` **维持可空**（契约红线：不强制对象存储）；
- `source_ref` 与 `rag_document.source_ref` 同名不同义（3.6 警告行）——启用时在字段字典显式区分，避免重演 A-09 口径错误；
- `availability` 由校验任务维护（前提是先有资源写入者，TD-016），**不是猜测值**；
- 更新文件 = 新 asset 行或新 `content_hash`，不得原地换文件不换身份（`sample-contract.md` S-04；样本 U-08）。

## 13. 候选 10：D-25 就绪状态与 DB-09 源版本的字段落点（2026-09-23 定稿）

```sql
ALTER TABLE rag_document
  ADD COLUMN source_content_version INT NULL COMMENT 'DB-09：投影来源的 question_content.version',
  ADD COLUMN display_ready BIT NOT NULL DEFAULT b'0' COMMENT 'D-25：展示就绪',
  ADD COLUMN retrieval_ready BIT NOT NULL DEFAULT b'0' COMMENT 'D-25：检索就绪（权威判定可派生，见下）',
  ADD COLUMN gen_rule_version VARCHAR(32) NULL COMMENT 'D-26/D-08：正文生成规则版本';
ALTER TABLE rag_chunk
  ADD COLUMN source_block_id VARCHAR(64) NULL COMMENT 'D-23：切片来源内容块';
ALTER TABLE rag_embedding
  ADD COLUMN source_content_version INT NULL COMMENT '该向量与哪个源版本一致';
```

- `retrieval_ready` 双轨：落列便于过滤与对账；**权威判定 = "存在 `rag_embedding` `indexed` 且其
  `source_content_version` = `rag_document.source_content_version`"**（4.6）；两者不一致时以派生判定为准并触发对账；
- 现状 `status='ready'` 保留为文档层生命周期标记，**不再承担就绪语义**（4.3 定稿；2839 个"ready 无向量"文档是本改造的直接动因）；
- 失效动作：源版本变更 → 按题置 `retrieval_ready=0`（标记，不删数据），重试与对账规则见 4.6；
- 降级矩阵的读侧消费（词法兜底、不进入自动解答等）是查询层行为，不需要额外结构。

## 14. 设计测试与红线复核（plan 第 3 组"候选约束隔离设计测试"项）

- **候选约束隔离设计测试已存在**：`apps/backend/backend-app/src/test/java/com/mindskip/xzs/dbdesign/` 下
  `QuestionContentContractTest`（T1，显式建立并清理候选 DDL `current_question_id`/`uk_question_content_current`）、
  `QuestionContentConcurrencyTest`（T2，含 T2-5 真实写入流程并发）、`QuestionVersioningTest`（T3）、
  `QuestionBackfillIdempotencyTest`（T4）；只连隔离库 `master408_design_test`，写探针事务内回滚；
- **主线契约测试保持现状断言**：`DatabaseSchemaContractTest` 只断言共享库 `master408_v2` 的现有结构
  （表清单等），**无任何候选 DDL 引用** → 满足"不让设计测试要求共享库提前升级"；
- 候选 7～10 的**隔离验证留待实施 Feature**（与候选 3 的"开始作答"落库点同批）：本设计只承诺
  语句可执行性与回滚动作（删列即可），不做提前实验——共享库结构不得因本 Feature 变动（红线 2）。

## 15. 本批次未做与待验证（2026-09-23 更新）

- 未在共享库执行任何 DDL；未修改业务代码；
- 未测候选 3 的真实写入路径（"开始作答"落库点尚不存在，实现阶段需先确定 3a/3b）；
- 未测 `(deleted, status)` 复合索引在真实分布下的表现；
- ~~未验证"停用后 RAG 返回前过滤"的实现位置~~ **已设计定稿**（2026-09-23）：降级矩阵与对账见
  `data-contract.md` 4.6，字段落点见候选 10；实现与验证在实施 Feature；
- 候选 7～10 未做隔离库实验（本设计只承诺语句可执行性与回滚动作；共享库不得提前变动，红线 2）；
- `EXPLAIN` 不等于性能结论；所有索引取舍须在实施阶段复测。
