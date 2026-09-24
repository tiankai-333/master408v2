# 数据库架构设计（2026-09-20）— 导航

按 [SDD 工作说明](../README.md)（源自 DeepLearning.AI《Spec-Driven Development with Coding Agents》课程）：
每个 Feature 以**三份主规格**为导航入口，复杂 Feature 另加可选证据文档层。

状态：**设计已收敛（2026-09-23）**；剩作者自我考核 + 红线测试补跑（见 plan 与 validation）。
迁移与结构变更未开始，属后续实施 Feature。

## 三份主规格

| 文件 | 问题 | 内容 |
| --- | --- | --- |
| [requirements.md](requirements.md) | **做什么** | 范围与非目标、**28 条已定决策（D-01～D-28）**、剩余未完成项 |
| [plan.md](plan.md) | **怎么做** | 有顺序、可独立审查的编号任务组与进度（当前下一项在末尾） |
| [validation.md](validation.md) | **怎样证明完成** | DB-C01～C11 逐条验证记录、未执行清单、**自我考核表（下一动作）** |

## appendix/（证据文档层，按需查）

本 Feature 的核心设计交付物 [`data-contract.md`](appendix/data-contract.md) 在此层：
字段所有权三列、内容块模型（3.9）、RAG 就绪与降级（4.6）、生命周期事务边界（6.4）、
停用下游失效（7.3）、管理端可/不可承诺清单。

| 文件 | 一句话 |
| --- | --- |
| `data-contract.md` | **核心设计交付物**（见上） |
| `physical-design.md` | 表结构怎么改：候选 1～10 的 DDL 与隔离验证 |
| `migration-plan.md` / `rehearsal-report.md` | 迁移/对账/回滚设计 + 代表性演练证据 |
| `audit-report.md` / `audit-queries.sql` | 共享库只读审计的事实与 SQL（A-01～A-13） |
| `entry-inventory.md` | 102 个读写入口盘点（I/B/K/R/S/W/Q/U 编号） |
| `design-test-plan.md` + `ddl/` | 隔离库设计测试 T1～T5 方案与执行记录、候选 DDL 脚本 |
| `sample-contract.md` / `sample-2024-q43.md` | 第 43 题样本契约与真题事实调查（含原创夹具） |
| `pending-verification.md` | 16 项待验证的处置台账（已全部收口，留追溯） |
| `decision-cards.md` / `decision-recommendations.md` | 决策过程记录（结论已归档进 requirements，纯历史） |
| `workflow.md` | 执行节奏、四个人工门、批次 0～7 流水账 |
