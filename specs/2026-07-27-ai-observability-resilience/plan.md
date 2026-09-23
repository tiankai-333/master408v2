# Plan — AI 可观测与稳定性

> 历史恢复：根据最终代码和测试重建。

## Group 1：统一观察模型

1. 扩展 AI Client 结果，使 Usage 和元数据可返回 Gateway。
2. 实现 Spring AI Usage Observation Service。
3. 使用 Flyway V4 增加 `request_id`、TTFT 和 Usage 来源等字段。
4. 为同步 JSON 和流式 SSE 暴露反馈关联标识。

## Group 2：反馈安全

1. 建立反馈提交服务和学生端接口。
2. 使用 `usage_log_id + user_id` 更新，拒绝跨用户反馈。
3. 校验评分范围和不存在的调用记录。

## Group 3：失败分类

1. 优先读取结构化 HTTP 状态和强类型网络异常。
2. 只在兼容客户端丢失状态时回退到异常链文本。
3. 将分类结果映射为可重试、不可重试、容量和熔断状态。

## Group 4：稳定性策略

1. 增加公平 Semaphore 并发舱壁和每秒预算。
2. 实现有界重试、指数退避、jitter 和 Retry-After。
3. 实现关闭、打开、半开探测的熔断状态。
4. 实现按优先级供应商降级。
5. 对流式调用区分首 Token 前与首 Token 后失败。
6. 输出调度、拒绝、熔断和恢复指标。

## Group 5：验证

1. 测试 Usage 来源、Prompt 版本、反馈归属和评分校验。
2. 测试失败分类、429 恢复、熔断、降级和流式不重放。
3. 验证 Flyway V4 和数据库契约。
