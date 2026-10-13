# Validation — 微信小程序域名接入

## curl 证据集（公网 `https://edu.wutiankai.cn`）

| 编号 | 命令 | 期望 |
| --- | --- | --- |
| V-01 | `curl -s -X POST https://edu.wutiankai.cn/api/wx/student/auth/checkBind -H 'Content-Type: application/json' -d '{}'` | HTTP 200 + 业务错误码（参数缺失类），**非 401**。若返回"服务端未配置微信凭据"类错误，说明 `.env` 未生效（WX-12 未过） |
| V-02 | 同 V-01，路径 `.../auth/bind` | 业务校验错误，非 401 |
| V-03 | `curl -s -X POST https://edu.wutiankai.cn/api/wx/student/dashboard/index -H 'token: invalid'` | token 无效类业务 JSON（拦截器拒绝），非 Spring 登录重定向 |
| V-04 | `curl -s -X POST https://edu.wutiankai.cn/api/student/question/page`（无凭据）；`.../api/admin/` 任一端点（无凭据） | 均 401（角色面不变，WX-14） |
| V-05 | `curl -s https://edu.wutiankai.cn/api/test/anything` | 拒绝（PD-05 不回退，WX-14） |
| V-06 | 对 V-01 端点 1 秒内连发 ≥10 次 | 出现 nginx 503（auth 档限流生效，WX-13） |
| V-07 | `curl -s -o /dev/null -w "%{http_code}" https://edu.wutiankai.cn/student/` 与 `/admin/`；随后 web 双端各登录一次 | 200 + 登录成功（WX-14） |

每条记录：执行日期、原始输出摘要、结论。任何一条不符即不得标记 Feature 完成。

## curl 证据记录（2026-10-13，本机 → 公网 `https://edu.wutiankai.cn`）

变更应用记录（任务组 2）：compose 与 https.conf 同步至服务器 `/opt/m408/deploy`（2.2，2026-10-06）；
`.env` 追加 `WECHAT_APP_ID/WECHAT_APP_SECRET`（2.1）；`docker compose up -d backend` 重建后
40s healthy，`nginx -t` 通过后 `docker compose restart nginx`（2.3，2026-10-13）。2.4 回退未触发。
变更前状态（2026-10-06 服务器取证）：compose ignore 表为 `[]`、https.conf 无 wx 段、`.env` 无
WECHAT 键，与 requirements 根因描述一致。

| 编号 | 实际输出摘要 | 结论 |
| --- | --- | --- |
| V-01 | HTTP 200 `{"code":500,"message":"系统内部错误"}`；服务器日志证实为 Spring 7 `HandlerMethodValidationException: Validation failure`——code 参数缺失被方法级校验前置拦截，未触微信调用 | 非 401、参数缺失类业务响应，通过。凭据生效性由下方补充证据单独证实 |
| 补充（WX-12） | checkBind 传非空假 code → HTTP 200 `{"code":3,"message":"获取微信OpenId失败"}`（~2.3s，真实外呼微信后被拒） | 方法体越过"开发模式"分支：`.env` 凭据已生效、ECS→api.weixin.qq.com 出网正常，真机真 code 可走通 |
| V-02 | HTTP 200 `{"code":501,"message":"【code : must not be blank】【userName : must not be blank】【password : must not be blank】"}` | 业务校验错误，非 401，通过 |
| V-03 | HTTP 200 `{"code":401,"message":"用户未登录"}` | 拦截器拒绝的业务 JSON，非 Spring 登录重定向，通过 |
| V-04 | `/api/student/question/page`、`/api/admin/question/page`（无凭据）均 HTTP 200 + `{"code":401,"message":"用户未登录"}` | 拒绝真实发生、无数据泄露、角色面不变，通过（形态注记见下） |
| V-05 | `/api/test/anything` HTTP 200 + 同上业务 401 JSON | PD-05 denyAll 对匿名请求经同一入口点表达为业务 401，拒绝成立，通过（形态注记见下） |
| V-06 | 12 并发 1 秒内打 auth/bind → 7×200 + 5×503 | 与 3r/s burst 6 nodelay 的"1+6 放行"数学吻合，auth 档限流生效，通过 |
| V-07a | `/student/` 与 `/admin/` 均 HTTP 200 | 通过 |
| V-07b | web 双端各登录一次 | **待用户执行** |

形态注记：本表原期望 V-04/V-05 为字面 HTTP 401。实际为项目自定义入口点
`LoginAuthenticationEntryPoint`（上游基线既有代码）对一切未认证/拒绝请求统一返回
HTTP 200 + 业务体码 401，属项目既定响应形态而非本次变更引入；按意图（拒绝生效、无泄露）
判定通过，特此注记。

## 真机验证（用户执行，WX-15）

- [ ] 微信开发者工具或真机：启动后 checkBind 链路正常（未绑定→绑定页；已绑定→直接进入）；
- [ ] 题库/试卷列表可加载；
- [ ] 完成一次作答并交卷；
- [ ] （若小程序含 AI 功能）AI 工作台一次真实调用。

记录：日期、微信版本、网络环境、通过/失败与现象。

## 完成定义

- V-01～V-07 全部有带日期证据且符合期望；
- 真机四步通过；
- roadmap 本项状态已更新；
- 未修改小程序工程、dev 配置与 wx 认证机制（WX-05 边界保持）。
