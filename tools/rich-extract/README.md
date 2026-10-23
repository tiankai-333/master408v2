# tools/rich-extract — 富内容片段提取（受控入口）

408 题库富内容（表格/公式）从源站保存页到导入 JSON 的提取纯函数，**受版本控制的规范实现**。

## 背景

- legacy 爬虫 `v1-reference/crawler/exam_crawler_with_images.py`（含真实题库数据）被
  `.gitignore` 忽略、不可复现；其中 v1 富内容提取逻辑已提纯为本模块。
- 源页公式是 KaTeX **渲染后 DOM**：无权威 TeX annotation 时只能从渲染几何重建，
  一律标记 `formula_source=reconstructed`（待核），导入侧写入 `statusReason`，
  **不得静默当作完整正确内容**。有 `annotation encoding="application/x-tex"` 时为权威。
- 安全边界在后端导入白名单（jsoup）：本模块剥 script/style 仅为去页面噪声，非消毒。

## 用法

```bash
# CLI：页面/片段 → JSON（fragments + formula_source）
python tools/rich-extract/rich_extract.py <page.html>

# 测试（原创夹具，无真实题库内容）
python -m unittest discover -s tools/rich-extract -p "test_*.py"
```

## 输出格式

```json
{
  "fragments": ["<p>…\\(n^{2}\\)…</p>", "<table>…</table>"],
  "formula_source": "annotation | reconstructed | none",
  "formula_stats": {"annotation": 0, "reconstructed": 1}
}
```

## 边界

- 不触网、不下载资源、不读题库数据文件。
- mtable（矩阵）不支持，降级线性文本并计入 reconstructed。
- 导入侧对应实现：`apps/backend/backend-app/.../newdb/importing/`（富路径校验 + 净化）。
