# Validation — Hybrid RAG 与引用溯源

> 历史恢复：本文件区分已经验证的软件行为和仍待黄金集证明的质量假设。

## 自动化验证

- `RagServiceTest`：向量/词法候选融合、排序、引用和降级；
- `DatabaseSchemaContractTest`：Flyway V5 ngram FULLTEXT 结构；
- Gateway/Analysis 相关测试：检索上下文和最终回答链路保持兼容。

## 历史结果

- 新增 RAG 测试已通过；
- 首次编译时 Mockito `any()` 同时匹配两个 `similaritySearch` 重载，改为
  `any(SearchRequest.class)`，未修改业务逻辑；
- Flyway V5 已纳入项目数据库基线；
- README 和工程演进记录已记录单路故障保留另一路的行为。

## 人工检查

- Qdrant 关闭时仍能从 MySQL 返回相关 Chunk；
- 词法路径失败时仍可保留向量候选；
- 回答中未提及的候选不会标记为 used citation；
- Trace 能说明候选来自 vector、lexical 或两者融合。

## 尚未完成的验证

- 尚未建立检索黄金集和人工相关性标注；
- 尚无充分证据证明当前 Chunk、Top-K、RRF 权重和重排公式最优；
- 需要后续比较 Vector、Lexical、Hybrid、Hybrid + Rerank 的 Recall、MRR、nDCG、延迟和成本；
- 当前确定性重排不等同于 Cross-Encoder 语义重排。
