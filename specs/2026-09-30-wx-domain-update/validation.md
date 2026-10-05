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
