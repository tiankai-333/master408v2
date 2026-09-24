# 数据契约 — 题目领域字段所有权、题型语义与管理端承诺

> **状态**：**主体定稿（2026-09-23）**。字段级三列、五题型契约、可承诺/不可承诺清单、
> 内容块契约（3.9）、RAG 就绪与降级契约（4.6）、生命周期事务边界（6.4）、下游失效语义（7.3）已完成；
> Q-01 至 Q-13 已全部拍板（D-13～D-28），16 项待验证全部收口（[`pending-verification.md`](appendix/pending-verification.md)）。
> 仍开放：物理结构附录（第 3 组）、迁移阈值数字（D-11，实施阶段推导）。
> **日期**：2026-09-21 初稿；2026-09-23 按 D-23～D-28 定稿
> **依据的代码提交**：`c2c0a84`（工作区含 19 项未提交改动，与 `audit-report.md` 头部一致；
> 本文件新增的代码走查结论以该提交的 main 代码为准）
> **依据的决策**：`requirements.md` 的 D-01 至 D-28（Q-01 至 Q-13 已全部拍板；D-23～D-28 于 2026-09-23 确认）
> **依据的事实**：`audit-report.md`（A-01 至 A-13）、`entry-inventory.md`（I/B/K/R/S/W/Q/U 编号）、
> `design-test-plan.md` 第 5 节（T1 至 T4 实测）、`sample-contract.md`（S-01 至 S-06）、
> `sample-2024-q43.md`（第 43 题样本事实 + 原创夹具）、`pending-verification.md`（16 项待验证处置）
> **本轮性质**：只读设计。未修改任何业务代码，未连接数据库，未执行任何 DDL 或 DML。

## 本文件仍未定稿的部分

| # | 未定稿内容 | 卡在什么上 | 影响章节 |
| --- | --- | --- | --- |
| 1 | 所有一致率/成功率/覆盖率数字 | D-11 禁止预设阈值；在实施阶段由审计基线推导 | 第 7 节、第 8 节 |
| 2 | 物理结构附录（字段字典、索引、约束、外键适用范围）与块/资源的物理落库形态 | 属第 3 组交付物（含 D-23 块形态、D-24 发布标记字段、D-26 资源表二选一、U-11 `t_essay_question` 定位） | `physical-design.md` |

以下原未定稿项**已定稿**：权威内容格式（→D-23，3.9）、草稿与发布（→D-24）、`t_text_content` 退出时点
（→D-27）、RAG 就绪降级（→D-25，4.6）、资源与派生映射（→D-26，3.6/4.5）、题型 3/4 语义（→D-22）、
简答题空答案（→D-28，5.5）、选项 JSON 字段级规范（→`pending-verification.md` #1/#2：抽样一致，
逐字段人工对照转实施验收）。

## 1. 使用说明

### 1.1 证据指针约定

- `A-xx` → `audit-report.md` 的审计编号与其 SQL 原文、真实结果；
- `I-/B-/K-/R-/S-/W-/Q-/U-` → `entry-inventory.md` 的条目编号（读入口 Q- 与题号 `Q-08` 决策编号不同，正文中一并写出以免混淆）；
- `T-x-y` → `design-test-plan.md` 第 5 节的实测结果；
- `D-xx` → `requirements.md` 的已确认决策；
- `文件:行号` → 直接代码/脚本证据，路径相对仓库根。

### 1.2 `[已确认]` / `[待验证]` 的判定口径

| 标记 | 含义 |
| --- | --- |
| `[已确认]` | 至少有**运行期证据**（A 编号的真实计数、T 编号的实测、或可复现的脚本/代码走查）支持；且证据指针可被第三方按行号重放 |
| `[待验证]` | 只有静态走查、无样本、或存在互相矛盾的证据；每条"待验证"必须写出未验证原因 |

**注意**：「脚本写入 + 代码走查确认无人读」= 已确认（可以重放）；「表为空/字段全空所以推断没问题」= 待验证（无样本，A-09 的整组 0 就是这种假阴性）。

### 1.3 本文件的字段覆盖数（自计量）

| 契约表 | 章节 | 字段行数 |
| --- | --- | ---: |
| `t_question` 内容列组 | 3.2 | 10（9 个内容列 + `images`） |
| `t_text_content` | 3.3 | 5（其中 `content` 按"题目语义 / 试卷·任务·答案语义"拆两行） |
| `question_content` | 3.4 | 18（含 `(question_id,version)` 唯一键与新增生成列 `current_question_id`） |
| `question_source` | 3.5 | 11 |
| `question_asset` | 3.6 | 9 |
| `question_knowledge_point` | 3.7 | 4 |
| RAG 投影（`rag_document`/`rag_chunk`/`rag_embedding`） | 4.3 | 13（其中 `rag_embedding.*` 一行合并 10 个字段） |
| **合计** | 第 3、4 章 | **70 行 / 约 79 个字段** |

另：第 5 章按"契约项"给出 5 种题型的 **52 行**内容与评分契约；第 8 章给出可承诺 **17** 项、
不可承诺 **28** 项（其中第 26/27 项已按 D-24/D-23 改写定性）；第 10 章的 16 项待验证
**已全部收口**（→ [`pending-verification.md`](appendix/pending-verification.md)）。

### 1.4 本文件不做的事

- 不预设一致率、成功率、覆盖率阈值（D-11）；
- 不做物理表结构设计，块的物理落库形态与资源表二选一留给物理结构附录（D-23/D-26 的契约级要求在本文件）；
- 不把「数据库已有一行引用」当作「富文本已保存」（`workflow.md` 第 6 节①）；
- 不因本文件而删除表、列或切换线上读路径（范围/非目标）。

### 1.5 决策速查（本文件引用的 D 编号原文摘要）

| 编号 | 本文件要落实的点 |
| --- | --- |
| D-01 | `t_question` = 身份 + 业务属性聚合根；重复内容列进只读兼容，不立即删除 |
| D-02 | `question_content` = 题干/选项/答案/解析及版本历史的权威载体 |
| D-03 | `t_text_content` 按引用它的业务类型分别退出 |
| D-04 | `question_source` / `question_asset` / `question_knowledge_point` 先不接入主链、不删除 |
| D-06 | 无法恢复历史版本时显式标记不可恢复，不用当前版本补齐 |
| D-13 | current 唯一性用**生成列 + 唯一索引**（候选 A，`ddl/current-uniqueness-strict.sql`） |
| D-14 | 数据库只保证"至多一个 current"；"必须有一个"靠事务与业务校验；保存失败整体回滚；进考试无可用版本必须拒绝并报错；巡检只发现异常 |
| D-15 | 并发编辑拒绝并要求刷新；保留用户未保存输入；不自动合并、不静默覆盖 |
| D-16 | 提交编辑携带"打开编辑页时的版本号"，做乐观并发校验 |
| D-17 | 历史作答在**开始作答时**绑定不可变题目版本；展示/判分/回看统一用该版本；交卷只存答案与结果 |
| D-18 | 管理端展示来源；缺失字段显式为空不推测；原始采集信息保留，人工修改另留记录 |
| D-19 | 图片与外部 HTML 的资源契约在本期；素材库/OCR/对象存储不做 |
| D-20 | 允许人工增删题目-知识点关联并记录修改人与时间；自动任务不得覆盖人工结果 |
| D-21 | 软删除改称「停用」，逐场景行为见 requirements D-21 |
| D-22 | 题型编号统一 3=判断、4=填空（后端与学生端为准）；管理端为偏差方 |
| D-23 | 权威内容格式 = **结构化内容块**（题干/公共材料/小问 1..n/答案/解析），块内原文保留 HTML 不过度解析；`*_text` 按规则从块重算；拆不出者保留单块整段降级；物理落库形态在物理结构附录定稿 |
| D-24 | **简化版本 + 显式发布标记**，不做完整草稿工作流；历史绑定与发布无关 |
| D-25 | **展示就绪与检索就绪两个独立状态**，分级降级；展示未就绪不进入自动解答；检索未就绪只用词法召回并标注 |
| D-26 | 资源 = **引用 + 校验值 + 归属**（版本/块/题干或解析 + 定位 + 校验 + 可得性）；派生结果带源版本与生成配置；不强制对象存储；落库形态在物理结构附录二选一 |
| D-27 | `t_text_content` 题目部分**分字段退役、整表保留**；R-1～R-4 满足后按"停写→切读→确认无依赖→观察"退出 |
| D-28 | 简答题 `correct_answer` **允许为空且不自动补齐**（合法状态）；成因实施前由内容负责人最终确认 |

---

## 2. 契约的四个身份概念（先定名，再谈字段）

字段所有权之所以反复出现"两个副本"，根因是同一道题有四种不同身份被混用。本契约统一使用下表名称。

| 身份 | 定义 | 载体 | 是否可变 |
| --- | --- | --- | --- |
| **题目身份** | 一道题本身 | `t_question.id` | 永久稳定（A-02：无缺 id、无悬空 id） |
| **内容版本** | 一次保存产生的完整内容（题干+选项+答案+解析） | `question_content.id`，等价唯一键 `(question_id, version)` | **不可变**：追加式写入，永不被 UPDATE 内容列（T3-2 实测 v1/v2/v3 并存） |
| **当前版本指针** | 该题"现在对外可用"的那一版 | `question_content.is_current` | 可变，且**至多一个**（D-13/D-14） |
| **兼容表示** | 旧链路表示，供尚未切换的读路径使用 | `t_question` 内容列、`t_text_content.content` JSON | 可变，随一次保存与内容版本同时被覆盖 |

判分与历史回看只允许引用**内容版本**，不允许引用**兼容表示**（D-17 直接结论；现状违反点见第 7.2 节）。
RAG 投影的来源身份同样只允许是**内容版本**，不允许是 hash（第 4.5 节）。

---

## 3. 字段级数据契约表

### 3.1 读表方法

每行给三列：

1. **当前读写位置** —— 谁写、谁读、证据指针；
2. **目标权威位置** —— 依据哪条 D 决策；
3. **兼容策略** —— 保留为只读副本 / 派生投影 / 待退出的时机与条件。

「写入者」用 `entry-inventory.md` 的 W-/I-/S-/R- 编号；「读取者」用 Q-/K-/R- 编号；
「应用层零写入」指全仓库 `INSERT`/`UPDATE` 语句扫描无命中（`entry-inventory.md` 的检索方式）。

**为什么下方表格里 `[已确认]` 远多于 `[待验证]`**：本表确认的是**写入者/读取者这一事实层**
（谁写谁读可由语句扫描与行号重放），不是**取值语义层**。
取值语义不确定的地方不会降低整行的标记，而是在"兼容策略"列内单独写"未验证原因"，
并集中登记到第 10 节。因此 `[已确认]` 的行**不代表该字段的取值已被验证**，只代表"它现在被谁读写"已被验证。
唯一例外是：整行结论本身依赖取值语义时（如 `embedding`、`paper_name`），整行标 `[待验证]`。

### 3.2 `t_question` 内容列组（9 列，全部为兼容表示）

**前置事实（本轮走查新增，A-06 无法覆盖这一层）**：`Question` 域对象只有
`id/questionType/subjectId/score/gradeLevel/difficult/correct/infoTextContentId/createUser/status/createTime/deleted`
12 个字段（`domain/Question.java:14-66`），**没有** title/options/correct_answer/analysis/title_text/analysis_text/images/content_format 字段；
`QuestionMapper.xml` 的 `Base_Column_List`（`:18-21`）与 `selectByPrimaryKey`（`:22-27`）、
`selectForAiPaper`（`:254-263`）、`selectMistakesForAiPaper`（`:265-282`）**都不选这些列**。
因此这 9 列在 MyBatis 读路径上**不可达**，只被脚本与回填语句读。
这使 D-01 的"重复内容列"实际是**写侧与脚本侧的遗留，而非读侧分歧源**——读侧分歧源全在 `t_text_content.content` 的 JSON 上（3.3 节）。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `title` | `[已确认]` | 写：脚本 S-3/S-4/S-7（`16_import_2026_html_mock_exam.sql:23` 起、`17_import...sql:288` 起、`current/03_questions_and_exams.sql:112` 起）；运行时**无写入**（`setQuestionInfoFromVM` 只写 TextContent，`QuestionServiceImpl.java:186-204`）。读：仅 `QuestionContentMapper.backfillFromLegacy`（`QuestionContentMapper.xml:68`）与脚本 S-1（`13_backfill_canonical_ai_data.sql:18`）；应用层零读取（`Base_Column_List` 不含该列） | `question_content.title`（D-02） | 保留为**只读兼容列**；随一次保存被 `t_text_content` 同步改写为同一新值（`QuestionServiceImpl.java:120-122` 走 `updateByIdFilter`，但**不写 `t_question.title`**——该列实际在运行时写入后即不变，属"历史遗留快照"）。退出条件：**D-27**——全部读路径切到 `question_content`（R-1～R-2）且确认无其他业务依赖后按迁移方案退出，退出顺序为"停写→切读→确认→观察" |
| `options` | `[已确认]` | 写：脚本 S-3/S-4/S-7（同上）；运行时零写入（编辑路径把选项写进 `t_text_content` JSON 与 `question_content.options` 两处，均不写 `t_question.options`）。读：脚本 S-1（`:19`）与 `backfillFromLegacy`（`QuestionContentMapper.xml:69`）；应用层零读取 | `question_content.options`（D-02） | 保留为**只读兼容列（脚本回填输入）**；不得作为任何展示/判分来源。注意 A-06 的 `missing_options=0` 只说明该列非空，**不证明它与旧 JSON 的选项语义等价**（人工对照未执行，见第 12 节） |
| `correct_answer` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：脚本 S-1（`:20` 的 `COALESCE(q.correct_answer, q.correct)`）与 `backfillFromLegacy`（`:70`）；应用层零读取 | `question_content.correct_answer`（D-02，判分侧见第 5 节与 Q-14 改造清单） | 保留为**只读兼容列**；它是版本的**初始输入**，不是版本本身。A-06 证明 6613/6613 中题型 5 的 `question_content.correct_answer` 为空，与该列的取值无关 |
| `analysis` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：脚本 S-1（`:21`）与 `backfillFromLegacy`（`:71`）；应用层零读取 | `question_content.analysis`（D-02） | 保留为**只读兼容列**；退出条件同 `title` |
| `title_text` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：`QuestionMapper.xml` 的 AI 组卷检索条件 `q.title_text LIKE`（`:226`，属 Q-15）+ `KnowledgeGraphServiceImpl:293`、`:341`、`:351`（K-7/K-8/Q-20/Q-21） | `question_content.title_text`（D-02；投影规则见第 6.2 节） | **当前不可直接删除**：K-7/K-8/Q-15 三条检索路径仍读它。兼容策略 = 保留为**只读检索投影**，在检索路径改读 `question_content.title_text` 之前不得写入新值（否则与版本内容不一致）。退出条件：三条检索路径切换 + EXPLAIN 对比完成 |
| `analysis_text` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：`QuestionMapper.xml:227`（Q-15）+ `KnowledgeGraphServiceImpl:332`（K-8/Q-21） | `question_content.analysis_text`（D-02） | 同 `title_text` |
| `content_format` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：仅 `backfillFromLegacy`（`QuestionContentMapper.xml:74` 的 `COALESCE(q.content_format,'html')`）；应用层零读取 | `question_content.content_format`（D-02；T3-3 实测新表恒写 `'html'`） | 保留为**只读兼容列**；不得作为格式判定依据（真实取值未采样，见第 12 节） |
| `has_image` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：仅 `backfillFromLegacy`（`:75`）。**A-07：`t_question.images` 全空、题干无内嵌 `<img>`，而 `question_content.has_image=1` 有 137 行**——两处标记不同源 | `question_content.has_image`（D-02；推导规则见 `QuestionContentServiceImpl.java:47`） | 保留为**只读兼容列**；明确它**不是**资源存在性的权威标记（D-19：资源事实在仓库外 `question-html/**`，见 3.6 节） |
| `has_code` | `[已确认]` | 写：脚本 S-3/S-4/S-7；运行时零写入。读：仅 `backfillFromLegacy`（`:76`）。A-07：`question_content.has_code=1` 有 185 行 | `question_content.has_code`（D-02；推导规则 `QuestionContentServiceImpl.java:48-49`） | 同 `has_image` |
| `images` | `[已确认]` | 写：**零写入者**——应用层与全部脚本都无 `images` 列写入（`entry-inventory.md` 第九节 9.1"图片/附件"行 + 3.6 节）。读：应用层零读取；脚本层无命中 | 无权威位置（D-04/D-19：不升级为事实源） | 保留为**休眠列**，在契约与部署文档中标注"非事实源"。**A-07 实测该列 6613 行全空** → 任何"图片已入库"的推断都无样本支撑。退出条件：Q-05/D-19 的资源方案落地后单独论证删除 |

**`t_question` 的身份与业务属性列（不在本次收敛范围，列出以保证聚合根完整）**：
`id`（身份，权威）、`question_type`（权威，但 3/4 语义待确认，见 5.3/5.4）、`subject_id`、`grade_level`、`score`、`difficult`、
`correct`（**当前判分权威**，Q-14 实测；目标见 3.4）、`info_text_content_id`（兼容指针，见 3.3）、
`create_user`、`status`、`create_time`、`deleted`（D-21 停用标记，当前 **0 行已停用**，A-01/A-09 → 语义不可验证）。
`source` / `source_year` / `source_question_no` / `tags` / `knowledge_point` 五行见 3.5。
另注：`difficulty`（`:590`）与 `difficult`（`:604`）两列并存，应用只读写 `difficult`（`QuestionMapper.xml:10`、`:62-64`），
`difficulty` 列的应用层读写为零 → 休眠列候选，需在第 3 组字段字典中登记（当前无证据判定其取值来源，**待验证**）。

### 3.3 `t_text_content`（D-03：按引用业务分别退出）

**该表是四种语义共用一张表**（W-7/W-9/W-10/W-11/W-12/W-13/W-14）：题目内容、试卷框架、任务框架、主观题答案。
表结构只有 `id` / `content` / `create_time` / `embedding` 四列（`V1__baseline.sql:644-650`）。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `content`（题目语义） | `[已确认]` | 写：W-1/W-3 经 `setQuestionInfoFromVM` 写入 `JSON{questionItemObjects, analyze, titleContent, correct}`（`QuestionServiceImpl.java:186-204`，W-8 的列清单）；W-4 循环同路径。读：**Q-3 至 Q-7 全部经它**——题干 `:144`、答案四路分流 `:151-169`、解析回落 `:171`、选项 `:175-182`；Q-8 三级回落第二级 `:66-78`；Q-10 后台列表 `:40-54`；K-7/K-8 的 `JSON_EXTRACT(tc.content,'$.titleContent')`（`KnowledgeGraphServiceImpl.java:293`、`:342`、`:352`）；R-4/W-17 的切片正文 `RagDocumentMapper.xml:196-198`；R-5/W-18 的失效判据基准 `:119-120` | 题目语义的权威 = `question_content` 的对应版本行（D-02）；本列降级为**兼容表示** | **分阶段退出的过渡字段**。① 立即：所有写路径必须与 `question_content` 同事务双写，禁止只改一处（否则界面出现"新题干+旧选项"，见 7.2）；② 阶段二：Q-3 至 Q-8/Q-10/K-7/K-8/R-4 全部切到 `question_content`；③ 阶段三：只读保留，禁止新写入；④ 阶段四：按 **D-27** 退出——R-1～R-4 全部满足后，本字段按"先停写、再切读、再确认无其他业务依赖、再观察一个窗口"退出；**整表不删除**（试卷 Frame/任务/答案语义仍权威，见下一行）。**在阶段三之前不得把它标为"已废弃"**，否则会掩盖实际读依赖 |
| `content`（试卷框架/任务框架/主观题答案语义） | `[已确认]` | 写：W-9（`CalculateExamPaperAnswerListener.java:60-65` 主观题答案快照）、W-10（`ExamPaperServiceImpl.java:96`/`:107` 试卷框架）、W-11（`TaskExamServiceImpl.java:73`/`:96` 任务框架）、W-12（`TaskExamCustomerAnswerImpl.java:43-44`/`:51-52` 任务作答）、W-13（`ExamPaperAnswerServiceImpl.java:144-150`）、W-14（`AiPaperComposeServiceImpl.java:266-269`）；脚本 S-6/S-10/S-11。读：Q-11 试卷回显（`ExamPaperServiceImpl.java:115-145`）、Q-12 答题记录详情（`QuestionAnswerController.java:118-135`） | **本列在这三种语义上仍是权威**（D-03 明确：题目内容迁移完成 ≠ 整表可删） | **不得随题目内容一起删除**（**D-27**：整表保留）。这三种语义的退出按业务类型分别判断；题目部分的退出时点已定稿（上一行），这三种语义**无退出计划**，长期保留 |
| `embedding` | `[待验证]` | 写：W-3（`QuestionServiceImpl.java:122` 经 `updateByIdFilter`）、W-7、W-13。读：**仓库内无检索读取代码**；`entry-inventory.md` 第七节读入口清单无该列 | **无权威位置**（D-04：不接入主链） | 休眠列。未验证原因：只有写入点、无读取点，且真实库该列的非空率未采样（本轮未执行该查询）。不得据此推断"向量已入库"——现役向量检索走 `rag_embedding` + 外部 Qdrant（R-7/R-8） |
| `create_time` | `[已确认]` | 写：W-1/W-3/W-7/W-9/W-10/W-11/W-12/W-13。读：无展示消费点（仅排序/审计用途） | 保留为**行级时间戳**（非业务事实） | 不参与契约，仅作迁移排序与断点用途 |
| `id`（被 `t_question.info_text_content_id` 引用） | `[已确认]` | 写：自增；由 W-1/W-3 写回 `t_question.info_text_content_id`（`QuestionServiceImpl.java:99`；W-3 仅在非 null 时更新，`QuestionMapper.xml:144-146`）。读：Q-3 `:141`、Q-8 `:66`、Q-13 答题行冻结 `:212`、K-7/K-8 JOIN、R-4 JOIN | **降级为"兼容指针"**：不得作为版本身份（D-17 的关键判据） | 保留为只读指针，供未切换路径回连；`question_content.legacy_text_content_id` 是它的反向索引（见 3.4）。**新代码禁止再冻结该 id 作为历史依据** |

### 3.4 `question_content`（D-02：内容与版本的权威载体）

表结构见 `V1__baseline.sql:172-194`；约束现状见 A-12（只有主键 + `uk_question_content_version`，`idx_question_content_current` 非唯一）。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `question_id` | `[已确认]` | 写：W-2 `saveFromEdit`（`QuestionContentServiceImpl.java:38`）、W-15 回填（`QuestionContentMapper.xml:66`）、脚本 S-1/S-3/S-4。读：全部读路径按它过滤（`QuestionContentMapper.xml:28`、`:49`；`RagDocumentMapper.xml:111`） | 已权威 | — |
| `version` | `[已确认]` | 写：W-2 `selectMaxVersion + 1`（`QuestionContentServiceImpl.java:36-39`）；W-15 固定写 `1`；脚本 S-3/S-4 写常量。读：`selectCurrentByQuestionId` 的 `ORDER BY version DESC LIMIT 1`（`QuestionContentMapper.xml:30`） | **已权威**，且是 RAG 投影必须携带的"源版本号"（DB-09） | 不适用。**遗留风险**：`SELECT MAX(version)+1` 的竞态由唯一键暴露为 1062（T2-2），不被约束消除（D-10） |
| `title` | `[已确认]` | 写：W-2 `:40`；W-15 `:68`（`COALESCE(q.title, JSON$.titleContent, tc.content)`）。读：Q-4 覆盖旧 JSON（`QuestionServiceImpl.java:146-148`）；Q-8 第二级（`QuestionAnswerController.java:62-63`）；另 `RagDocumentMapper.xml:113` 读的是 `title_text` 而非本列 | **权威（题干原文，含 HTML）**（D-02） | 已权威但**读侧未收口**：Q-7 选项、Q-6 填空/简答答案仍不读本表。在 Q-3 全量切换前必须保持与 `t_text_content` 同事务双写（见 6.1） |
| `options` | `[已确认]` | 写：W-2 `:41`（`JsonUtil.toJsonStr(model.getItems())`，元素含 `prefix/content/score/itemUuid`）；W-15 `:69`（直接抄 `q.options`）。读：**应用层零读取**——Q-7 明确"完全不读 `question_content.options`"（`QuestionServiceImpl.java:175-182`）；T3-4 实测把该列改成"新选项 Z"后读回仍是"旧选项 A" | **目标权威（选项序列）**（D-02、DB-03） | **当前是只写字段**。兼容策略 = 短期保留双写；切换动作必须同时改 Q-7 与 Q-8/前端渲染，并验证旧 JSON 的 `questionItemObjects` 与本列 JSON 的元素结构可无损互转（元素结构差异见 5.1，**待验证**） |
| `correct_answer` | `[已确认]` | 写：W-2 `:42`（取 `question.getCorrect()`，即 `t_question.correct` 的副本）；W-15 `:70`（`COALESCE(q.correct_answer, q.correct)`）。读：`RagDocumentMapper.xml:114`（切片正文"答案："段）；编辑回显与判分**都不读**（T3-4） | **目标权威（规范答案）**（D-02）；判分权威切换见第 5 节 | **当前是只写字段 + 已有一个读方（RAG）**。切换判分来源必须同时改 Q-14（`ExamPaperAnswerServiceImpl.java:228-244`）与 Q-6（`QuestionServiceImpl.java:151-169`），否则判分依据与回显答案不同源（现役分歧见 7.2） |
| `analysis` | `[已确认]` | 写：W-2 `:43`；W-15 `:71`。读：Q-5（`QuestionServiceImpl.java:171`，非 null 时优先于旧 JSON） | **权威（解析原文）**（D-02） | 已权威；退出条件同 `title` |
| `title_text` | `[已确认]` | 写：W-2 `:44`（`stripHtml(title)`：`<[^>]+>` → 空格 + 压缩空白 + trim，`QuestionContentServiceImpl.java:72-77`）；W-15 `:72`（抄 `q.title_text`）。读：Q-8 第一级（`QuestionAnswerController.java:61`）；`RagDocumentMapper.xml:113`、`:117` | **权威（检索/列表投影）**（D-02，DB-04） | 已权威。T3-3 实测"同一输入重复生成结果一致"；但**两套生成规则不同源**（Java `stripHtml` vs 脚本抄 `q.title_text`），同一题经不同路径会有两个投影 → 迁移时必须选定 Java 规则并在 `migration-plan.md` 记录重算动作 |
| `analysis_text` | `[已确认]` | 写：W-2 `:45`；W-15 `:73`。读：`RagDocumentMapper.xml:115`、`:118`；K-8 检索读的是 `t_question.analysis_text`（`KnowledgeGraphServiceImpl.java:332`）而非本列 | **权威（检索投影）**（D-02） | 同 `title_text`；注意 K-8 尚未切换 |
| `content_format` | `[已确认]` | 写：W-2 `:46` **硬编码 `"html"`**；W-15 `:74`（`COALESCE(q.content_format,'html')`）。读：应用层零读取 | **权威（内容格式标记）**（D-02）；格式枚举已定稿（**D-23**） | 当前恒为 `'html'`（T3-3 实测）。**D-23 定稿**：块内原文保留 HTML（或 Markdown）不过度解析 → 枚举语义 = "块内原文的书写格式"，首个合法值 `'html'`；未来引入 `'markdown'` 时读侧必须显式分支，禁止默认当 html 渲染。在此前的过渡期，读侧仍**不得依赖本字段**作分支依据（全库恒 html，分支无意义） |
| `has_image` | `[已确认]` | 写：W-2 `:47`（`title/analyze` 含 `<img` 字面量）；W-15 `:75`。读：应用层零读取。**A-07 实测：`has_image=1` 有 137 行，但题干里内嵌 `<img>` 为 0 行、`<table>` 为 0 行、KaTeX 标记为 0 行** | **权威（资源存在性标记）**（D-02/D-19），但**判据必须扩展**：现判据只能识别内嵌 `<img>`，识别不了 `<div class="question-html-ref" data-src="…">` 外链 | **两处标志判定口径已确认为缺陷（TD-018），作者确认延期（2026-09-23，待验证 #7）**：`has_image`/`has_code` **标"不可信"，不作强制约束**——样本 8541 实际 2 张图但标志为 0。策略：保留现值不动；**D-23/D-26 落地时按"块级资源引用存在即有图"重算**（外链 `data-src` 引用也算），并保留人工覆盖入口；判定脚本修复留在 TD-018 / 实施阶段 |
| `has_code` | `[已确认]` | 写：W-2 `:48-49`（`<code` / ` ``` ` 字面量）；W-15 `:76`。读：应用层零读取。A-07：`has_code=1` 有 185 行 | **权威（代码块存在性标记）**（D-02） | 同 `has_image`（**TD-018**：标"不可信"，不作强制约束；重算规则随 D-23/D-26 落地） |
| `legacy_text_content_id` | `[已确认]` | 写：W-2 `:50`（= `question.infoTextContentId`）；W-15 `:77`。读：`idx_question_content_legacy`（A-12 的索引清单）；A-10 用它证明"108 条答题全部能定位到内容行"（`no_content_row_by_legacy_link = 0`） | **降级为回连索引，非权威**（D-17） | 保留为只读。价值有两点：① 迁移对账的对照键；② 历史答题行的兼容解析。**但不得作为历史版本绑定的依据**——它指向 `t_text_content` 行，而该行可变（`QuestionServiceImpl.java:120-122`）。D-17 要求改绑 `question_content.id` |
| `source_hash` | `[已确认]` | 写：W-2 `:51`（Java `sha256(id\|title\|options\|correct_answer\|analysis)`，`QuestionContentServiceImpl.java:79-91`，hex 小写）；W-15 `:78`（`SHA2(CONCAT_WS('\|', q.id, q.title, q.options, COALESCE(q.correct_answer,q.correct,''), q.analysis, tc.content),256)`）；脚本 S-3/S-4 **手写字面量**（S-3 `:37`/`:62`）。读：`RagDocumentMapper.xml:119-120` 的失效判据基准；`QuestionBackfillIdempotencyTest` 断言其不变 | **降级为"内容指纹线索"，不是版本身份**（DB-09：源版本号必须用 `question_content.version`） | **三套算法不统一，当前不可比较**（entry-inventory 第 9.3 节"统一 source_hash 算法"行；T4-2 只证明同一路径重复执行不变）。策略：① 禁止任何代码用跨路径比较 `source_hash` 判定"内容是否变了"（现役 R-5 正因此恒不相等，见 4.5）；② 迁移时统一为单一表达式并全量重算；③ 重算后 `source_hash` 只作幂等回填键 |
| `is_current` | `[已确认]` | 写：W-2 `clearCurrent`（`:53`，`UPDATE ... SET is_current=b'0' WHERE question_id=? AND is_current=b'1'`）后再 `insert`（`:54`）；W-15 **无条件写 `b'1'`**（`:79`）。读：`selectCurrentByQuestionId`（`:29`）、`RagDocumentMapper.xml:111`、Q-8 `:58` | **权威（当前版本指针）**，唯一性由生成列 + 唯一索引保证（D-13），存在性由事务与业务校验保证（D-14） | 保留语义不变。**必须新增的约束** `ddl/current-uniqueness-strict.sql`（生成列 `current_question_id` + `uk_question_content_current`）；**必须修复的缺陷**：W-15 无条件写 `b'1'` 会对"缺 version=1 但已有 current"的题造出第二个 current（T4-3 陷阱记录），迁移前必须先处理该状态 |
| `create_time` / `update_time` | `[已确认]` | 由数据库默认值与 `ON UPDATE` 维护（`V1__baseline.sql:188-189`）；无应用层写入 | 行级时间戳 | 注意 `update_time` 只反映"该版本行被 UPDATE"，**不能当版本内容的最后修改时间**（内容列从不 UPDATE） |
| `(question_id, version)` | `[已确认]` | 唯一键 `uk_question_content_version`（A-12） | 已权威 | 保留。T2-1 实测顺序重插被拒 |
| `current_question_id`（**新增生成列**） | `[已确认]`（T1-3 实测） | 尚不存在 | 生成列 + `uk_question_content_current`（D-13，候选 A） | 待迁移实施；回滚语句见 `ddl/current-uniqueness-strict.sql:51-53` |
| `id` | `[已确认]` | 自增主键 | **内容版本身份**（D-17 的历史绑定目标） | 保留。**契约要求**：历史答题行（新增列或改列语义）指向该 id，而非 `t_text_content.id` |

**`is_current` 的契约边界（D-14 原文落实）**：

- 数据库**只**保证"至多一个 current"（T1-4 实测两个候选都做不到"必须有"）；
- "可用题目必须有 current"由**保存事务 + 业务校验**保证：保存失败必须整体回滚（T2-3 实测回滚后原 current 仍在）；
- **进入考试时找不到可用版本必须拒绝并明确报错**，不得回落旧 JSON 静默放行；
- 巡检 SQL 只用于**发现异常**，不替代写入时的保证。

**并发编辑契约（D-15 + D-16）**：

| 场景 | 契约 |
| --- | --- |
| 两个请求生成同一 `(question_id, version)` | 唯一键拒绝，抛 `DuplicateKeyException`；服务层必须翻译为业务冲突提示（"该题已被他人更新，请刷新后重试"），**不得返回 500**（T2-2 实测 1062 + `Duplicate entry '990301-2'`） |
| 同一题两个不同版本同时提交，唯一键未冲突 | **仍必须提示冲突**：提交体携带"打开编辑页时的版本号"，服务端做乐观并发校验；版本号不一致即拒绝（D-16）。**现状缺口**：`QuestionEditRequestVM` 无任何版本字段（`QuestionEditRequestVM.java:13-37`），前端 `form` 也不携带（`single-choice.vue:52-60` 等五处） |
| 冲突时的用户输入 | 必须保留用户未保存的输入；不自动合并答案与解析，不静默覆盖（D-15） |
| 幂等 | 按"请求身份"与"业务身份"分别定义；`SELECT MAX(version)+1` 的竞态靠唯一键暴露，不静默重试（D-10）。T2-4 在幂等方案决定前不实现 |

### 3.5 `question_source`（D-04：不接入主链、不删除）

**定性依据 A-08**：6613 行、覆盖 6613 题（A-08b：有效题缺来源行 = 0）；
`raw_ref` **100%**、`metadata` **100%**、`source_question_no` **100%**、`source_year` **94.3%**（6236/6613）；
`page_no` / `crawler_batch` / `ocr_batch` **全为 0**。
A-08 的关键结论：`raw_ref` 与 `metadata` **不可从 `t_question` 派生** → 本表是**独有事实**，不能降级为可重建投影。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `raw_ref` | `[已确认]` | 写：脚本 S-1/S-3/S-4（`13_backfill...sql:48-68` 的列清单**不含** `raw_ref`；S-3 `:38`、S-4 `:152814` 起写入 `source_type='crawler_html'` 的整行）。应用层**零写入**（`entry-inventory.md` 9.1：唯一写入者 = 脚本）。读：**应用层零读取**（读入口清单里没有任何 `question_source` 查询） | **权威（来源原始引用）**（D-18：管理端展示来源，缺失显式为空） | 当前是**只写不读**事实。策略：保留且**禁止清空**；在管理端来源展示接口落地时接入读取路径。D-18 要求"原始采集信息保留，人工修改另留记录" |
| `metadata` | `[已确认]` | 写：脚本 S-1 写 `JSON_OBJECT('legacy_tags', q.tags, 'legacy_knowledge_point', q.knowledge_point)`（`13_backfill...sql:58`）、S-3/S-4。读：应用层零读取 | **权威（采集元数据）**（D-18） | 同 `raw_ref`。注意其内容含 `legacy_tags`/`legacy_knowledge_point` 的**快照**，与 `t_question.tags`/`knowledge_point` 是两代表示 → 迁移时不得互相覆盖 |
| `source_type` | `[已确认]` | 写：脚本（S-1 `:53` 恒为 `'exam'`；S-3/S-4 为 `'crawler_html'`）。读：应用层零读取 | **权威（来源类型枚举）** | 已有至少两种取值，枚举清单需在字段字典中固化（第 3 组） |
| `source_name` | `[已确认]` | 写：脚本 S-1 `q.source`（`:54`）、S-3/S-4。读：应用层零读取 | **权威（来源名称）**，D-18 展示项 | 注意与 `t_question.source` 的对应关系是**回填时的一次性映射**，不是持续同步 |
| `source_year` | `[已确认]` | 写：脚本；A-08 覆盖率 94.3%。读：应用层零读取 `question_source`，但 `t_question.source_year` 被 K-7/K-8（`KnowledgeGraphServiceImpl.java:294`、`:343`）与 Q-15（`QuestionMapper.xml:230-232`）读取 | **权威（来源年份）**（D-18） | 双表示并存：`question_source.source_year`（权威目标）与 `t_question.source_year`（现役读源）。切换读取路径前必须双写；缺失的 5.7% 必须**显式为空**、不推测（D-18） |
| `source_question_no` | `[已确认]` | 写：脚本；A-08 覆盖率 100%。读：同上，仅 `t_question.source_question_no` 被读 | **权威（原题号）**（D-18） | 同 `source_year`。注意类型差异：`question_source` 是 `varchar(50)`，`t_question` 是 `int`（`V1__baseline.sql:594` vs `:210`）→ 非数字题号在旧列不可表达，属契约缺口 |
| `paper_name` | `[待验证]` | 写：脚本 S-1 `q.source`（`:57`）等。读：应用层零读取。非空率**未采样** | **权威（试卷名称）**（D-18 展示项之一） | 未验证原因：`audit-queries.sql` 的 A-08 未统计该列，本轮不补跑写操作/新查询（只读审计已收官）。策略：在管理端契约中列为"**可展示但可能为空**" |
| `page_no` | `[已确认]`（休眠） | 写：脚本层无写入命中；读：零。A-08：非空 0 | **暂不使用**（D-04） | 保留为**休眠列**；D-18 要求"缺失字段显式为空，不推测补齐" |
| `crawler_batch` | `[已确认]`（休眠） | 同 `page_no`，A-08 非空 0 | 暂不使用 | 休眠列；若将来做采集批次追溯再启用 |
| `ocr_batch` | `[已确认]`（休眠） | 同 `page_no`，A-08 非空 0 | 暂不使用 | 休眠列。注意 D-19 明确 OCR 平台不在本期 |
| `create_time` | `[已确认]` | 数据库默认值；A-01 显示最近写入 2026-05-27 | 行级时间戳 | 不参与契约 |

### 3.6 `question_asset`（D-19：资源契约在本期，素材库不做）

**定性依据 A-07**：表 **0 行**；`t_question.images` **全空**；题干**无内嵌** `<img>`/`<table>`/KaTeX；
图文题以 `<div class="question-html-ref" data-src="question-html/…" data-fallback="…"></div>` 外链 + 降级文本存在（A-07b 样本 question_id 7421）。
`entry-inventory.md` 的脚本层结论：17 个候选脚本中**没有任何脚本 INSERT 过该表**，三处 dump 的 asset 数据段均为空（S-12/S-14/S-7）。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `id` | `[已确认]`（无样本） | 自增；零读写 | 未定（休眠表） | 休眠 |
| `question_id` | `[已确认]`（无样本） | 零写入者；唯一引用是 S-4 的 `DELETE qa FROM question_asset qa JOIN tmp_…`（`17_import_csgraduates_html_exams.sql:279`） | 未定 | 休眠 |
| `question_content_id` | `[已确认]`（无样本） | 零写入、零读取。**注意**：该列的 DDL 注释暗示了"资源归属某个内容版本"，正是 D-19/D-26 需要的语义 | **候选权威位置**（D-26：归属 = 题目版本 + 块 + 题干/解析）——落库形态二选一（启用本表补版本/块归属列，或另建资源引用表）在**物理结构附录**定稿 | 休眠。策略：保留 DDL 不动；D-26 的资源契约先用**引用 + 校验 + 归属 + 可得性**表达（见 3.6 末），不依赖本表有数据 |
| `asset_type` | `[已确认]`（无样本） | 零写入者；默认 `'image'`，注释列 `image/formula/code/original_file` | 已定方向（**D-26**）：枚举需扩展"块归属"维度（图/公式/代码/原始文件 × 题干块/材料块/小问块/解析块），枚举清单与块模型（3.9）对齐后在物理结构附录固化 | 休眠；不启用前不承诺任何取值 |
| `asset_url` / `storage_key` | `[已确认]`（无样本） | 零写入者；`asset_url` NOT NULL | 已定方向（**D-26**）：定位 = 相对路径/URL；校验 = 内容 hash 或大小+修改时间 | 休眠。**契约红线**：D-19/D-26 明确"不强制对象存储"，因此 `storage_key` 不得作为必需字段 |
| `alt_text` | `[已确认]`（无样本） | 零写入者 | 未定 | 休眠。**契约要求**：`alt_text` 一旦启用，必须与 `data-fallback` 的降级文本口径一致（A-07b：`title_text` 正是从 `data-fallback` 提取） |
| `source_type` / `source_ref` | `[已确认]`（无样本） | 零写入者。注意与 `rag_document.source_ref`（`t_question:<id>`，A-11b）**同名不同义**，两者不可混用 | 已定方向（**D-26**）：若启用本表，必须显式定义与 `rag_document.source_ref` 的区别，避免重演 A-09 的口径错误 | 休眠 |
| `sort_order` | `[已确认]`（无样本） | 零写入者；索引 `idx_question_asset_question(question_id, sort_order)` | 未定 | 休眠。排序语义对应 `sample-contract.md` 的"公共材料图 a/图 b 顺序"，是 S-01 的必要能力 |
| `create_time` | `[已确认]`（无样本） | 数据库默认值 | — | 休眠 |

**D-19 + D-26 在本期的落地形式（不依赖本表）**：

| 契约条目 | 内容 | 证据 |
| --- | --- | --- |
| 归属 | 每个外部资源必须能归属到（题目版本 + **块** + 题干/解析）——**D-26 把归属粒度从"版本"细化到"块"**（3.9 的 block_id） | A-07b：资源以 `data-src` 出现在题干 HTML 中；解析资源归属需与题干区分（DB-12）；样本 2024/43 要求"这一问用哪张图"可回答 |
| 定位 | 库内只保存引用（`data-src` 相对路径）与降级文本（`data-fallback`）；**数据库不是显示事实源** | A-07 结论第 3 条 |
| 校验 | 每条资源引用记录**校验值**（内容 hash，或大小+修改时间的组合）；引用与文件不一致 = 校验失败 | D-26 拍板；样本 U-08：原地换文件不产生新身份，必须靠校验值发现 |
| 可得性 | 每条资源引用记录**可得性状态**：存在 / 缺失 / 不可用（授权受限等）；状态由校验动作维护，不是推测 | D-26 拍板；TD-015（文件在仓库之外且受授权限制） |
| 备份 | 外部文件目录（`question-html` 13318 文件/159.1 MB、`question-assets` 163/20.1、`knowledge-html` 116/69.3、`knowledge-assets` 102/12.8）**被 `.gitignore` 排除**，必须另有备份与校验动作；库内引用不等于文件已保管 | `workflow.md` 第 6 节①的规模表；A-07 |
| 缺失标记 | 外部文件缺失时，题面必须显式标为"不可恢复"，不用当前内容补齐（D-06）；缺失时对应的**块**标记"资料未就绪"（联动 D-25 的展示未就绪） | D-06、D-19、D-25 |
| **更新文件不得改变历史版本题面** | 资源更新必须产生可辨认的新身份或新版本，不得替换同一路径文件 | `sample-contract.md` S-04；D-19；样本 U-08 |
| 落库形态 | 启用 `question_asset` 补版本/块归属列 **或** 另建资源引用表——物理结构附录二选一；**无论哪种，必须能回答"这个文件属于哪一版、哪一块"** | D-26 拍板（2026-09-23） |
| 不在本期 | 素材库、批量上传后台、OCR 平台、强制对象存储 | D-19、D-26 |

### 3.7 `question_knowledge_point`（D-20：允许人工增删 + 记录人与时间）

**定性依据 A-04**：6033 行关系，其中 **643 条指向不存在的 `t_question.id`**（`kp_relation_without_question`），
`kp_relation_without_point = 0`；且 `deleted <> 0` 的题目为 0 行 → 这 643 条**不是停用残留**，是硬删或从未存在的题留下的孤儿。
运行期写状态：K-1/K-2/K-3 三个 Mapper statement 存在但**无任何 Java 调用者**（entry-inventory 3.1）→ **运行期只读表**；
唯一写入者是脚本 S-1（`13_backfill...sql:74-84`，名称/标签精确匹配）与 S-5（`01_demo_seed.sql:106-117`）。

| 字段 | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `question_id` | `[已确认]` | 写：脚本 S-1/S-5；K-1 有 statement 无调用者。读：K-6/K-9/K-10（`KnowledgeGraphServiceImpl.java:217`、`:238`、`:257`） | 已权威（关系主体） | **必须建立失效路径**：题目被硬删或停用时，关系行的处置当前无代码（W-6 只写 `t_question.deleted`，不清关系）。D-20 要求 643 条孤儿先隔离并查明原因，不凭题意猜测 |
| `knowledge_point_id` | `[已确认]` | 写：脚本 S-1/S-5；读：K-6/K-9/K-10 | 已权威 | 无外键（A-04：`kp_relation_without_point=0` 只是当前数据干净；D-07 不批量补外键） |
| `relevance` | `[已确认]` | 写：**脚本 S-1 恒写 `1.0000`**（`13_backfill...sql:75`）；S-5 写入值见 `01_demo_seed.sql:106-117`。读：**应用层零读取**——K-9/K-10 的查询未取该列（`KnowledgeGraphServiceImpl.java:238`、`:257`），K-6 的 `findCurrentByKnowledgePointId` 亦不消费 | **权威（关系强度）**，D-20 的"人工标注 vs 自动生成"需在此列之外增加来源区分（见下） | 当前是**只写不读、且脚本恒为 1.0** → 该列现在没有区分能力。策略：① 保留列语义（`decimal(3,2)`，范围 0–1）；② D-20 要求"区分人工标注与自动生成、自动任务不得覆盖人工结果"——现有表**没有** `source`/`create_user`/`update_time` 列，因此人工标注**无法记录修改人与时间**，属结构缺口（登记为第 3 组物理结构附录的候选改动；本章不做表结构设计） |
| `id` | `[已确认]` | 自增主键；读：K-9/K-10 的 `SELECT *` | 行标识 | 无唯一键（`V1__baseline.sql:195-203` 只有两个普通索引），同一 `(question_id, knowledge_point_id)` 可重复。A-04 未统计关系重复度 → **待验证**（见第 12 节） |

**D-20 契约条目**：人工可添加/移除关联并记录修改人与时间；自动任务（脚本 S-1/S-5）不得覆盖人工结果；
643 条孤儿先隔离查明原因。以上三条在结构上需要新增列/表才能落实，**具体形态属第 3 组**。

### 3.9 内容块契约（D-23 定稿：权威内容格式与层次）

**依据**：Q-09 于 2026-09-23 拍板（D-23）。本节是**逻辑契约**；块的物理落库形态
（JSON schema、块表，或复用 `t_essay_question`）由物理结构附录定稿（回应 U-11）。
验证基准：`sample-2024-q43.md`（真题事实）+ 第 6 节原创同结构夹具 `FIXTURE-NOVA16-01`（能力断言）。

**块模型**（一题 = 有稳定标识与顺序的块序列）：

| 块类型 | 数量 | 内容 | 资源关联 | 约束 |
| --- | --- | --- | --- | --- |
| `title` 题干 | 1 | HTML 原文（不过度解析） | 可关联图/表/代码资源 | — |
| `material` 公共材料 | 0..n | HTML 原文 | 图号可独立寻址（"图 a/图 b"顺序稳定） | 位于题干后、小问前 |
| `sub_question` 小问 | 1..n（简单题恰为 1 或 0） | HTML 原文 + 分值 | **记录所引图块标识** | 顺序显式 |
| `answer` 答案 | 按小问归属 | 规范答案 | — | **允许为空**（简答题无标准答案，D-28）；为空是合法状态，不是缺陷 |
| `analysis` 解析 | 0..n | HTML 原文 | 解析资源**不得混入作答题面**（DB-12） | 可回溯到小问 |

**契约规则**：

1. **块身份稳定**：每块有稳定标识（block_id），同一小问跨版本可对应（版本不可变 + 追加式，与 3.4 一致）；
2. **顺序显式**：块顺序必须显式存储并可重现渲染，不依赖隐式约定；
3. **块内保真**：块内原文保留 HTML（或 Markdown）不过度解析（DB-04）；
4. **纯文本投影规则（回应 U-10）**：`*_text` = 按块顺序、对每块**分别**执行统一规则
   （`<[^>]+>` → 空格 → 压缩空白 → trim，即 3.4 Java 规则）后拼接；**资源内部文本
   （如内联 SVG 的图内标签）不得混入投影**。现库 `title_text` 混入图内标签（样本：`title` 300 字符
   vs `title_text` 1122 字符）**不符合本规则** → 迁移时按新规则全量重算，登记为迁移前置动作；
5. **单块降级**：拆不出的旧题保留"单块整段"（类型 `title`），转换器不得丢弃原文，可后续再拆；
6. **选项/填空不重复表达**：选项与填空项仍由 `question_content.options` 承载（第 5 章契约不变），
   块模型负责题干/材料/小问/答案/解析的层次；小问块的答案/解析归属通过块标识表达；
7. **能力断言（S-01/S-02，夹具驱动）**：① 每题可持有 ≥2 个有序图块且图号可独立寻址；
   ② 每个小问记录顺序、分值、所引图块标识与自己的答案；③ 解析与题干严格分离且可回溯到小问；
   ④ 图块有独立资源身份与校验值（D-26），正文检索不混入图内标签（规则 4）；
8. **`t_essay_question` 定位（回应 U-11）**：该表 98 行中 `sub_questions` **0 行有值**、`answer` 仅 3 行、
   `analysis` 仅 4 行，2024/43 行 `total_score=10` 与正文 13 分不符 → **"已声明但从未填充"**，
   本契约**不**把它的现状数据当作任何块的事实源；是否复用该表作为块模型的物理载体
   （复用 = 需清理历史空壳行并补齐列语义；另立 = 新表）由物理结构附录论证并给出结论；
9. **对 RAG 输入的影响（S-06 结论）**：现 RAG 切片正文 = 旧 JSON 整串（`RagDocumentMapper.xml:196`）
   → 缺图描述、缺小问结构、且投影混入图内标签（N-2 + U-10）。D-23 落地后 RAG 输入改为
   **块级纯文本投影**（规则 4），块标识随 chunk 落库（4.3 `chunk_index` 行）；检索引擎本身不改
   （词法 + 向量链路保留，见 requirements 范围）。

---

## 4. RAG 投影契约（`rag_document` / `rag_chunk` / `rag_embedding`）

### 4.1 定性（D-08）

RAG 文档、切片与向量**整体定性为可重建投影**，必须携带**源版本**与**生成配置**。
现有 `content_hash` 只作对照线索，**不证明**完整版本与生成配置已落库。

### 4.2 入口与触发（事实）

| 事实 | 证据 |
| --- | --- |
| 唯一触发点是 `POST /api/admin/ai-config/rag/index`（R-9），由前端"开发者 API 清单"页列出，配置页未调用 | `AiConfigController.java:80-145`；`developer/index.vue:517` |
| **题目编辑不会触发任何 `rag_*` 写入**：`insertFullQuestion` / `updateFullQuestion` 全方法体内无 Rag 调用 | `QuestionServiceImpl.java:79-126` |
| **不存在任何定时任务/后台对账者**：`@Scheduled`/quartz/xxl-job 全仓库 main 代码零命中 | B-1；entry-inventory 2.2 |
| 生产机上是否有外部调度调用该端点**无法从仓库确认** | U-6 |

### 4.3 字段级契约

| 字段（表） | 状态 | 当前读写位置（谁写/谁读 + 证据） | 目标权威位置 | 兼容策略 |
| --- | --- | --- | --- | --- |
| `rag_document.document_type` | `[已确认]` | 写：R-1（`'knowledge_base'`）、R-3（`'question'`）；W-16/W-17。读：Q-19 JOIN 条件、`RagDocumentMapper.xml:108` | 权威（投影类别） | 保留 |
| `rag_document.source_ref` | `[已确认]` | 写：R-3 `CONCAT('t_question:', q.id)`（`RagDocumentMapper.xml:172`）、R-1 `CONCAT('t_ai_knowledge_base:', kb.id)`（`:33`）。读：R-5 回连 `SUBSTRING_INDEX(rd.source_ref,':',-1)`（`:110`）；R-4 回连（`:206`） | 权威（**题目身份**引用） | 保留。**口径已确认**（A-11b 样本 `t_question:7417`）；A-09 原查询的 `CAST(source_ref AS CHAR)` 是假阴性，任何新 SQL 必须用 `CONCAT('t_question:', q.id)` 写法 |
| `rag_document.version` | `[已确认]` | 写：R-3 **恒写 `1`**（`:174`）。读：无 | **必须改为承载"源内容版本号"**（DB-09） | 当前值无意义（恒 1）。策略：**新增/改造为 `question_content.version` 快照**；这是 DB-09"源版本号"的落点。改动属第 3/4 组，本章只定语义 |
| `rag_document.content_hash` | `[已确认]` | 写：R-3 `SHA2(CONCAT_WS('\|', q.id, tc.content),256)`（`:175`）；R-1 知识库路径另有算法。读：无（仅 A-11 统计非空） | **降级为投影指纹线索** | 与 `question_content.source_hash` **不同基准**（A-06 结论 + entry-inventory 9.3）→ 不可跨表比较。保留，但禁止作为"投影是否最新"的唯一判据 |
| `rag_document.status` | `[已确认]` | 写：R-1/R-3 写 `'ready'`；无代码写 `'disabled'`。读：Q-19 `JOIN rag_document` 条件（`RagRetrievalMapper.xml:5-20` 取 `rd.status`）；A-09 的停用泄漏检查 | **语义必须收紧**（DB-09/DB-14）；收紧方案已定稿（**D-25**，见 4.6） | **A-11 关键事实：6613 个题目文档全部 `ready`，但只有 3774 个有 `indexed` 向量 → 2839 个（42.9%）"ready 但不可检索"**。因此 `ready` **当前不等于"可检索"**。**D-25 定稿**：`ready` 语义拆分为"展示就绪"与"检索就绪"两个独立状态，分级降级矩阵见 4.6；`status` 字段的目标语义与承载方式在 4.6 表中给出 |
| `rag_document.permission_scope` / `title` / `summary` / `subject_id` / `knowledge_point_id` / `source_type` / `source_name` / `legacy_knowledge_base_id` / `create_user` | `[已确认]` | 写：R-1/R-3；读：Q-19 取 `rd.title`、`rd.status` | 权威（投影元数据） | 保留。`knowledge_point_id` 在题目文档恒写 `NULL`（`RagDocumentMapper.xml:169`）→ 题目侧知识点只能靠 `question_knowledge_point`，两处**未打通**（登记为 DB-09/DB-13 的缺口） |
| `rag_chunk.content` | `[已确认]` | 写：R-2/R-4（`tc.content`，即 `t_text_content` JSON，`:196`）；R-6 `updateChunkContent`。读：Q-19 的 `COALESCE(content_text, content)` 兜底 | 派生投影 | 保留为"原始输入快照"。**注意它现在存的是旧 JSON 整串**（`:196`），不是规范内容 → 一旦旧链路退出，该列会成为唯一残留的旧表示 |
| `rag_chunk.content_text` | `[已确认]` | 写：R-5 `refreshQuestionChunkText`（`RagDocumentMapper.xml:112-115`）拼接 `题干：title_text` + `答案：correct_answer` + `解析：analysis_text`；R-6。读：Q-19 首选、R-8 向量输入首选 | 派生投影（检索正文） | 保留。**两个既有缺陷必须写进契约**：① R-5 **不拼选项**（`sample-contract.md` 已指出）→ 选项型题目的检索召回天生缺失；② R-5 的失效判据 `rc.content_hash <> SHA2(q.id, qc.source_hash)` 与 R-4 写入的 `SHA2(q.id, tc.content)` **基准不同 → 恒不相等**，每次调用都整表重写（entry-inventory 4.4）。策略：统一基准并把"源版本号"改为 `question_content.version` |
| `rag_chunk.content_hash` | `[已确认]` | 写：R-4 `SHA2(q.id\|tc.content)`（`:203`）；R-5 `SHA2(q.id\|qc.source_hash)`（`:119`）；R-6 `SHA2(contentText)`（`:154`）。读：R-5 的 `WHERE` 判据（`:120`） | **投影指纹，基准必须唯一** | 三套算法并存 → 保留但**必须统一**（同 4.3 `rag_document.content_hash`） |
| `rag_chunk.chunk_index` | `[已确认]` | 写：R-4 恒 0；R-6 递增。读：R-4 的 `NOT EXISTS(chunk_index=0)` 幂等条件（`:211`） | 派生投影（顺序） | 保留。**`sample-contract.md` 要求按小问切分**（S-03：从一个小问对应 Chunk 反查来源），现实现按长度切分（R-6）→ 语义缺口，**改造方向已定稿（D-23/D-26）**：chunk 必须携带源**块标识**（3.9 的 block_id），切分单位从"长度窗口"改为"内容块优先、超长块再按长度细分"，块标识随 chunk 落库（物理落点在物理结构附录） |
| `rag_chunk.citation_label` / `source_position` | `[已确认]` | 写：R-4 `CONCAT('题目 #', q.id)` / `CONCAT('t_question:', q.id)`（`:201-202`）；R-8 写入向量 metadata。读：Q-18 向量 metadata、R-8 | 派生投影（引用定位） | 保留。当前二者都只到"题目"粒度，**不含版本号与块标识** → DB-13 要求"关联源题目版本、源内容块/资源"未满足。**目标（D-23/D-26）**：引用定位 = 题目 id + 版本号 + 块标识，使引用可以反查到"哪一版、哪一块" |
| `rag_chunk.token_count` / `enabled` / `subject_id` / `knowledge_point_id` | `[已确认]` | 写：R-2/R-4/R-6（`token_count` 以 `CHAR_LENGTH/3` 估算）；读：`idx_rag_chunk_kp(knowledge_point_id, enabled)` | 派生投影 | 保留。`token_count` 是估算值，不得作为计量事实 |
| `rag_embedding.*`（`chunk_id`/`embedding_model`/`embedding_dimension`/`vector_store`/`collection_name`/`vector_id`/`payload_hash`/`indexed_at`/`status`/`error_message`） | `[已确认]` | 写：R-7 `upsertEmbeddingMetadata`（`RagDocumentMapper.xml:215-235`，`ON DUPLICATE KEY UPDATE`），调用方 W-20 **无事务**；R-8 写外部 Qdrant。读：检索走外部向量库（Q-18），DB 侧只作状态记录 | **权威（生成配置 + 索引状态）**（DB-09） | **A-11c 实测：`failed` 40 条（集中在 2026-05-29 06:08–06:11，一次失败批次的痕迹）；`indexed` 4473 条**。契约要点：① `payload_hash` 当前是 `SHA2(chunk_id\|model\|collection\|vector_id)`（`:222`），**不含正文指纹** → 正文变更后它不变，因此**它不能表达"向量与正文一致"**；② 写入与正文刷新不同事务（W-18/W-20），会出现"正文已换、元数据仍 indexed"；③ 唯一键 `uk_rag_embedding_chunk_model`/`uk_rag_embedding_vector` 提供了自然幂等键。策略：保留表结构，把"向量是否与某一内容版本一致"改为显式携带**源版本号**（落点见 4.5） |

### 4.4 停用传播（D-21 在 RAG 侧）

| 契约条目 | 现状证据 | 契约要求 |
| --- | --- | --- |
| 停用题目不得作为新检索证据，返回前过滤 | 无代码写 `rag_document.status='disabled'`；W-6 只写 `t_question.deleted`，无级联 | 需新增过滤条件；**停用语义当前无真实样本**（A-09：`deleted<>0` 行为 0），必须在隔离库用合成样本验证 |
| 历史 AI 回答不改写，引用可标记题目已停用 | 无实现 | 保留历史，引用侧加标记 |
| 紧急撤下（版权/安全）与停用是两种操作 | 无实现 | 不混用（D-21 末句） |

### 4.5 源版本与生成配置的最小追溯（DB-09 / DB-13）

`sample-contract.md` 已列出最小追溯信息：题目版本标识、源块/资源标识、生成规则或模型配置标识、
源内容指纹、处理状态、校验状态、错误信息及 Chunk 关联。对照现状：

| 追溯项 | 现状落点 | 缺口 |
| --- | --- | --- |
| 题目版本标识 | **无**（`rag_document.version` 恒 1，`source_ref` 只到题目 id） | **必须补**：源版本号 = `question_content.version`（DB-09） |
| 源块/资源标识 | 无（题干资源是外部文件 `data-src`；chunk 无块关联） | **已定稿（D-23/D-26）**：源块 = 内容块标识（3.9 block_id）；资源 = 资源引用（归属+定位+校验+可得性，3.6 末）。物理落点（`question_asset` 补列 vs 另建引用表；chunk 加块标识列）在物理结构附录定 |
| 生成规则/模型配置 | `rag_embedding.embedding_model`/`embedding_dimension`/`collection_name`/`vector_store` | 部分满足；**正文生成规则（拼接模板、投影算法）未落库** → 无法判断"这份 chunk 正文由哪条规则产生" |
| 源内容指纹 | `rag_chunk.content_hash`、`rag_document.content_hash` | 基准不统一（4.3），且不含 `question_content.version` |
| 处理/校验状态 | `rag_document.status`、`rag_embedding.status`/`error_message`/`indexed_at` | "文档 `ready` ≠ 可检索"——准确表述是：2839 个题目文档**没有已记录的 `indexed` 向量**（A-11），因此向量召回链路不可用；这**不等于**整条检索链路不可用（词法召回、向量召回、最终返回过滤需分别验证）；缺"校验状态"独立字段 |
| Chunk 关联 | `rag_chunk.document_id`/`chunk_index` | 缺"小问级"关联（S-03）；改造方向见 4.3 `chunk_index` 行（D-23：chunk 携带块标识） |

### 4.6 就绪状态与降级契约（D-25 定稿：DB-09 / DB-14）

**依据**：Q-11 于 2026-09-23 拍板（D-25）。本节是 RAG 投影契约的就绪/失效/重试/对账定稿；
状态字段的物理落点（扩展现有 `rag_document.status` 或新增列）在物理结构附录定。

**两个独立状态**：

| 状态 | 含义 | 判定依据 |
| --- | --- | --- |
| **展示就绪**（display-ready） | 题面材料完整可渲染 | 内容块齐全（3.9）；资源可得性 ≠ 缺失/不可用（3.6 末）；缺图描述或缺内容块 → **未就绪** |
| **检索就绪**（retrieval-ready） | 向量投影已建立且与源版本一致 | `rag_embedding` 存在 `indexed` 记录且携带的源版本号 = 当前源版本（现状 2839 个文档无 `indexed` 向量 → 未就绪） |

**降级矩阵（契约行为）**：

| 展示就绪 | 检索就绪 | 行为 |
| --- | --- | --- |
| 是 | 是 | 正常：展示原题 + 自动解答引用检索证据 |
| 是 | **否** | 展示原题与降级文本；自动解答**只用词法召回**（`rag_chunk.content_text`），并**标注"向量检索未就绪"** |
| **否** | 任意 | 只返回原题 + 明确的"资料未就绪"提示；**不进入自动解答**——不得用残缺材料生成答案 |
| 已停用 | — | 不作为新检索证据；返回前过滤（D-21，见 4.4） |

**附加规则**：

- **"向量未就绪"与"无可用证据"必须区分**：前者是可恢复的投影滞后（提示用户稍后重试或仅词法），
  后者是检索确实无结果；两者都**不得被标为完整成功**（DB-14），也不得让降级被误读为"模型答不出"；
- 失败或缺失**不得静默吞掉**：展示未就绪必须显式提示，不允许回落旧 JSON 拼凑题面（与 D-14 的
  "不得回落旧 JSON 静默放行"同原则）。

**源版本、失效、重试与对账（DB-09 定稿落点）**：

| 契约条目 | 内容 |
| --- | --- |
| 源版本号 | 投影必须携带 `question_content.version`（现状 `rag_document.version` 恒 1，无意义 → 改造为源版本快照，见 4.3） |
| 生成配置 | 正文生成规则（拼接模板/投影算法）必须带**规则版本**落库（现状缺失，4.5）；规则版本变更 = 全量重投影的触发条件之一；模型与集合沿用 `rag_embedding` 现有字段 |
| 失效条件 | 源内容产生新版本 → 该题全部投影标记"待刷新"（标记，不立即删除；已检索证据按 D-25 矩阵降级）；源单块变更 → 仅对应 chunk 标记 |
| 失效判据 | 以**源版本号比较**为准；**不得以 hash 基准比较判失效**——现状 R-5 的 `content_hash <> SHA2(...)` 基准不一致导致恒不相等、每次整表重写（4.3，TD 登记），改造时必须移除该判据 |
| 重试 | `rag_embedding.status='failed'` 保留 `error_message`（A-11c 实测 40 条失败痕迹）；重试幂等由唯一键 `uk_rag_embedding_chunk_model` 天然保证；单题失败不阻塞批次内其他题目 |
| 对账 | 巡检对比"应有投影的题目集合"与"实际 `indexed` 集合"，输出缺口清单；对账**只发现异常，不自动修复**（与 D-14 的巡检定位一致）；现状基线：6613 ready / 3774 indexed / 40 failed（A-11/A-11c） |

---

## 5. 五种题型的内容与评分契约（DB-03）

### 5.0 题型编码与样本基数（先看这张表）

`QuestionTypeEnum`（`domain/enums/QuestionTypeEnum.java:8-12`）：

| 编码 | 后端名称 | 真实行数（A-06 + 补充查询） | 本轮可用的验证强度 |
| ---: | --- | ---: | --- |
| 1 | 单选题 | **3780** | 有真实样本；T3-1 合成往返 + 真实库分布 |
| 2 | 多选题 | **0** | **仅合成样本验证**（T3-1） |
| 3 | 判断题 | **0** | **仅合成样本验证**（T3-1） |
| 4 | 填空题 | **0** | **仅合成样本验证**（T3-1） |
| 5 | 简答题 | **2833** | 有真实样本；`correct_answer` 100% 为空（A-06） |

**跨端编码冲突（本轮走查新增，必须由人确认）**：

| 端 | 编码 3 | 编码 4 | 证据 |
| --- | --- | --- | --- |
| 后端 `QuestionTypeEnum` | 判断题 `TrueFalse` | 填空题 `GapFilling` | `QuestionTypeEnum.java:10-11` |
| 后端判分 `setSpecialFromVM` | `case TrueFalse:` 与单选同支（比 `correct` 字面量） | `case GapFilling:` 存答案数组、**customerScore 恒 0** | `ExamPaperAnswerServiceImpl.java:231-247` |
| 后端编辑回显 `getQuestionEditRequestVM` | `case TrueFalse:` 读 `t_question.correct` | `case GapFilling:` 读旧 JSON items 的 content | `QuestionServiceImpl.java:153-163` |
| 学生端错误本题型名 | `3 → 判断题` | `4 → 填空题` | `question-error/index.vue:197-198` |
| 学生端 `typeEnum` | 判断题 | 填空题 | `store/modules/enumItem.js:22` |
| **管理端题目列表** | **填空题** | **判断题** | `views/exam/question/list.vue:97-98` |
| **管理端试卷编辑** | **填空题** | **判断题** | `views/exam/paper/edit.vue:136-137` |
| **管理端编辑页表单默认值** | `gap-filling.vue:55` = **3** | `true-false.vue:53` = **4** | 五处 `questionType:` 见 `single-choice.vue:58`=1、`multiple-choice.vue:56`=2、`short-answer.vue:50`=5 |

**结论**：管理端为判断题/填空题写出的 `question_type` 与后端（及学生端）的语义**相反**。
因为真实库这两类题目**零行**（A-06），该冲突**至今没有产生脏数据**，但一旦有人用管理端新增判断题，
后端会按 `GapFilling` 分支处理它（回显读旧 JSON items、判分恒 0 分）。**契约立场**：
以 `QuestionTypeEnum` 为权威（后端判分、学生端展示、`sample-contract.md` 均按它），管理端两处标签与两个表单默认值属缺陷，
需在第 3 组的实施清单中登记；本文件**只登记冲突，不改代码**。状态：`[已确认]`（代码可重放）。

### 5.1 单选题（编码 1，真实 3780 行）

| 契约项 | 内容 | 证据 / 状态 |
| --- | --- | --- |
| 题干 | 权威 `question_content.title`（含 HTML 原文）；检索投影 `title_text` | D-02；`[已确认]`（读侧 Q-4 已优先新表，`:146-148`） |
| 选项存储 | 权威目标 `question_content.options`（JSON 数组，元素 `{prefix, content, score, itemUuid}`）；**当前读侧仍只读旧 JSON** 的 `questionItemObjects` | `[已确认]`：T3-4 实测"新表 options 写'新选项 Z'，读回仍是旧选项 A"；写入端 `QuestionContentServiceImpl.java:41`，读取端 `QuestionServiceImpl.java:175-182` |
| 选项顺序 | 以 JSON 数组顺序为准；数组顺序即展示顺序，**不得依赖 `prefix` 排序** | `[待验证]`——管理端 `single-choice.vue:97-111` 在增删选项后按 `ABCDEFGHIJKLMNOPQRSTUVWXYZ` 重排 prefix，脚本 S-3/S-4 写入的 `q.options` 顺序规则未逐行核对（U-1/U-2），二者是否一致未做人工对照（A-06 人工对照未执行） |
| 选项前缀 | `prefix` 必填（`QuestionEditItemVM.java:8-9` 有 `@NotBlank`）；默认 A/B/C/D（`single-choice.vue:63-66`） | `[已确认]`（代码） |
| 选项分值 | `item.score` 字段存在但**单选题路径不使用**：判分给整题 `t_question.score` | `[已确认]`：`ExamPaperAnswerServiceImpl.java:235` 用 `question.getScore()`；`scoreFromVM/scoreToVM` 以"分"为整数字面量（`ExamUtil.java:22-45`，分值 ×10 存储、显示 ÷10） |
| 项标识 | `itemUuid` 字段存在（`QuestionEditItemVM.java:15`）；**脚本导入写它、运行期表单不写它**：S-3 样例题的 `itemUuid` 有值（`16_import_2026_html_mock_exam.sql:21`、`:29`、`:36`，如 `3513fb0a`），而管理端五处 `form` 均不生成也不提交 | `[已确认]`（代码 + 脚本样例）；结论：**当前不能承诺 `itemUuid` 在全库范围内非空或稳定**——它是脚本侧标识，运行期保存会写入 `null` |
| 两种表示的元素结构差异 | 旧 JSON `questionItemObjects` 元素 = `{prefix, content, itemUuid}`（**无 `score`**，S-3 样例题为证）；`question_content.options` 元素 = `QuestionEditItemVM` 序列化 = `{prefix, content, score, itemUuid}`（`QuestionContentServiceImpl.java:41`） | `[已确认]`（脚本样例逐字比对）；→ 互转不是恒等映射，属 DB-03 的实测缺口，登记为第 10 节第 1 项 |
| 答案表达 | `t_question.correct`（varchar(255)）存正确项 `prefix` 字面量，如 `"A"` | 写：`Question.java:165-173`（非多选走 `setCorrect(correct)`）；读：`QuestionServiceImpl.java:153-156`（回显）、`ExamPaperAnswerServiceImpl.java:234`（判分） |
| 答案权威位置 | **当前 = `t_question.correct`**（判分唯一来源，Q-14）；目标 = `question_content.correct_answer`（D-02） | `[已确认]`：T3-1/T3-4 实测 + Q-14 走查。切换必须同步改 Q-14 与 Q-6（entry-inventory 9.3） |
| 判分来源与算法 | `t_question.correct.equals(提交content)` 字符串全等；答对得 `question.score`，否则 0 | `[已确认]`：`ExamPaperAnswerServiceImpl.java:234-235` |
| 默认分值 | 管理端默认 `score` 表单值见 `single-choice.vue`（本项目未在契约中固化默认值，分值由作者填写） | `[已确认]`（不设默认值属产品约定，非契约） |

### 5.2 多选题（编码 2，**真实 0 行 → 仅合成样本验证**）

| 契约项 | 内容 | 证据 / 状态 |
| --- | --- | --- |
| 题干 / 选项结构 | 同单选题（`options` JSON 数组、`prefix` A–D 可扩展） | **仅合成样本验证**：T3-1 用 `saveFromEdit` 往返，断言"顺序、前缀、分值、项标识全部保留" |
| 选项顺序 | 数组顺序 = 展示顺序 | **仅合成样本验证** |
| 答案表达 | `t_question.correct` 存**排序后逗号连接**的 prefix 串，如 `"A,C"` | `[已确认]`（代码）：`Question.java:167-169` → `ExamUtil.contentToString(correctArray)`（`ExamUtil.java:82-84`：`.sorted().joining(",")`） |
| 回显表达 | `ExamUtil.contentToArray` 按 `,` 切分（`ExamUtil.java:93-95`） | `[已确认]`（代码）：`QuestionServiceImpl.java:157-158` |
| 判分来源与算法 | 提交数组同样排序连接后**全等**比较；**不区分部分正确**，答对得整题分、否则 0 | `[已确认]`（代码）：`ExamPaperAnswerServiceImpl.java:237-241`；**未验证**：无真实样本，部分给分策略是否被业务需要未确认 |
| 契约风险 | `contentToString` 依赖输入已排序；若提交含重复项（如 `["A","A","C"]`）会得到 `"A,A,C"` 而不等于 `"A,C"` → 判错 | **待验证**：无真实样本，重复项是否被前端拦截未核对（管理端 `multiple-choice.vue:116` 由勾选生成，理论上不重复；外部 API 可构造） |
| 目标权威位置 | 同单选题：`question_content.correct_answer` | 未验证（无真实样本） |

### 5.3 判断题（编码 3，**真实 0 行 → 仅合成样本验证**）

| 契约项 | 内容 | 证据 / 状态 |
| --- | --- | --- |
| 编码冲突 | 后端编码 3 = 判断题；管理端把 3 标为"填空题"且 `gap-filling.vue` 提交 3 | `[已确认]`：见 5.0 冲突表 |
| 题干 | 权威 `question_content.title` | 仅合成样本验证 |
| 选项 | **无独立选项**：管理端真/假用 `el-radio-group` 表达，并**构造一个伪 item** `form.items = [{content: correct, isAnswer: ...}]`（`true-false.vue:85-86`） | `[已确认]`（代码）；**注意**：该伪 item 会被 `setQuestionInfoFromVM` 序列化进旧 JSON 与 `question_content.options`，形成"只有一个选项的判断题"结构 |
| 答案表达 | `t_question.correct` = 字符串 `'1'`（正确）或 `'0'`（错误） | `true-false.vue:85`；回显判定 `:131`（`correct === '1' \|\| 'true' \|\| items[0].isAnswer`） |
| 判分来源与算法 | 与单选题同支：`question.correct.equals(提交content)` | `[已确认]`（代码）：`ExamPaperAnswerServiceImpl.java:231-235` |
| 契约立场 | 权威编码 = 3 = 判断题（后端）；管理端标签与表单需修正 | 见 5.0 |

### 5.4 填空题（编码 4，**真实 0 行 → 仅合成样本验证**）

| 契约项 | 内容 | 证据 / 状态 |
| --- | --- | --- |
| 编码冲突 | 后端编码 4 = 填空题；管理端把 4 标为"判断题"且 `true-false.vue` 提交 4 | `[已确认]`：见 5.0 冲突表 |
| 题干 | 权威 `question_content.title` | 仅合成样本验证 |
| 选项/填空项 | 每个空是一个 item，**管理端只填 `content`，不生成 `prefix`**（`gap-filling.vue:22-27`、`:59`、`:88`） | `[已确认]`（代码）。**契约缺口**：`QuestionEditItemVM.prefix` 有 `@NotBlank`（`:8-9`），而填空表单只传 `{content}` → 依赖前端以外的路径可能校验失败；**未验证**该路径是否真的会被 400 拒绝（无真实样本、未运行接口测试） |
| 项标识 | 当前**没有**填空项标识：既不生成 `prefix`，也不生成 `itemUuid` | `[已确认]`（代码）。DB-03 要求"填空项标识可无损表达" → **现状不满足**，需在契约中把"填空项标识"定义为**数组下标（0 起）**或补 `itemUuid` |
| 答案表达 | 每个填空的正确答案 = 该 item 的 `content`（`gap-filling.vue:24`）；落库到旧 JSON 的 `questionItemObjects[].content` | 写：`QuestionServiceImpl.java:186-204`；读：`QuestionServiceImpl.java:160-163` |
| 答案权威位置 | **当前 = 旧 JSON items 的 content**（`t_question.correct` 不参与）；目标 = `question_content.options`（同结构，含各空答案，数组下标为填空项标识）。**与 D-23 块模型的关系**：题干中的空位在题干块内表达，各空答案仍由 `options` 数组承载，数组下标与空位顺序一一对应 | `[已确认]`：`QuestionServiceImpl.java:160-163`（回显）、`:175-182`（选项，填空路径与选项同源）；块关系见 3.9 规则 6 |
| 判分来源与算法 | **不判分**：`case GapFilling` 只存 `JsonUtil.toJsonStr(contentArray)` 到 `answer`，`customerScore = 0`（`ExamPaperAnswerServiceImpl.java:243-246`） | `[已确认]`（代码）。契约结论：**填空题当前一律 0 分**，需人工阅卷；`do_right` 不会被设置为 true |
| 契约立场 | 权威编码 = 4 = 填空题（后端）；管理端标签与表单需修正；填空项标识需补齐 | 见 5.0 |

### 5.5 简答题（编码 5，真实 2833 行）

| 契约项 | 内容 | 证据 / 状态 |
| --- | --- | --- |
| 题干 | 权威 `question_content.title` | `[已确认]` |
| 选项 | 无选项。管理端表单只有 `title` 与 `correct`（`short-answer.vue:47-55`） | `[已确认]`（代码） |
| 答案表达 | `t_question.correct` 存**整段参考答案文本**（不受 255 字符限制？见风险行） | 写：`Question.java:165-173` 的 else 分支 `setCorrect(correct)`；`short-answer.vue:54,72` 要求必填 |
| 判分来源与算法 | **不判分**：`default` 分支存 `customerQuestionAnswer.getContent()` 到 `answer`，`customerScore = 0`（`ExamPaperAnswerServiceImpl.java:248-251`） | `[已确认]`（代码）：简答题一律 0 分，需人工阅卷 |
| **`correct_answer` 100% 为空** | **A-06 实测：题型 5 的 2833 行，`question_content.correct_answer` 全部为空（`missing_correct_answer = 2833`）；题型 1 的 3780 行为 0** | `[已确认]`（A-06）。**结论**：这是 **`question_content` 路径的系统性缺口，而非业务上"简答题没有标准答案"**，理由有三：① 编辑表单把 `correct` 设为**必填**（`short-answer.vue:72`），说明业务上存在参考答案；② 后端把参考答案写进 `t_question.correct`（`Question.java:171`），而 `saveFromEdit` 复制的是 `question.getCorrect()`（`QuestionContentServiceImpl.java:42`）——若两者都非空，新表不该为空；③ 真实数据里简答题走的是**脚本/回填**路径（S-1/S-3/S-4，应用层编辑路径的写入量可忽略），而 `backfillFromLegacy` 取 `COALESCE(q.correct_answer, q.correct)`（`QuestionContentMapper.xml:70`），脚本 S-1 用同一表达式（`13_backfill...sql:20`）→ 2833 行全空**反推出这些题目在 `t_question.correct_answer` 与 `t_question.correct` 两列上都没有值**，即旧链路里也没有参考答案 |
| 该结论的业务含义 | 2833 道简答题**没有可用标准答案**：AI 答疑或人工批改无从引用"标准答案"；RAG 切片正文（`RagDocumentMapper.xml:114`）对简答题写入空串 | `[已确认]`（A-06 + 代码）；**定性已部分落定（D-28，2026-09-23）**：契约先按"允许为空且不补齐"执行；成因（业务无答案 vs 导入漏采）由内容负责人在实施前最终确认，再定是否补采 |
| 契约要求 | ① 不得用"当前答案"补齐历史简答题的答案（D-06）；② 管理端展示必须把空答案显示为**空/待补充**，不得显示为空字符串以外的占位推测；③ 简答题进入考试时的判分必须明确标为"待人工/不自动判分"；④ **空答案是合法状态**（D-28）：答案块（3.9）允许为空，为空不触发告警、不自动生成占位答案；若成因确认为"导入漏采"，走补采流程并留痕，不静默补齐 | D-06、D-18、D-21、D-28 |
| 契约风险 | `t_question.correct` 是 `varchar(255)`（`V1__baseline.sql:605`），而简答参考答案是整段文本 → **超过 255 字符会被截断或报错** | **待验证**：未查真实库该列长度分布，也未运行插入探针（只读约束）。若成立，则"参考答案无损表达"（DB-03）在旧列上不成立，反向支持把 `question_content.correct_answer`（longtext）作为权威 |

### 5.6 五题型判分的共同契约（跨题型）

| 条目 | 内容 | 证据 |
| --- | --- | --- |
| 判分只读 `t_question.correct` | 单选/判断/多选全部用 `question.getCorrect()`；填空/简答恒 0 分 | `ExamPaperAnswerServiceImpl.java:228-253` |
| 判分不读任何版本行 | 判分用当前 `t_question` 行，题目被编辑后判分依据随编辑改变 | 同上；D-17 要求改为绑定版本 |
| 选项读侧未收口 | 判分与回显都不读 `question_content.options` | T3-4、`QuestionServiceImpl.java:175-182` |
| 分值为整题粒度 | 每题一个 `t_question.score`（×10 存整数）；选项级 `item.score` 不参与判分 | `ExamUtil.java:22-45`；`ExamPaperAnswerServiceImpl.java:235` |
| 版本一致性 | 目标：一次读取的题干/选项/答案/解析必须来自**同一版本行** | DB-02；T3-2 在合成样本上已通过（"`getCurrent` 返回的题干与解析来自同一版本行，未出现跨版本拼接"），但**当前线上读路径并不满足**（7.2） |

---

## 6. 跨版本一致性与历史契约

### 6.1 写侧：一次保存必须是一个版本

| 条目 | 契约 | 证据 / 状态 |
| --- | --- | --- |
| 保存边界 | `t_question` 业务列 + `t_text_content`（兼容表示）+ `question_content`（新版本行）在**同一事务**内完成；失败整体回滚 | D-14；W-1/W-3 有 `@Transactional`（`QuestionServiceImpl.java:79`、`:108`）；T2-3 实测回滚后原 current 仍在。**例外**：W-5（HTTP JSON 批量导入）**无事务**（`QuestionController.java:113-151`），会留半批数据 → 属必须修复的边界 |
| 版本号产生 | `SELECT COALESCE(MAX(version),0)+1`；竞态由唯一键暴露（1062），不静默重试 | D-10、D-15；T2-2 |
| current 翻转 | 先 `clearCurrent` 再 `insert`，二者同事务；`clearCurrent` 不能单独提交 | D-13/D-14；T2-3 |
| 选项/答案副本 | 在 Q-3 与 Q-14 切换完成前，`question_content.options`/`correct_answer` 与旧 JSON/`t_question.correct` **必须双写**，且以版本行内的值为最终判据 | 依据 3.3/3.4 与 T3-4 |
| 幂等 | 按请求身份 + 业务身份分别定义；重复请求与合法二次编辑必须可区分 | D-10；T2-4 未实现（幂等方案未定） |

### 6.2 读侧：同一次展示必须来自一个版本

| 条目 | 契约 | 现状差距 |
| --- | --- | --- |
| 题干、选项、答案、解析同版本 | 全部来自同一 `question_content` 版本行 | **不满足**：题干/解析来自新表（`QuestionServiceImpl.java:146-148`、`:171`），**选项来自旧 JSON**（`:175-182`），**答案四路分流**（`:151-169`：单选/判断/多选读 `t_question.correct`，填空/简答读旧 JSON）。一次编辑后界面可出现"新题干 + 旧选项"（entry-inventory 7.1 结论） |
| `title_text` 投影规则 | 单一规则：`<[^>]+>` → 空格、压缩空白、trim；重复生成结果一致 | T3-3 通过（Java 侧）；但与脚本 S-1 抄 `q.title_text` 的规则不同源（3.4） |
| 列表短标题回落 | `title_text` → `data-fallback` 提取 → `HtmlUtil.clear(title)` → 旧 JSON `$.titleContent` | Q-8（`QuestionAnswerController.java:58-78`）三级 + 第四级 |
| 展示可用 vs 检索可用 | 分开表达，不得把"能展示"标为"能检索"；降级规则已定稿（**D-25**） | DB-14；现状 `rag_document.status='ready'` 混淆两者（4.3）。降级矩阵与"向量未就绪 ≠ 无可用证据"的区分见 **4.6** |

### 6.3 历史作答绑定（D-17）

**契约**：

1. **绑定时机 = 开始作答时**；绑定对象 = **不可变的内容版本**（`question_content.id` 或等价 `(question_id, version)`）；
2. 展示题面、自动判分、历史回看**统一使用该绑定版本**；
3. **交卷只保存答案与结果，不重新选择版本**；
4. 进考试时若找不到可用版本 → **拒绝并明确报错**（D-14）；
5. 无法恢复当时版本的旧记录 → **显式标记不可恢复**，不用当前版本补齐（D-06）。

**现状对照**：

| 事实 | 证据 | 与契约的差距 |
| --- | --- | --- |
| 绑定发生在**交卷时**，不是开始作答时 | `ExamPaperAnswerServiceImpl.java:202` 方法注释 + `:212` 写入 `questionTextContentId` | 时机不符；但真正的致命点是绑定对象 |
| 绑定对象是 `t_question.info_text_content_id`（旧表示行 id） | `ExamPaperAnswerServiceImpl.java:212` | 该行**可变**：题目编辑就地更新同一行（`QuestionServiceImpl.java:120-122`）→ 换任何时点都冻不住历史 |
| 判分读"当前位置"的答案 | `ExamPaperAnswerServiceImpl.java:234`、`:240` | 与"统一用绑定版本"冲突 |
| 绑定列在答题表上 | `t_exam_paper_question_customer_answer.question_text_content_id`（`V1__baseline.sql:547`） | 需改为指向 `question_content.id`（新增列或改列语义，属第 3/4 组） |
| 真实数据无漂移候选 | A-10b 返回空集（没有任何 `content_versions > 1` 的题出现在答题记录里）；A-10：108 条答题题目侧引用完整（`no_content_row_by_legacy_link = 0`），96 条缺答案侧 `text_content_id` | **无真实多版本样本** → 本契约只能靠设计推演 + 隔离库合成样本验证（T3 已就绪，T5 未实现） |
| 96 条缺答案侧文本 | A-10 | 契约定性：属**正常业务状态**（学生未作答/未保存文本），不是数据缺陷；但需在管理端契约中体现为"该次作答无文本内容"（第 8 节） |

**目标绑定时点的取舍记录**：D-17 明确"试卷发布时固定版本"留待将来"全场同卷"需求；本期不在组卷时固定（组卷侧目前也未见落库点，`decision-cards.md` Q-03 走查记录）。

### 6.4 生命周期事务边界与失败行为（DB-05 / DB-08 定稿）

每个生命周期动作给出：事务范围（必须同事务的部分）、失败行为、下游动作。
"下游动作"允许最终一致的，必须可对账（4.6 / 7.3）。

| 生命周期动作 | 事务范围（必须同事务） | 失败行为 | 依据 / 证据 |
| --- | --- | --- | --- |
| 创建 | 写 `t_question`（身份+业务列）+ `t_text_content`（兼容表示）+ `question_content` v1（current）+ 知识点关系（如有），同一事务 | 任一步失败整体回滚；不得留下无 current 的有效题（D-14）；版本冲突 1062 → 业务冲突提示 | W-1；T2-3 |
| 编辑（产生新版本） | `update t_question` 业务列 + `update t_text_content`（兼容）+ `clearCurrent` + `insert` 新版本行，同一事务 | 同上；乐观并发校验（D-16）失败 → 冲突提示且保留用户输入（D-15） | W-1/W-3；T2-2/T2-3 |
| 发布标记（D-24 新增） | 在保存事务内对当前版本行写"已发布"标记，**不单独成事务**；历史绑定（D-17）不依赖该标记 | 发布随保存回滚 | D-24（2026-09-23 拍板） |
| 停用 | 主记录（`t_question.deleted`）单行即时生效；下游为"标记 + 读时过滤"（7.3），允许最终一致但必须可对账 | 下游标记失败不回滚停用本身，但必须留下待处理记录并被对账发现 | W-6；D-21；7.3 |
| 恢复（停用反操作） | 清除 `deleted` + 恢复下游可见性，与停用对称 | 历史答卷与引用不因恢复改写 | D-21 |
| 删除（物理） | **本契约不鼓励物理删除**；若发生，知识点关系/资源引用/RAG 投影处置见 7.3；历史答卷对该题的引用按 D-06 显式标记"不可恢复" | A-04 的 643 条孤儿即硬删后果，是反例证据 | D-06；A-04 |
| 回填（W-15） | 前置普查 + 跳过"缺 version=1 但已有 current"的题 + 逐条闭环记录（原因/责任/重试/去向）；单条回填语句原子 | 无条件写 `is_current=b'1'` 会造第二个 current（T4-3 实测）→ 靠前置普查规避；语句失败整语句回滚（已演练） | `migration-plan.md` 第 2/4/5 节；`rehearsal-report.md` |
| RAG 投影刷新 | 与题目保存**不同事务**（现状即如此且目标不变）→ 靠 4.6 的"源版本失效 + 对账"收敛，不追求分布式事务 | 刷新失败 → `status='failed'` + 幂等重试（4.6） | W-20 无事务（事实）；4.6 |

**登记**：W-5（HTTP JSON 批量导入）等五处写入口**无事务保护**（entry-inventory 第 5 节，TD 已登记）
→ 违反本节契约，属实施阶段必须修复项；在修复前，这些入口产出的数据不得作为"契约已满足"的证据。

---

## 7. 停用（原「软删除」）契约（D-21）

### 7.1 逐场景行为

| 场景 | 契约行为 | 实现缺口 |
| --- | --- | --- |
| 新组卷 / 新增入卷 | 阻止 | W-6 只写 `t_question.deleted`；组卷查询的 `WHERE` 是否含 `deleted` 需在实施时逐条核对（`QuestionMapper.xml:181-203`、`:254-282`） |
| 已组卷未开始 | 保留引用并提示；阻止新的作答启动；要求管理员替换或确认 | 无实现 |
| 已开始的作答 | 继续使用已固定版本，不中途换题 | 依赖 6.3 的版本绑定先落地 |
| 已完成考试与历史答卷 | 保持可读且不改评分 | 现状天然满足（判分结果已落库） |
| 错题本 | 保留历史并标记停用，不进入新练习推荐 | **纠正（pending-verification #10，2026-09-23）**：`selectMistakesForAiPaper` **有过滤 `deleted`**（`AiPaperQuestionWhere` 片段第一行即 `q.deleted = 0`，`QuestionMapper.xml:218`；entry-inventory 早前"未见 deleted 条件"的判断有误，已登记纠正）。**但该片段未过滤 `status`** → D-21 停用上线时必须补此条件 |
| RAG | 不再作为新检索证据，返回前过滤 | 无实现（4.4） |
| 历史 AI 回答 | 不改写，引用可标记题目已停用 | 无实现 |
| 紧急撤下 | 与停用**不是同一种操作**，不混用 | 未定义 |

### 7.2 可验证性

**A-09 整组返回 0，根因是 `deleted <> b'0'` 的行为 0（A-01）**——即"停用传播"在真实数据下**不可验证**，
既没被证明存在也没被证明不存在。契约要求：停用语义必须在**隔离库合成样本**上验证（合成样本含
"已停用 + 已组卷未开始 + 已开始作答 + 已完成 + 错题本 + RAG 文档"六种状态）。
现状唯一可核对的过滤事实：`AiPaperQuestionWhere` 已含 `q.deleted = 0`（`QuestionMapper.xml:218`，7.1 纠正行）；
其余场景（组卷、RAG、错题本的 `status` 维度）无实现。

### 7.3 停用的下游失效语义清单（DB-08 / DB-11 定稿）

一致性立场：停用主记录**单行写、即时生效**；下游一律"**标记 + 读时过滤**"，
允许最终一致，但必须能被 4.6 的对账与 7.2 的隔离库合成样本验证覆盖。
不采用级联物理删除（A-04 的 643 条孤儿是硬删后果的反例）。

| 下游 | 现状 | 停用时的契约动作 | 一致性 |
| --- | --- | --- | --- |
| 知识图谱（`question_knowledge_point`） | W-6 不清关系（3.7）；K-6/K-9/K-10 只读关系行 | 关系行**保留不动**；图谱节点/边查询必须过滤已停用题（`deleted=0` JOIN `t_question`） | 读时过滤，即时 |
| RAG 文档 / chunk | 无代码写 `disabled`（4.4） | `rag_document` 打"停用"标记（与 4.6 状态体系合流），检索返回前过滤；chunk 不删 | 标记 + 读时过滤；对账兜底（4.6） |
| RAG 向量（Qdrant） | 无级联 | 向量**不即时物理删除**；命中结果在返回前按停用标记过滤 | 读时过滤 |
| 来源（`question_source`） | 只写不读（3.5） | **不动作**：来源是历史事实，停用不改变它 | — |
| 历史试卷 / 已完成答卷 | 判分结果已落库（7.1） | **不动作**：保持可读、不改评分；引用侧可标记"题目已停用"（不改历史 AI 回答正文） | — |
| 已组卷未开始的引用 | 无实现 | 保留引用并提示管理员替换或确认；**阻止新的作答启动** | 7.1 场景行 |
| 错题本 | `selectMistakesForAiPaper` 已过滤 `deleted`、未过滤 `status`（7.1 纠正行） | 补 `status` 条件；错题记录保留并标停用、不进新练习推荐 | 读时过滤 |
| 紧急撤下（版权/安全） | 未定义 | **独立操作，不与停用混用**：立即全链路不可见，动作与留痕单独定义 | D-21 末句 |

---

## 8. 管理端可承诺 / 不可承诺清单（DB-11）

### 8.1 管理端可以依赖的字段与语义

| # | 可依赖项 | 语义承诺 | 依据 / 证据 |
| --- | --- | --- | --- |
| 1 | `t_question.id` | 题目永久身份，不因内容修改而变 | A-02：无缺 id、无悬空 id |
| 2 | `question_content` 的 `(question_id, version)` | 内容版本号从 1 起连续，不跳号 | A-05 空集（真实数据）；T3-2（连续 1/2/3） |
| 3 | 每题**恰好一个** current（数据层） | 管理端可按"一题一条当前内容"设计页面 | A-03：6613/6613 恰好 1 个；A-03b 空集 |
| 4 | 每题**至多一个** current（约束层，待上线） | 重复 current 会被数据库拒绝并翻译为业务冲突 | D-13；T1-3 实测 1062（键名 `uk_question_content_current`） |
| 5 | 并发冲突有确定结果 | 并发编辑时失败方收到冲突提示而非 500，且用户输入保留 | D-15/D-16；T2-2 显式 1062 |
| 6 | 保存失败不留半成品 | 事务失败后原 current 版本仍在 | D-14；T2-3 实测 |
| 7 | `question_content.title` / `title_text` / `analysis` / `analysis_text` | 题干与解析（原文 + 纯文本投影）；投影规则可重复 | T3-2/T3-3；`QuestionContentServiceImpl.java:72-77` |
| 8 | `question_content.options` | 选项序列（JSON 数组，含 `prefix`/`content`/`score`/`itemUuid`） | 写入端 `QuestionContentServiceImpl.java:41`；**读侧未收口**（见 8.2 第 3 项） |
| 9 | 五种题型的答案表达（见第 5 节） | 单选 = 单个 prefix；多选 = 排序后逗号连接；判断 = `'1'`/`'0'`；填空 = 各空 `content`；简答 = 整段文本 | 见 5.1–5.5 |
| 10 | 来源展示（名称/年份/题号/原始引用） | 有值即显示；缺失显式为空，不推测补齐；原始采集信息保留 | D-18；A-08：`raw_ref` 100%、`metadata` 100%、`source_question_no` 100%、`source_year` 94.3% |
| 11 | 题型编码 1–5 的含义 | 以后端 `QuestionTypeEnum` 为准（1 单选/2 多选/3 判断/4 填空/5 简答） | `QuestionTypeEnum.java:8-12`；学生端一致（`enumItem.js:22`） |
| 12 | 停用（`t_question.deleted`）语义 | 见第 7 节逐场景表 | D-21 |
| 13 | 题目-知识点关联可人工增删并留痕 | 人工结果不被自动任务覆盖 | D-20 |
| 14 | 资源引用的定位方式 | 库内保存引用（`data-src`）与降级文本（`data-fallback`）；资源缺失显式标记；更新不得改变历史版本题面 | D-19；A-07b |
| 15 | RAG 投影携带源版本与生成配置（目标态） | 管理端可区分"展示可用"与"检索可用" | DB-09/DB-14；**当前未满足**（8.2 第 7 项）；降级矩阵已定稿（4.6/D-25） |
| 16 | 简答题空答案语义 | 空 = **合法状态**，展示为"空/待补充"，不自动补齐、不告警 | D-28（2026-09-23 拍板）；A-06；5.5 契约要求④ |
| 17 | 内容块模型的目标能力（目标态） | 多图/公共材料/小问/答案解析的**块级定位与显式顺序**；`*_text` 按统一规则重算且不混资源内部文本 | D-23；3.9（含夹具能力断言）；U-10 规则 |

### 8.2 当前**不能**承诺的能力

| # | 不可承诺项 | 原因 | 证据 |
| --- | --- | --- | --- |
| 1 | "每道有效题一定有 current"由数据库保证 | T1-4 实测两个候选 DDL 都只保证"至多一个"；存在性靠事务与业务校验 | D-14；T1-4 |
| 2 | 任何**具体的一致率 / 成功率 / 覆盖率数字** | D-11：阈值必须由审计基线推导；且 `migration-plan.md` 未产出 | D-11 |
| 3 | 编辑回显返回的选项与当前版本一致 | 读侧完全不读 `question_content.options`，仍取旧 JSON；新表 options 是只写字段 | T3-4 实测（改新表为"新选项 Z"，读回仍是"旧选项 A"） |
| 4 | 判分依据与回显答案同源 | 判分只读 `t_question.correct`（Q-14），回显按题型四路分流（Q-6） | `ExamPaperAnswerServiceImpl.java:228-244`；`QuestionServiceImpl.java:151-169` |
| 5 | 历史答卷展示"当时"的题面与答案 | 历史绑定的是**可变**的 `t_text_content` 行；D-17 的版本绑定尚未实现 | `ExamPaperAnswerServiceImpl.java:212`；`QuestionServiceImpl.java:120-122`；A-10b（无多版本样本） |
| 6 | 判断题/填空题的端到端语义一致 | 管理端与后端的 3/4 编码语义相反；且两类题真实库零行，从未端到端验证 | 5.0 冲突表；A-06 |
| 7 | "RAG 文档 `ready` = 可被检索" | 6613 个 `ready` 题目文档中只有 3774 个有已记录的 `indexed` 向量 → 2839 个（42.9%）**缺向量索引**；另有 40 条 `failed`。**限定**：这只说明向量召回链路对这批文档不可用，不等于整条检索链路不可用——词法召回、向量召回、最终返回过滤三者需分别验证，`ready` 不作为统一证明 | A-11、A-11c + 补充查询 |
| 8 | 向量与当前正文一致 | `rag_embedding.payload_hash` 不含正文指纹（只含 chunk_id/model/collection/vector_id）；正文刷新（W-18）与元数据写入（W-20）不同事务 | `RagDocumentMapper.xml:222`、`:119-120`；entry-inventory 4.4 |
| 9 | 题目变更会自动反映到 RAG 投影 | 题目写入路径不触发任何 `rag_*` 写入；唯一触发点是一个人工调用的 admin 端点 | `QuestionServiceImpl.java:79-126`；R-9 |
| 10 | 停用会从 RAG 检索中消失 | 无代码写 `rag_document.status='disabled'`，也无级联；且该语义**无真实样本**（A-09 全 0） | 4.4；A-09 |
| 11 | `source_hash` 可用于判断"内容是否变了" | 三套算法（Java `sha256`、回填 `SHA2(CONCAT_WS(...))`、R-4 的 `SHA2(q.id\|tc.content)`）基准不同，跨路径比较无意义 | `QuestionContentServiceImpl.java:79-91`；`QuestionContentMapper.xml:78`；`RagDocumentMapper.xml:119,203` |
| 12 | `question_asset` 能提供任何资源事实 | 表 0 行、`t_question.images` 全空、**没有任何脚本 INSERT 过该表**；图片事实在仓库外（被 `.gitignore` 排除） | A-07；entry-inventory 第五节结论；`workflow.md` 第 6 节① |
| 13 | 题干里的富文本能被枚举 | 题干**不含**内嵌 `<img>`/`<table>`/KaTeX 标记（各 0 行）；图文以外链 `div` 形式存在 → 不能用题干 HTML 判断富文本 | A-07 分层计数 |
| 14 | 题目知识点关联是可信的权威关系 | 运行期只读、由脚本写入、含 **643 条孤儿**（指向不存在的题目），且不是停用残留 | A-04；entry-inventory 3.1 |
| 15 | 知识点关联的 `relevance` 有区分度 | 唯一脚本写入者恒写 `1.0000`，且应用层零读取 | `13_backfill...sql:75`；`KnowledgeGraphServiceImpl.java:238,257` |
| 16 | 人工标注可记录修改人与时间 | `question_knowledge_point` 表只有 4 列（`id`/`question_id`/`knowledge_point_id`/`relevance`），**没有** `source`/`create_user`/`update_time` | `V1__baseline.sql:195-203`；D-20 要求的留痕无处落 |
| 17 | 填空题与简答题能被自动判分 | 两者 `customerScore` 恒 0、`do_right` 不设置 | `ExamPaperAnswerServiceImpl.java:243-251` |
| 18 | 简答题有标准答案 | 2833 道简答题在 `question_content.correct_answer` **100% 为空**；旧链路当次回填时 `q.correct_answer` 与 `q.correct` 也都为空。**D-28 定性：空为合法状态**（成因由内容负责人实施前确认） | A-06；`QuestionContentMapper.xml:70`；`13_backfill...sql:20`；D-28 |
| 19 | 多选/判断/填空的题型契约有真实数据支撑 | 三类题真实库**零行**（只有题型 1 与 5） | A-06 + 补充查询 |
| 20 | 题号能表达非数字形式 | `t_question.source_question_no` 是 `int`，`question_source.source_question_no` 才是 `varchar(50)` | `V1__baseline.sql:594` vs `:210` |
| 21 | 管理端能维护来源数据 | `question_source` 的应用层写入者为 0，读取者也为 0（纯脚本表） | entry-inventory 5/7 节；A-08 |
| 22 | 已停用题目在各下游都被正确屏蔽 | 停用无级联、无事务；且真实数据 `deleted` 行数为 0，语义**不可验证** | A-01/A-09；W-6 |
| 23 | 迁移期间"不丢题、不覆盖新编辑"的方案已成立 | `migration-plan.md`（第 4 组）未产出；W-15/W-16/W-17/W-20 与 W-6 的下游失效是四处未补齐的事务边界 | entry-inventory 6.1/9.2；plan 第 4 组未开始 |
| 24 | `itemUuid` 在全库范围内非空或可作为稳定标识 | 脚本导入会写它（`16_import_2026_html_mock_exam.sql:21`），但运行期五处管理端表单都不生成/不提交它 → 经编辑保存后该字段为 `null` | `QuestionEditItemVM.java:15`；`single-choice.vue:52-60` 等 |
| 25 | 填空题有填空项标识 | 管理端只填 `content`，既不生成 `prefix` 也不生成 `itemUuid` | `gap-filling.vue:22-27,59,88` |
| 26 | 完整草稿工作流（草稿箱/多级审校/定时发布） | **D-24 已拍板：不做**——采用简化版本 + 显式发布标记；将来需要审校/多级发布时按 D-20 留痕模式补状态字段 | D-24（2026-09-23）；6.4 发布标记行 |
| 27 | 复杂图文题（第 43 题）当前即可按块入库 | 块**模型**已定稿（D-23，3.9），但物理落库形态待物理结构附录、实现属后续 Feature；当前库内仍是整段 HTML + 外链 div | D-23；3.9；`physical-design.md` |
| 28 | 旧 JSON 选项与 `question_content.options` 能恒等互转 | 两处元素结构不同：旧 JSON `{prefix,content,itemUuid}`（无 `score`）vs 新表 `{prefix,content,score,itemUuid}`；尚无逐题人工对照 | `16_import_2026_html_mock_exam.sql:21` vs `QuestionContentServiceImpl.java:41`；3.4 `options` 行 |

---

## 9. 决策归档（Q-08 至 Q-12，2026-09-23 已拍板）

初稿本节为"待决策小节"。五项已于 2026-09-23 按 [`decision-recommendations.md`](appendix/decision-recommendations.md)
由作者**整体确认**，归档为 requirements 的 **D-23～D-27**（简答题空答案口径另归档为 **D-28**）。
本契约各受影响位置已同步定稿：

| 编号 | 决策（归档编号） | 本契约中的定稿落点 |
| --- | --- | --- |
| Q-09 | 结构化内容块（**D-23**） | 3.9（块模型 + 投影规则 + 能力断言 + S-06 结论）；3.4 `content_format`/`has_image` 行；4.3 `chunk_index` 行；5.4 填空答案行 |
| Q-10 | 简化版本 + 显式发布标记（**D-24**） | 6.4 发布标记行；8.2 第 26 项改写；发布标记的物理字段在物理结构附录 |
| Q-11 | 展示/检索就绪分离 + 分级降级（**D-25**） | 4.6（就绪定义 + 降级矩阵 + 失效/重试/对账）；4.3 `status` 行；6.2 末行 |
| Q-12 | 资源引用 + 校验 + 归属（**D-26**） | 3.6（列行改写 + D-19/D-26 落地表）；4.5 源块/资源标识行；3.9 规则 7④ |
| Q-08 | `t_text_content` 分字段退役、整表保留（**D-27**） | 3.3 两行；3.2 `title` 行退出条件；6.4 回填行 |

**注意**：Q-05（`question_asset` 去留）已由 D-19 拍板为"资源契约在本期、素材库/OCR/对象存储不做"，
D-26 进一步把资源契约细化为"归属（版本+块）+ 定位 + 校验 + 可得性"；落库形态二选一在物理结构附录定，
**仍不设计素材库**。

---

## 10. 待验证清单（已全部收口，2026-09-23）

初稿登记的 16 项已于 2026-09-21 补证据、2026-09-23 经作者确认**全部收口**。
**逐项结论与证据的当前权威清单是 [`pending-verification.md`](appendix/pending-verification.md)**；下表为初稿登记，保留追溯。

| 归宿 | 项号 |
| --- | --- |
| 本轮补证据（已验证） | 1（抽样一致，逐字段人工对照转实施验收）、2、3、4、5、6、9、10（含对 entry-inventory 的纠正）、11 |
| 拍板归档为 D 决策 | 8 → **D-28**；13 → **D-22**；15 → **D-25** |
| 设计已处理 | 12（迁移方案已写入前置普查 + 跳过 + 闭环） |
| 子任务完成 | 14（`sample-2024-q43.md`，保真验证"部分"，新发现 N-1～N-3） |
| 确认延期 | 7 → TD-018（`has_image`/`has_code` 标"不可信"） |
| 转实施验收 | 16（需合成样本 + 已实现的过滤逻辑） |

**初稿登记（历史，保留"未验证原因"原文）**：

| # | 待验证项 | 为什么未验证 | 归属 |
| --- | --- | --- | --- |
| 1 | 旧 JSON `questionItemObjects` 与 `question_content.options` 的元素结构能否无损互转 | A-06 的人工规范化对照未执行；脚本 S-3/S-4 写入的 `q.options` 取值规则未逐行通读（U-1/U-2） | A-06 人工对照；第 3 组字段字典 |
| 2 | 脚本写入的选项顺序/前缀与编辑路径序列化是否一致 | 同上 | A-06 人工对照 |
| 3 | `question_source.paper_name` 的非空率与取值分布 | A-08 的 SQL 未包含该列；只读审计已收官，本轮不补跑 | 第 4 组迁移对账 |
| 4 | `t_text_content.embedding` 的非空率与是否有历史消费方 | 只读到写入点、无读取点；真实库该列未采样 | 第 3 组字段字典 |
| 5 | `t_question.difficulty`（与 `difficult` 并存）的取值来源与是否为零引用列 | 应用层读写均为零，脚本写入未逐行核对 | 第 3 组字段字典 |
| 6 | `t_question.correct` 的 `varchar(255)` 对简答参考答案的实际截断风险 | 未采样该列长度分布，未做插入探针（只读约束） | 隔离库 T3 补充用例 |
| 7 | `has_image=137` / `has_code=185` 的取值口径（脚本如何判定） | 脚本 S-3/S-4 未逐行通读（U-1/U-2） | A-06/A-07 人工对照 |
| 8 | 简答题 `correct_answer` 为空是"业务无标准答案"还是"导入漏采" | A-06 只能证明为空，不能证明原因；需管理端/内容负责人确认 | 人工确认（G1 之外的确认项） |
| 9 | `question_knowledge_point` 是否存在同一 `(question_id, knowledge_point_id)` 重复行 | A-04 只统计了孤儿与悬空，未统计关系重复度 | 只读补查（需新查询，本轮不做） |
| 10 | `selectMistakesForAiPaper` 的 WHERE 是否实际过滤 `deleted` | `AiPaperQuestionWhere` 片段（`QuestionMapper.xml:217-252`）中未见 `deleted` 条件，但拼接结果未逐条验证 | 第 3 组读路径切换清单 |
| 11 | `QuestionContentMapper.insert` 未设 `has_image`/`has_code` 时是否会撞 NOT NULL | T3 测试夹具首次撞上 `Column 'has_image' cannot be null`（`design-test-plan.md` 5.2 第 2 项）；真实调用方是否总会设值未走查完 | entry-inventory 未确认项；第 3 组 |
| 12 | `backfillFromLegacy` 是否会造出第二个 current（对"缺 version=1 但已有 current"的题） | T4-3 实测**会**（无条件写 `is_current=b'1'`）；但真实数据每题都有 version=1（A-05），因此无真实触发样本 | 迁移方案必须处理（第 4 组） |
| 13 | 题型 3/4 的编码冲突以哪一侧为准 | 需人确认（管理端标签 vs 后端枚举）；真实数据零行 | 人工确认（见 5.0） |
| 14 | 复杂图文题（2024 第 43 题）的结构化实例 | 原图未核对，S-01/S-02 未执行 | plan 第 1 组未勾选项 |
| 15 | "展示就绪 / 检索就绪"的降级行为 | Q-11 未决定 | G1 后续确认 |
| 16 | 停用语义在下游的真实行为 | A-09 全 0：`deleted<>0` 的行数为 0，整组检查**无样本** | 隔离库合成样本（T4/T5 扩展） |

---

## 11. 本契约对 `plan.md` 第 2 组完成判据的对应关系

`plan.md` 第 2 组完成判据：**DB-01 至 DB-05、DB-07、DB-11 每条在 `data-contract.md` 中有对应字段级结论**。

| 需求 | 本文件中的落点 |
| --- | --- |
| DB-01 唯一权威写入者 | 第 3 节全表（每行的"目标权威位置"列）+ 第 8.1 节 |
| DB-02 版本一致、不拼接不同版本 | 第 2 节（四个身份）、6.1、6.2、5.6 版本一致性行 |
| DB-03 五题型无损表达 | 第 5 节（5.1–5.5）+ 5.0 编码冲突 |
| DB-04 保真与投影 | 3.2（`title`/`title_text` 行）、3.4（`title_text`/`content_format` 行）、3.6（资源契约）、6.2 `title_text` 投影规则 |
| DB-05 current 唯一性与并发 | 3.4 的 `is_current` 行 + 「契约边界」+「并发编辑契约」两个子节 |
| DB-07 来源/资源/知识点生命周期 | 3.5（`question_source`）、3.6（`question_asset` + D-19 落地形式）、3.7（`question_knowledge_point`） |
| DB-11 管理端契约 | 第 8 节（可承诺 17 项 / 不可承诺 28 项） |
| （附带覆盖）DB-06 历史绑定 | 6.3 |
| （附带覆盖）DB-08 停用语义 | 第 7 节（7.1/7.2/7.3）+ 6.4 生命周期事务边界 |
| （附带覆盖）DB-09 索引可追踪 | 4.3、4.5、4.6（失效/重试/对账定稿） |
| （附带覆盖）DB-12 至 DB-15 | 3.9（块模型，DB-12）、4.6 + 4.3/4.5（DB-13/DB-14）、3.9 规则 9（DB-15/S-06 结论）；运行验收依赖实施 |

**原"未完成"的第 2 组条目现状**：

- Q-07 的索引调整方案与 `EXPLAIN` 对比——**已完成**（批次 4，`physical-design.md` 第 3 节：建议不新增索引、改写读路径）；
- 「隔离库应用候选 DDL + EXPLAIN 对比」——**已完成**（批次 4，`ddl/batch4-experiment.sql`）；
- 一题多图/公共材料/小问的结构化实例（S-01/S-02）——**已完成**（`sample-2024-q43.md` 真题事实 +
  原创夹具 `FIXTURE-NOVA16-01` 能力断言；契约落点 3.9 规则 7）；
- S-06 现有 RAG 输入差异——**结论已记录**（3.9 规则 9：现输入为旧 JSON 整串，缺图描述/缺小问结构/
  投影混图内标签；D-23 落地后改块级投影），运行级验证依赖实施。

---

## 12. 变更记录

| 日期 | 变更 | 依据 |
| --- | --- | --- |
| 2026-09-21 | 初稿：字段级三列、五题型契约、可承诺/不可承诺清单、待决策与待验证清单 | `requirements.md` D-01..D-21；`audit-report.md` A-01..A-13；`entry-inventory.md`；`design-test-plan.md` 第 5 节；`sample-contract.md` |
| 2026-09-21 | 新增两条本轮走查证据：① `t_question` 内容列在 MyBatis 读路径不可达（`domain/Question.java:14-66` + `QuestionMapper.xml:18-21`）；② 管理端与后端的题型 3/4 语义相反（`list.vue:97-98`、`paper/edit.vue:136-137`、`gap-filling.vue:55`、`true-false.vue:53` vs `QuestionTypeEnum.java:10-11`） | 本轮代码走查（提交 `c2c0a84`） |
| 2026-09-23 | **定稿**：Q-08～Q-12 整体确认 → D-23～D-27、简答空答案 → D-28。新增 **3.9 内容块契约**（块模型 + `*_text` 投影规则回应 U-10 + `t_essay_question` 指向回应 U-11 + S-01/S-02 能力断言 + S-06 结论）、**4.6 就绪状态与降级契约**（两状态 + 降级矩阵 + 源版本失效/重试/对账，DB-09/DB-14）、**6.4 生命周期事务边界**（DB-05/DB-08）、**7.3 停用下游失效语义**（DB-08/DB-11）。同步：3.2/3.3/3.4/3.6/4.3/4.5/5.4/5.5/6.2/8.1/8.2 按决策定稿；7.1 错题本行纠正（`selectMistakesForAiPaper` 实际已过滤 `deleted`，pending-verification #10）；第 10 章 16 项待验证收口；修复表格内未转义 `\|` | `requirements.md` D-23～D-28；`decision-recommendations.md`（作者 2026-09-23 整体确认）；`sample-2024-q43.md`；`pending-verification.md` |
