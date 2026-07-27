# Master408 AI 学习工作台

> 面向 408 计算机考研场景的 AI 学习与刷题系统。项目在既有题库、考试、学习记录和管理后台基础上进行增量升级，重点探索 Spring AI 在真实 Java 业务中的工程化落地，而非重新实现一套聊天 Demo。

## 项目简介

Master408 原有系统已经具备题库、试卷、错题、知识点和用户学习记录等完整业务能力。本项目大量复用这些成熟资产，并将后端升级至 Java 21、Spring Boot 4.1 和 Spring AI 2.0，在不破坏原有业务的前提下逐步加入：

- 供应商无关的模型调用边界；
- 同步与 SSE 流式回答；
- 按用户和会话隔离的 Redis 对话记忆；
- 面向 408 知识库的 RAG 检索；
- 受控的 Tool Calling；
- 可测试、审批、灰度和回滚的 PromptOps；
- 管理端模型配置、用量统计和 Prompt Studio。

项目关注的不只是“模型能否回答”，还包括兼容迁移、数据契约、故障回退、密钥安全、运行时治理以及后续扩展成本。

## 核心能力

### AI 学习工作台

- 支持题目粘贴、知识目录选择和学习上下文组合；
- 支持同步回答与 SSE 流式输出；
- 提供苏格拉底式教学 Prompt，引导用户逐步思考；
- 支持运行时引擎标识和会话记忆清理；
- 根据用户、业务场景和会话生成隔离的 Memory ID，防止上下文串话。

### 模型接入与兼容迁移

- 使用 Spring AI `ChatModel` 统一基础模型能力；
- 通过自定义 `AiAnalysisClient` 隔离上层业务与具体 AI 框架；
- 使用 `AiAnalysisGateway` 统一路由 Legacy 与 Spring AI 实现；
- 保留 Legacy 回退路径，可通过配置切换引擎，降低一次性迁移风险；
- 支持从环境变量或数据库加载模型供应商配置，密钥不返回前端。

### RAG 与工具调用

- 使用 Spring AI VectorStore 抽象对接 Qdrant；
- 支持知识内容索引和相似性检索；
- RAG 不可用时保留非向量链路，避免阻断核心问答；
- Tool Calling 采用“规划—确认—执行”模式，写操作不由模型直接触发；
- 当前工具覆盖学习画像、知识点检索及受控组卷等业务场景。

### PromptOps 控制面

- Prompt 定义与版本分离；
- 草稿、提交、审批、灰度、全量发布和回滚；
- 发布前变量校验与测试；
- 基于稳定哈希的用户灰度；
- Kill Switch 和代码兜底 Prompt；
- 管理端提供版本 Diff、测试和发布操作入口。

## 系统架构

```mermaid
flowchart LR
    U["学生端<br/>Vue 3"] --> API["Spring Boot API"]
    A["管理端<br/>Vue 3"] --> API

    API --> G["AiAnalysisGateway"]
    G --> L["Legacy AI"]
    G --> C["AiAnalysisClient"]
    C --> M["Spring AI ChatModel"]

    API --> MEM["Redis Chat Memory"]
    API --> RAG["RAG Service"]
    RAG --> V["Qdrant"]
    API --> TOOL["受控 Tool Calling"]

    A --> OPS["PromptOps 控制面"]
    OPS --> DB["MySQL"]
    DB --> API
```

## 技术栈

| 分层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1、Spring AI 2.0 |
| 数据访问 | MyBatis、MySQL、Flyway |
| AI 基础设施 | ChatModel、VectorStore、Tool Calling、SSE |
| 状态与检索 | Redis、Qdrant |
| 前端 | Vue 3、Vite、Element Plus、Pinia、ECharts |
| 测试 | JUnit 5、Spring Boot Test、Mock ChatModel |

## 工程化改造

本项目没有采用“大版本直接重写”的方式，而是按兼容边界分段迁移：

1. 复用原系统领域模型、Mapper、Service、Controller、Prompt 和前端 API 契约；
2. 按 Spring Boot 2.7 → 3.5 → 4.1 完成 Jakarta、Security、Jackson、MyBatis 等兼容升级；
3. 使用 Flyway 固化数据库结构，并通过数据库契约测试同时检查缺表、多表和关键字段；
4. 在控制器与模型实现之间加入 Gateway 和 Client 边界；
5. 通过配置开关、小流量灰度和 Legacy 回退逐步迁移 AI 链路；
6. 使用 Mock 模型完成隔离测试，避免单元测试依赖外部大模型的费用和稳定性。

更多运行与模块说明见：

- [后端说明](apps/backend/README.md)
- [前端说明](apps/frontend/README.md)

## 目录结构

```text
master408v2/
├─ apps/
│  ├─ backend/
│  │  └─ backend-app/       # 主应用、AI 能力与数据库迁移
│  └─ frontend/
│     ├─ student/           # 学生端，默认端口 8001
│     └─ admin/             # 管理端，默认端口 8002
├─ README.md
└─ .gitignore
```

## 本地运行

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+
- MySQL 8
- Redis
- Qdrant（仅向量 RAG 需要）
- 一个兼容 OpenAI 协议的模型服务（启用 Spring AI 实际调用时需要）

### 1. 配置本地环境

敏感配置请通过系统环境变量或本地 `.env` 提供，不要写入仓库。

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/master408_v2?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='<your-password>'

$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='6379'

# 默认使用 Legacy 链路；启用 Spring AI 时再设置以下变量
$env:AI_ENGINE='spring'
$env:AI_CHAT_API_KEY='<your-api-key>'
$env:AI_CHAT_BASE_URL='<openai-compatible-base-url>'
$env:AI_CHAT_MODEL='<model-name>'
```

完整数据库结构由 Flyway 管理，首次启动时会执行：

- `V1__baseline.sql`
- `V2__prompt_ops_control_plane.sql`

Flyway 文件只负责结构和 PromptOps 必要配置，不包含原题库。需要最小演示数据时，
可在 Flyway 完成后手动导入原创示例：

```powershell
mysql -u root -p master408_v2 --execute="source apps/backend/backend-app/src/main/resources/db/demo/01_demo_seed.sql"
```

### 2. 启动后端

```powershell
mvn -f apps/backend/backend-app/pom.xml spring-boot:run
```

后端默认地址：`http://127.0.0.1:8003`

### 3. 启动学生端

```powershell
cd apps/frontend/student
npm ci
npm run serve
```

学生端默认地址：`http://127.0.0.1:8001`

### 4. 启动管理端

```powershell
cd apps/frontend/admin
npm ci
npm run dev
```

管理端默认地址：`http://127.0.0.1:8002`

## 测试

后端测试覆盖应用上下文、数据库结构契约、模型路由、同步与流式委托、Prompt 解析、会话隔离、RAG 配置、Tool Calling 和 PromptOps 状态流转。

```powershell
mvn -f apps/backend/backend-app/pom.xml test
```

多数 AI 测试使用 Mock ChatModel，不会调用真实模型。数据库契约测试和 Redis 集成测试需要相应的本地基础设施。

## 安全说明

- API Key、数据库口令、Redis 口令和主密钥只能来自环境变量或本地忽略配置；
- 管理端接口需要管理员权限；
- 用户模型密钥加密存储，接口只返回脱敏信息；
- Prompt 草稿不能直接进入生产流量；
- 模型生成的工具参数必须经过后端权限和参数校验；
- 仓库不应包含真实用户数据、个人简历、聊天记录或内部培训资料。
- 原题库、解析、爬虫知识页和图片不随开源仓库分发；仓库仅提供原创 Demo Seed。

如果密钥曾经进入 Git 历史，仅删除文件或补充 `.gitignore` 并不能消除泄露风险，应先轮换密钥并清理历史。

## 当前边界与后续计划

已经完成的主链路包括 Spring AI 接入、Legacy 回退、流式响应、Redis 会话记忆、基础 RAG、受控工具调用和 PromptOps 第一阶段。

后续重点：

- 建立固定评测集，补齐 Prompt 质量、Token 成本和端到端延迟对比；
- 增加限流、重试、熔断、供应商降级和 429 调度；
- 完善 RAG 混合检索、重排序与引用溯源；
- 建立模型调用、Prompt 版本和用户反馈的统一可观测链路；
- 处理前端历史依赖的安全升级与回归测试。

## 项目来源与许可

本项目基于既有开源考试系统进行学习和二次开发，复用了其部分业务代码与前端结构。公开部署或二次分发前，请同时遵守上游项目许可及相关依赖的许可证要求。

项目仍在持续整理中。题库、解析、图片、字体及其他第三方资源只有在确认拥有公开授权后才应进入公开仓库。
