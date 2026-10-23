# -*- coding: utf-8 -*-
"""富内容片段提取（受版本控制的受控入口，v1）。

来源与边界：
- 逻辑自 legacy 爬虫 v1-reference/crawler/exam_crawler_with_images.py 的 v1 新增部分
  提纯而来；该爬虫目录被 .gitignore 忽略（含真实题库数据，不入库），本模块为
  **规范实现**，legacy 仅作历史参考。
- 输入：单页/片段 HTML（源站保存页或任意含 KaTeX 渲染标记的片段）。
- 输出：JSON（stdout），含保留结构的 html 片段与公式语义来源统计。
- 公式语义来源：优先 `<annotation encoding="application/x-tex">`（权威 TeX）；
  无 annotation 时从渲染 DOM 重建（sup/sub 依 vlist top 符号、mfrac 依 vlist-r 顺序），
  一律记 formula_source=reconstructed——**待核，不得静默当作完整正确内容入库**
  （导入侧以此标记 statusReason）。mtable（矩阵）明确不支持，降级线性文本。
- 本模块不下载资源、不触网、不做安全消毒；XSS 边界在后端导入白名单
  （apps/backend .../newdb/importing/HtmlSanitizer.java）。

用法：
    python tools/rich-extract/rich_extract.py <page.html>
"""
import json
import re
import sys
from typing import Tuple

from bs4 import BeautifulSoup, NavigableString

ZERO_WIDTH = {'​': '', '⁡': '', '⁢': '', '⁣': ''}


def katex_replacement(span, stats):
    # 语义来源优先：annotation 里的 TeX 是权威；无 annotation 时从渲染 DOM
    # 几何重建，记 reconstructed 待核。
    annotation = span.find('annotation', attrs={'encoding': 'application/x-tex'})
    if annotation is not None:
        stats['annotation'] += 1
        return '\\(' + annotation.get_text().strip() + '\\)'
    html_layer = span.find('span', class_='katex-html')
    if html_layer is None:
        return span.get_text()
    stats['reconstructed'] += 1
    body = re.sub(r'\s+', ' ', _tex_children(html_layer)).strip()
    return '\\(' + body + '\\)'


def _tex_children(node) -> str:
    out = []
    for child in node.children:
        if isinstance(child, NavigableString):
            out.append(str(child))
            continue
        cls = set(child.get('class') or [])
        if 'msupsub' in cls or 'msup' in cls or 'msub' in cls:
            # 上/下标：基座是前一个兄弟节点文本；sup/sub 由 KaTeX 渲染几何判定
            # （vlist 的 top 偏移为负 = 下标，非负 = 上标）——重建值记待核
            out.append(_script_mark(child)
                       + re.sub(r'\s+', '', _tex_children(child)) + '}')
        elif 'mfrac' in cls:
            num, den = _frac_parts(child)
            out.append('\\frac{%s}{%s}' % (num, den))
        else:
            out.append(_tex_children(child))
    return ''.join(out)


def _script_mark(node) -> str:
    pos = node.find(lambda t: t.name == 'span' and 'top:' in (t.get('style') or ''))
    style = pos.get('style', '') if pos else ''
    m = re.search(r'top:\s*(-?[\d.]+)em', style)
    return '_{' if (m and m.group(1).startswith('-')) else '^{'


def _frac_parts(mfrac) -> Tuple[str, str]:
    """KaTeX mfrac DOM：第一个 vlist-r 为分子，第二个 vlist-r 为分母。"""
    rows = mfrac.find_all('span', class_='vlist-r', recursive=True)
    texts = [re.sub(r'\s+', '', row.get_text()) for row in rows[:2]]
    if len(texts) < 2:
        return re.sub(r'\s+', '', mfrac.get_text()), ''
    return texts[0], texts[1]


def fragment_html(elem, stats=None) -> str:
    """元素 → 存储 HTML 片段：剥 script/style（页面噪声，非安全边界），
    KaTeX 渲染 span 重建为可验证的 TeX 文本，其余结构标记原样保留。"""
    if stats is None:
        stats = {'annotation': 0, 'reconstructed': 0}
    import copy
    frag = copy.copy(elem)
    for bad in frag.find_all(['script', 'style']):
        bad.decompose()
    for display in frag.find_all('span', class_='katex-display'):
        inner = display.find('span', class_='katex')
        text = katex_replacement(inner, stats) if inner else display.get_text()
        display.replace_with(NavigableString('\\[' + text + '\\]'))
    for span in frag.find_all('span', class_='katex'):
        span.replace_with(NavigableString(katex_replacement(span, stats)))
    html = frag.decode()
    for ch, rep in ZERO_WIDTH.items():
        html = html.replace(ch, rep)
    return re.sub(r'[ \t]+', ' ', html).strip()


def formula_source_label(stats) -> str:
    if stats['reconstructed'] > 0:
        return 'reconstructed'
    if stats['annotation'] > 0:
        return 'annotation'
    return 'none'


def extract_file(path: str) -> dict:
    stats = {'annotation': 0, 'reconstructed': 0}
    soup = BeautifulSoup(open(path, encoding='utf-8').read(), 'lxml')
    main = soup.find('main') or soup
    frags = [fragment_html(el, stats) for el in main.find_all(['div', 'p', 'table'])]
    return {
        'fragments': frags,
        'formula_source': formula_source_label(stats),
        'formula_stats': stats,
    }


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print('usage: python rich_extract.py <page.html>', file=sys.stderr)
        sys.exit(2)
    print(json.dumps(extract_file(sys.argv[1]), ensure_ascii=False))
