# Plan — 微信小程序域名接入

## 任务组 1：仓库侧配置（已具备全部事实，可立即执行）

- [ ] 1.1 `deploy/docker-compose.yml`：`SPRING_APPLICATION_JSON` 的 ignore 表保留
      `"/api/wx/**"`，注释写明 WX-02 依据与 PD-05 的关系；
- [ ] 1.2 `deploy/nginx/https.conf`：`auth/bind`、`auth/checkBind`、`user/register` 三个匿名
      端点加入 auth 限流档（WX-04）；
- [ ] 1.3 `deploy/.env.example`：`WECHAT_APP_ID/SECRET` 移出"可选第三方"分组，标注必需与
      缺失行为（WX-03）；
- [ ] 1.4 `deploy/README.md`：新增小程序接入节（env 必填项、同步方式、reload、验证指引）。

## 任务组 2：服务器侧执行（部署机操作，需用户或授权会话）

- [ ] 2.1 服务器 `.env` 填写 `WECHAT_APP_ID` / `WECHAT_APP_SECRET`；
- [ ] 2.2 同步 `docker-compose.yml` 与 `nginx/https.conf` 到服务器 `deploy/` 目录；
- [ ] 2.3 应用变更：`docker compose up -d backend`（env 变更需重建容器）；
      nginx 先 `docker compose exec nginx nginx -t` 校验，再 `docker compose restart nginx`；
- [ ] 2.4 失败回退：将 `SPRING_APPLICATION_JSON` 还原为清空态并重建 backend（回退只影响小程序
      可达性，不影响 web 双端，属低风险回退）。

## 任务组 3：验证与收尾

- [ ] 3.1 按 validation.md 执行 V-01～V-07 curl 证据（记录日期与原始输出摘要）；
- [ ] 3.2 用户真机验证（WX-15 四步），结果记录进 validation.md；
- [ ] 3.3 roadmap 本项状态更新；修订记录进 git 提交信息（spec 纪律）。

## 执行顺序与依赖

1 → 2 → 3 严格串行；2.3 依赖 2.1/2.2；真机验证（3.2）必须在 2.3 之后。
仓库侧任务组 1 不依赖任何服务器信息，可先行完成并审查。
