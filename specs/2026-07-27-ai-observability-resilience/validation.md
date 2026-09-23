# Validation — AI 可观测与稳定性

> 历史恢复：历史结果来自工程演进记录；当前仓库测试名称用于建立可追溯关系。

## 自动化验证

- `AiUsageObservationServiceTest`：真实/估算 Usage、Prompt 与请求关联；
- `AiFeedbackServiceTest`：用户归属和评分范围；
- `SpringAiAnalysisClientTest`：供应商响应元数据；
- `AiFailureClassifierTest`：结构化状态及网络异常分类；
- `AiResiliencePolicyTest`：重试、429、熔断和恢复；
- `AiAnalysisGatewayTest`：双引擎、降级与流式不重放；
- `DatabaseSchemaContractTest`：Flyway V4 结构。

## 历史结果

- 可观测链路定向测试 10 项通过；
- 稳定性及 Gateway 定向测试 5 项通过；
- Flyway V4 已成功应用至本地 `master408_v2`；
- 默认治理参数记录为：并发 16、每秒 8、最多尝试 3 次、熔断阈值 5。

## 关键场景

- 429 在有界调度后恢复，并尊重可获得的 Retry-After；
- 连续瞬时失败触发熔断，冷却后只允许半开探测；
- 401/403 和其他确定性 4xx 不重试；
- 首 Token 前失败可以安全降级；首 Token 后失败不重放；
- 用户不能对其他用户的 Usage 记录提交反馈。

## 已知边界

- 当前配额和熔断状态为单实例级；
- 部分兼容供应商的流式 Usage 仍为 estimated；
- Agent 运行日志的静默失败问题属于后续技术债，尚不能宣称全链路审计永不丢失。
