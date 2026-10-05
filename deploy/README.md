# deploy/ — 公网部署产物（specs/2026-09-23-public-deploy）

单机 Docker Compose 部署。镜像供应链（PD-16，2026-09-24 定案）：**不依赖 Docker Hub 直连、
不使用代理（纯国内环境）**，只走三条国内链路——自有 ACR 单仓库、mindskip 公共 ACR（mysql）、
mirrors.aliyun.com/alpine（自建基座）。阿里云加速器仅白名单命中时（如 nginx）可选加速。

## 文件清单

| 文件 | 用途 |
| --- | --- |
| `build-base-images.sh` | 自建基础镜像 `m408base:{alpine3.20,jre21,redis7}`（本机与服务器各跑一次） |
| `Dockerfile.backend` | 后端运行时镜像（jar 本机构建；基座 m408base:jre21；非 root；无密钥） |
| `Dockerfile.web` | 静态站点镜像（基座 nginx:1.25-alpine；内置 HTTP 引导配置） |
| `docker-compose.yml` | 生产编排：mysql / redis / backend / nginx（qdrant 按 `--profile vector` 启用） |
| `docker-compose.local.yml` | 本地演练覆盖（nginx 映射 18080、关 restart） |
| `nginx/http.conf` `nginx/https.conf` `nginx/proxy-common.inc` | 反代与静态配置（限流、gzip、SSE 透传；HTTPS 证书就绪后切换 https.conf） |
| `mysql/small.cnf` | 2 GiB 内存收敛参数（PD-02） |
| `.env.example` | 环境变量模板（真实 `.env` 只存在于部署机，不入 Git） |
| `build-and-push.sh` | 本机：构建 jar/dist → 打镜像 → 推 ACR 单仓库 |
| `sql-init/` | 遗留全量 DDL+种子（gitignored 副本；**单一事实源 = master408/database/current**，部署前复制） |
| `MEMORY-BUDGET.md` | 内存预算与实测（PD-02） |
| `local/` | 本地运维记录（gitignored：服务器/ACR/加速器事实） |
| `mvp/` | 第 0 组连通性探针（已完成使命） |

## 镜像命名（A-03 单仓库方案）

```text
crpi-z118pytqkuhi1rn1.cn-wulanchabu.personal.cr.aliyuncs.com/wutiankai/master408:backend-<git短sha>
crpi-z118pytqkuhi1rn1-vpc.cn-wulanchabu.personal.cr.aliyuncs.com/wutiankai/master408:backend-<git短sha>  # 服务器用 VPC
                                                              .../master408:web-<git短sha>
```

发布 tag = git 短 SHA（PD-08）；生产禁用 latest。演练构建用 `rehearsal-N` 标记，不冒充发布版本。

## 本机构建流程

```bash
bash deploy/build-base-images.sh     # 一次性：自建基座三件套
bash deploy/build-and-push.sh        # jar+dist → 镜像 → 推 ACR（TAG=git 短 SHA）
docker login crpi-z118pytqkuhi1rn1.cn-wulanchabu.personal.cr.aliyuncs.com   # 用户名 nick4576420725
cp "c:/Dev/Workspaces/master408/database/current/"*.sql deploy/sql-init/     # 刷新 DB 前置副本
```

## 服务器部署流程（概要；完整手册在第 5 组产出）

```bash
# 0) 前置：docker 已装（2026-09-24 docker-ce 26.1.3）；创建 2G swap；scp deploy/ 上服务器
# 1) 服务器跑一次基座脚本（拉取 mysql:8.0.33 走 mindskip ACR；验证见 plan 第 2 组待办）
bash deploy/build-base-images.sh
# 2) 配置
cp .env.example .env   # 真实值：三个随机密码、AI_SECRET_MASTER_KEY（须匹配库中加密 Key，见下）、TAG=git短sha
# 3) 登录拉取（VPC 端点，不占 3 Mbps 公网带宽）
docker login crpi-z118pytqkuhi1rn1-vpc.cn-wulanchabu.personal.cr.aliyuncs.com   # --password-stdin
docker compose pull && systemctl disable --now nginx   # 释放 80（MVP 探针遗留）
docker compose up -d
# 向量形态（首期默认关闭，A-02）：--profile vector + AI_RAG_VECTOR_ENABLED=true
# 本地演练：-f docker-compose.yml -f docker-compose.local.yml up -d → http://localhost:18080/student/
```

## 微信小程序接入（specs/2026-09-30-wx-domain-update）

- 前置（用户维护）：微信公众平台 request 合法域名已配置 `edu.wutiankai.cn`；小程序工程在仓库外。
- `.env` 必填 `WECHAT_APP_ID` / `WECHAT_APP_SECRET`（缺失时 wx auth 接口显式报配置错误）。
- prod security ignore 表唯一保留 `/api/wx/**`（compose `SPRING_APPLICATION_JSON`）：wx 认证在
  MVC `TokenHandlerInterceptor` 层执行，security 层放行≠匿名；匿名面仅 bind/checkBind/register
  三个端点（nginx auth 限流档）。
- 变更应用：`docker compose up -d backend`；nginx 先 `docker compose exec nginx nginx -t`
  校验再 `docker compose restart nginx`。
- 验证命令与完成定义见该 Feature 的 `validation.md`。

## 已知边界与部署日待决（记录事实，不冒充已验证）

- **AI 引擎与真实 Key 是部署日决策**：库中 `ai_provider_config` 的 Key 由开发机 master-key 加密；
  服务器若导入同一份 DB（sql-init 含加密行），`.env` 的 `AI_SECRET_MASTER_KEY` 必须用原值，
  且建议 `AI_ENGINE=spring` 走库配置；演练用 legacy + 假 Key 仅验证了"未登录 401 不挂起"，
  **AI 真实调用尚未验证**。
- **AI 真实调用、公网 HTTPS、限流实测、扫仓** 等属第 3/4 组，未开始。
- 题目图片的上传与静态服务路径尚未核对（v1 有 `/images/` 别名，v2 待演练确认）。
- `JAVA_OPTS` 内存百分比为预算值；本机实测见 MEMORY-BUDGET.md，服务器复测后回填。
- 健康检查当前接受任意 HTTP 响应（含 401）；`/actuator/health` 的认证放行在 PD-05/07 处理。
- 服务器 `docker login` 凭据落在 `~/.docker/config.json`（base64 非加密）——用 `--password-stdin`。
- MVP 探针的 dnf nginx 在正式部署时须 `systemctl disable --now` 释放 80 端口。
