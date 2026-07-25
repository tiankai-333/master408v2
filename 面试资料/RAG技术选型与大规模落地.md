# 408Master RAG 技术选型与大规模落地

## 1. 结论

主向量库选择 **Qdrant**，普通 Redis 继续承担 ChatMemory、Session 和后续共享限流，MySQL 的 `rag_document/rag_chunk` 继续作为文档元数据真相源。

```text
MySQL：原文、分块、版本、索引状态（可审计、可重建）
Qdrant：向量、检索所需 payload（派生查询索引）
Redis：短期状态、缓存、限流（不承担当前向量主库）
EmbeddingModel：可替换的向量生成边界
ChatModel：只负责最终生成，与检索解耦
```

本地开发使用单节点 Qdrant；它只证明接口和数据闭环，不代表生产环境已经高可用。生产根据数据规模选择 Qdrant Cloud 或 Linux/Kubernetes 多节点。

## 2. 为什么不继续使用 MySQL 全量加载 + JVM 余弦

当前旧实现每隔五分钟把全部向量解析并加载到 JVM，查询时逐个计算余弦：

- 查询复杂度随向量数线性增长；
- 多个后端实例各保存一份向量，内存成本随实例数重复；
- 缓存刷新期间会产生解析、GC 和数据一致性问题；
- 无法自然利用 ANN、payload 过滤、分片与副本。

它可以作为小数据诊断工具，但不适合作为大范围应用的生产检索路径。

## 3. Qdrant、Redis Stack 与 MySQL/JVM 对比

| 维度 | Qdrant | Redis Stack | MySQL + JVM |
| --- | --- | --- | --- |
| 定位 | 专用向量数据库 | 内存数据平台 + 搜索模块 | 关系库 + 应用内暴力检索 |
| Spring AI | 官方 VectorStore | 官方 VectorStore | 没有适合当前 MySQL 版本的官方向量实现 |
| 大规模成本 | 支持 mmap、on-disk 和量化，可用磁盘换 RAM | 延迟很低，但高维向量以内存为主时 RAM 成本敏感 | 每个后端重复占用 JVM 内存 |
| 并发扩展 | HNSW、分片、副本、负载均衡 | Redis 集群能力强，但要额外部署 Search/JSON 能力 | O(N) 扫描，扩后端会重复数据 |
| 高可用 | 多节点、分片副本、故障转移 | Sentinel/Cluster，具体能力取决于部署版本 | MySQL可靠，但向量计算仍困在应用实例 |
| 当前迁移成本 | 已有 Qdrant payload/索引代码，可渐进迁移 | 当前是普通 Windows Redis，需要更换基础设施 | 无迁移，但保留现有性能债 |

选择 Qdrant 不是因为本机刚好能运行，而是因为项目目标明确包含高维检索、元数据过滤和未来横向扩展；同时它允许向量/大 payload 放到磁盘，并可通过量化降低内存成本。

## 4. 成本估算原则

1024 维 `float32` 原始向量约占：

```text
1024 × 4 bytes = 4096 bytes
```

只计算原始向量：

| 数量 | 原始向量大小 |
| ---: | ---: |
| 1 万 | 约 39 MiB |
| 10 万 | 约 391 MiB |
| 100 万 | 约 3.81 GiB |

实际还包含 HNSW、payload、版本和优化临时空间。Qdrant 官方容量估算可使用 `数量 × 维度 × 4 × 1.5` 作为全部向量驻内存的粗略起点。副本因子为 2 时，存储成本也接近翻倍。

成本优化顺序：

1. 先减少无价值分块和重复向量；
2. 大文本只在 MySQL 保存，Qdrant payload 保存检索与引用必需字段；
3. 数据增大后使用 mmap/on-disk；
4. 用评价集验证后再启用 scalar/binary quantization，不能只追求压缩率；
5. 热数据与冷数据分 collection 或 shard，不无限提高机器规格。

## 5. 是否依赖外部大模型

RAG 要区分两个模型依赖：

- **EmbeddingModel**：将文档和查询转成同一向量空间；
- **ChatModel**：读取检索资料并生成最终回答。

向量检索本身不依赖 ChatModel。ChatModel 不可用时，系统仍可返回检索结果或明确降级；不能把“检索失败”和“生成失败”混成一种错误。

当前为兼容已有 1024 维知识库，继续使用智谱 `embedding-2`。长期通过 Spring AI `EmbeddingModel` 保持可替换边界：

- 外部 Embedding：无需自建 GPU，初期固定成本低；存在费用、429、网络、隐私和供应商故障；
- 本地 Ollama/ONNX：无按次 API 费用、数据不外发；需要模型文件、CPU/GPU容量、扩容和运维；
- 迁移模型必须全量重建索引，不能用新模型查询旧模型生成的向量。

生产策略是“外部模型默认 + 本地模型可选”，而不是请求失败后临时切换到不同向量模型。后者会使查询向量与存量向量空间不一致。

## 6. 并发与稳定性设计

### 查询链路

```text
限流/舱壁
→ 查询 Embedding（可缓存）
→ Qdrant ANN + metadata filter
→ 可选重排序
→ ChatModel
```

- Embedding与Chat分别设置超时、并发额度和指标；
- Qdrant使用连接池/官方客户端，不为每个请求新建客户端；
- 对重复规范化查询可做短期向量缓存，但缓存必须包含 embedding 模型版本；
- topK和候选数必须有上限，避免大 payload 放大网络与 Prompt；
- 流式Chat占用时间长，应与Embedding/Qdrant使用不同舱壁。

### 写入链路

- 文档、分块和版本先写 MySQL；
- 向量生成和 Qdrant 批量 upsert 异步化；
- MySQL记录 `pending/indexed/failed` 状态与 embedding 模型版本；
- Qdrant是可重建派生索引，失败可重试，不能让双写失败变成静默数据丢失；
- 全量重建使用新 collection，验收后切换别名，避免在线 collection 半新半旧。

### 故障策略

| 故障 | 策略 |
| --- | --- |
| Embedding 429/超时 | 有界重试或快速失败；不切换不同向量空间 |
| Qdrant 超时 | 熔断并降级为无 RAG 回答或仅返回可用结构化资料 |
| ChatModel 不可用 | 保留检索引用，返回生成服务繁忙 |
| Redis 不可用 | 不应导致 Qdrant 主检索不可用 |
| 单个 Qdrant 节点故障 | 本地开发会中断；生产使用副本和负载均衡 |

## 7. 分阶段容量方案

| 数据规模 | 建议 |
| --- | --- |
| 当前约数千/数万分块 | 单节点 Qdrant，先建立正确性与评价基线 |
| 10万–100万 | SSD + mmap/on-disk，监控 RAM、p95 和 recall，评估标量量化 |
| 百万以上或高可用要求 | 多 shard、多副本、负载均衡；优先托管版或成熟 Kubernetes 运维 |

具体阈值不是固定标准，最终由向量维度、过滤条件、并发量和评价结果决定。

## 8. 本阶段验收门槛

- 业务只依赖 Spring AI `VectorStore`，不依赖 Qdrant客户端类型；
- 主请求链路不再加载全部向量到 JVM；
- MySQL元数据可以重建 Qdrant索引；
- 固定问题集记录 Recall@K、MRR或命中率，而非只看“返回了几条”；
- Mock故障测试覆盖 Embedding、Qdrant和Chat分别失败；
- 并发测试记录吞吐、p95/p99、错误率和资源使用；
- 单节点事实与生产多节点设计必须分开表达。

## 8.1 实施与验收结果（2026-07-25）

- 在线业务已经只依赖 Spring AI `VectorStore`，旧手写 Qdrant HTTP Service 已删除；
- MySQL 全量加载到 JVM 和 O(N) 余弦在线兜底已删除；
- 题目富文本 JSON 已转换为纯文本题干、答案与解析；
- 知识资料完成约 4000 字、300 字 overlap 的切分；
- 6818 个规范化 chunk 中，当前 408 业务启用并建索引 1428 条；
- Qdrant `points_count=1428`，MySQL 新 collection 的 indexed 元数据为 1428，失败为 0；
- “栈”“进程与线程”“TCP 三次握手”“虚拟内存页面置换”真实查询均得到相关 Top 1；
- 后端 23 项自动测试全部通过，RAG 关闭时离线测试不依赖 Qdrant 或外部模型。

详细问题过程、数据证据、面试回答和未完成边界见 `RAG正式迁移复盘.md`。

## 9. 资料依据

- [Spring AI Qdrant VectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/qdrant.html)
- [Spring AI Ollama Embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html)
- [Qdrant 分布式部署](https://qdrant.tech/documentation/scaling/distributed_deployment/)
- [Qdrant 存储](https://qdrant.tech/documentation/manage-data/storage/)
- [Qdrant 容量规划](https://qdrant.tech/documentation/operations/capacity-planning/)
- [Qdrant 量化](https://qdrant.tech/documentation/manage-data/quantization/)
- [Redis 向量检索概念](https://redis.io/docs/latest/develop/ai/search-and-query/vectors/)
