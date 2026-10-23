# rag-smoke — 可复现离线词法检索小评测

本目录是 C 路（RAG 评测）的独立离线交付：一份**原创合成**的计算机基础小语料、
带人工相关性标注的查询集、输入校验、确定性 BM25 词法基线和逐条结果。
不依赖数据库、向量引擎、网络或任何第三方包，仅用 Python 3.12 标准库。

**这不是**向量/混合检索、真实模型或生产语料的实验结果；语料与查询均为
本目录内原创编写（明确 synthetic，不含真题复制），只用于验证评测口径可跑通、可复算。

## 复现

```bash
cd experiments/rag-smoke
python -m unittest discover -s tests -v   # 14 项确定性测试
python run.py --topk 5 --out results/run-001
```

两次运行产出的 `per_query.jsonl`、`metrics.json` 逐字节相同（有测试保证）；
`manifest.json` 固定语料/查询/源码的 SHA-256 与 BM25 参数（k1=1.5, b=0.75, CJK 2-gram）。

## 数据

- `data/corpus/c01..c10.md`：10 篇计算机基础短文（进制、补码、流水线、Cache、
  虚拟内存、进程线程、死锁、TCP、HTTP/HTTPS、哈希表）。文档 id = 文件名。
- `data/queries.jsonl`：10 条查询，每条含人工标注与理由：
  - `normal` ×2：含文档原词的直问。
  - `paraphrase` ×2：口语改写，不含关键术语原词。
  - `confusable` ×3：词面与干扰文档高度重叠的易混淆查询，标注里写明干扰项与不确定项。
  - `unanswerable` ×3：语料不覆盖的主题，`relevant` 必须为空，理由写明缺什么。

标注口径：文档级粒度；`relevant` 只标"回答该查询所需的文档"，干扰项写进
`rationale` 而不进标签。校验规则见 `rag_smoke/harness.py::validate_dataset`
（id 存在性、无答案必须空标签、可作答必须非空、理由非空）。

## 指标口径

只对 7 条可作答查询计算排名指标；3 条无答案查询**不进分母**，单独报告其 top-1
BM25 分数（词法检索不会拒绝任何查询，分数非零 ≠ 证据存在）。

- Hit@K：前 K 名至少含一个标注文档的查询比例，分母 = 7。
- Recall@K：`|topK ∩ relevant| / |relevant|`，逐条分母为该查询的标注数（本集均为 1），再求均值。
- MRR：首个相关文档排名倒数的均值，分母 = 7。
- 并列分数按文档 id 升序，保证确定性。

## 结果（run-001，K=5）

- Hit@5 = 7/7；若只看 Hit@1 = 6/7（见 per_query 逐条 rank）。
- MRR = 0.9286（6/7）。
- 失败样本：**q05**（"CPU 的 Cache 和浏览器缓存是一回事吗"）干扰项 c09
  （HTTP 层缓存）排在正确文档 c04 之前——词面重叠压过了语义层次，
  这正是 confusable 类查询要暴露的词法基线弱点。
- 无答案查询 top-1 分数 2.16～2.82，与可作答查询的命中分数同量级，
  说明纯词法分数不能当"有证据"的判据；阈值策略留待接入真实环境。

## 边界（不声称的部分）

- 只有词法 BM25 基线；没有向量、混合检索、重排或任何模型实测，对比留待真实环境接入。
- 语料为原创合成小样本，结果不能外推到生产语料或真实用户问题分布。
- 未实现"无证据"判据，只有分数记录；回答生成与引用回看不在本交付块。

## M3：真实 470 题固定语料与词法基线（私有快照，代码公开）

真实语料快照、查询集与基线输出按**私有来源**处理（worktree 父目录
`data-private/m3/`，不入 Git）——已有私有快照**不等于**公开从零数据可复现；
从零复现需自行从候选库（M1 产物）导出。

安全复跑入口（凭据走环境变量，不入命令历史）：

```bash
export M3_DB_USER=... M3_DB_PASSWORD=...   # 本地忽略文件注入，勿写死
python real_corpus_export.py --db-url jdbc:mysql://127.0.0.1:3306/master408_candidate \
  --out <私有目录>
python run.py --topk 5 \
  --corpus-dir <私有目录>/corpus --queries <私有目录>/queries.jsonl \
  --verify-manifest <私有目录>/manifest.json --out <私有目录>/baseline-run-001
python -m unittest discover -s tests   # 23/23（合成 14 + 真实输入回归 9）
```

`--verify-manifest` 对照冻结 manifest（语料逐文件哈希/queries/参数含 topk），
不符即拒绝运行且不写结果。`timing.json` 独立计时（仅建索引+逐查询，无端到端）。

限制：查询集为**构造的已知题目定位初标**（GLM 初标待抽核，非金标/非用户日志）；
单相关标签非穷尽；**20/20 是已知目标命中，不是 RAG 准确率**；image-dependent
命中不代表图可理解；无答案关键词缺失核对只是辅助手段；词法基线非向量/混合对照。

## M4：本地三路检索对照（BM25 / 真实语义向量 / RRF 混合，私有输出）

模型：本地 HF 缓存 `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`
@ `e8f8c211…`（Apache-2.0，458MB，仅 CPU；离线强制、safetensors、无
trust_remote_code）。输入复用 M3 冻结 470 题/24 初标（私有）。输出私有
`data-private/m4/`（向量/原题/标签/结果不进 Git）。

```bash
HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 python m4_compare.py \
  --corpus-dir <M3私有>/corpus --queries <M3私有>/queries.jsonl \
  --verify-manifest <M3私有>/manifest.json \
  --cache-dir <私有>/m4/vector-cache --out <私有>/m4/run-001
```

安全边界：向量缓存绑定模型 repo/revision/**本地文件哈希**/语料指纹/行序/
**查询文本哈希**/编码参数，错配拒绝；矩阵哈希/形状/有限值核验，
`allow_pickle=False`。timing 分段（BM25 建索引/文档 embedding/query 编码/
检索+融合，非端到端）。限制：查询为构造初标非金标；20/20 非准确率；
RRF 60/等权与 128 窗口为固定预设，结果仅对当前配置有效；混合未超 BM25。
