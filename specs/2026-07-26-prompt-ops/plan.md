# Plan — PromptOps 控制面

> 历史恢复：任务顺序根据最终提交和依赖关系重建，不代表原始实施顺序。

## Group 1：数据模型与迁移

1. 使用 Flyway V2 建立 Prompt 定义、版本、发布和审计结构。
2. 扩展 Usage 记录，使模型调用可关联 Prompt 版本。
3. 更新数据库结构契约测试。

## Group 2：运行时解析

1. 定义 `PromptRegistry`、`PromptRef`、`PromptContext` 和 `ResolvedPrompt`。
2. 实现数据库 Registry、稳定灰度选择和代码兜底。
3. 将解析结果贯穿 Legacy/Spring AI 调用边界。
4. 提供初始 Prompt Seed，避免首次部署无可用配置。

## Group 3：状态机与管理服务

1. 实现草稿创建、提交、审批、灰度、全量发布、回滚和 Kill Switch。
2. 校验模板变量和非法状态转换。
3. 为全部管理操作写入审计日志。
4. 提供版本 Diff 和在线测试能力。

## Group 4：管理端

1. 增加 PromptOps API 封装与管理员路由。
2. 实现定义列表、版本详情、Diff、测试和发布界面。
3. 确保接口不返回模型密钥或其他敏感配置。

## Group 5：验证与文档

1. 增加 Registry、状态机、解析和 Gateway 测试。
2. 验证管理端生产构建。
3. 更新后端说明与工程演进记录。
