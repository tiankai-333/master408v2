# Requirements — 公网部署（单机 Docker Compose）

状态：规格初稿；范围与关键决策已经用户确认，待用户审查本稿后进入实施。
本 Feature 对应 [roadmap Phase 8](../roadmap.md#phase-8部署权限与公开证明材料) 的第一轮拆分。

## 目标与已确认边界

用户已确认三项决策：目标形态为**单台云服务器 + Docker Compose**；范围覆盖
**部署产物与发布流程、公网安全加固、公开证明材料**三部分；用户**已有云服务器和域名**。

目标：v2 系统（后端、学生端、管理端及依赖服务）能从干净环境按文档部署到公网并安全运行；
升级与回滚演练成功；公开仓库不含敏感或无授权数据；部署证据可追溯到具体版本。
本 Feature 不重建业务代码；发现业务缺陷只记录，不在本 Feature 内顺手修复。

- 用户明确排除"数据与备份"部分：备份恢复体系与可公开 Demo Seed 制作不在本轮范围，
  另立规格。升级回滚所需的单个恢复点（快照或 dump）属于回滚流程本身，不属于备份体系。
- 单机形态是真实边界不是缺陷：不拆微服务、不上多实例与灰度，这些触发条件见 roadmap 条件式扩展。

## 现状事实（部署基线）

以下为已核对的事实，实施以它们为起点：

- 后端配置已全量环境变量化（[application.yml](../../apps/backend/backend-app/src/main/resources/application.yml)）：
  `DB_*`、`REDIS_*`、`QDRANT_*`、`AI_*`、`AUTH_RSA_*` 均来自环境变量；默认端口 8003，
  `SPRING_PROFILES_ACTIVE` 默认 `local`。
- v2 运行依赖 MySQL 8（业务事实）、Redis（对话记忆）、Qdrant（向量投影，可关闭并回退词法路径）、
  两个 Vite 前端（生产 base path 分别为 `/student/` 与 `/admin/`）。
- [v1-reference/deploy/](../../v1-reference/deploy/) 提供可参照的 compose/nginx/Let's Encrypt 结构，
  但含三个不得复制的反模式：数据库密码明文入库、无 Redis 服务、指向旧域名 `mastertooling.shop`。
- 安全隐患事实：`system.security-ignore-urls` 含 `/api/test/**`、`/api/admin/upload/configAndUpload`、
  `/api/admin/upload/auth`、`/api/student/user/register`；本地开发可接受，公网暴露前必须逐项审计。
- Actuator/Micrometer 已引入依赖但未做暴露配置；健康检查端点现状未验证。
- 目标服务器（本地运维记录 [server-inventory.local.md](../../deploy/local/server-inventory.local.md)，
  已由 `.gitignore` 排除）：阿里云 ECS `ecs.e-c1m1.large`，**2 vCPU / 2 GiB 内存 / 40 GiB 盘 /
  3 Mbps 固定带宽**，Alibaba Cloud Linux 3，公网 IP `8.130.53.51`，包年包月至 2027-05-25。
- 2026-09-23 已用 root+密码 SSH 登录验证成功；登录横幅显示 904 次失败登录尝试与 46 条待更新安全公告。
- 域名 `edu.wutiankai.cn` 已由用户解析至 `8.130.53.51`（用户 2026-09-23 确认，未独立复核 DNS 生效）。
- 2 GiB 内存对 Java + MySQL + Redis + Qdrant + nginx 同机运行是紧约束，内存预算是本 Feature 的一级需求。

### 环境约束（部署前提，2026-09-23/24 实测；部署类规格的必要章节）

| 项 | 事实 | 来源 |
| --- | --- | --- |
| 目标机 | 阿里云 ECS，2 vCPU / 1.8 GiB 可见内存 / 40 GiB 盘 / 3 Mbps 固定带宽，Alibaba Cloud Linux 3，地域 `cn-wulanchabu` | 实例元数据 + 只读盘点 |
| 网络性质 | 本机与服务器均为**纯国内环境，不使用代理**（用户硬性要求） | 用户决定 |
| Docker Hub | 本机与 ECS 直连 `registry-1.docker.io` 均失败（connection refused / timeout） | 双端实测 |
| 阿里云专属加速器 | `yi1rba6m.mirror.aliyuncs.com`；仅对阿里云产品生效（本机 403），且白名单只含部分热门镜像——nginx 可拉，redis / eclipse-temurin / mysql 均 404 后回退失败 | ECS 实测 |
| 可用国内渠道 | ① 自有 ACR（VPC 内网，业务镜像中转，单仓库）；② mindskip 公共 ACR 仓库（`mysql:8.0.33`，v1 验证可用）；③ `mirrors.aliyun.com/alpine`（rootfs 与 apk 包，用于自研基础镜像） | 实测/设计 |
| 服务器 Docker | 2026-09-24 已安装 docker-ce 26.1.3（aliyun docker-ce repo）；daemon.json 仅配专属加速器 | 执行记录 |
| 本机 Docker | Docker Desktop（WSL2 引擎）；修改 daemon.json 后必须整体重启引擎（含 docker-desktop WSL 发行版）才生效，只重启 UI 进程无效 | 执行记录 |
| 数据库初始化前提 | `V1__baseline.sql` 非自足（空库执行 1146 失败：假设 legacy 表先行存在，开发库均为老库故未暴露）；干净环境部署必须先经 initdb 导入遗留全量 SQL（`deploy/sql-init/`，副本源于 `master408/database/current`）。迁移脚本自足性问题已留档，归属 9.20/Phase-2 评估；本 Feature 不改写已提交迁移 | 本地演练实测（2026-09-24） |

由此确定的镜像供应链（PD-16 执行方案，任何环节不得引入 Docker Hub 直连或代理）：

- 基础镜像**自建**：`m408base:alpine3.20` / `m408base:jre21` / `m408base:redis7`，脚本
  [build-base-images.sh](../../deploy/build-base-images.sh)，原料全部来自 mirrors.aliyun.com/alpine；
- nginx 基座用本机既有 `nginx:1.25-alpine`；MySQL 用 mindskip 公共 ACR 镜像 `8.0.33`；
  qdrant 用本机既有 `v1.9.7`（向量期再上服务器）；
- 业务镜像走自有 ACR **单仓库** `wutiankai/master408`，tag 前缀 `backend-<sha>` / `web-<sha>`
  （用户定位：ACR 仅为过网速的中转，修改与备份都在本地）。

## 必须满足的需求

| 编号 | 要求 | 验收证据 |
| --- | --- | --- |
| PD-01 | 部署产物完整可复现：后端镜像、前端构建、compose、nginx、`.env.example` 与部署文档齐备；干净环境按文档可完成部署 | 从零到公网可访问的逐步执行记录 |
| PD-02 | 内存预算先行：每个服务的内存上限有明确分配表并实测 RSS；总量不超过 2 GiB 物理内存加受控 swap；超预算的裁剪决策（如首期关闭 Qdrant）显式记录理由与启用开关 | 内存分配表与实测数据，关闭/开启 Qdrant 两种形态均可解释 |
| PD-03 | 密钥与配置管理：生产密钥只存在于服务器环境文件，不入仓库、不入日志、不进镜像层；`.env.example` 只含键名与占位 | 扫仓与扫日志证据；镜像历史检查 |
| PD-04 | 传输安全：HTTPS 全站生效，HTTP 301 跳转，TLS ≥1.2；证书签发与续期方式明确且有到期提醒手段 | 证书链检查与续期演练或续期机制说明 |
| PD-05 | 认证面收敛：`security-ignore-urls` 逐项给出公网处置（保留/收紧/禁用）；`/api/test/**` 类调试入口生产禁用；管理端公网暴露策略按 A-04 结论执行 | 逐项处置表与未认证访问实测 |
| PD-06 | 最小暴露面：公网仅开放 80/443；MySQL、Redis、Qdrant、Actuator 仅内网或受保护；服务器登录加固（SSH 密钥、更换已泄露口令、处理安全更新、失败登录防护） | 端口扫描自查与安全组截图/导出 |
| PD-07 | 健康检查与降级可观测：编排使用真实健康检查；AI、Redis、Qdrant 部分不可用时行为符合 tech-stack 可靠性约定，公网用户可见明确的失败或降级而非挂起 | 逐组件故障注入记录 |
| PD-08 | 版本标识：从 Git 提交到镜像 tag 到运行环境可追溯；发布记录含日期、版本、变更与执行人 | 一次真实发布记录样例 |
| PD-09 | 升级与回滚：Flyway 前向迁移兼容；升级前有恢复点；升级与回滚各演练一次且数据不丢 | 演练记录，含失败回退路径 |
| PD-10 | 滥用防护基线：公网登录、注册与 AI 入口有限流或预算上限，AI 成本暴露面有明确阈值；不要求分布式限流 | 限流配置与触发实测 |
| PD-11 | 日志卫生：密钥、密码、Token 不落日志；日志有轮转与大小上限 | 日志抽查与轮转配置 |
| PD-12 | 公开仓库安全：仓库（含历史）无生产密钥、真实用户数据、无授权题库；本地运维记录确认被 ignore | 扫描证据与 `git check-ignore` 记录 |
| PD-13 | 公开证明材料：部署文档、架构图、带日期环境的运行证据整理成对外可展示材料；演示指标可追溯到版本 | 文档与证据链接清单 |
| PD-14 | 现状一致：文档区分已部署、已验证、未完成；不以本地演练冒充公网验证 | validation 逐项核对 |
| PD-15 | 自我考核：作者独立解释部署链路、安全决策与故障行为 | validation 自我考核记录 |
| PD-16 | **镜像供应链不依赖 Docker Hub 直连、不使用代理（用户硬性要求，纯国内环境）**：实际执行路径仅限三条国内链路——自有 ACR 单仓库（业务镜像，tag 前缀 `backend-<sha>`/`web-<sha>`）、mindskip 公共 ACR（`mysql:8.0.33`）、`mirrors.aliyun.com/alpine`（自建 `m408base` 基础镜像）；加速器仅作为白名单命中时（如 nginx）的可选加速，不作为依赖 | 在本机与服务器各验证一次：构建/部署日志只出现上述三条链路的端点，无 docker.io 直连请求 |

## 待确认决策

代理提供推荐与代价，只向用户询问影响业务与费用的选择；实施前逐项确认。

- A-01 服务器现状与共存关系：**用户 2026-09-23 已确认——不动旧部署，在 `8.130.53.51` 上独立部署 v2。**
  该机现有应用、端口、数据仍先只读盘点；发现与 v2 端口或资源冲突时回报用户，不自行清理或覆盖。
- A-05 证书与备案：**用户 2026-09-23 确认 ICP 备案已完成**，80/443 可用；证书签发方式
  （certbot 容器 / 宿主机 acme.sh）在实施 HTTPS 时再定，HTTP-01 验证路径已可用。
- A-02 内存分配方案：推荐首期关闭 Qdrant（tech-stack 明确词法回退路径合法，Qdrant 属可重建投影）、
  配置受控 swap、保留 `AI_RAG_VECTOR_ENABLED` 开关；运行稳定后再评估升配或启用向量。
  备选是全栈极限压缩同跑，OOM 风险由用户承担。
- A-03 镜像构建与分发：**已定稿（2026-09-23，按用户决定改为单仓库方案）**——本机构建 → push ACR 个人版
  （乌兰察布，命名空间 `wutiankai`，**单仓库 `master408`**，私有 + 本地仓库；用户定位：ACR 仅为过网速的
  中转，修改与备份在本地）→ 服务器经 VPC 端点内网拉取。镜像区分用 tag 前缀：
  `master408:backend-<git短sha>`、`master408:web-<git短sha>`。已建的 `master408-web` 仓库废弃不用
  （是否删除由用户决定）。端点：本机推送用
  `crpi-z118pytqkuhi1rn1.cn-wulanchabu.personal.cr.aliyuncs.com`；服务器拉取用
  `crpi-z118pytqkuhi1rn1-vpc.cn-wulanchabu.personal.cr.aliyuncs.com`（2026-09-23 服务器实测：
  解析内网 IP 100.100.0.57、`/v2/` 返回 401，不占 3 Mbps 公网带宽）。
  基础镜像（JRE/nginx/mysql/redis/qdrant）不进 ACR：本机与服务器 daemon.json 配阿里云专属加速器拉取。
  生产禁用 `:latest`；镜像不携带密钥；服务器 `docker login` 经 `--password-stdin` 注入，
  Registry 密码只存在于服务器 docker 凭据文件，不入仓库与文档。
- A-04 管理端公网暴露：推荐 HTTPS + 强口令 + 限流公网可达；IP 白名单或隐藏前缀作为可选加固，
  不以后端授权已完备为由省略基线防护。
- A-06 公网注册开放：`/api/student/user/register` 公开即接受垃圾注册与 AI 成本滥用风险；
  Demo 期是否开放注册、是否改为邀请/关闭，由用户决定。
- A-07 产物与文档位置：**已定稿（2026-09-23，消除双源）**——部署相关内容统一在仓库根 `deploy/`：
  产物与编排在 `deploy/` 根，部署文档与使用说明为 `deploy/README.md`（第 5 组扩充），
  本地运维记录（含基础设施地址、凭据相关事实）只进 `deploy/local/*.local.md`（`.gitignore` 排除）。
  原 `docs/deployment/` 目录已退役并迁入 `deploy/local/`。

## 非目标

- 备份恢复体系、定时备份、恢复演练与可公开 Demo Seed 制作（用户明确排除，另立规格）；
- 多实例、灰度发布、分布式限流/熔断、K8s、微服务拆分（触发条件见 roadmap）；
- CDN、对象存储迁移、静态资源优化专项（3 Mbps 带宽下只记录事实与 gzip 基线，不做优化工程）；
- 性能压测与容量调优专项（只实测并记录资源占用，不承诺并发指标）；
- 微信小程序端部署与回调域名配置；
- 旧 `mastertooling.shop` v1 部署的数据迁移（若用户需要，另立 Feature）；
- 新业务功能、PromptOps/RAG 能力变化；本 Feature 只部署已有系统。

## 学习目标

本 Feature 服务于求职证据，完成后作者应能回答：

1. 从一次 Git 提交到公网可访问，中间经过哪些产物、检查和决策？哪个环节最容易出事故？
2. 生产密钥为什么不入仓库？实际如何注入、轮换？如果已经泄露（如本次口令已在聊天中出现），处置顺序是什么？
3. 2 GiB 内存如何分配？为什么 Qdrant 可以首期砍掉而 MySQL 不行？（用派生投影与业务事实的所有权解释）
4. 如何证明线上运行的是哪个版本？升级失败时如何回滚且不丢已发生的数据？
5. `security-ignore-urls` 里的免认证入口，为什么本地开发无害、公网致命？逐项怎么处置？
6. 904 次失败登录尝试说明什么？应做哪些处置、哪些不该据此推断？
