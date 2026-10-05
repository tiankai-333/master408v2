# 发布记录（PD-08：版本可追溯）

格式：日期 | 版本(tag=git短sha) | 变更 | 镜像摘要 | 执行人 | 备注

| 日期 | 版本 | 变更 | 镜像摘要 | 执行人 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 2026-09-24 | `backend-3f58003` / `web-3f58003` | 首次公网上线：全栈（mysql/redis/backend/nginx）+ HTTPS + 默认拒绝安全策略 | backend `sha256:d0ff4e6f…` / web `sha256:60eeedd2…` | Claude（代理）经用户授权 | ⚠️ 镜像包含**未提交工作区修订**（SecurityConfigurer 默认拒绝 + deploy/ 全套产物）；待用户提交后，下一版本 tag 即严格对应提交树。首日策略：AI 未启用（假 Key + legacy 引擎）、注册关闭（A-06 默认态） |

## 回滚索引

- 任意历史 tag 可从 ACR 拉取：`docker compose` 修改 `.env` 的 `TAG` 后 `up -d backend nginx`；
- Flyway 仅前向迁移（不做自动降级），回滚镜像前先阅读对应迁移的兼容说明（PD-09，演练待执行）。
