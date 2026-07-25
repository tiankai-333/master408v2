# Spring AI 改造学习手册

> 目标不是记住几个 API，而是能够解释：为什么改、如何分层、请求怎样流动、遇到了什么问题、如何验证，以及下一步为什么这样做。

---

## 1. 先记住一句话

这次改造没有重写 Master408 的业务，而是在保留 Prompt、RAG、用户体系和前端接口的前提下，把“模型调用”替换成了 Spring AI 提供的统一抽象。

当前模型分工：

```text
智谱 embedding-2
负责：把问题转换为 1024 维向量

DeepSeek deepseek-v4-flash
负责：根据 Prompt 和 RAG 参考资料生成答案
```

Spring AI 在这里解决的是工程边界和模型适配问题，不会让大模型本身变得更聪明。

---

## 2. 为什么不重新做一个项目

旧项目已经具备有价值的业务资产：

- 用户登录、权限和会话；
- 题库、错题和学习业务；
- 四套 Prompt 模板；
- RAG 数据及向量；
- 同步和 SSE 接口；
- 学生端与管理端；
- 模型配置、用量记录等基础能力。

如果重新创建一个聊天 Demo，只能证明会调用 API，无法证明能够在真实系统中实施渐进式改造。

因此采用了“保留业务，替换边界”的策略：

```text
旧业务代码 ──→ AiAnalysisGateway ──→ 模型调用实现
                                      ├─ Legacy 手写 HTTP
                                      └─ Spring AI
```

这也是面试中比“我搭了一个 Spring AI Demo”更有价值的部分。

---

## 3. 改造前后的核心区别

| 观察项 | 改造前 | 改造后 |
| --- | --- | --- |
| 对话调用 | 手写 HTTP、JSON 和厂商分支 | Spring AI `ChatClient` |
| 向量调用 | `RestTemplate` 手写 embedding 请求 | Spring AI `EmbeddingModel` |
| 业务依赖 | 业务层理解厂商协议 | 业务依赖项目自己的稳定边界 |
| 模型切换 | 容易触碰业务代码 | 主要调整配置和模型 Bean |
| 同步与流式 | 各自处理响应 | 统一使用 `call()` / `stream()` |
| 观测 | 分散日志 | Micrometer 记录结果与耗时 |
| 回滚 | 需要改调用代码 | `AI_ENGINE` 可切换 legacy/Spring |

改造价值不是“代码变整齐”，而是把变化频繁的厂商协议隔离在稳定的业务边界之外。

---

## 4. 必须理解的五个 Spring AI 概念

### 4.1 ChatModel

`ChatModel` 是“对话模型”的统一抽象。

不同厂商可能使用不同 URL、鉴权和响应结构，但业务不应关心这些差异。Spring AI 根据配置创建具体模型实现，例如本项目使用 OpenAI 兼容实现连接 DeepSeek。

### 4.2 ChatClient

`ChatClient` 是建立在 `ChatModel` 之上的易用调用 API，负责组织 system/user message，并支持同步、流式、Advisor 等能力。

本项目同步调用：

```java
chatClient.prompt()
        .system(request.systemPrompt())
        .user(request.userPrompt())
        .call()
        .content();
```

流式调用：

```java
chatClient.prompt()
        .system(request.systemPrompt())
        .user(request.userPrompt())
        .stream()
        .content();
```

### 4.3 EmbeddingModel

`EmbeddingModel` 负责把文本转换为向量：

```text
"Java 21 的虚拟线程"
        ↓
[0.018, -0.023, ...]  共 1024 维
```

向量本身不是答案。它用于计算语义相似度，从知识库中找出与问题接近的资料。

### 4.4 Prompt

Prompt 不只是用户的一句话。本项目最终发送给模型的内容由以下部分构成：

- system prompt：定义角色和回答规范；
- question：用户问题；
- knowledgePoints：知识点；
- referenceDocs：RAG 检索结果；
- taskType：任务类型；
- style：默认、费曼、苏格拉底/柏拉图、第一性原理等风格。

这部分继续复用旧项目，因为它属于业务资产，不属于厂商 SDK。

### 4.5 自动配置与 Bean

Spring Boot 根据 `spring.ai.*` 配置创建模型 Bean。

本项目中：

```yaml
spring.ai.model.chat: openai
spring.ai.model.embedding: none
```

`chat: openai` 表示使用 OpenAI 兼容协议，并不表示实际厂商是 OpenAI。DeepSeek 支持该协议，因此可以使用 Spring AI 的 OpenAI 适配器。

embedding 设置为 `none`，是因为项目需要单独创建一个连接智谱的 `EmbeddingModel`，不能让自动配置错误地使用 DeepSeek 的地址和密钥。

---

## 5. 一次真实请求如何执行

以“解释 Java 21 虚拟线程”为例：

### 第一步：Controller 接收请求

`AIAnalysisController`：

- 取得当前登录用户；
- 校验问题；
- 准备风格、任务类型和知识点；
- 发起 RAG 检索；
- 调用 `AiAnalysisGateway`。

### 第二步：智谱生成查询向量

`RagService` 调用 Spring AI `EmbeddingModel`：

```java
float[] embedding = springEmbeddingModel.embed(text);
```

真实验收得到 1024 维向量。

### 第三步：RAG 检索

查询向量和知识库已有向量计算相似度，选出 TopK 资料。

如果没有达到相似度阈值的资料，结果可以为空。这不代表 embedding 失败，只表示当前知识库没有足够相关的内容。

### 第四步：复用旧 Prompt

`AiAnalysisGateway` 调用旧 `AnalysisService`：

```java
PromptTemplate template = legacyAnalysisService.getTemplate(style);
String userPrompt = legacyAnalysisService.generatePrompt(...);
```

这里体现“复用旧项目”：Prompt 模板和业务规则没有被重新实现。

### 第五步：DeepSeek 生成答案

Gateway 把 system prompt 和 user prompt 封装成 `AiAnalysisRequest`，交给 `SpringAiAnalysisClient`。

Spring AI 使用 DeepSeek 的 OpenAI 兼容接口完成调用并返回统一文本。

### 第六步：记录指标并返回网页

记录：

- `master408.ai.calls`：调用次数、同步/流式、成功/失败；
- `master408.ai.call.duration`：应用模型边界的调用耗时。

Controller 返回答案以及 `engine=spring-ai`，前端据此显示当前运行引擎。

---

## 6. 为什么对话和 embedding 使用不同厂商

模型选型应按能力和成本，而不是为了“全家桶”强行使用一家厂商。

当前选择：

| 能力 | 厂商/模型 | 原因 |
| --- | --- | --- |
| 对话生成 | DeepSeek `deepseek-v4-flash` | 负责回答生成，使用独立对话 Key |
| 向量生成 | 智谱 `embedding-2` | 已有 1024 维知识库向量，需要保持维度与模型兼容 |

特别要注意：查询向量和库内向量必须来自兼容的 embedding 模型和维度。不能随意把查询切换到另一种向量模型，否则即使维度相同，向量空间也可能不同，相似度将失去意义。

---

## 7. 真实遇到的四个问题

### 7.1 数据库密文无法解密

现有数据库保存了加密后的模型 Key，但找到的部署主密钥与密文不匹配。

处理：

- 不伪造“数据库配置已验证”；
- 增加环境变量配置模式；
- 删除源码中的默认主密钥；
- 缺少主密钥时快速失败。

教训：加密数据和主密钥必须一起纳入迁移与备份设计。

### 7.2 智谱返回 429

第一次真实联调时，智谱明确返回账户速率限制。

同时发现业务会先调用 embedding，再立即调用聊天接口。如果二者共用低额度账号，连续请求容易触发限流。

处理：

- DeepSeek 和智谱使用独立 Key；
- 对话和 embedding 职责拆分；
- 请求超时 30 秒；
- SDK 自动重试设为 0，避免网页无反馈等待数分钟。

教训：重试不是越多越好。同步请求、限流和用户等待时间必须一起设计。

### 7.3 自动配置产生两个 EmbeddingModel

Spring AI 根据 DeepSeek 的 OpenAI 配置自动创建了一个 embedding Bean，同时项目又创建了智谱 embedding Bean。

运行时出现：

```text
expected single matching bean but found 2
```

处理：

- 设置 `spring.ai.model.embedding=none`；
- 显式创建智谱 `EmbeddingModel`；
- 将其标记为 `@Primary`。

教训：多模型系统不仅是多写几组 URL，还要明确 Bean 的职责、名称、优先级和生命周期。

### 7.4 单次微秒测试没有业务意义

隔离测试显示 Spring AI 抽象有微秒级额外开销，但真实模型请求需要十几秒。

因此不能宣称 Spring AI 带来性能提升，也不应把微秒差异写进简历。

隔离测试唯一合理的结论是：没有发现数量级异常的框架开销。

真正应该比较：

- 首 Token 时间；
- 端到端 p50/p95；
- 成功率和超时率；
- Token 与成本；
- 输出质量；
- 新增模型或能力时的修改范围。

---

## 8. 已验证、未验证与下一阶段

### 已真实验证

- Java 21 + Spring Boot 4.1 + Spring AI 2.0 可以启动；
- DeepSeek `ChatClient` 同步调用成功；
- 智谱 `EmbeddingModel` 返回 1024 维向量；
- embedding → RAG → Prompt → DeepSeek 联合链路成功；
- 登录会话和网页接口正常；
- 失败时返回脱敏错误编号。

### 已通过自动化测试验证

- legacy/Spring 路由；
- 同步和流式委托；
- 成功/失败指标；
- 数据库供应商装配；
- 环境变量 embedding 装配；
- 缺少密钥时快速失败；
- 12 题固定评价集结构；
- 数据库表结构契约。

当前共 14 项自动化测试通过。

### 尚未形成结论

- legacy 与 Spring AI 的完整真实 A/B 数据；
- SSE 首 Token 时间；
- 并发、限流和重试策略；
- Token 和费用统计的准确性；
- 12 题回答质量的人工评分；
- ChatMemory；
- Spring AI VectorStore；
- Tool Calling、MCP 和 Agent。

---

## 9. 面试时如何讲这次改造

### 30 秒版本

> 旧系统已经有题库、Prompt、RAG 和前端，我没有重写，而是通过 Gateway 把模型调用收敛到稳定边界。对话使用 Spring AI ChatClient 接入 DeepSeek，向量使用独立 EmbeddingModel 接入智谱，保留 legacy 路径用于回滚。真实联调解决了密钥迁移、429 限流以及多 EmbeddingModel Bean 冲突，并用自动化测试和真实联合请求验证链路。

### 两分钟版本

1. **背景**：旧代码能调用模型，但业务、厂商协议、JSON/SSE 处理耦合；
2. **目标**：不是重写项目，而是为 Memory、RAG、Tool Calling 建立统一模型边界；
3. **方案**：Controller 不直接依赖 Spring AI，通过 Gateway 在 legacy 和 Spring 实现间切换；
4. **复用**：保留四套 Prompt、RAG 数据、用户体系和前端；
5. **多模型**：DeepSeek 负责 ChatModel，智谱负责 EmbeddingModel；
6. **问题**：处理数据库密钥不匹配、429、多 Bean 冲突和超时重试；
7. **验证**：14 项测试；真实生成 1024 维向量并完成对话；
8. **权衡**：框架增加抽象和版本成本，价值主要在扩展边界，不是提高模型智力或速度。

---

## 10. 高频追问与回答

### 为什么用 ChatClient，不直接注入 ChatModel？

`ChatModel` 是底层模型抽象，`ChatClient` 提供更适合应用层的 Prompt 组织、同步/流式和 Advisor 扩展。业务调用使用 ChatClient，更方便后续加入 Memory、日志和结构化输出。

### 为什么项目还需要 AiAnalysisClient？

不能让业务直接依赖某个框架。`AiAnalysisClient` 是项目自己的稳定端口，Spring AI 只是其中一个实现。这样可以测试、回滚，也能避免框架类型扩散到 Controller。

### 为什么 DeepSeek 配置中的类型是 openai？

这里指协议适配器，不是实际厂商。DeepSeek 提供 OpenAI 兼容接口，因此 Spring AI 可以用统一的 OpenAI 实现连接它。

### 为什么不把所有调用都交给同一个模型？

对话和 embedding 是不同任务。分开选型可以兼顾能力、成本和已有数据兼容性。已有知识库使用智谱向量，继续使用同一 embedding 模型能保持向量空间一致。

### 为什么关闭自动重试？

同步网页请求中，429 后长时间退避会让用户一直等待。当前先快速失败并记录错误，后续再根据幂等性、请求类型和异步任务设计有上限的退避重试。

### Spring AI 是否提升回答质量？

不会直接提升。回答质量取决于模型、Prompt、参数和上下文。Spring AI 的价值是统一调用、扩展点、测试性和观测能力。

### 为什么保留 legacy？

渐进式改造需要安全回滚，也需要同条件 A/B 对照。删除旧路径应发生在真实数据证明新路径稳定之后。

### 为什么当前 RAG 可能返回 0 条？

embedding 成功只代表生成了查询向量。知识库规模、向量模型一致性和相似度阈值都会影响召回；没有超过阈值的资料时应返回空，而不是强行把无关内容塞给模型。

---

## 11. 你应该能够独立画出的图

不看代码，尝试在纸上画出：

```text
Controller
   ↓
RagService
   ↓
EmbeddingModel → 智谱
   ↓
向量检索
   ↓
AiAnalysisGateway
   ↓
旧 Prompt 模板
   ↓
AiAnalysisClient
   ↓
ChatClient → DeepSeek
```

然后在图旁标出：

- 哪些属于旧项目复用；
- 哪些属于 Spring AI；
- 哪些可以通过配置切换；
- 哪些位置记录指标；
- 哪些位置可能发生超时和限流。

---

## 12. 自测题

如果下面的问题不能脱稿回答，就说明还需要回到对应章节：

1. `ChatModel`、`ChatClient` 和 `EmbeddingModel` 分别负责什么？
2. 为什么 DeepSeek 使用 `openai` 适配器？
3. 为什么查询向量不能随便换模型？
4. Gateway 比 Controller 直接注入 ChatClient 好在哪里？
5. 这次改造复用了哪些旧项目代码？
6. 两个 `EmbeddingModel` 为什么会冲突，最终怎样解决？
7. 为什么 100μs 的框架开销不值得写入简历？
8. 429 时为什么没有继续增加自动重试？
9. 哪些能力已经真实验证，哪些只是测试验证？
10. 如果面试官让你删除 legacy，你会先满足哪些条件？

---

## 13. ChatMemory 第一切片：已经完成

### 13.1 先回答四个设计问题

1. **记什么**：当前保存用户与助手消息，不保存完整业务审计历史；
2. **记多久**：最多 12 条消息，Redis TTL 为 24 小时；
3. **如何隔离**：服务端把已认证 `userId` 与客户端随机 `conversationId` 组合成存储 ID；
4. **如何压缩**：第一切片超过窗口直接淘汰，尚未实现摘要记忆。

ChatMemory 的真正难点不是调用一个 Advisor，而是多用户隔离、上下文污染、Token 成本、隐私和摘要失真。

### 13.2 Memory 不等于 History

- `ChatMemory` 的目标是把必要上下文重新放入下一次模型请求，使模型表现得像“记得”；
- 聊天历史是面向用户展示、审计和长期查询的完整记录，通常要落 MySQL；
- 当前实现是短期 Prompt Memory，不应对外宣称已经完成永久聊天记录。

### 13.3 真实调用链

```text
浏览器生成随机 conversationId
  → Controller 读取已登录 userId
  → user:{userId}:conversation:{conversationId}
  → MessageChatMemoryAdvisor
  → MessageWindowChatMemory（最多 12 条）
  → 自定义 Redis ChatMemoryRepository（TTL 24h）
  → ChatClient → DeepSeek
```

客户端不能直接提交最终 Redis Key，否则可以猜测或覆盖其他用户的会话。服务端绑定认证用户，才形成可信的隔离边界。

### 13.4 为什么写自定义 Redis Repository

Spring AI 2.0 内置的 Redis ChatMemory Repository 依赖 Redis Stack 的 RedisJSON 与 Query Engine；当前部署是普通 Redis。为了复用现有基础设施，第一切片使用 `StringRedisTemplate` 将有界消息窗口序列化为一个 JSON 字符串，并在每次保存时刷新 TTL。

这是一项部署兼容性决策，不代表自定义实现永远优于官方实现。若以后升级 Redis Stack，可以重新评估内置实现。

### 13.5 兼容性与验证

- 仅当请求含合法会话 ID 时才挂载 Memory Advisor，旧调用仍然保持无记忆；
- 客户端 ID 只允许 8–64 位字母、数字、下划线和连字符，拒绝路径式输入；
- 自动化测试验证同一客户端 ID 在不同用户下互不污染；
- 真实 DeepSeek 测试中，同一会话成功回答 `BLUE_WHALE_7319`，另一会话未泄漏；
- Redis 中对应会话保存 4 条 `USER/ASSISTANT` 消息，验收时 TTL 约 24 小时。
- 分析页提供“新对话”和“清空记忆”：前者轮换随机 ID，后者调用本人会话清理接口；
- 清理接口真实验收中，目标 Redis Key 从存在变为删除，路径式恶意 ID 返回拒绝。

### 13.6 当前边界

尚未完成摘要记忆、Token 预算、永久历史以及工作台链路接入。新对话与清空短期记忆已经完成，但页面刷新仍会自然生成新 ID，旧会话只等待 TTL 清理。面试时必须明确这是“可控的短期 ChatMemory”，不能包装成完整会话历史系统。

### 13.7 面试回答

> 我没有只把 `MessageChatMemoryAdvisor` 加进调用链。先区分了短期 Prompt Memory 和永久聊天历史，再用认证用户 ID 绑定客户端随机会话 ID，防止跨用户污染。由于现网是普通 Redis，而 Spring AI 内置实现依赖 Redis Stack，我复用 `StringRedisTemplate` 实现了有界 JSON 消息窗口，设置 12 条上限与 24 小时 TTL，并提供显式新对话和本人会话清理。最后用自动化隔离测试、真实 DeepSeek 双会话暗号实验及 Redis 删除验收验证。当前没有摘要和永久历史，我会把这些作为后续阶段，而不是提前写进简历。

---

## 14. 柏拉图式真实多轮教学

### 14.1 旧 Prompt 为什么是假的多轮

旧 `plato.json` 同时要求“不要直接给答案”和“自问自答、展示完整历程、输出最终结论”。模型只能在一次响应中扮演老师和学生，ChatMemory 即使保存它，也不会自然变成真实教学对话。

### 14.2 改造后的语义

- 首轮：题目、知识点和 RAG 资料只在首次 Prompt 中提供；
- 续轮：服务端发现该 Memory 已有消息后，只把学生当前回答作为本轮输入；
- 历史：`MessageChatMemoryAdvisor` 从 Redis 注入此前师生消息；
- 引导：每次只推进一个认知或计算动作，不自问自答；
- 揭示：只有学生明确要求答案、表示不会，或已经得出关键结论时才结束引导。

这里使用独立的 `PlatoConversationPromptPolicy`，而不是让 Controller 理解 Prompt 细节。其他三种解析风格仍走原生成路径。

### 14.3 为什么不截断模型的第二个问题

早期真实测试中，模型把两个相关计算写成两个问句。直接在流式输出中截断虽然能让页面只显示一个问题，但 Memory Advisor 仍可能保存模型的完整回复，导致下一轮模型看到的内容与学生看到的不一致。因此选择通过正反例约束“一个认知动作”，而不是制造展示状态与记忆状态分叉。

### 14.4 真实验收

题目：4KB 页面需要多少位页内偏移。

1. 首轮只问“4KB 精确等于 2 的多少次方字节”，未出现 12 位；
2. 学生错误回答 10 位后，只追问“4KB 准确等于多少字节”，未给最终答案；
3. 学生说“我不会，请直接告诉我答案”后，才解释 `4096 = 2^12` 并揭示 12 位；
4. Redis 中保存 3 轮共 6 条 `USER/ASSISTANT` 消息，TTL 约 24 小时。

### 14.5 当前边界

答案揭示目前是 Prompt 中的明确意图规则，不是 Tool Calling。摘要压缩和 Token 预算也未完成。`RevealAnswerTool` 留到 M6 与其他真实工具统一实现，面试时不能声称当前已经使用工具调用控制答案。
