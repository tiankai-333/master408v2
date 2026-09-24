# 2024 年 408 真题第 43 题 — 样本事实调查

调查方式：本机只读查询（MySQL 仅 SELECT + 文件系统读取），未写入任何库表、未修改业务代码。
调查范围：MySQL 库 `xzs`、`apps/frontend/student/public/question-html|question-assets`、相关前后端读取代码。
上游规格：[`specs/2026-09-20-database-architecture/sample-contract.md`](./sample-contract.md)

## 0. 顶部状态

| 项 | 结论 |
| --- | --- |
| 找到真题 | **找到**（题目身份唯一，库内 + 磁盘文件均可定位） |
| 真题保真验证 | **部分** |
| 部分的原因 | 题干文字、2 张图、6 个小问与分值、对应解析**均可还原**；但①来源为第三方整理页而非官方原卷，无官方比对证据；②库里 `has_image=0`、`images` 为 NULL、`question_asset` 表 0 行，图与"图号/图序/小问引用"完全没有结构化记录；③`correct` 与 `correct_answer` 为空，`t_essay_question` 中本题行为空壳（`sub_questions` 全库 0 行有值），无结构化答案与小问；④唯一图片载体是被 `.gitignore` 排除且 git 未跟踪的 HTML 文件；⑤解析 HTML 本身有损坏（`<del>` 被误用作位上标），公式与竖式信息在纯文本化后失真。 |
| 原图是否可获得 | **内存/渲染层面可获得，资源文件层面不可获得**。原图以 **base64 内联** 在该 HTML 的 2 个 draw.io SVG 内（61 张 PNG）；但 `question-assets/csgraduates/408-real-2024/` **为空目录**，全库 `question_asset` 表 0 行。 |

> 本文件不复制整题与图片。第 6 节附**原创同结构夹具**（明确标记非真题）。

## 1. 题目定位与行级证据

### 1.1 身份行

```sql
SELECT id, source_year, source_question_no, question_type, info_text_content_id,
       correct, source, CHAR_LENGTH(title) AS title_chars
FROM t_question WHERE source_year=2024 AND source_question_no=43;
```

| 字段 | 值 |
| --- | --- |
| `id` | **8541** |
| `source_year` / `source_question_no` | 2024 / 43 |
| `question_type` | **5**（全库另有 type=1；2024 卷内 1→183 条、5→53 条） |
| `info_text_content_id` | **107082** |
| `source` | `2024年408真题` |
| `subject_id` | 2 |
| `correct` | **空**（0 字符） |
| `title` | 300 字符（截断的 fallback，非题干全文） |
| `score` / `difficult` | 100 / 2 |
| `has_image` | **0（与事实矛盾：该题有 2 张图）** |
| `has_code` | 1 |
| `images` | NULL（0 字符） |
| `knowledge_point` | NULL；`tags` = `html,external_html,code,katex` |
| `content_format` | html |

### 1.2 新表 `question_content`

```sql
SELECT id, version, is_current+0, has_image+0, has_code+0, content_format,
       legacy_text_content_id, source_hash,
       CHAR_LENGTH(title), CHAR_LENGTH(analysis), CHAR_LENGTH(title_text), CHAR_LENGTH(analysis_text)
FROM question_content WHERE question_id=8541;
```

| 字段 | 值 |
| --- | --- |
| `id` | 8850 |
| `version` | 1（**仅此 1 行，无历史版本**） |
| `is_current` | **1** |
| `content_format` | html |
| `has_image` / `has_code` | **0** / 1 |
| `legacy_text_content_id` | 107082 |
| `source_hash` | `e7bf430790d3b4056505c796692537f1cba406910a6f26d511fbf26a29712efa` |
| `title` / `analysis` | 300 字符 / 303 字符（**均为截断的引用占位符**） |
| `title_text` / `analysis_text` | 1122 字符 / 1154 字符 |
| `options` | `[]`（2 字符） |
| `correct_answer` | **空** |

### 1.3 `question_source`（全部列）

```sql
SELECT * FROM question_source WHERE question_id=8541;
```

| 列 | 值 |
| --- | --- |
| `id` | 8850 |
| `question_id` | 8541 |
| `source_type` | `crawler_html` |
| `source_name` | `csgraduates` |
| `source_year` / `source_question_no` | 2024 / `'43'`（varchar） |
| `paper_name` | `2024年全国硕士研究生招生考试计算机学科专业基础综合试题` |
| `page_no` | NULL |
| `raw_ref` | `https://www.csgraduates.com/study_methods/408quiz/2024/`（**第三方整理页，非官方原卷**） |
| `crawler_batch` / `ocr_batch` | NULL / NULL（无批次追溯信息） |
| `metadata` | `{"examKey":"408-real-2024","sourceUrl":"...","assetDir":"question-assets/csgraduates/408-real-2024","htmlDir":"question-html/csgraduates/408-real-2024","subjectName":"计算机组成原理","paperKind":"real","family":"408","html":true}` |
| `create_time` | 2026-05-27 20:41:20 |

> `metadata.assetDir` 声明了资源目录，但该目录**为空**（见 4.2）。

### 1.4 关联表为空的证据

```sql
SELECT COUNT(*) FROM question_asset;                                            -- 0
SELECT COUNT(*) FROM question_asset WHERE question_id=8541;                      -- 0
SELECT COUNT(*) FROM question_asset WHERE question_content_id=8850;              -- 0
SELECT COUNT(*) FROM question_knowledge_point WHERE question_id=8541;            -- 0
```

`question_asset` 在**全库范围内 0 行**（表已建、未回填）。

### 1.5 `t_essay_question`：存在形状正确但基本为空的"壳行"

该表列结构恰好覆盖本样本所需语义（`sub_questions` / `answer` / `analysis` / `images` / `total_score`），但 2024 年第 43 题对应行**只有身份与分值**：

```sql
SELECT id, subject_id, year, question_no, total_score,
       CHAR_LENGTH(title), CHAR_LENGTH(sub_questions), CHAR_LENGTH(answer),
       CHAR_LENGTH(analysis), CHAR_LENGTH(images)
FROM t_essay_question WHERE year=2024 AND question_no=43;
```

| 列 | 值 |
| --- | --- |
| `id` | **94** |
| `subject_id` / `year` / `question_no` | 2 / 2024 / 43 |
| `total_score` | **10（与实际小问分值合计 13 分不符，疑为列默认值）** |
| `title` | `''`（0 字符） |
| `sub_questions` | **NULL** |
| `answer` | `''`（0 字符） |
| `analysis` | `''`（0 字符） |
| `images` | `''`（0 字符） |

全表填充率（`SELECT COUNT(*), SUM(...) FROM t_essay_question`）：

| 总行数 | 有 `title` | 有 `sub_questions` | 有 `answer` | 有 `analysis` | 有 `images` | 年份范围 |
| --- | --- | --- | --- | --- | --- | --- |
| 98 | 14 | **0** | 3 | 4 | 2 | 2011–2024 |

2024 年 7 行（q41–q47，id 92–98）`total_score` 全为 10，`title` 仅 q47 有 103 字符，其余 6 行为空。

> 结论：这张表**声明了**"小问 / 答案 / 解析 / 图片"的契约意图，但 `sub_questions` 列**全库 0 行有值**，且本样本行为空壳 → 不能作为已保存的结构化证据，只能作为候选落点。

## 2. 旧 JSON 与新表形态对照（字段级）

旧 JSON 载体：`t_text_content.id = 107082`（`info_text_content_id` / `legacy_text_content_id` 同时指向它）。

```sql
SELECT JSON_KEYS(content) FROM t_text_content WHERE id=107082;
-- ["analyze", "titleContent", "questionItemObjects"]
SELECT CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(content,'$.titleContent'))) ,  -- 300
       CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(content,'$.analyze'))),        -- 303
       JSON_LENGTH(JSON_EXTRACT(content,'$.questionItemObjects')),          -- 0
       CHAR_LENGTH(content)                                                 -- 677
FROM t_text_content WHERE id=107082;
```

| 语义 | 旧 JSON（`t_text_content.content`，677 字符） | 旧表 `t_question` | 新表 `question_content` id=8850 | 简短摘录 / 差异 |
| --- | --- | --- | --- | --- |
| 题干 | `$.titleContent`，300 字符 | `title` = 同值 | `title` = 同值 | `<div class="question-html-ref" data-src="question-html/csgraduates/408-real-2024/q43-title.html" data-fallback="假定计算机 M 字长为 32 位。…add 指令 sll"></div>` |
| 选项 | `$.questionItemObjects` = `[]` | `options` = `[]` | `options` = `[]` | 非选择题，**无选项**（三分表一致） |
| 答案 | **无该键** | `correct` = `''` | `correct_answer` = `''` | **旧新表与 JSON 均无答案字段内容** |
| 解析 | `$.analyze`，303 字符 | `analysis` = 同值 | `analysis` = 同值 | `<div class="question-html-ref" data-src="…/q43-analysis.html" data-fallback="1）从 43 题 (a) 图可以看到，Y 寄存器 rs1 和 rs2 用 5 位表示…所以只需要 5bit 就可以表示对"></div>` |
| 题干纯文本 | 无 | `title_text`（1122 字符） | `title_text`（1122 字符） | 与旧表同值；**含图内标签**，见 3.3 |
| 解析纯文本 | 无 | `analysis_text`（1154 字符） | `analysis_text`（1154 字符） | 与旧表同值 |
| 图片 | **无该键** | `images` = NULL | 无图片列 | **两代结构都不存图**，仅 `has_image` 布尔位 |
| 图像布尔位 | 无 | `has_image` = 0 | `has_image` = 0 | **错误**：该题实际有 2 张图 |
| 资源记录 | 无 | — | `question_asset` 0 行 | 无 URL / hash / alt / 顺序 |

一致性校验（证明旧新同源、fallback 非全文）：

```sql
SELECT (q.title = qc.title) AS tq_title_eq_qc,          -- 1
       (q.analysis = qc.analysis) AS tq_ana_eq_qc,      -- 1
       (qc.title = LEFT(qc.title_text,300)) AS title_is_prefix300,   -- 0
       (qc.analysis = LEFT(qc.analysis_text,303)) AS ana_is_prefix303 -- 0
FROM question_content qc JOIN t_question q ON q.id=qc.question_id WHERE qc.question_id=8541;
```

- `title` / `analysis` 是**独立的抽取结果并截断**（300 / 303 字符，均在词中间断掉：`…add 指令 sll`、`…所以只需要 5bit 就可以表示对`），**不是** `title_text` / `analysis_text` 的前缀。
- 结论：**旧 JSON 只存"截断的引用占位符"，完整内容只存在于被 gitignore 的外部 HTML 文件中。**

## 3. 外部文件与资源清单

### 3.1 文件清单

| 相对路径 | 存在 | 大小 | 结构 | 引用图片 |
| --- | --- | --- | --- | --- |
| `apps/frontend/student/public/question-html/csgraduates/408-real-2024/q43-title.html` | 是 | **441,725 字节**（440,702 字符，无 BOM，首字节 `3C 70 3E` = `<p>`） | 18×`<p>`、74×`<code>`、**2×`<div class="svg-wrapper">`**；**`<img>` 0 个，`<table>` 0 个，外链 0 个** | 是，但以 **base64 内联**：61 处 `data:image/png;base64` |
| `apps/frontend/student/public/question-html/csgraduates/408-real-2024/q43-analysis.html` | 是 | 3,674 字节（2,694 字符） | 15×`<p>`、4×`<code>`、1×KaTeX 行内块、1×`<pre><code>` 竖式、2×`<a href="/constitution_principle/cpu/structure/#…">`（站点内相对外链）、**4×`<del>`**；`<img>` 0 个 | 否（0 图片） |

两者均被排除版本控制，**git 完全未跟踪**：

```
.gitignore:32:apps/frontend/student/public/question-html/
.gitignore:33:apps/frontend/student/public/question-assets/
# git check-ignore -v → 上述两行命中；git ls-files <该目录> → 空
```

### 3.2 内容头部摘录

`q43-title.html` 头部：

```
<p>假定计算机 M 字长为 32 位。按字节编址，采用 32 位定长指令字，指令 <code>add slli</code> 和 <code>lw</code> 的格式、编码和功能说明如题 43(a) 图所示。</p>
<div class="svg-wrapper"><div class="svg-container" style="height:auto;width:120%"><!DOCTYPE html>
<svg content='&lt;mxfile host="Electron" agent="… draw.io/27.0.5 …'&gt; …
```

`q43-analysis.html` 头部：

```
<p>1）从 43 题 (a) 图可以看到，Y 寄存器 rs1 和 rs2 用 5 位表示，所以计算机 M 最多的是 2⁵=32 个寄存器。根据指令 <code>R[rd] ← [rs1] &lt;&lt; shamt</code> 可知，shamt 表示左移。</p><p>shamt：…只需要 5bit 就可以表示对应的范围：
<span class="katex"><span aria-hidden="true" class="katex-html">…l</span>…o</span>…g</span>…2…=5…
```

### 3.3 两张图的实际形态（关键）

| 项 | 图 a（题 43(a) 指令格式） | 图 b（题 43(b) 数据通路） |
| --- | --- | --- |
| 容器 | 第 1 个 `.svg-wrapper`，`svg-container style="width:120%"` | 第 2 个 `.svg-wrapper`，`svg-container style="width:80%"` |
| 载体 | draw.io 导出的内联 `<svg content='<mxfile …>'>` | 同 |
| 内嵌位图 | **31** 张 `data:image/png;base64` | **30** 张 |
| 该段字符数 | 230,752 | 209,788 |
| 矢量文字 | **`<text>` = 0**（无矢量文字，图示内容以栅格图呈现） | 同 |
| 图内标签残留 | mxfile XML 中 `value=` 属性共 81 处（去重 57 个可见标签），如 `000 0000`、`rs2`、`rs1`、`rd`、`011 0011`、`shamt`、`imm`、`add 指令`、`slli 指令`、`lw 指令`、`二进制位序`、`指令功能说明` | 如 `ALU`、`MUX`、`多路选择器`、`扩展器`、`ALUBsrc`、`ALUctr`、`Ext`、`IR[31:20]` |

- base64 总量 337,216 字符（≈253 KB 二进制），单张最大 17,680 字符；抽样解码 `#0` 为合法 PNG **392×92**、`#1` 392×92、`#2` 312×92、`#3` 312×92。
- 渲染路径：`apps/frontend/student/src/views/exam/components/QuestionHtml.vue:57` 取出 `.question-html-ref[data-src]`，`:67` 以 `fetch(..., { cache: 'force-cache' })` 拉取 HTML 后 `ref.outerHTML = …`（`:69`）；失败时 `:71` 用 `data-fallback` 兜底。
- 因此：**HTML 文件在 → 图可见；HTML 文件缺失 → 只剩 300/303 字符的截断文字，且图与图号静默消失，无任何缺图告警。**

### 3.4 缺失项（资源侧）

| 缺失项 | 证据 |
| --- | --- |
| 独立图片文件 | `apps/frontend/student/public/question-assets/csgraduates/408-real-2024/` **存在但 0 个文件**（`Get-ChildItem -Force` 子项数 0） |
| 同卷其它年份有资源，2024 无 | `408-real-2023` → 1 个文件（`2023_19_ans-32d6511766c54a12.jpg`，25,389 B）；`408-real-2025` → 4 个文件（`2025_20_raw-…png`、`2025_3_sol-…png`、`44.drawio-…svg`、`46.drawio-…svg`）；`408-real-2024` → **0** |
| 命名规范（可反证 2024 缺件） | 兄弟目录采用 `<年>_<题号>_<用途>-<hash>.<ext>` 或 `<题号>.drawio-<hash>.svg`；全仓 `question-assets/**` 中无任何 `2024` 命名的 408 资源（仅 english/math 有） |
| DB 资源记录 | `question_asset` 全库 0 行 → 无 `asset_url` / `storage_key` / `alt_text` / `sort_order` / 资源 hash |
| 图号与图序 | 库内无字段区分"图 a / 图 b"；"题 43(a) 图"/"题 43(b) 图"仅作为正文文字出现；图与 `.svg-wrapper` 的对应只能靠 `width:120%` vs `width:80%` 人工判读 |
| 图片可信来源 | 无官方原卷比对；`raw_ref` 为第三方整理页 |

### 3.5 原图可获得性结论

- **可获得（仅限本机磁盘现状）**：61 张原始 PNG 的完整字节以 base64 内联在 `q43-title.html` 中，可无损解码；渲染后 SVG 亦完整。
- **不可获得（作为受管资源）**：不存在任何独立图片文件、无 `question_asset` 记录、无资源哈希/版本、目录被 gitignore 且 git 未跟踪 → **无冗余副本、无版本身份、无法与官方原卷核验**。一旦该 HTML 文件丢失或被原地覆盖，原图即永久消失（且违反 `sample-contract.md:29` 的"资源更新须产生可辨认的新身份"）。
- 因此：**真题保真验证依赖单个未纳入版本控制的 HTML 文件，不能视作"原图已受管可复现"。**

## 4. 小问与解析的可还原度

题干正文（去除 2 个图块后的可见文字）共 768 字符，含 3 个公共材料段落 + `请回答下列问题` + **6 个小问**：

| 小问 | 分值 | 出处（`q43-title.html`，`<p>` 序号） |
| --- | --- | --- |
| (1) 通用寄存器个数 / shamt 占 5 位 | 2 分 | TP3 |
| (2) `ALUBsrc` 取值 + `F`/`OF`/`CF` + 溢出标志 | 3 分 | TP4 |
| (3) `slli` 的 `Ext` 可 0 可 1 | 2 分 | TP5 |
| (4) `lw` 的 `Ext`、`ALUctr` | 2 分 | TP6 |
| (5) `A040 A103H` 必为 `lw` | 2 分 | TP7 |
| (6) 读数据存储地址 | 2 分 | TP8 |

- 合计 13 分；但 `t_question.score=100`、`t_essay_question.total_score=10`（id=94）**都与 13 分不符**，分值实际只写在正文文字里（`（2 分）` 等）。
- 解析 HTML 用 15 个 `<p>` 按 `1）…6）` 顺序覆盖 6 个小问 → 与题干小问**语义上可对应**，但没有独立标识字段。
- 解析内**无 `答案：` 标记**，`correct` / `correct_answer` 为空 → 小问级答案只能从解析散文中人工切分。

### 4.1 解析 HTML 的质量缺陷（影响保真的实证）

| 缺陷 | 原文证据 |
| --- | --- |
| `<del>` 被误用作位上标，位区间已损坏 | `6<del>0 位 = 0000011，中间的 14</del>12 位 = 010`（出现 2 次）；原文应为 `6~0 位` / `14~12 位` |
| 公式在纯文本化后破碎 | KaTeX 仅 `katex-html`（无 `katex-mathml`、无 `annotation`），抽文本得 `l o g 2 ​ 32 = 5` |
| 竖式依赖等宽空格对齐 | `<pre tabindex="0"><code> 8765 4321 + 9876 5432 ------------ 11FDB 9753 </code></pre>`，纯文本换行丢失后行列可能错位 |
| 外链为站点内相对路径 | `<a href="/constitution_principle/cpu/structure/#%e6%a0%87%e5%bf%97%e5%af%84%e5%ad%98%e5%99%a8">` ×2（"标志寄存器"锚点） |

### 4.2 派生文本的污染（`title_text`）

- `title_text` = 1122 字符，而渲染可见正文仅 768 字符；差额来自 **mxfile XML 里的图内标签**。
- 验证：`title_text` 含 `ALU 多路选择器`（在 HTML 文本层出现 0 次，只在 `mxCell value=` 里），并含 `二进制位序`、`指令功能说明`、`ALUBsrc`、`IR[31:20]`。
- 渲染正文 174 个 token 中 **172 个**同时出现在 `title_text` 中 → `title_text` ≈ 对整份 HTML 的朴素标签剥离（含转义 XML），**顺序是 XML 顺序而非阅读顺序，且无任何"这是图内文字"的来源标记**。
- 风险：RAG 会把图内标签当作题干正文拼接；`tags` 声明了 `katex`，但 `q43-title.html` 中 `katex` 出现 0 次（KaTeX 只在解析里），说明 `tags` 是**按题聚合的粗粒度标签**，不能作为内容形态判据。

## 5. 未完成 / 未验证清单

| 编号 | 未完成或未验证项 | 现状 |
| --- | --- | --- |
| U-01 | 与官方原卷核对题号、完整小问、排版 | 未做；`raw_ref` 为第三方整理页 |
| U-02 | 图 a / 图 b 与 `.svg-wrapper` 的权威对应；图号是否在原始卷面标注 | 未验证；现仅能凭 `width:120%`/`80%` 与正文引用顺序推断 |
| U-03 | 61 张内联 PNG 与 2 张"原图"的边界（哪些小图拼成图 a / 图 b） | 未验证；`<text>`=0，无法用矢量文本判定分组 |
| U-04 | 图内文字的可信提取（图 a 位段 31~25 等标注、图 b 各控制信号连线的含义） | 未有 checkpoint；mxfile XML 标签只能作为线索，不能作为连线关系的证据 |
| U-05 | 小问 ↔ 答案 ↔ 分值的结构化拆分 | 库里不存在；需人工从解析散文切分，未做 |
| U-06 | 为什么 `has_image=0`（抽取规则 / 回填遗漏） | 未查；同类错误会波及 408-real-2024 全卷其它含图题：`q44-title.html` = 208,650 B、`q47-title.html` = 167,389 B、`q45-title.html` = 33,964 B（对照无图题 `q40` = 313 B、`q46` = 1,054 B） |
| U-07 | `question_asset` 回填链路是否存在 | 未查；表为 0 行，仅有 `question_content.has_image` / `t_question.images` 两个未被使用的占位 |
| U-08 | 第 2 版/图替换与失效语义（对应 `sample-contract.md:46` S-04） | 无版本数据可测：`question_content` 仅 version=1；取用侧 `cache:'force-cache'`（`QuestionHtml.vue:67`）原地换文件不会产生新身份 |
| U-09 | 知识点归属 | `question_knowledge_point` 0 行 |
| U-10 | 图片资源指纹 / 校验状态 / 处理状态 | 无任何字段或记录 |
| U-11 | 磁盘 HTML 与 DB `source_hash` 的可复算关系（`source_hash` 是对什么取哈希） | 未验证 |
| U-12 | gitignore 之外是否有备份或对象存储副本 | 未见；`question-html/`、`question-assets/` 均未跟踪 |
| U-13 | `t_essay_question` 壳行的写入方与回填链路（谁写了 id=94 与 `total_score=10`） | 未查；该表 `sub_questions` 全库 0 行有值，本样本 6 列全空 |
| U-14 | `t_question.score=100` 与 `t_essay_question.total_score=10` 的真实语义（满分口径？百分比？默认值？） | 未验证；两者都与正文标注的 13 分不符 |

## 6. 原创同结构夹具

> ## ⚠️ 原创夹具，非真题保真
> 以下内容由本次调查**自行编写**，用于覆盖「多图 + 公共材料 + 3 个小问 + 答案解析」这一**结构**，与 2024 年 408 第 43 题**没有任何内容重合**（虚构机器 NOVA-16、虚构指令、虚构数值）。**不得**作为真题证据、不得标注为真题、不得写入真题数据。

**夹具标识**：`FIXTURE-NOVA16-01`（原创，非真题保真）

### 公共材料

虚构机器 **NOVA-16**：字长 16 位，按字节编址，指令定长 16 位。

**图 F1 — 指令格式**：`[15:12] op | [11:8] Rd/Rs | [7:0] imm`

| 助记符 | op | 功能 |
| --- | --- | --- |
| `LDM Rd, imm` | `0001` | `Rd ← imm` |
| `ADD Rd, Rs` | `0010` | `Rd ← Rd + Rs` |
| `STA Rs, imm` | `0011` | `M[Rs + imm] ← R0` |
| `JNZ Rs, imm` | `0100` | 若 `Rs ≠ 0` 则 `PC ← PC + imm` |

**图 F2 — 数据通路**：寄存器堆（8 个 16 位寄存器，A/B 两个读口）→ 16 位 ALU（仅实现加法，输出 `F`，并给出零标志 `Z`）→ 写回多路选择器 `WBSel` → 寄存器堆写口。立即数经扩展器 `Ext` 后送入 ALU 的 B 端多路选择器 `ALUSrc`。

控制信号约定：`Ext`：0 = 零扩展，1 = 符号扩展；`ALUSrc`：0 = 取 B 口寄存器，1 = 取扩展后的 imm；`WBSel`：0 = 取 ALU 输出 `F`，1 = 取 imm。

### 小问

1. （2 分）图 F1 中寄存器号字段占 4 位。据此说明 NOVA-16 至多可有多少个通用寄存器；若实际只实现 8 个，其余编码应如何处理？
2. （3 分）执行 `STA` 指令时，图 F2 中 `Ext`、`ALUSrc`、`WBSel` 的取值分别是什么？说明理由。
3. （2 分）若 `JNZ` 指令的机器码为 `0x4A0C`，写出它比较的寄存器号与跳转偏移量（十进制），并说明该偏移是否需要符号扩展。

### 答案与解析

**第 1 问**：寄存器号字段 4 位 → 2⁴ = 16 种编码，故至多 16 个通用寄存器。若只实现 8 个，可固定最高位为 0，或把其余 8 种编码保留为非法指令并在译码阶段触发异常。**引用图 F1。**

**第 2 问**：`STA` 的访存地址由 `Rs + imm` 得到，imm 可正可负 → `Ext = 1`（符号扩展）；地址加法需要立即数而非 B 口寄存器 → `ALUSrc = 1`；`STA` 写的是存储器，不写回寄存器堆 → `WBSel` 无写回动作（本夹具取 0）。**引用图 F1、图 F2；依据是图 F2 的 ALU 只有加法且地址计算不经过写回 MUX。**

**第 3 问**：`0x4A0C` = `0100 1010 0000 1100B`；op = `0100`（`JNZ`），寄存器号 = `1010` = 十进制 **10**，imm = `0000 1100` = `0x0C` = 十进制 **12**。跳转偏移需要符号扩展（`Ext = 1`），否则无法向后跳转；本夹具中 `0x0C` 为正，两种扩展结果相同。**引用图 F1。**

**归属说明**：第 1 问引用图 F1；第 2 问引用图 F1 + 图 F2；第 3 问引用图 F1。解析不引入新图。分值与答案逐问归属明确 —— 这正是真题样本当前**缺失**的结构。

**建议的契约断言（由本夹具驱动）**：①每题可持有 ≥2 个有序图块且图号可独立寻址；②每个小问记录顺序、分值、所引图块标识与自己的答案；③解析与题干严格分离且可回溯到小问；④图块有独立资源身份与指纹，正文检索不混入图内标签。
