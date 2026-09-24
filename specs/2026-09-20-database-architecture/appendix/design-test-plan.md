# 设计验收测试方案 —— 题目数据契约

状态：方案已定义，测试尚未编写和执行。本文件说明「用什么命令、在哪个库、断言什么」，
使 validation.md 的「设计验收」一列不再是抽象描述。

**本轮不执行正式迁移。** 下面的测试在隔离库中验证**候选设计能否成立**，不改变任何共享库的结构。

2026-09-21 补充：T1/T2 目前针对 `is_current` 候选约束，不代表已选定最终模型。
若 Q-01 选择当前版本指针，先同步本测试方案再执行；唯一性只能保证“至多一个”，
有效题“必须存在一个可用版本”需另验证。DB-12 至 DB-15 使用
[`sample-contract.md`](sample-contract.md) 的 S-01 至 S-06 补充验证。
设计测试独立于共享库的主线结构契约；正式迁移前不得把候选约束变为主线必需条件。

## 1. 隔离库

所有会写数据的测试必须指向独立库，禁止连接共享的开发库。命名与建库：

```sql
CREATE DATABASE master408_design_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

结构与数据来源（**2026-09-21 修正**）：

**不能用「由 Flyway 从空库建到 V5」这条路径。** 实测：在空库上执行迁移会在
`V1__baseline.sql` 第 807 行失败——该脚本先对 `ai_user_key` 执行 `ALTER TABLE`（第 802-807 行），
而建表语句在第 809 行，空库上必然抛 `ERROR 1146 Table 'ai_user_key' doesn't exist`。
也就是说 V1 是**基线脚本**，它假定库中已存在原始结构与后续迁移的表。
（同一原因意味着新克隆的仓库无法从零建库，这条已记入技术债候选，见第 5 节运行记录的备注。）

因此隔离库的建立方式是**结构导出**，而不是从空库迁移：

```powershell
# 1) 建空库
mysql -h 127.0.0.1 -u root -p -e "DROP DATABASE IF EXISTS master408_design_test;
  CREATE DATABASE master408_design_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
# 2) 导出参照库的结构（不含业务数据）与 Flyway 历史行
mysqldump -h 127.0.0.1 -u root -p --no-data --single-transaction --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 --result-file=.data/design-test-schema.sql xzs
mysqldump -h 127.0.0.1 -u root -p --no-create-info --single-transaction --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 --result-file=.data/design-test-history.sql xzs flyway_schema_history
# 3) 导入（注意：PowerShell 5.1 的 > 会写成 UTF-16，必须用 --result-file）
mysql -h 127.0.0.1 -u root -p master408_design_test -e "source .data/design-test-schema.sql"
mysql -h 127.0.0.1 -u root -p master408_design_test -e "source .data/design-test-history.sql"
```

导入后应用启动时 Flyway 只做校验（`Schema is up to date. No migration necessary.`）。
数据来源按可复现程度排序：① 无既有数据时用 T1/T4 自建的合成样本（当前选择）；
② 脱敏后的真实库子集（题目、内容、来源、知识点，不含用户与密钥）；
③ 需要时再导入 `src/main/resources/db/demo/01_demo_seed.sql`。

2026-09-21 实测环境：MySQL 8.0.36（候选 B 的函数索引可用，需 8.0.13+）；
隔离库 50 张表、Flyway V0(baseline)–V5 全部 success、业务数据 0 行。

保存库名、建立方式、Flyway 版本和数据条数；报告只写这些，不写连接口令。

```powershell
mvn -f apps/backend/backend-app/pom.xml test `
  "-Dtest=QuestionContentContractTest,QuestionContentConcurrencyTest,QuestionVersioningTest,QuestionBackfillIdempotencyTest"
```

## 2. 测试类与断言

候选 DDL 的应用/回滚由测试夹具或迁移脚本完成，测试方法本身**只读断言 + 探针行回滚**，
不得在库中留下探针数据。

### T1 `QuestionContentContractTest`（结构契约，只读 + DDL）

前置：应用 `ddl/current-uniqueness-strict.sql` 或 `ddl/current-uniqueness-transitional.sql`。

| 断言 | 验证的需求 |
| --- | --- |
| `uk_question_content_version` 存在且列为 `(question_id, version)` | DB-05 |
| `uk_question_content_current` 存在，且对同一 `question_id` 插入两行 `is_current=1` 抛 `DuplicateKeyException` | DB-05 |
| 对同一 `(question_id, version)` 重复插入抛 `DuplicateKeyException` | DB-05 |
| 六张题目领域表与 `question_content` 全部列存在，逐列状态与 Flyway 一致 | DB-01、DB-11 |
| 在 `question_asset`、`question_source`、`question_knowledge_point` 中，删除被引用题目会因缺外键而**不**失败 | DB-07、DB-10（记录现状，作为不补外键的依据） |

第一项与第四项可直接扩展现有 `DatabaseSchemaContractTest`，不必新建类；
后两项需要探针写入，必须包在 `@Transactional` 的测试方法内以便自动回滚。

### T2 `QuestionContentConcurrencyTest`（并发与冲突，隔离库写探针）

| 用例 | 操作 | 断言 |
| --- | --- | --- |
| T2-1 顺序重试 | 同一 `(question_id, version)` 插入两次 | 第二次失败，最终该题只有一个该版本行 |
| T2-2 并发版本竞争 | 两个线程同时执行 `selectMaxVersion` → `clearCurrent` → `insert` | 恰有一个成功；另一个要么失败，要么得到更大的版本号；结束后该题 `is_current=1` 的行数恒为 1 |
| T2-3 事务失败不得留下半成品 | 在 `clearCurrent` 之后让 `insert` 抛异常 | 事务回滚后该题 `is_current=1` 的行仍存在且为原版本 |
| T2-4 重复请求 | 相同内容、相同请求标识提交两次 | 不产生第二个版本（需要 DB-05 的幂等方案落地后才有意义） |

T2-2 是本方案的核心证据：它证明「唯一键把竞态从静默数据损坏变成显式失败」。
T2-4 在幂等方案确认前标为「方案未定，暂不实现」。

### T3 `QuestionVersioningTest`（版本与题型）

| 用例 | 断言 |
| --- | --- |
| 单选、多选、判断、填空、简答五种题型各一题 | `title/options/correct_answer/analysis` 往返转换后语义不变，选项顺序、项标识、分值不变（DB-03） |
| 同一题连续三次编辑 | 版本为 1、2、3，`is_current` 只在最后一版为 1（DB-02） |
| 读取 API | 返回的题干、选项、答案、解析全部来自**同一个**版本行（DB-02、DB-11） |
| 富文本样本 | `title` 原样保存，`title_text` 为按规则生成的投影，重新生成结果可重复（DB-04） |

### T4 `QuestionBackfillIdempotencyTest`（回填可重复）

| 用例 | 断言 |
| --- | --- |
| 连续执行两次 `backfillFromLegacy` | 第二次不新增行（现有 `NOT EXISTS (version = 1)` 保证），题数、版本数、`source_hash` 不变（DB-10） |
| 回填后新增一版再回填 | 已有版本不被覆盖，仍只有 version=1 由回填产生 |
| 软删除题目 | 不产生新的 version=1 行（现有语句的 `q.deleted = b'0'` 条件），且已存在的行不被删除（DB-08） |
| 中断后重跑 | 同一批次重复执行不产生重复版本，异常题目可定位 |

### T5 `AiRagProjectionContractTest`（派生投影，本轮先定义不实现）

| 用例 | 断言 |
| --- | --- |
| 题目内容升到第 5 版，第 4 版的索引任务后到 | 检索文档不因旧任务回退（DB-09） |
| 题目停用 | 新的组卷与检索不可用，历史引用仍可读（DB-08） |
| 索引失败 | 有明确的重试与对账状态，失败可观测（DB-09） |

T5 依赖 `rag_document.source_ref` 与题目的连接方式，需先完成 `audit-queries.sql` 的 A-11b。

## 3. 不入库的验证

以下不需要数据库，用现有测试或走查完成：

- **DB-01 权威写入者**：按 `audit-queries.sql` A-01/A-02 的读写入口清单逐条走查代码，
  报告中每个字段给出「当前写入者 → 目标权威位置」结论。
- **DB-06 历史考试**：走查 `ExamPaperQuestionCustomerAnswerMapper.xml`、
  `QuestionServiceImpl.getQuestionEditRequestVM`、`ExamPaperAnswerServiceImpl` 的实际读取路径，
  再用 A-10/A-10b 的真实分布判断是否已存在漂移候选。
- **DB-11 管理端契约**：字段所有权表 + A-03/A-06 的覆盖率结果，输出可依赖与不可承诺两部分。

## 4. 命令与结果记录

```powershell
# 结构契约（现有基线，不得因本 Feature 变红）
mvn -f apps/backend/backend-app/pom.xml test "-Dtest=DatabaseSchemaContractTest"

# 本 Feature 的定向测试（T1、T3、T4）
mvn -f apps/backend/backend-app/pom.xml test `
  "-Dtest=QuestionContentContractTest,QuestionVersioningTest,QuestionBackfillIdempotencyTest"

# 隔离库并发探针（T2，会写数据，必须指向 master408_design_test）
mvn -f apps/backend/backend-app/pom.xml test "-Dtest=QuestionContentConcurrencyTest"
```

每条记录包含：日期、代码提交、库名、Flyway 版本、命令原文、退出码、失败用例名与原始断言消息。
未执行的项保持未勾选，并在 validation.md 的「未执行」清单中列出。

## 5. 运行记录

环境（三次运行相同）：库 `master408_design_test`（结构导出建立，50 张表，Flyway V0–V5 success，
无业务数据）；MySQL 8.0.36；代码提交 `c2c0a84`（工作区含未提交改动）；日期 2026-09-21。

| 时间 | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 20:34 | `mvn -o test -Dtest=QuestionContentContractTest` | 0 | Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 |
| 20:5x | `mvn -o test -Dtest=QuestionBackfillIdempotencyTest` | 0 | Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 |
| 20:5x | `mvn -o test -Dtest=QuestionContentConcurrencyTest` | 0 | Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 |
| 21:1x | `mvn -o test -Dtest=QuestionVersioningTest` | 0 | Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 |
| 21:3x | `mvn -o test -Dtest=QuestionContentConcurrencyTest`（追加 T2-5 真实写入流程后） | 0 | Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 |

### 5.1 关键实测结果

- **T1-3 / T1-5**：候选 A（生成列 + 唯一键）与候选 B（函数索引）**都能**在 MySQL 8.0.36 上建立，
  并拒绝同一题的第二个 `is_current=b'1'` 行（错误为 1062，键名 `uk_question_content_current`）；
  两者都不约束非 current 行。
- **T1-4**：两个候选都**只保证"至多一个"，不保证"必须有一个"**——只有非 current 行的题目可以正常写入。
  这条证实了 design-test-plan 开头写的"唯一性只能保证至多一个，存在性需另验证"。
- **T1-6**：删除被引用的题目**成功**，并在 `question_content` 留下孤儿行——现状无外键（D-07 的现状依据）。
- **T2-1**：同一 `(question_id, version)` 顺序重插被 `uk_question_content_version` 拒绝。
- **T2-2（本方案核心证据）**：两个线程并发执行 `selectMaxVersion → clearCurrent → insert`，
  实测**成功写入的版本=[2]，失败方得到显式 `DuplicateKeyException`（`Duplicate entry '990301-2'`），
  最终 current 行数 = 1**。即：唯一键把竞态从静默数据损坏变成显式失败，不变量成立。
- **T2-3**：`clearCurrent` 之后抛异常，事务回滚，原 current 行与版本保持不变，不会留下 0 个 current 的半成品。
- **T2-5（2026-09-21 追加，真实写入流程）**：两个线程并发调用**真实服务** `QuestionContentServiceImpl.saveFromEdit`
  （而非简化 Mapper 序列），实测**成功写入版本=[2]，失败方得到显式 `DuplicateKeyException`
  （`Duplicate entry '990301-2' for key 'uk_question_content_version'`），最终 current 恒为 1 行**。
  结论与 T2-2 一致，但证据来自真实写入路径。
- **T4-2**：重复执行 `backfillFromLegacy` 不新增行，`source_hash` 不变（`NOT EXISTS (version = 1)` 生效）。
- **T4-3（陷阱记录）**：对"缺 `version=1` 但已有 current 行"的题目，回填会**造出第二个 current**
  ——因为 `backfillFromLegacy` 无条件写 `is_current=b'1'`。当前真实数据不触发（A-05 证明每题都有
  version=1），但候选约束上线后这类题目会让回填直接报 1062；迁移方案必须先处理这种状态。
- **T4-4**：回填确实以旧 JSON 为输入，题干与解析来自 `t_text_content.content` 的
  `titleContent` / `analyze`（DB-01 的现状证据）。
- **T3-1**：五种题型（单选/多选/判断/填空/简答）经真实 `QuestionContentServiceImpl.saveFromEdit`
  往返，题干与解析原样保存，选项 JSON 的**顺序、前缀、分值、项标识全部保留**，
  `correct_answer` 取自 `t_question.correct`（由调用方先落库）。
- **T3-2**：连续三次编辑产生 version 1/2/3，只有最后一版 `is_current=b'1'`；
  `getCurrent` 返回的题干与解析来自**同一版本行**，未出现跨版本拼接。
- **T3-3**：`title` 原样保存 HTML；`title_text` 为去标签投影（`<[^>]+>` → 空格、压缩空白、trim）
  且同一输入重复生成结果一致；`has_image`/`has_code` 由内容推导；`content_format` 恒为 `html`。
- **T3-4（字段所有权证据，影响 DB-01）**：`question_content.options` 由 `saveFromEdit` 写入，
  但编辑读取路径 `QuestionServiceImpl.getQuestionEditRequestVM`（`:175-182`）**只从旧 JSON 取选项**，
  从不读新表。实测：把新表的 `options` 写成"新选项 Z"后，读回来的仍是旧 JSON 的"旧选项 A"。
  即新表的 `options` 与 `correct_answer` 目前是**只写字段**——这是"每个核心字段必须指定唯一权威
  写入者"的直接反例，必须在 `data-contract.md` 中定论（改写路径、改读取路径，或明确降级为投影）。

### 5.2 实测中发现的两个既有问题（供技术债候选）

1. **空库无法由 Flyway 建库**（`V1__baseline.sql` 第 802-809 行顺序问题）——与 `README.md`
   "完整数据库结构由 Flyway 管理，首次启动时会执行 V1..V5"的说法不符；新克隆的仓库无法从零启动。
2. **`QuestionContentMapper.insert` 要求调用方显式提供 `has_image` / `has_code`**：
   该语句把它们列入 INSERT 列表，因此未设值时会撞 `Column 'has_image' cannot be null`，
   而不是走列默认值 `b'0'`。测试夹具第一次就撞上了；真实调用方是否总会设值需要走查确认
   （见 `entry-inventory.md`）。
3. **新表的选项与答案目前只写不读**（T3-4 实测）：`saveFromEdit` 写入
   `question_content.options` / `correct_answer`，而编辑读取路径只从旧 JSON 与 `t_question.correct` 取值。
   两个副本都被维护，但只有一个被读取——字段所有权未定，静默分叉的风险存在。

### 5.3 未执行

- **T5 `AiRagProjectionContractTest`**：按计划本轮只定义不实现。
- **T2-4（重复请求幂等）**：按 design-test-plan 在幂等方案确认前不实现。
- **A-06 / A-07 的规范化人工对照**：需要外部 `question-html/**` 文件与渲染结果，未执行。
- **S-01/S-02/S-06 样本契约**：依赖第 43 题原图核对，未执行。
