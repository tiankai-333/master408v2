# Spring AI RAG 正式迁移复盘

## 1. 本阶段结论

2026-07-25，408Master 的在线 RAG 已从“手写 Qdrant HTTP + MySQL 全量加载到 JVM 做余弦扫描”的双轨实现，正式迁移到：

```text
MySQL（文档与切片真相源）
  → Spring AI EmbeddingModel（智谱 embedding-2，1024 维）
  → Spring AI VectorStore
  → Qdrant（可重建派生索引）
  → RagService
  → 业务编排器
  → ChatModel（当前可使用 DeepSeek）
```

本阶段已经完成真实数据建索引和真实查询验收。完成的是 **RAG 规范化迁移**，不是整个 Spring AI 项目升级；Tool Calling、系统化评测以及高并发治理仍属于后续阶段。

## 2. 改造前后对比

| 维度 | 改造前 | 改造后 |
| --- | --- | --- |
| 在线检索 | 手写 Qdrant HTTP；关闭时加载全部 MySQL embedding 到 JVM，逐条算余弦 | 统一调用 Spring AI `VectorStore.similaritySearch` |
| 复杂度 | JVM 路径为 O(N)，实例越多，缓存重复越多 | 当前小数据由 Qdrant 精确扫描；数据增大后可由 Qdrant 建 ANN 索引 |
| 框架边界 | 业务理解 Qdrant URL、payload 和响应 JSON | 业务只理解 `VectorStore`、`SearchRequest` 和 `Document` |
| 数据真相源 | 旧 embedding、元数据和真实索引可能不一致 | MySQL 保存文档、chunk 和索引状态；Qdrant 可全量重建 |
| 文本质量 | 题目直接使用富文本 JSON/KaTeX HTML；部分“chunk”超过 4 万字符 | 题目使用纯文本题干、答案、解析；知识资料执行有 overlap 的切分 |
| 模型耦合 | embedding 调用混在业务服务与供应商解析逻辑中 | `EmbeddingModel` 与 `ChatModel` 分离 |
| 重建 | 只找“未索引”记录，无法识别 collection 已丢失 | 索引版本包含模型与 collection；支持 `force + afterId` 游标重建 |

旧方案不是完全没有价值：它曾让小数据版本快速可用，也提供了 Qdrant payload 设计和业务降级经验。但它不适合继续作为大范围应用的在线主路径。

## 3. 三种存储的职责

### MySQL

- 保存 `rag_document`、`rag_chunk`、`rag_embedding`；
- 保存原文关联、规范化文本、版本、启用状态和索引结果；
- 是可审计、可恢复的事实来源；
- Qdrant 丢失时，可以从 MySQL 和源业务表重新生成。

### Qdrant

- 保存 1024 维向量和检索所需 payload；
- 承担相似度查询；
- 是派生查询索引，不是唯一事实来源；
- 当前本地是单节点，只证明数据闭环，不代表生产高可用。

### Redis

- 当前保存 ChatMemory 和 Session 等短期共享状态；
- 后续可承担多实例共享限流；
- 当前普通 Redis 没有 Redis Search/JSON，因此没有为了“少一个中间件”强行把它作为向量主库；
- Redis 故障不应直接导致 Qdrant 主索引丢失。

## 4. 为什么不能只相信 MySQL 的“已索引”

迁移前检查发现：

```text
rag_document：6732
rag_chunk：6732
旧 rag_embedding 元数据：3409
新 Qdrant collection：0 point
```

如果只根据 MySQL 中的 `status=indexed` 做增量任务，新 collection 会跳过已有元数据对应的 chunk，最终得到一个不完整索引。

因此，增量判断必须至少包含：

```text
chunk_id + embedding_model + vector_store + collection_name + status
```

同时增加强制重建能力：

- `force=true`：忽略旧的 indexed 状态；
- `afterId`：按 chunk 主键向后推进，不使用高 offset；
- stable vector id：根据 `rag-chunk:{chunkId}` 生成确定性 UUID，重复写入执行 upsert；
- 每批成功后才更新 MySQL 元数据，失败记录 `failed` 和错误信息。

这能解决“元数据说成功，但实际 collection 为空”的问题。更完整的生产方案应使用新 collection 重建、验收后切换 alias，避免在线索引处于半新半旧状态。

## 5. 迁移中暴露的数据问题

### 5.1 表名叫 chunk，不代表数据真的切过

第一轮全量索引在 120 条成功后出现 100 条失败：

```text
Tokens in a single document exceeds the maximum number of allowed input tokens
```

检查发现：

- 最大 `content_text` 长度为 42115；
- 416 条记录超过 6000 字符；
- 题目数据包含完整富文本 JSON 和大量 KaTeX 渲染 HTML；
- 知识资料也有单条超过 17000 字符的情况。

这说明旧结构完成了“建表”，但没有完成可用于 embedding 的内容规范化和切片。

### 5.2 题目内容规范化

题目不再把 `t_text_content.content` 的原始 JSON 直接送去 embedding，而是从权威的 `question_content` 当前版本生成：

```text
题干：title_text
答案：correct_answer
解析：analysis_text
```

这样保留了检索需要的语义，同时排除了 HTML 布局、KaTeX DOM 和其他展示噪声。原始富文本仍由业务表保存并用于页面渲染，RAG 的 `content_text` 只是派生检索文本。

### 5.3 知识资料切分

知识资料使用约 4000 字符的目标长度，并保留约 300 字符 overlap。切分优先寻找换行、句号或分号边界，找不到合适边界时才使用长度边界。

规范化结果：

```text
题目 chunk：6613
知识资料：119 条源记录 → 205 个 chunk
总 chunk：6818
最大 content_text：5360
超过 6000 字符：0
```

这里使用字符数是当前工程可落地的保守边界，不等同于精确 Token 切分。后续应根据具体 embedding tokenizer 和评测结果优化，尤其要处理代码、英文长文、表格和公式。

## 6. 为什么最终只有 1428 个向量

6818 是规范化后的全部 chunk 数量，但当前项目是 408 备考平台，只有以下数据处于启用状态：

| 科目 | 启用题目 |
| --- | ---: |
| 计算机 408 综合 | 377 |
| 计算机组成原理 | 235 |
| 数据结构 | 233 |
| 操作系统 | 216 |
| 计算机网络 | 162 |
| 知识资料 chunk | 205 |
| 合计 | 1428 |

另外 5390 道题属于数学、英语和思想政治，原数据已经设置 `enabled=0`。它们保留在 MySQL 中，但不进入当前 408 知识库。

最终一致性证据：

```text
MySQL 新 collection 的 indexed 元数据：1428
Qdrant points_count：1428
失败：0
```

因此“6818 个 chunk”和“1428 个向量”并不矛盾：前者是全部规范化数据，后者是当前业务启用集合。

## 7. 真实召回验收

使用真实智谱 embedding 和新 Qdrant collection，阈值为 0.5：

| 查询 | Top 1 chunk | Top 1 score |
| --- | ---: | ---: |
| 什么是栈，它有哪些基本操作 | 13（栈） | 0.6582 |
| 进程和线程有什么区别 | 67 | 0.6632 |
| TCP 为什么需要三次握手 | 2324 | 0.5762 |
| 虚拟内存的页面置换算法 | 6793 | 0.6090 |

“什么是栈”第一次验收曾返回空，最终发现是 PowerShell 请求体没有按 UTF-8 发送，导致服务端收到乱码查询。改为 `application/json; charset=utf-8` 和 UTF-8 bytes 后，chunk 13 正确成为 Top 1。这说明验收工具本身也必须进入排查范围。

这些数据证明真实链路可以工作，但不能据此宣称已经得到高召回率。固定问题集、Recall@K、MRR、负例、阈值曲线和重排序实验将在评测阶段完成。

## 8. 外部模型依赖、成本和稳定性

当前：

- ChatModel 可以使用 DeepSeek；
- EmbeddingModel 使用智谱 `embedding-2`；
- 向量维度为 1024；
- 建索引采用 20 条一批、单路串行；
- 全量重建使用游标推进，没有用并发压供应商。

Embedding 和 Chat 必须分开治理。外部 embedding 的优点是无需自建 GPU，初期固定成本低；缺点是存在按量费用、网络延迟、429、隐私和供应商故障。

不能在查询 embedding 失败时临时切到另一个 embedding 模型，因为不同模型的向量空间不兼容。正确做法是：

1. 把模型名和维度纳入索引版本；
2. 使用新模型建立新 collection；
3. 全量重建；
4. 用同一评价集验收；
5. 再切换线上 alias 或配置。

生产写入还应继续补充异步任务、有界重试、断点续传、任务审计和供应商限流。当前批量 HTTP 管理接口适合本地迁移和受控运维，不是最终的大规模任务平台。

## 9. Qdrant 当前状态应该怎样解释

本地 collection 有 1428 个 point，但 Qdrant 显示：

```text
points_count = 1428
indexed_vectors_count = 0
```

这不表示向量没有写入。当前数量低于 Qdrant 默认 `indexing_threshold=10000`，因此没有构建 HNSW ANN 索引，而是执行精确扫描。当前规模下可以接受，也有利于建立正确性基线。

数据增大后，再根据真实 p95/p99、内存和 Recall 指标决定 HNSW、mmap/on-disk 与量化配置，不能只为了看到 `indexed_vectors_count` 非零就提前调低阈值。

## 10. 条件化配置与离线测试

引入 Qdrant starter 后，完整应用测试曾在没有 `EmbeddingModel` 时仍尝试自动创建 `VectorStore`，导致 ApplicationContext 启动失败。

最终处理：

- 排除 starter 默认的 Qdrant 自动配置；
- 建立项目自己的 `SpringAiQdrantConfiguration`；
- 只有 `ai.rag.vector.enabled=true` 且存在 `EmbeddingModel` 时，才创建共享 `QdrantClient` 和 `VectorStore`；
- RAG 关闭时，离线测试不连接 Qdrant，也不要求外部模型。

清理旧手写实现后，后端共有 292 个 Java 源文件，自动测试结果：

```text
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
```

## 11. 已完成与未完成

### 已完成

- Spring AI `VectorStore + Qdrant` 成为在线唯一向量检索主路径；
- 删除旧手写 Qdrant HTTP Service；
- 删除在线 MySQL 全量加载、JVM 缓存和 O(N) 余弦兜底；
- 题目纯文本规范化；
- 超大知识资料切分；
- 模型 + collection 版本化增量判断；
- stable vector id；
- 强制游标式重建；
- 1428 条启用数据全量建索引，失败 0；
- 四类真实问题召回验收；
- 离线应用上下文与全部 23 项测试通过。

### 明确未完成

- 固定 RAG 评价集和 Recall@K/MRR；
- rerank、metadata filter 策略实验；
- 异步索引任务和新 collection alias 原子切换；
- embedding 429 限流、舱壁、退避与多实例共享配额；
- Qdrant 多节点、副本和故障转移；
- Prompt Token 预算与引用内容裁剪；
- Tool Calling 和后续编排器规范化。

## 12. 面试参考答案

> 原项目的 RAG 在数据量小时可以工作，但关闭 Qdrant 后会把 MySQL 中全部向量加载到 JVM，并逐条计算余弦相似度，多实例会重复占用内存，索引刷新和一致性也比较难控制。我把在线检索迁移到 Spring AI VectorStore，底层使用 Qdrant，使业务只依赖统一的 SearchRequest 和 Document。
>
> MySQL 是文档、chunk 和索引状态的真相源，Qdrant 是可以重建的派生索引，Redis 保存 ChatMemory、Session 和未来的共享限流状态。迁移时发现 MySQL 已有 3409 条已索引元数据，但新 Qdrant collection 实际为空，所以增量任务不能只相信 status，还必须绑定 embedding 模型和 collection，并支持 force 加主键游标的全量重建。
>
> 第一次重建又发现旧 rag_chunk 最大超过 4 万字符，题目还直接包含富文本 JSON 和 KaTeX HTML。我把题目改为使用纯文本题干、答案和解析；知识资料按约 4000 字、300 字 overlap 切分。最终全部规范化数据是 6818 个 chunk，但当前 408 业务只启用了 1428 条，其余是数学、英语和政治，所以 Qdrant 1428 个 point 与 MySQL 1428 条新版本元数据是一致的，失败为 0。
>
> Chat 和 Embedding 依赖是分离的。当前 Chat 可以使用 DeepSeek，Embedding 使用智谱 1024 维模型。查询失败时不能随意切换另一个 embedding 模型，因为向量空间不同；切换必须建立新 collection 并全量重建。当前真实召回和自动测试已经通过，但系统化召回评测、异步索引和高并发 429 治理仍是后续工作，我不会提前包装成已经完成。

## 13. 面试官可能继续追问

1. 为什么选择 Qdrant 而不是 Redis VectorStore？
2. 为什么 Qdrant 是派生索引，MySQL 才是真相源？
3. MySQL 状态和 Qdrant 实际状态不一致时怎样修复？
4. 为什么使用 `afterId`，不使用 offset？
5. overlap 太大或太小分别有什么影响？
6. 为什么不能在 embedding 429 后切换供应商？
7. 1428 条为什么没有建立 HNSW？
8. 如何证明 0.5 是合理阈值？
9. 全量重建时怎样做到不停机切换？
10. 如果一批 20 条中只有一条坏数据，当前实现有什么不足？

其中第 8、9、10 仍有继续改进空间。面试中应先说当前事实，再给出生产化方案。

