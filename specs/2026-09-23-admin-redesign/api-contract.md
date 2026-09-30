# 接口契约 — 页面动作、数据来源、状态与错误示例

> **状态**：设计稿 **v1.1（2026-09-24 修订：AI 运营优先定位）**。v1.0（2026-09-23）的矩阵与示例有效；
> v1.1 变更：题目编辑端点转为 **AI 写入管道契约**（新增 §3.7：dry-run 校验/批量提交/作业查询），
> 编辑器页面相关条目标注延后，新增 MCP 协议立场（§7.6）。修订处标注 v1.1。
> 本文件是**页面—接口—数据库契约映射**，不是实现清单：
> 标注 `[已有]` 的条目附代码依据（当前主干可复核）；`[待改]` / `[待新增]` 为设计目标，
> **实施 Feature 完成前一律视为不存在**。任何页面不得在端点落地前渲染为可用功能。
> **依据**：[`scope-and-pages.md`](scope-and-pages.md) P1～P17、[`flows-and-wireframes.md`](flows-and-wireframes.md) F-01～F-17、
> [`../2026-09-20-database-architecture/data-contract.md`](../2026-09-20-database-architecture/appendix/data-contract.md)
> （D-13～D-28、第 8 节可承诺/不可承诺清单）、
> [`../2026-09-21-dual-client-account-conflict/requirements.md`](../2026-09-21-dual-client-account-conflict/requirements.md)（AUTH 契约）。
> **依据的代码**：`apps/backend/backend-app/src/main/java/com/mindskip/xzs/controller/admin/**`、
> `configuration/spring/security/**`（行号为提交 `c2c0a84` + 工作区状态）。

## 1. 通用契约

| 项 | 契约 | 现状依据 |
| --- | --- | --- |
| 响应封装 | `{code, message, response}`；`code=1` 成功 | 全部 admin Controller 沿用 `RestResponse` |
| 分页 | 请求 `{pageIndex, pageSize, …filters}` → 响应 `response={list, total, pageNum}` | 旧端 9 个列表页同构使用 |
| 时间格式 | 请求/响应统一 `yyyy-MM-dd HH:mm:ss`（字符串）；前端展示层转 `YYYY-MM-DD HH:mm:ss`（Element Plus token） | 修复旧端缺陷 #5 |
| 鉴权 | 所有页面接口位于 `/api/admin/**`，后端强制 `ROLE_ADMIN`；`401` 未登录/分区无上下文，`403` 角色不符 | `SecurityConfigurer.java:118-120`；无方法级注解，新增敏感操作须评估是否补方法级校验（见 §7 风险） |
| requestId | 5xx 类错误响应必须携带 `requestId`，前端原样展示 | tech-stack"对外错误返回稳定错误码和 requestId"；后端已有 `ErrorController` 统一出口，补齐 `requestId` 透出为 `[待改]` |
| 写幂等 | 重复提交的判定按 D-10：请求身份与业务身份分离；唯一键冲突必须翻译为业务冲突，不得 500 | `data-contract.md` 3.4 并发编辑契约、T2-2 |

## 2. 认证契约（9.21 引用，不改设计）

| 动作 | 端点 | 契约 |
| --- | --- | --- |
| 登录 | `POST /api/admin/login` `[已有]` | body `{userName, password, remember}`；成功置 admin 分区会话；勾选 remember 时种 `remember-me-admin`（30 天） |
| 退出 | `POST /api/admin/logout` `[已有]` | 只清 admin 分区上下文与该端凭据；学生端不受影响（AUTH-02） |
| 当前用户 | `POST /api/admin/user/current` `[已有]` | 前端刷新时恢复内存态用户信息；失败按未登录 |
| 会话过期 | 任一接口 `code=401` | 前端跳登录（记录原目标）；不得清除学生端任何状态 |

> 端标识只选择上下文、不授予角色（`ClientEnd.java:17-19`）；多角色规则未定（TD-001），本设计不引入权限 UI。

## 3. 页面 → 接口矩阵（ADM-05）

标注：**已** = 已有（附依据）；**改** = 已有需改造；**新** = 待新增。
"数据来源"列只写数据库契约已定稿的表/字段（引用 9.20，不自造）。

### 3.1 认证与工作台

| 页面动作 | 端点 | 标注 | 数据来源 / 契约 |
| --- | --- | --- | --- |
| 登录/退出/当前用户 | §2 三端点 | 已 | 9.21；`t_user` |
| 总数统计 | `POST /dashboard/index` | 已 | `DashboardController.java:34` → 五个 Service 计数；字段：`examPaperCount, questionCount, doExamPaperCount, doQuestionCount, mothDay*` |
| 待办清单 | `GET /api/admin/overview/todos` | **新** | 只读聚合：① RAG 对账缺口（4.6 对账：应有投影 vs `indexed`）；② `rag_embedding.status='failed'` 计数；③ 停用残留（7.3 下游标记待处理记录）；④ 待人工阅卷答卷数（`t_exam_paper_answer.status`）。**不新增采集体系**，全部为既有表的可查询状态 |

### 3.2 题目域（内容治理）

| 页面动作 | 端点 | 标注 | 数据来源 / 契约 |
| --- | --- | --- | --- |
| 题目列表 | `POST /api/admin/question/page` | **改** | `QuestionController.java:40`。响应每行补：`version`（当前版本号）、`status`（1/2/3）、`displayReady`/`retrievalReady`（候选 10 列，或列表查询派生）、`sourceYear`；短标题改用 `title_text`（Q-10 已用旧 JSON，切换按 9.20 R-1~R-2 节奏） |
| 题目回显（预览/编辑载荷） | `POST /api/admin/question/select/{id}` | **改** | `:72` → `getQuestionEditRequestVM`。响应补：`expectedVersion`（当前 `question_content.version`，D-16）、来源面板字段（`question_source`，D-18）、**写入者归属（v1.1）**、资源引用列表（`question_asset` 候选 9 列）。**回显读侧收口（选项读新表）属 9.20 读路径切换，不在本端点单独决定**（8.2 第 3 项约束）。v1.1：主要消费方 = 预览/版本历史 + AI 写入管道（表单延后） |
| 保存（新建/更新） | `POST /api/admin/question/edit` | **改**（v1.1：消费方 = AI 写入管道，表单延后） | `:56`。请求补 `expectedVersion`（D-16）；块内容按 D-23 `content_blocks` schema；`*_text` 由后端重算（任何写入方不提交）；同事务写三处（6.1）；冲突语义见 §4.1。**在 9.20 双写期结束前，本端点继续双写兼容表示**。批量场景改走 §3.7 管道端点 |
| 停用/恢复/紧急撤下 | `POST /api/admin/question/{id}/disable`、`/restore`、`/withdraw` | **新** | 写 `t_question.status`（1/2/3）+ `status_reason/status_update_user/status_update_time`（物理设计 §7）；请求带原因；下游为"标记+读时过滤"（7.3）。**旧 `POST /question/delete/{id}`（`:79`）冻结使用，仅保留兼容直至旧端退役** |
| 版本历史 | `GET /api/admin/question/{id}/versions` | **新** | 读 `question_content` 全版本行：`version, is_current, published, published_at, create_time`（D-02/D-24，候选 8 列）；操作人暂无留痕列（缺 `create_user` 于版本行）→ 展示"缺失显式为空" |
| 来源面板 | 随 `select/{id}` 返回（不单独开端点） | 新（并入） | `question_source`：`source_name, source_year, source_question_no, raw_ref, metadata`；`paper_name` 可空（3.5 待验证项按可空展示）；全缺时显示"无采集来源" |
| 预览 | 复用 `select/{id}` | 已+改 | 前端渲染遵循 `data-src`/`data-fallback` 内联规则（现行展示事实，A-07b） |
| （不提供）批量导入 | `POST /question/upload`、`/upload/txt` | 冻结 | 无事务保护（TD）；转换器落地前不提供 UI（`data-contract.md` 6.4） |

### 3.3 试卷 / 任务 / 答卷（考试运营）

| 页面动作 | 端点 | 标注 | 数据来源 / 契约 |
| --- | --- | --- | --- |
| 试卷列表/编辑/回显/删除 | `POST /api/admin/exam/paper/page`、`/edit`、`/select/{id}`、`/delete/{id}`、`/list` | 已 | `ExamPaperController.java:29-68`；框架写 `t_text_content`（该语义仍权威，D-03/D-27） |
| 选题弹窗查询 | `POST /api/admin/question/page` | 改（同 3.2） | **查询过滤 `deleted=0 AND status=1`**（D-21：新组卷排除非正常题；`AiPaperQuestionWhere` 已有 `deleted` 过滤先例 `QuestionMapper.xml:218`） |
| 任务列表/编辑/回显/删除 | `POST /api/admin/task/*` | 已 | `TaskController.java:30-56`；前端修复创建时试卷下拉（`exam/paper/list` 创建即加载） |
| 答卷列表 | `POST /api/admin/examPaperAnswer/page` | 已 | `ExamPaperAnswerController.java:34`；只读 |
| 答卷详情 | `GET /api/admin/examPaperAnswer/{id}/detail` | **新** | ① 答卷头（`t_exam_paper_answer`）；② 逐题：学生答案（`t_exam_paper_question_customer_answer`）+ 题面按**绑定版本**读（D-17：`question_content_id` 列，候选 3b；未绑定旧行 → "候选匹配、未证实"或"不可恢复"标记，物理设计 §8 三态）；③ 停用题标记（`t_question.status`）。填空/简答显示"待人工阅卷"（5.4/5.5 判分事实） |
| 管理端评分 | — | 不提供 | 后端无评分端点；延后另立 Feature |

### 3.4 AI 治理

| 页面动作 | 端点 | 标注 | 数据来源 / 契约 |
| --- | --- | --- | --- |
| 供应商列表/保存/测试/删除 | `POST /api/admin/ai-config/providers`、`/provider/save`、`/provider/{id}/test`、`/provider/delete/{id}` | 已 | `AiConfigController.java:38-65`；Key 仅掩码（`listSafe()`） |
| 用量分析 | `POST /api/admin/ai-config/usage` | 已 | `:71`（窗口 ≤365 天）：`summary/byProvider/byDay/recentLogs` |
| Prompt 全生命周期 | `GET/POST /api/admin/prompt-ops/*`（13 端点） | 已 | `PromptOpsController.java:42-109`；V2 状态机 + 审计留痕（操作人+IP） |
| 评测数据集/启动/记录/详情/对比 | `GET/POST /api/admin/ai-evaluation/*`（5 端点） | 已 | `AiEvaluationController.java:32-57`；V3 运行表 |
| RAG 构建触发 | `POST /api/admin/ai-config/rag/index` | 已 | `:80`；响应 `{backfilled, normalized, indexed, failed, …}` |
| RAG 检索冒烟 | `POST /api/admin/ai-config/rag/search` | 已 | `:147` |
| RAG 状态查询 | `GET /api/admin/ai-config/rag/status`、`GET /rag/status/{questionId}` | **新** | 读候选 10 列：`rag_document.source_content_version/display_ready/retrieval_ready/gen_rule_version`、`rag_embedding.status/error_message/indexed_at`；汇总行 = 对账（4.6：只发现异常不自动修复） |
| RAG 单题重建 | `POST /api/admin/ai-config/rag/index`（范围=单题） | 已（复用） | 前端以 `{scope:'question', questionId}` 调用；服务端按题回填+归一化+嵌入 |
| 用量明细浏览器 | `GET /api/admin/ai/usage-logs` | **延后** | 服务层 `AiAgentService.getUsageLogs` 已实现但无 Controller（§5.4）；实施可与待办接口同批补只读端点，页面延后 |
| 检索日志浏览器 | — | **延后** | `rag_retrieval_log`/`rag_answer_citation` 写而不读；Phase 7 Trace 收敛后再议 |

### 3.5 系统管理

| 页面动作 | 端点 | 标注 | 数据来源 / 契约 |
| --- | --- | --- | --- |
| 账户列表/编辑/启停/重置密码 | `POST /api/admin/user/page/list`、`/edit`、`/changeStatus/{id}`、`/resetPassword`、`/select/{id}` | 已 | `UserController.java:43-159`；`role` 参数区分学生(1)/管理员(3) |
| 学科 CRUD | `POST /api/admin/education/subject/*` | 已 | `EducationController.java:30-62` |
| 消息列表/发送 | `POST /api/admin/message/page`、`/send` | 已 | `MessageController.java:41,58` |
| 消息详情 | `GET /api/admin/message/{id}` | **新** | `t_message` + `t_message_user`（接收与已读计数）；修复旧端断链 |
| 操作日志 | `POST /api/admin/user/event/page/list` | 已 | `UserController.java:51` |

### 3.7 AI 写入管道（v1.1 新增；F-17 的契约面）

定位修订后，题目域的**受约束写入路径（sanctioned write path）** = 本管道。三个消费者共用同一批端点：
管理端界面（作业记录/确认/验收）、dev-time AI Agent（经 CLI 壳，见 architecture §1.5）、
未来的产品内 Agent（触发条件见 §7.6）。设计原则：**操作与协议分离**——管道是 Internal Operation +
Confirmed Command（tech-stack 术语），不绑定任何 AI 协议。

| 动作 | 端点 | 标注 | 契约 |
| --- | --- | --- | --- |
| 批量校验（dry-run，不落库） | `POST /api/admin/question/batch/validate` | **新** | 载荷与 commit 同构；返回结构化报告：逐题 schema 违例（D-23/五题型契约/D-22 编码）、重复题、资源引用缺失、`expectedVersion` 失效。**只发现问题不修复**（D-14 哲学）；无副作用，可反复调用 |
| 批量提交（Confirmed Command） | `POST /api/admin/question/batch/commit` | **新** | 携带幂等键（客户端生成 UUID）+ 逐题 `expectedVersion`；**必须先经管理端界面人工确认**（L2）或携带确认凭据；逐题同事务双写（6.1），部分失败不回滚全批；`*_text` 后端重算；产出作业记录 |
| 作业列表 | `GET /api/admin/question/batch/jobs` | **新** | 分页：批号、操作者（人工/脚本/AI）、类型、范围、成功/失败计数、状态（校验中/待确认/执行中/已完成/部分失败/已回滚） |
| 作业详情 | `GET /api/admin/question/batch/jobs/{id}` | **新** | 逐题成败 + 失败原因 + 回滚动作记录；`[重试失败项]` 以同一幂等键复用 commit |
| 写入者归属查询 | 随 `question/page`、`select/{id}` 返回（并入） | 新（并入） | 最后写入者：人工用户名 / 脚本批次号 / 作业号；无归属显式 `null`，前端标"未知"（D-18 同源：不推测） |

管道红线：① AI 不得绕过管道直写生产库（直写仅限 9.20 隔离库演练）；② 冲突/错误响应**机读化**
——`response` 内含稳定错误码 + 结构化明细（§4.1 的 `conflictType` 模式推广到全部校验错误），
人类可读 `message` 只是附加；③ 幂等键相同 = 同一作业，重放返回原结果不重复执行（D-10）。

### 3.8 矩阵小结

- 已有且直接可用：**34** 个端点（含复用）；已有需改造：**6**；待新增：**12**
  （v1.0 的 8 项 + v1.1 管道 4 项：批量校验、批量提交、作业列表、作业详情）；
  冻结/延后：批量导入旧入口 ×2（由 §3.7 管道取代）、评分、用量明细、检索日志、题目编辑表单（页面延后，端点转管道消费）。
- 每个 `[待新增]` 端点均在 `scope-and-pages.md` §4.2 页面清单中有唯一归属页面；
  不存在"为页面便利新造答案/版本/RAG 状态语义"的端点——状态字段全部引用 9.20 已定稿契约（§5）。

## 4. 关键请求 / 响应示例

> 示例为**设计契约**（目标行为），字段名以实施为准、语义不得偏离。

### 4.1 版本冲突（D-15/D-16；flows F-06）

```jsonc
// 请求：A 基于打开时的 v2 提交
POST /api/admin/question/edit
{ "id": 8541, "expectedVersion": 2, "questionType": 5,
  "contentBlocks": [ {"id":"b01","type":"title","seq":1,"html":"…"},
                     {"id":"b02","type":"material","seq":2,"label":"图a","html":"…","assetRefs":["a1"]} ],
  "analysis": "…", "score": 10, "subjectId": 1, "difficult": 3 }

// 响应（服务端校验当前版本已推进到 3）：业务冲突，HTTP 200 + 业务码（或 409，实施定一种并全站统一）
{ "code": 4409, "message": "该题已被他人更新（当前 v3，你基于 v2）。已保留你的修改，请选择继续编辑或放弃。",
  "response": { "conflictType": "expectedVersionMismatch",
                "currentVersion": 3, "expectedVersion": 2 } }
```

前端行为：弹 W4 冲突框；表单不清空；提供"复制我的内容"。绕过校验的旁路写入由唯一键兜底：
`DuplicateKeyException`（MySQL 1062，键 `uk_question_content_version`）翻译为**同一** `4409` 语义，绝不 500（T2-2）。

### 4.2 重复提交（D-10）

```jsonc
// 同一请求体因双击/重试到达两次；服务端以业务身份（内容+expectedVersion）判定：
// 第一次：成功 → { "code":1, "response": { "questionId":8541, "version":3 } }
// 第二次：expectedVersion 已失效 → 与 4.1 同一冲突响应；不得静默再插一个版本行
```

按钮级防重（提交中禁用）是前端第一道闸；服务端不依赖它。

### 4.3 会话过期（ADM-08）

```jsonc
// 任一 admin 接口在无 admin 分区上下文时（学生端登录不影响本判定）：
{ "code": 401, "message": "登录状态已过期", "response": null }   // 前端：单例提示 → /login（记录原目标）
// 角色不符（学生身份请求 admin 接口）：后端 403 → 前端消息条"无权限"，不跳登录
```

### 4.4 缺失资源（D-19/D-26；flows F-11）

```jsonc
// select/{id} 响应片段：
"assets": [
  { "assetId":"a1", "blockId":"b02", "assetUrl":"question-html/…/fig-a.html",
    "fallbackText":"图 a：指令格式示意", "availability": 1, "contentHash":"…", "lastCheckedAt":"2026-09-20 10:00:00" },
  { "assetId":"a2", "blockId":"b03", "assetUrl":"question-html/…/fig-b.html",
    "fallbackText":"图 b：数据通路示意", "availability": 2, "lastCheckedAt":"2026-09-23 09:00:00" }
]
// availability: 0 未校验 / 1 存在 / 2 缺失 / 3 不可用
```

前端行为：`availability=1` 内联原图；`=2/3` 显示 `fallbackText` + "资料未就绪"标记；
**不显示伪造缩略图，不用当前内容补历史**（D-06）。

### 4.5 索引失败（D-25/DB-14；flows F-13）

```jsonc
GET /api/admin/ai-config/rag/status/8541
{ "code": 1, "response": {
    "questionId": 8541, "sourceContentVersion": 3,
    "displayReady": true, "retrievalReady": false,
    "chunks": 3, "embeddings": [
      { "status": "failed", "errorMessage": "embedding timeout after 3 retries",
        "embeddingModel": "embedding-2", "indexedAt": null, "vectorId": "…" } ],
    "reconciliation": "缺口：应有投影但无 indexed 向量；自动解答将仅用词法召回并标注" } }
```

文案红线："向量检索未就绪"（可恢复滞后）≠"无可用证据"；两者均不得渲染为成功。

### 4.6 正常分页（契约基准）

```jsonc
POST /api/admin/question/page
{ "pageIndex": 1, "pageSize": 20, "questionType": 1, "status": 1, "subjectId": 1 }
→ { "code": 1, "response": { "total": 3780, "pageNum": 1, "list": [
     { "id": 8542, "questionType": 1, "shortTitle": "链表操作…", "score": 2,
       "difficult": 2, "version": 1, "status": 1, "sourceYear": 2024,
       "displayReady": true, "retrievalReady": true, "createTime": "2026-05-27 20:41:30" } ] } }
```

## 5. 与 9.20 数据契约的交叉核对（不自造语义声明）

| 管理端消费的语义 | 引用 | 禁止事项（管理端不得） |
| --- | --- | --- |
| 版本号 = `question_content.version`，从 1 连续 | 8.1 第 2 项 / A-05 | 不得把 `legacy_text_content_id` 当版本身份（D-17） |
| 至多一个 current；存在性靠事务 | D-13/D-14 | 不得假设"必有 current"而省略空态；进考试/预览无可用版本须显式报错 |
| 冲突 = 保留输入 + 刷新重试 | D-15/D-16 | 不得自动合并、静默覆盖、或把冲突当 500 吞掉 |
| 发布 = `published` 置 1 不回退 | D-24（候选 8） | 不得实现"取消发布/撤回到草稿" |
| 就绪 = 展示/检索两状态 | D-25（候选 10）+ 4.6 | 不得用一个"ready"绿灯概括；不得把降级当成功 |
| 资源 = 引用+校验+归属+可得性 | D-26（候选 9） | 不得以 `has_image`（TD-018 不可信）判定有图；不得承诺对象存储 |
| 停用 = `status` 1/2/3 + 留痕 | D-21（物理设计 §7） | 不得把紧急撤下与停用混为一谈；不得级联物理删除 |
| 简答空答案 = 合法状态"待补充" | D-28 | 不得自动补齐、告警或显示为异常 |
| 填空项标识 = 数组下标 | 5.4 契约缺口结论 | 不得假设 `prefix`/`itemUuid` 非空（8.2 第 24/25 项） |
| 题型 3=判断、4=填空 | D-22 | 不得沿用旧端相反标签 |
| 判分与回显的读源现状 | 8.2 第 3/4 项 | 编辑回显在 9.20 读路径切换前不得宣称"新表读侧已收口" |

## 6. 状态矩阵（ADM-06 汇总）

四个正交维度（详见 flows F-11）：**展示就绪 × 检索就绪 × 资源可得性 × 停用状态**。
降级行为引用 9.20 4.6 矩阵（展示未就绪→不进自动解答；检索未就绪→仅词法+标注；停用→不作新证据）。
管理端任何页面不得把其中两个维度合并渲染。

## 7. 契约风险与实施约束

1. **方法级授权缺口**：现有 admin Controller 无 `@PreAuthorize`，仅靠 URL 前缀。本设计沿用
   （requirements 边界：复用既有授权），但 `[待新增]` 的写端点（停用/撤下/重建索引）实施时
   必须评估敏感级别，必要时补方法级校验；授权边界总审计属 Phase 8。
2. **匿名端点**：`/api/admin/upload/configAndUpload` 在 ignore 列表（`application.yml:53`）——
   新端不使用；退役动作见 `architecture-and-cutover.md` §4.2。
3. **读路径切换顺序**：`[待改]` 的列表/回显改造必须与 9.20 的 R-1～R-2 读路径切换同批或其后，
   不得超前于数据契约实施（否则制造新的只写不读字段，重演 TD-014）。
4. **响应码统一**：业务冲突码取值（如 `4409`）与 HTTP 映射在实施 Feature 中定死一次，
   前后端同批发布；本文件锁定**语义**不锁定数值。
5. **本文件不含任何"已实现"声明**：`[已有]` 仅表示后端端点当前存在且行为可复核；
   其响应结构按本契约改造后才算满足管理端页面需要。
6. **MCP 协议立场（v1.1）**：管道按"操作与协议分离"设计——§3.7 端点是本体；dev-time AI Agent
   以薄 CLI 壳消费同一批端点（当前唯一 AI 客户端，成本≈0）；**本 Feature 不引入 MCP**。
   MCP 是第 3 层协议换壳（触发制）：当真实出现第二个 AI 客户端、或需要工具被外部发现/复用时，
   将同一批 Internal Operation 包装为 MCP server（对应 roadmap M6 进入条件："外部 AI 客户端接入"）。
   若先建协议而后定操作，只会把漂移的 SQL 换成漂移的工具。

## 8. 变更记录

| 日期 | 变更 | 依据 |
| --- | --- | --- |
| 2026-09-23 | 初稿：通用契约、认证契约、六组矩阵、六类示例、交叉核对表定稿 | 双端代码盘点；9.20 D-13～D-28；9.21 AUTH 契约 |
| 2026-09-24 | **v1.1**：新增 §3.7 AI 写入管道（校验/提交/作业×4 + 写入者归属并入）、编辑端点消费方改管道、§3.8 计数更新（待新增 8→12）、§7.6 MCP 协议立场 | 用户定位修订（2026-09-24）；tech-stack Confirmed Command 术语；roadmap M6 条件 |
