# Master408 v2 Frontend

本目录直接复用 v1 的 Vue 3 前端：

- `student`：学生端，开发端口 `8001`
- `admin`：管理端，开发端口 `8002`
- 两端 `/api` 均代理到新版后端 `http://localhost:8003`

未迁入旧 `node_modules`、`dist`、压缩依赖包和运行日志。公开仓库也不分发
`student/public` 下的题目 HTML、解析、知识页、题图和爬虫资产；这些目录可能
包含未获得再分发授权的业务数据。本地已有数据仍可继续使用，开源环境可导入
后端提供的原创最小 Demo Seed 验证主链路。

## 本地运行

先启动后端：

```powershell
$env:JAVA_HOME='C:\Users\wutia\.jdks\jdk-21.0.11+10'
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/xzs?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='<local password>'
mvn -f apps/backend/backend-app/pom.xml spring-boot:run
```

再分别启动前端：

```powershell
cd apps/frontend/student
npm ci
npm run serve

cd apps/frontend/admin
npm ci
npm run dev
```

访问：

- 学生端：`http://127.0.0.1:8001`
- 管理端：`http://127.0.0.1:8002`
- 后端：`http://127.0.0.1:8003`

## AI 引擎标识

学生端 AI 工作台顶部显示当前引擎：

- `Legacy AI`：默认安全回退路径
- `Spring AI 2.0`：服务端配置 `AI_ENGINE=spring` 且启用模型后

同步响应和 SSE 流式响应也返回 `engine` 标识。标识不包含 API Key、模型密钥或
数据库凭据。

## 已知依赖债务

2026-07-24 执行 `npm ci` 后：

- 学生端审计报告 6 个漏洞（1 moderate、5 high）
- 管理端审计报告 35 个漏洞（含 4 critical）

当前只记录，不执行 `npm audit fix --force`，避免在本轮 Spring AI 迁移中混入
破坏性前端依赖升级。应安排独立升级与回归任务。
