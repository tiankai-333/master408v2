# Validation — 固定 AI 评测基线

> 历史恢复：以下历史结果来自《AI工程演进记录》和仓库 README。

## 自动化验证

- `AiEvaluationDatasetTest`：数据集版本、数量和契约；
- `DeterministicAiResponseEvaluatorTest`：覆盖、禁用表述、长度和 Token 估算；
- `AiEvaluationServiceTest`：运行创建、查询与比较约束；
- `AiEvaluationWorkerTest`：真实执行、逐条失败隔离和最终状态；
- `DatabaseSchemaContractTest`：Flyway V3 结构。

## 历史结果

- 固定集共 12 条，其中 9 条调用真实模型，3 条验证输入契约和失败隔离；
- 首轮实测通过 4/9，平均质量分 81.11/100；
- 失败包含 3 条概念覆盖门槛和 2 条回答长度门槛，没有隐藏；
- 当时后端全量 53 项测试通过，管理端生产构建通过；
- Flyway V3 已在本地基准库成功应用。

## 已记录的失败与修正

- 混合文本 Token 估算测试预期为 5，公式实际为 4；确认算法符合公开约定后修正测试预期；
- `TaskRejectedException` 与其父类不能同时用于 multi-catch，改为捕获父类
  `RejectedExecutionException`。

## 已知边界

- 确定性规则适合回归信号，但不足以证明回答事实正确；
- 仍需人工标注集、检索专项指标，并谨慎评估模型裁判；
- 历史模型结果受供应商、模型版本和运行时间影响，不应视为永久基准数值。
