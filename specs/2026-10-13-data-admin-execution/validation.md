# Validation — 新数据库与管理端落地

## 第一步验收（2026-10-20 执行，本地 Windows / MySQL 8.0.36 / JDK 21 Temurin）

| 编号 | 场景 | 判定与实际结果 | 证据 |
| --- | --- | --- | --- |
| V-01 | 新库从零初始化 | ✅ `newdb-v0.sql` 执行成功，`master408` 4 表齐全，不依赖旧库 dump；ngram FULLTEXT 建表正常 | `show tables in master408` 输出 4 表 |
| V-02 | 10 题导入 | ✅ 首次导入报告 `candidate=10 imported=10 duplicate=0 rejected=0`；SQL 抽查 id=1：stem_html 含 4 个 `data-key`、代码内 `<` 已转义、correct=A 与源 JSON 一致 | 导入日志 + SQL 抽查 |
| V-03 | 重复导入幂等 | ✅ 第二次启动 `duplicate=10 imported=0`，question 仍 10 行、import_batch 仍 1 行，note 更新为重放汇总 | run2 日志 + SQL 计数 |
| V-04 | 坏样本被拒 | ✅ 夹具 3 条（空 options / answer=E / images 非空）→ `rejected=3`，原因码 `options-unsplitable` / `answer-invalid` / `images-not-supported-in-v0`，库行数不增；夹具在 gitignored 的 `tmp/`，未入库 | badfixture 日志 |
| V-05 | 未登录/坏 token 401 | ✅ 无 token 与伪造 token 访问 `/api/newdb/stats` 均 HTTP 401 + 机读 body；logout 后旧 token 复用 401 | curl 输出 |
| V-06 | 登录失败 | ✅ 错误凭据 HTTP 401 + `code=401`；账号未配置（空环境变量）时恒 401（代码路径：credentialsMatch 空值短路） | curl 输出 |
| V-07 | 管理端完整链路 | ✅ curl 层全部通过（登录/stats/分页筛选/详情/404）；dev 服务 8002 页面与 `/api` 代理验证通过。**浏览器四页人工走查由作者执行，本条闭合以作者走查记录为准** | curl + vite 日志 |
| V-08 | 旧库前后行数不变 | ✅ `xzs.t_question`=6613 与 S1 基线一致；`master408_v2` 全 0 行不变；本次所有写路径只指向 `master408` | S1 基线 vs S7 复测 |
| V-09 | 不带 profile 行为不变 | ✅ `/api/newdb/*` → 旧安全链 `{"code":401,"message":"用户未登录"}`（与改动前一致）；旧管理端登录端点 402 形状不变；`mvn test` 115/115 全绿（含既有上下文/契约/双端登录隔离测试） | curl + mvn 输出 |
| V-10 | Flyway 未触碰新库 | ✅ `master408` 无 `flyway_schema_history`；`master408_v2` 仍 baseline+V1–V5 | SQL 查询 |
| V-11 | 口令不入 git | ✅ `deploy/local/newdb-admin.local.md` 被 `.gitignore:168` 覆盖（check-ignore 命中）；仓库内只有 `.env.example` 键名模板 | git check-ignore |
| V-12 | 前端渲染安全 | ✅ raw 经后端 jsoup 白名单消毒（仅 div/p + class/data-key）；`QuestionHtml.vue` 为 v-html 唯一入口且只接受 stemHtml/analysisHtml；sourceRef 链接带 `rel="noopener noreferrer"`；DOM 无 script/on*（消毒单测覆盖） | 单测 + 组件注释 |

**执行中发现并修正的两个问题**（详见 [plan](plan.md) P3/P4）：

1. HandlerInterceptor 在旧 `WebMvcConfigurationSupport` 配置下不生效 → 改为 Servlet Filter。
2. ignore-urls 若写入 application.yml 会改变不带 profile 时的安全行为 → 收进 application-newdb.yml。

**限制说明**：仅 10 行数据，未验证大数据量分页与 FULLTEXT 检索性能；token 内存态，后端重启即失效（前端 401 自动回登录，属预期）；license_tag='crawler-review' 的批次不得用于公网展示。

## 功能收尾原则

文档写完或任务勾完不等于功能完成。最终交付边界随讨论明确，由实际结果验收；
功能结束仍需作者自我考核，题目围绕亲自落地的内容制定，不由 AI 代答或默认通过。
本 spec 定稿提交与后续功能完成是两件事。
