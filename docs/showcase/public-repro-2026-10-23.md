# 公开复现入口（2026-10-23）

> 本文只列**在干净源码工作树（提交 `53a6f630`，`git status` 无未跟踪/未提交文件）实际执行过**的命令与结果。
> 招聘者可在克隆本仓库后按此复现；不依赖任何未跟踪本机文件。边界见文末。

## 前置

| 项 | 本机实测版本 |
| --- | --- |
| Python | 3.12（仅需标准库） |
| JDK / Maven | OpenJDK 21.0.11 / Maven 3.9.11（`mvn -o` 依赖本机仓库缓存；全新机器去掉 `-o` 联网下载） |
| Node / npm | 24.11.1 / 11.6.2 |
| （可选）Playwright | 1.64.1 + 系统 Chrome |

```bash
git clone git@github.com:tiankai-333/master408v2.git && cd master408v2
git checkout 53a6f630   # 本文所有结果的固定源码基线
```

## 1. 富内容提取受控模块（Python，无外部依赖）

```bash
python -m unittest discover -s tools/rich-extract -p "test_*.py"
```

实测：**6/6 OK**（KaTeX→TeX 重建、annotation 权威、mtable 降级等原创夹具）。

## 2. 离线词法检索小评测（Python，无外部依赖）

```bash
cd experiments/rag-smoke
python -m unittest discover -s tests                       # 实测 14/14 OK
python run.py --topk 5 --out results/my-run                # 实测 exit 0
```

- `results/my-run/metrics.json`、`per_query.jsonl` 与随库的 `results/run-001/` **逐字节一致**（有测试保证双次运行一致）。
- 指标口径：Hit@5 = 7/7、Hit@1 = 6/7、MRR = 0.9286，分母为 7 条可作答查询；3 条无答案查询不入分母、单独报告分数。
- `manifest.json` 记录语料/查询/源码 SHA-256。注意：该哈希按检出文件字节计算，Windows `core.autocrlf=true` 检出为 CRLF 时哈希与随库 manifest 不同（LF 归一化后一致）；指标与逐条结果不受影响。
- 语料为**原创合成**小样本，不是生产题库；仅词法 BM25 基线，**无向量/混合/模型实验**。

## 3. 已公开的 Loop 回归测试（Java，mock，离线）

```bash
cd apps/backend
mvn -o -pl backend-app test -Dtest='SpringAiToolOrchestratorLoopContractTest,AiOrchestratorLoopExhaustionMappingTest,SpringAiToolOrchestratorTest'
```

实测：**Tests run: 7, Failures: 0, Errors: 0**，BUILD SUCCESS（LoopContractTest 3 + 编排器回归 1 + 消费方映射 3）。

**限制（如实）**：内部完整回归为 5 类 20 项，其中 13 项 confirm 刻画测试**未公开**（不在仓库中），公开可复现的只有上述 7 项；且均为 mock ChatModel 下的契约/回归，真实模型行为未验证。

## 4. 管理端前端构建

```bash
cd apps/frontend/admin-next
npm ci          # 锁定依赖安装，实测 88 packages
npm run build   # 实测 exit 0
```

`dist/` 不含 `dev/rich-fixture.*`（开发夹具页不进默认生产构建，实测无文件、无引用）。

## 5.（可选）富内容组件夹具实测

```bash
# 终端 A
cd apps/frontend/admin-next && npx vite --port 8794 --strictPort
# 终端 B：Playwright + 系统 Chrome 访问组件夹具
```

实测（headless Chromium 引擎 + 本机 Chrome，仓库 `dev/rich-fixture.html` 真实挂载 `QuestionHtml.vue`）：
**katex 渲染 3 处、表格 2 数据行×2 格、无 pageerror**。

**限制**：这是开发夹具页验证渲染链路，**不是真实题库恢复**，也未走生产构建产物或应用启动/数据库链路。

## 边界汇总

- 以上为**干净源码工作树**复现：本机已有依赖缓存（npm/maven/Playwright 浏览器）被利用，
  **不等价于全新机器从零复现**（全新机器需联网下载依赖，命令相同）。
- 未做、未声明：真实数据库/后端应用启动、真实模型调用、公网部署、微信端；RAG 真实语料与向量/混合对照。
- 20 项回归中 13 项未公开；组件夹具 ≠ 题库；合成语料 ≠ 生产检索效果。
