# -*- coding: utf-8 -*-
"""tools/rich-extract 受控提取模块测试（原创夹具，无真实题目内容）。

运行：python -m unittest discover -s tools/rich-extract -p "test_*.py" -t ..
（或在本目录：python -m unittest test_rich_extract_module -v）
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bs4 import BeautifulSoup

from rich_extract import extract_file, fragment_html, formula_source_label


def frag_of(html):
    soup = BeautifulSoup(html, 'lxml')
    el = soup.find('p') or soup.find('div')
    return fragment_html(el)


class RichExtractTest(unittest.TestCase):
    def test_table_structure_preserved(self):
        src = ('<div class="td-content"><p>性能对比：</p>'
               '<table><thead><tr><th>方案</th><th>命中率</th></tr></thead>'
               '<tbody><tr><td>直接映射</td><td>高</td></tr>'
               '<tr><td>组相联</td><td>更高</td></tr></tbody></table></div>')
        soup = BeautifulSoup(src, 'lxml')
        out = fragment_html(soup.find('div'))
        self.assertEqual(1, out.count('<table'))
        self.assertEqual(3, out.count('<tr'))
        self.assertEqual(4, out.count('<td'))
        self.assertIn('组相联', out)

    def test_superscript_reconstructed_flagged(self):
        stats = {'annotation': 0, 'reconstructed': 0}
        soup = BeautifulSoup('<p>x<span class="katex"><span class="katex-html">'
                             '<span class="mord">n</span><span class="msupsub">'
                             '<span class="vlist-t"><span class="vlist-r"><span class="vlist">'
                             '<span style="top:0.363em;"><span class="pstrut"></span>2</span>'
                             '</span></span></span></span></span></span></p>', 'lxml')
        out = fragment_html(soup.find('p'), stats)
        self.assertIn('\\(n^{2}\\)', out)
        self.assertEqual('reconstructed', formula_source_label(stats))

    def test_annotation_authoritative(self):
        stats = {'annotation': 0, 'reconstructed': 0}
        soup = BeautifulSoup('<p><span class="katex"><span class="katex-mathml">'
                             '<math><annotation encoding="application/x-tex">L^{2}</annotation></math>'
                             '</span><span class="katex-html">garbage</span></span></p>', 'lxml')
        out = fragment_html(soup.find('p'), stats)
        self.assertIn('\\(L^{2}\\)', out)
        self.assertNotIn('garbage', out)
        self.assertEqual('annotation', formula_source_label(stats))

    def test_script_stripped(self):
        self.assertNotIn('alert', frag_of('<p>文本<script>alert(1)</script></p>'))

    def test_plain_text_compatible(self):
        self.assertEqual('<p>纯文本。</p>', frag_of('<p>纯文本。</p>'))

    def test_cli_entry(self):
        import json
        import tempfile
        fx = os.path.join(tempfile.gettempdir(), 'rich_extract_cli_fixture.html')
        with open(fx, 'w', encoding='utf-8') as f:
            f.write('<main><div class="td-content"><p>夹具 \\(n^{2}\\)</p>'
                    '<table><tr><td>1</td></tr></table></div></main>')
        import subprocess
        mod = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'rich_extract.py')
        r = subprocess.run([sys.executable, mod, fx], capture_output=True, text=True,
                           encoding='utf-8')
        self.assertEqual(0, r.returncode)
        payload = json.loads(r.stdout)
        # 纯文本定界符非 KaTeX 渲染 DOM：无几何重建来源，如实记 none
        self.assertEqual('none', payload['formula_source'])
        self.assertTrue(any('<table' in f for f in payload['fragments']))


if __name__ == '__main__':
    unittest.main()
