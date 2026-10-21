# SDD 官方课程资料与本地采用说明

上游：[https-deeplearning-ai/sc-spec-driven-development-files](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files)。
本次核对日期：2026-10-16。固定提交：`4a9c2ac8cdac38a6689b65512461dde614487650`。
本文件提供中文归纳与原文导航；[sdd.md](../sdd.md)是本项目执行入口。

## 1. 阅读口径

- 原文链接固定到上述提交，避免 main 后续变化使当前说明失去依据。
- 工作流结论来自 prompts、两份 skill 说明、相关教学快照与示例；不根据视频标题猜测视频完整口述内容。
- 上游根目录本次未见 LICENSE / COPYING；本地提供归纳和链接，不复制完整第三方文档。
- 官方资料没有独立的一份“SDD 总规范”；各阶段展示一种可调整的工作方式。
- `example_specs/` 的大型设想与课堂实际小应用技术选择不同，不能把两个版本拼成强制架构。
- 官方示例不是经过本项目验证的可直接执行方案；示例内部若有字段或阶段差异，实施仍须核对。
- [官方全部文档与脚本路径](sdd-course-files.md)来自该提交的递归目录树。目录列出不等于逐文件全文核对。

## 2. 官方主要材料

| 材料 | 原文 | 用途与本地处理 |
| --- | --- | --- |
| 仓库说明 | [README](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/README.md) | 视频目录是阶段起始快照，可从某阶段进入，不要求每步复制项目 |
| Feature 生成工作流 | [feature-spec/SKILL.md](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/skills/feature-spec/SKILL.md) | 读取路线、功能分支、三方面访谈、三份规格与小范围交付；本地适配已有答案、工具和分支命名 |
| Changelog 工作流 | [changelog/SKILL.md](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/skills/changelog/SKILL.md) | 用提交整理真实变化，合并前人工调用和复核 |
| Changelog 实现 | [changelog.py](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/skills/changelog/scripts/changelog.py) | 读取提交并按日期组织；脚本会丢弃最后已记录日期，不能可靠补同日新增提交，暂不直接引入 |
| 产品详细示例 | [AgentClinic-Mission.md](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/example_specs/AgentClinic-Mission.md) | 涵盖产品流程、接口、状态、资料与开放问题；复杂场景可参考覆盖维度，不强制写同等篇幅 |
| 技术详细示例 | [AgentClinic-Tech-Stack.md](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/example_specs/AgentClinic-Tech-Stack.md) | 涵盖组件、数据、一致性、错误、集成及测试；技术本身不替换当前 Java 栈 |
| 教学后期宪法 | [mission](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/mission.md) / [tech-stack](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/tech-stack.md) / [roadmap](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/roadmap.md) | 实际教学使用简短使命、明确当前技术和小阶段路线，并未要求宪法包办所有细节 |
| 研究材料 | [数据库选型 backlog](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/backlog/2026-04-07-vercel-database-options.md) | 保存选项、背景、代价和建议；不是已经采纳的实现 |

## 3. 按官方提示词还原工作过程

文件中的 Lesson 编号与 Video 目录编号存在偏移，以下以真实文件名和内容对应，不自行改称某节逐字稿。

| 文件与原文 | 提示词涉及的操作 | 本地对应 |
| --- | --- | --- |
| [Lesson_04](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_04_prompts.md) | 从利益相关者输入建立宪法，询问产品与技术，随后补语言、目标读者和数据库选择 | 新项目先理解意图；已有项目基于事实补缺口，宪法可逐轮修订 |
| [Lesson_05](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_05_prompts.md) | 从下一阶段建立分支与日期规格；下一轮增加首页任务并同步规格 | requirements / plan / validation 相互一致，不把第一稿永久冻结 |
| [Lesson_06](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_06_prompts.md) | 继续实施剩余任务组 | 已授权明确任务持续执行，过程留下状态和验证 |
| [Lesson_07](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_07_prompts.md) | 修改布局实现、补规格，完成后更新路线并提交合并 | 实施与规格迭代；完成和合并以实际授权、验证为依据 |
| [Lesson_08](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_08_prompts.md) | 加入测试框架、测试及响应式要求；建立 Changelog 工作流；合并并合并路线阶段 | 工作流本身可演进，适用变更要同步；技术选择及提交动作不自动照搬 |
| [Lesson_09](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_09_prompts.md) | 改品牌色、类型定义、重跑测试、开展多视角审查、更新 Changelog | 设计与代码可按反馈改变；本项目多代理仅按用户或适用指令授权使用 |
| [Lesson_10](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_10_prompts.md) | 汇总多个阶段为 MVP 规格、实施、再追问规格中还缺哪些澄清 | 可以重组交付，但先明确用户结果和范围，实施后继续校正规格 |
| [Lesson_11](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_11_prompts.md) | 从已有 README / TODO 为遗留项目恢复宪法并访谈，再实施 | 恢复事实而不是假设空项目；先小改动验证方式 |
| [Lesson_12](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_12_prompts.md) | 把重复 Feature 提示变为本地 skill，再用它开始下一项 | 工作流稳定后可复用，不必重复打提示词，也不机械安装工具 |
| [Lesson_13](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/prompts/Lesson_13_prompts.md) | 询问代码哪些位置还需测试 | 接续时依据代码与现有验证识别缺口，不从无关新功能重启 |

## 4. 三份规格的实际示例

### 最小起步：Hello Hono

[requirements](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video07_Feature_Implementation/specs/2026-03-30-hello-hono/requirements.md)、
[plan](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video07_Feature_Implementation/specs/2026-03-30-hello-hono/plan.md)、
[validation](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video07_Feature_Implementation/specs/2026-03-30-hello-hono/validation.md)。

它通过最小页面确认运行基线，明确后续能力不纳入本期。验证侧检查编译、启动、HTTP 与配置，
不要求第一步先搭好全套自动测试。该阶段文件也保留了持续修改的痕迹，不把快照当作永远完美的模板。

### 小业务结果：反馈表单

[requirements](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/2026-04-06-feedback-form/requirements.md)、
[plan](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/2026-04-06-feedback-form/plan.md)、
[validation](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/2026-04-06-feedback-form/validation.md)。

范围具体到输入、公开可见性和页面行为；实现把数据、页面与提交连接起来；验证同时覆盖正常、
无效输入、刷新行为和人工交互。借鉴“可见结果与检查相对应”，不照搬示例的公开数据策略或表结构。

### 静态页面：About Us

[三份规格](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/tree/4a9c2ac8cdac38a6689b65512461dde614487650/Video14_Agents_replaceability/specs/2026-04-07-about-us-content)。

不需要数据库也可以构成功能；计划围绕内容、页面、路由、导航和检查，验证包含文案一致性。
本项目同样允许简单 Feature 只有必要层次，不为了标准目录补无关数据表或服务。

### 多阶段合并：MVP

[requirements](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video12_Legacy_support/specs/2026-04-06-mvp/requirements.md)、
[plan](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video12_Legacy_support/specs/2026-04-06-mvp/plan.md)、
[validation](https://github.com/https-deeplearning-ai/sc-spec-driven-development-files/blob/4a9c2ac8cdac38a6689b65512461dde614487650/Video12_Legacy_support/specs/2026-04-06-mvp/validation.md)。

多个阶段组合时，规格说明合并范围、共同边界及按阶段验证办法。它是一个具体教学产品的选择，
不要求本项目将所有遗留任务合成下一 Feature。

## 5. 官方内容与项目扩展的边界

| 议题 | 官方所见 | 本地扩展 |
| --- | --- | --- |
| 多轮过程留存 | 编号 prompts 保存用户指令序列 | discussion 追加关键问答、已接受/否定与未决项 |
| 版本化 | Git 分支、提交、合并及 Changelog | 用户可约定讨论完一次提交，避免擅自提交 |
| 完成判据 | 自动与人工检查、按示例适用的内容风格、可合并状态 | 增加作者自我考核及项目场景证据 |
| 计划粒度 | 小阶段、独立任务组，也有合并后的 MVP | 当前步骤具体、未来留白；避免把候选写成强制任务 |
| 文档职责 | 多种材料共存，包含 README、TODO、backlog | 本项目 specs 不增 README，入口由 sdd 和主规格承担 |
| 技术栈 | 不同材料展示不同技术选择 | 保留本项目 Java / Spring AI / MyBatis / MySQL 约束 |
| 多代理审查 | 一个提示词示例提出多代理审查 | 是否使用受当前用户/适用指令约束，不默认启动 |
| 工作流可替换 | 仓库定位为不依赖特定编码代理的方式 | 用规格、讨论和版本证据交接，工具名称不是强制依赖 |

## 6. 本地材料导航

[sdd 工作说明](../sdd.md) · [requirements 模板](../templates/requirements.md) ·
[plan 模板](../templates/plan.md) · [validation 模板](../templates/validation.md) ·
[discussion 模板](../templates/discussion.md) · [本次工作流讨论](sdd-workflow-discussion.md)。

官方仓库提供的是课程示例与演进素材，不是一份可以无差别全部执行的命令集合。
本地采用范围以 sdd 及用户明确指令为准。
