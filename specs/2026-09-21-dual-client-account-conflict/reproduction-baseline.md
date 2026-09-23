# 复现基线记录 — 管理端与学生端账户冲突（修复前）

状态：**修复前的历史快照，不再更新**。修复已实施并通过自动化、HTTP 集成与真实浏览器验证，
修复后的结果见 validation.md 的运行记录；本文档保留修复前的原始证据，用作对照基线。

本文档记录 plan.md 第 1、2 组的实际执行证据。按 validation.md 的要求，不保存密码、Cookie 或
Token 值；会话标识在文中以“会话 A / 会话 B”指代。

## 1. 环境与版本

| 项 | 值 |
| --- | --- |
| 日期 | 2026-09-21 |
| 代码版本 | `main` @ `c2c0a84`（2026-07-28 Merge pull request #1 from tiankai-333/agent/open-source-release） |
| 后端 | `http://localhost:8003`，Spring Boot 应用，数据库 `xzs`（本地 MySQL 8、Redis、Qdrant 均在运行） |
| 学生端 | `http://localhost:8001`，Vite dev server，`/api` 代理到 `http://localhost:8003` |
| 管理端 | `http://localhost:8002`，Vite dev server，`/api` 代理到 `http://localhost:8003` |
| 测试账户 | 学生端 `student`（`t_user.role = 1` → `ROLE_STUDENT`）；管理端 `admin`（`t_user.role = 3` → `ROLE_ADMIN`）。均为仓库现有账户，未新建、未改密码 |
| 客户端 | HTTP 客户端 + 单一 Cookie 容器（等价于同一浏览器共享 Cookie 的多标签页），分别经 8001 / 8002 代理访问；另用独立容器表示不同浏览器，用 8003 直连作对照 |

## 2. 复现步骤与结果

### 2.1 顺序一：先学生端 S，再管理端 A（同一 Cookie 容器）

| 步骤 | 请求 | 结果 |
| --- | --- | --- |
| 1 | `POST localhost:8001/api/user/login`（student） | `code=1`，响应 `Set-Cookie: JSESSIONID`（会话 A） |
| 2 | `POST localhost:8001/api/student/user/current` | `code=1`，身份 `student`，`role=1` |
| 3 | `POST localhost:8002/api/user/login`（admin，同一容器） | `code=1`，**未签发新的会话 Cookie** |
| 4 | `POST localhost:8001/api/student/user/current` | **`code=502`“用户没有权限访问”** |
| 5 | `POST localhost:8002/api/admin/user/current` | `code=1`，身份 `admin`，`role=3` |

### 2.2 顺序二：先管理端 A，再学生端 S（同一 Cookie 容器）

| 步骤 | 请求 | 结果 |
| --- | --- | --- |
| 1 | `POST localhost:8002/api/user/login`（admin） | `code=1`，`Set-Cookie: JSESSIONID`（会话 B） |
| 2 | `POST localhost:8002/api/admin/user/current` | `code=1`，身份 `admin`，`role=3` |
| 3 | `POST localhost:8001/api/user/login`（student，同一容器） | `code=1`，**未签发新的会话 Cookie** |
| 4 | `POST localhost:8002/api/admin/user/current` | **`code=502`“用户没有权限访问”** |
| 5 | `POST localhost:8001/api/student/user/current` | `code=1`，身份 `student`，`role=1` |

两种顺序对称：**后登录的一端覆盖先登录的一端**，先登录端随后的业务请求被角色校验拒绝。

### 2.3 一端退出导致另一端失效

| 步骤 | 请求 | 结果 |
| --- | --- | --- |
| 1 | 学生端登录（student） | `code=1` |
| 2 | 管理端登录（admin，同一容器） | `code=1`（学生端身份已被覆盖） |
| 3 | `POST localhost:8002/api/user/logout` | `code=1`，会话失效 |
| 4 | `POST localhost:8001/api/student/user/current` | **`code=401`“用户未登录”** |
| 5 | `POST localhost:8002/api/admin/user/current` | `code=401`“用户未登录” |

`logout` 配置为 `invalidateHttpSession(true)`，失效的是两端共用的同一个 `HttpSession`。
与退出前的覆盖行为相反，这次是“一端退出让两端都掉线”。

### 2.4 remember-me 凭据同样跨端共用

管理端勾选“记住密码”登录后，丢弃会话 Cookie（模拟会话过期），只保留 remember-me 凭据：

| 凭据来源 | 请求 | 结果 |
| --- | --- | --- |
| admin（remember-me） | `POST localhost:8001/api/admin/user/current` | `code=1`，身份 `admin` |
| admin（remember-me） | `POST localhost:8001/api/student/user/current` | `code=401`“用户未登录” |
| student（remember-me） | `POST localhost:8001/api/student/user/current` | `code=1`，身份 `student` |
| student（remember-me） | `POST localhost:8002/api/admin/user/current` | `code=401`“用户未登录” |

同一浏览器内 remember-me 只能记住一个账户，两端共用同一份凭据；一端的凭据可以在另一端端口上
被后端接受，恢复出另一端的账户身份。附带事实：remember-me 身份遇到角色不匹配时返回 `401`
而不是 `502`，因为 Spring Security 把 remember-me 视为“未完全认证”，走认证入口而非拒绝处理器；
这会让前端 `request.js` 的 `401` 分支直接跳转登录页。

### 2.5 对照与排除

| 对照 | 结果 | 说明 |
| --- | --- | --- |
| 两个独立 Cookie 容器（等价不同浏览器） | 各自保持自身身份 | 只有在共享 Cookie 容器时冲突才出现 |
| 匿名请求 `:8001` / `:8002` 管理端接口 | 均 `401` | 未登录行为基线 |
| 4001/8002 端口本身 | 同一 remember-me 凭据在 `:8001` 与 `:8002` 表现一致 | 端口不是变量，端点角色要求才是 |

## 3. 身份传递过程（用本次证据解释）

1. 浏览器向 `localhost:8001`（或 `localhost:8002`）发起 `/api/...` 请求，dev server 把请求反代到
   `localhost:8003`。
2. 后端在 `RestLoginAuthenticationFilter`（匹配 `POST /api/user/login`）中认证用户名口令，
   `RestAuthenticationProvider` 按 `t_user.role` 生成 `ROLE_STUDENT` / `ROLE_ADMIN` 权限。
3. 认证结果写入当前 `HttpSession` 的 `SecurityContext`。响应中的 `JSESSIONID` 没有 `Domain`
   属性，浏览器按主机 `localhost` 保存，**端口不参与 Cookie 匹配**，因此 8001 与 8002 共享
   同一份 `JSESSIONID`。
4. 第二次登录携带的仍是第一端建立的那个 `JSESSIONID`，容器复用它，于是后端把新的
   `Authentication` 写进**同一个** `HttpSession`：Web 层看到的是同一会话，身份已被替换。
   这解释了步骤 2.1/2.2 中第二次登录没有 `Set-Cookie` 的现象。
5. 先登录端的后续请求携带同一个 `JSESSIONID`，被认证为另一端的账户，命中
   `.requestMatchers("/api/admin/**").hasRole(ADMIN)` / `("/api/student/**").hasRole(STUDENT)`
   后返回 `502`；前端 `request.js` 对 `401`/`502` 都执行 `router.push('/login')`，用户被踢回登录页。
6. `logout` 使整个 `HttpSession` 失效；`remember-me` Cookie 同样以主机为作用域，被两端共用。

## 4. 根因

会话与凭据的作用域是**主机**，而两端身份的作用域是**应用**；两者被同一套 Cookie 容器（
`JSESSIONID` 与 `remember-me`）和同一个 `HttpSession`（一个 `SecurityContext`、一个
`Authentication`）强行合一。登录入口共用 `/api/user/login` 只是表象；即使换成两个登录 URL，
只要仍复用同一个 `HttpSession` 与同一份会话 Cookie，覆盖依旧会发生。

## 5. 已排除的假设

- “两个前端端口不同，会话天然隔离”：**排除**。Cookie 按主机匹配、忽略端口，一次登录的凭据会被
  另一端请求携带（2.1 步骤 3-4）。
- “共用登录 URL 是根因，换 URL 即可”：**排除其充分性**。第二次登录未签发新会话 Cookie，说明覆盖
  发生在同一个 `HttpSession` 内部；换 URL 不改变共享会话这一事实（详见第 4 节）。
- “前端 Cookie 名不同（`studentUserName` / `adminUserName`）已经完成隔离”：**排除其充分性**。
  两者同样以 `localhost` 为主机作用域被两端共享，且决定后端身份的是 `JSESSIONID` / `remember-me`。
- “8001 与 8002 行为不同”：**排除**。同一 remember-me 凭据在两个端口上表现一致（2.5）。

## 6. 相关静态事实（本轮核对）

- 两端 `src/api/login.js` 都调用 `/api/user/login` 与 `/api/user/logout`；`src/utils/request.js` 均
  `withCredentials: true`，对 `401`/`502` 跳转登录页。
- 学生端只调用 `/api/student/**`（加登录/退出），管理端只调用 `/api/admin/**`（加登录/退出）。
- 前端未用 `localStorage`/`sessionStorage` 保存身份（仅有 `knowledge-graph` 的一个界面偏好键）。
- `SecurityConfigurer`：单一安全链；`/api/admin/**` → `hasRole(ADMIN)`，`/api/student/**` →
  `hasRole(STUDENT)`，其余 `permitAll`；`requireExplicitSave(false)`；`logout` 失效整个会话。

## 7. 未验证项与限制

- **部署环境**：仓库内只找到 `v1-reference/deploy/nginx.conf`（学生端 `/student`、管理端 `/admin`
  在同一主机的同一源上，`/api/` 反代后端）。该布局同源共享 Cookie，问题应同样存在，但 v2 的实际
  部署方式、是否使用该配置**未验证**，按 AUTH-07 记录为未验证环境。
- 本轮证据为 HTTP 集成层；浏览器真实界面表现（双端页面、刷新、退出）尚未记录。
- 跨用户数据归属越权（读取他人学习记录）尚未实测。
