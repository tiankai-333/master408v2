# Master408 v2 Backend

## 模块边界

- `backend-app`：主应用和当前改造落点。复用 v1 的领域、Mapper、Service、Controller、Prompt 与 API 契约，并已分段升级到 Java 21 / Spring Boot 4.1。
- `backend-ai`：早期最小验证骨架，只用于确认 Spring AI 2.0 工具链；不再承担重复业务开发。真实接入放在 `backend-app`。
- `backend-core`：暂不创建；先迁移后拆分，避免为模块化重写成熟业务。
- `backend-langchain4j`：M4 对比实验，当前不创建。
- `backend-mcp-server`：M7 运维 MCP Server，当前不创建。

## 当前技术基线

- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Maven 多模块工程

Spring AI 2.0 面向 Spring Boot 4.x 与 Java 21。仓库原计划中的 Spring Boot 3.3 / Java 17 / Spring AI 1.0 已在开始实施时更新，原因与权衡记录在根目录的对话和对比文档中。

## 当前状态

已完成：

- 迁入 279 个旧 Java 源文件、26 个 Mapper XML 和 4 套 Prompt，排除日志、构建产物、静态包和旧环境配置。
- 按 Boot 2.7.18 → 3.5.16 → 4.1.0 分段迁移；完成 Jakarta、Spring Security 7、Jackson 3、MyBatis 4 和默认 Servlet 容器适配。
- 创建本地 `master408_v2` 数据库；经隔离重放和 Mapper 契约审计，确认业务基线为 43 张表。后续 Flyway V2–V5 增加 PromptOps、固定评测、可观测字段和 RAG 全文索引；数据库契约测试会同时拦截缺表、多表和关键字段漂移。
- `backend-app` 已接入 Spring AI 2.0，通过 `AiAnalysisClient` 建立供应商无关边界；`AiAnalysisGateway` 已接管控制器的后端默认模型调用。默认仍走 `legacy`，通过 `AI_ENGINE=spring` 才启用新实现。
- 测试覆盖完整 Web 应用上下文、数据库结构、Spring AI 同步/流式 Mock 委托、
  Legacy 回退、固定评测、真实 Usage、反馈归属、稳定性策略和混合 RAG；多数测试
  不调用真实模型。
- 同步与 SSE 流式调用均已进入 `AiAnalysisGateway`；学生端通过安全的运行时接口和消息标签显示当前引擎。
- 本地网页联调发现并修复 Security 7 登录后会话未持久化问题；旧 `test` 账号可登录新版后端并读取 `xzs` 的学习画像和 122 条知识目录。

Java 21 安装在 `C:\Users\wutia\.jdks\jdk-21.0.11+10`，未覆盖系统默认 Java 17。构建 `backend-ai` 前需让当前终端的 `JAVA_HOME` 指向该目录。

当前已完成固定评测、Redis Memory、混合 RAG、Tool Calling、PromptOps 第一阶段、
统一可观测以及限流/重试/熔断/供应商降级。实施证据见根目录
`docs/AI工程演进记录.md`。

任何 API key 都必须通过环境变量或本地忽略配置提供，禁止写入仓库。
