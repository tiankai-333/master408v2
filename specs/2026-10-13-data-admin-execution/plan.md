# Plan — 新数据库与管理端落地

本计划随交流逐步形成。进度见 [roadmap](../roadmap.md)。

## 第一步：本地新库 + 新管理端最小闭环（2026-10-20 执行）

执行顺序与结果（详细证据见 [validation](validation.md)）：

- **S1 旧库基线**：记录 `xzs` / `master408_v2` 表清单与关键行数（`tmp/baseline-olddb.txt`），作为零影响对比依据。
- **S2 建新库**：执行 `newdb-v0.sql` → `master408` 4 表（import_batch/question/paper/subject），ngram FULLTEXT 建表正常（MySQL 8.0.36）。
- **S3 后端**：backend-app 新增 `com.mindskip.xzs.newdb` 包（配置/鉴权/导入/仓储/控制器，约 20 类 + 3 个 mapper XML + `application-newdb.yml`），唯一既有文件改动为 pom.xml 加 jsoup。构建与单测通过。
- **S4 导入**：首次 `candidate=10 imported=10`；重放 `duplicate=10 imported=0`，行数与批次数不变；坏样本夹具 3 条全部按机读原因码拒绝。
- **S5 前端**：新建 `apps/frontend/admin-next`（登录/工作台/题目列表/题目详情），构建通过。
- **S6 走查**：curl 全套（错误凭据 401、无 token/坏 token 401、登录、stats、分页筛选、详情 404、logout 复用 401）通过；浏览器四页走查由作者完成。
- **S7 回归**：不带 profile 启动行为不变、115/115 测试全绿、旧库行数不变、新库无 flyway 表。

## 关键实现决定

| # | 决定 | 原因 |
| --- | --- | --- |
| P1 | 第二数据源不注册为 Spring bean：`NewdbDbClient` 内部编程式构建 Hikari + SqlSessionFactory，mapper 经 `getMapper()` 获取，全部新 bean 挂 `@Profile("newdb")` | Boot 4 的 MyBatis/Flyway 自动装配带 `@ConditionalOnSingleCandidate(DataSource)`，多一个候选 bean 会使旧链路自动装配整体退避 |
| P2 | Flyway 零感知新库；新库结构由一次性引导 SQL 负责 | `master408` 无 `flyway_schema_history` 是隔离证据 |
| P3 | 鉴权用 Servlet Filter（`NewdbAuthFilter`），不用 HandlerInterceptor | 旧 MVC 配置继承 `WebMvcConfigurationSupport`，不委托 WebMvcConfigurer，拦截器注册会被静默忽略（执行中发现并修正）；Filter 不触碰旧文件 |
| P4 | `/api/newdb/**` 的 Spring Security 放行只写在 `application-newdb.yml`（列表整体覆盖） | 若写进 application.yml，不带 profile 时该前缀从"要求认证"变"放行后无处理器"，破坏旧环境零影响（执行中发现并修正） |
| P5 | mapper XML 放 `mapper/newdb/` 子目录 | 旧工厂的 `classpath:mapper/*.xml` glob 不跨目录，双向隔离 |
| P6 | `stem_html` 结构固定：`div.q-stem`（逐行 p）+ `div.q-options`（`p.q-option` 带 data-key，字母同时入文本） | v0 无选项列，选项在 stem_html 内；字母入文本使 stem_text 派生保留选项字母 |
| P7 | 选项切分：顺序贪婪扫描 A./B./C./D.，得 4 段非空否则拒绝 | 560 条全量实测 559 过、唯一失败即空串坏样本；负向断言会误伤 IIB./CDROM 形态 |
| P8 | 消毒与文本派生用 jsoup 1.18.3（唯一新增后端依赖） | 一个依赖两用（clean + text）；OWASP sanitizer 只能消毒。移除成本：仅导入器与 2 个测试引用 |

## 启动方式（本地）

凭据与命令见 gitignored 的 `deploy/local/newdb-admin.local.md`。
注意：admin-next dev 端口 8002 与旧 admin 相同，二者不可同时启动（9.23 既定串行）；
newdb profile 下旧库主数据源照常装配，`DB_USERNAME`/`DB_PASSWORD` 仍必填。

## 后续如何补充

第一步已交付。下一批候选（按依赖与反馈展开，未确认不排期）：题目治理操作（停用/撤下）、
2024-Q43 综合题样本（需资产方案）、消费接口定型（RAG/学习任务对接）、正式鉴权与用户域、部署环境。
每步仍附实际结果与关键失败验证，不横向建齐全库后再联调。

每轮讨论追加 [discussion](discussion.md)，已有审计和设计直接引用，不补造历史会话。
