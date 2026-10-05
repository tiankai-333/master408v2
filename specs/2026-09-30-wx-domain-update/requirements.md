# Requirements — 微信小程序域名接入（edu.wutiankai.cn）

状态：小 Feature——修复公网部署上小程序 API 全量不可达的配置缺陷，并完成域名接入验证。

## 目标与范围

微信小程序前端工程已完成且不在本仓库；公众平台 request 合法域名已由用户配置为
`edu.wutiankai.cn`（2026-09-28 用户确认）。本 Feature 只做服务器侧：让 `/api/wx/**` 在
[public-deploy](../2026-09-23-public-deploy/requirements.md) 产物上可达、可认证、可限流，
并留下验证证据。不修改小程序工程，不新增业务功能，不重构 wx 认证（沿用 MVC
TokenHandlerInterceptor + `t_user_token` 机制）。

## 根因（已按代码定位，非推测）

- dev 配置中 `/api/wx/**` 依赖顶层 `system.security-ignore-urls`（`application.yml`）在 Spring
  Security 层放行；
- wx 的真实认证在 MVC 拦截器层：`WebMvcConfiguration.addInterceptors` 把
  `TokenHandlerInterceptor` 挂到 `/api/wx/**`，仅排除 `system.wx.security-ignore-urls`
  （bind/checkBind/register）；
- 公网部署按 PD-05 将顶层 ignore 表清空（compose `SPRING_APPLICATION_JSON`），`/api/wx/**`
  随之落入 `anyRequest().authenticated()`。小程序请求只带 wx token 头（供拦截器消费）、不携带
  Spring Security 凭据，在 security 层即被 401，永远到不了拦截器——**当前公网部署上小程序
  API 全量不可达**。

## 决策

| 编号 | 决策或处理规则 |
| --- | --- |
| WX-01 | 用户确认（2026-09-28）：小程序已完成且仓库外、公众平台域名已改；本 Feature 只动服务器侧配置。 |
| WX-02 | prod 顶层 ignore 表唯一保留 `/api/wx/**`。依据：wx 认证由拦截器层执行，security 层放行不产生匿名面；匿名面恰为拦截器排除的 3 个端点，与 dev 一致。PD-05 默认拒绝不受影响（`/api/test/**` 仍 denyAll，admin/student 角色校验不变）。 |
| WX-03 | `WECHAT_APP_ID/SECRET` 对小程序是必需项，不再是"可选第三方"：部署机 `.env` 必填；缺失时 AuthController 显式报配置错误（既有代码，不修改）。 |
| WX-04 | 3 个匿名 wx 端点（`auth/bind`、`auth/checkBind`、`user/register`）纳入 nginx auth 限流档（3r/s burst 6），与 web 登录同档；token 保护的 wx 端点走 `/api/` 总档，不另限流。 |
| WX-05 | 不改 `application.yml` dev 配置（dev 已工作）；不改 `/api/` 总限流参数；不动小程序工程；`unBind` 需 token，不列入匿名面。 |

## 必须满足的需求

| 编号 | 要求 | 验证 |
| --- | --- | --- |
| WX-11 | 公网 `/api/wx/**` 可达且经拦截器认证；匿名面恰为 bind/checkBind/register | validation V-01～V-03 |
| WX-12 | 部署机 `.env` 含有效微信凭据；secret 缺失时接口显式报配置错误而非静默异常 | 部署记录 + V-01 观察项 |
| WX-13 | 匿名 wx 端点限流生效 | V-06 |
| WX-14 | PD-05 不回退：`/api/test/**` 拒绝、admin/student 角色面不变、web 双端登录正常 | V-04/V-05/V-07 |
| WX-15 | 真机在新域名走通 checkBind→题库→作答→交卷 | 用户真机记录 |
| WX-16 | 仓库部署产物与服务器实际配置一致 | 部署同步记录 |

## 非目标

- 小程序工程本身的任何修改（仓库外，用户维护）；
- wx 认证机制重构（JWT、多端 token 统一属 TD-001 关联，不在本轮）；
- `WeatherController` 等遗留 demo 端点的外网依赖治理（仅记录现状）；
- QINIU 图片域名问题（若真机发现，另议）。

## 学习目标

- Spring Security 过滤器链与 Spring MVC 拦截器的职责边界：为什么"security 层放行"不等于"匿名"；
- ignore-urls 这类"跳过认证"清单的治理：prod 收紧（默认拒绝）时如何避免误伤依赖它放行的子系统；
- 小程序服务端要求：request 合法域名、HTTPS、code2session 登录链路与 openid 绑定。
