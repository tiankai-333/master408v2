# Validation — PromptOps 控制面

> 历史恢复：结果来自现有测试、提交和项目文档；未重新运行的历史结果不表述为本次验证。

## 自动化验证

- `DbPromptRegistryTest`：数据库版本解析、灰度和兜底行为；
- `PromptOpsServiceImplTest`：状态转换、审批、发布、回滚与 Kill Switch；
- `AnalysisServiceResolvedPromptTest`：业务调用使用解析后的 Prompt；
- `AiAnalysisGatewayTest`：Gateway 继续保持兼容路由；
- 数据库结构契约包含 Flyway V2 新增结构。

## 人工验证

- 管理端能够查看定义和版本差异；
- 草稿不能直接服务生产请求；
- 灰度用户分配在相同条件下保持稳定；
- 回滚后新请求使用目标历史版本；
- Kill Switch 启用时回到安全兜底；
- API 和页面不暴露密钥。

## 历史证据

- 实现提交：`1fbc710 feat(ai): Prompt control plane P0+P1 — versioned prompts + safe release`；
- 数据库迁移：`V2__prompt_ops_control_plane.sql`；
- 该提交包含后端状态机、运行时 Registry、管理 API、管理页面及相应测试。

## 已知边界

- 当前管理的是教学 Prompt 及发布策略，不是通用 Agent/Skill 平台；
- Prompt 质量仍需通过独立评测验证，发布流程本身不能证明内容效果更好。
