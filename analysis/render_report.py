"""Render the report to an offline HTML document with embedded figures and equations."""
from pathlib import Path
from io import BytesIO
import base64
import html
import re

import markdown
import matplotlib
matplotlib.use("Agg")
from matplotlib import mathtext, font_manager

ROOT = Path(__file__).resolve().parents[1]


def equation(source, display=False):
    source = " ".join(source.split())
    source = re.sub(r"\\(mathcal|mathbb|mathrm|mathbf|widetilde)\s+([A-Za-z])", r"\\\1{\2}", source)
    buffer = BytesIO()
    mathtext.math_to_image("$" + source + "$", buffer,
                          prop=font_manager.FontProperties(size=12), format="svg", dpi=200)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    cls = "equation" if display else "math-inline"
    return f'<img class="{cls}" src="data:image/svg+xml;base64,{encoded}" alt="{html.escape(source, quote=True)}">'


def main():
    text = (ROOT / "实验报告.md").read_text(encoding="utf-8")
    segments = re.split(r"(```.*?```)", text, flags=re.S)
    for i in range(0, len(segments), 2):
        segments[i] = re.sub(r"\$\$(.*?)\$\$", lambda m: "\n\n" + equation(m[1], True) + "\n\n", segments[i], flags=re.S)
        segments[i] = re.sub(r"\\\((.*?)\\\)", lambda m: equation(m[1]), segments[i], flags=re.S)
    md = markdown.Markdown(extensions=["tables", "fenced_code", "toc", "sane_lists"],
                           extension_configs={"toc": {"toc_depth": "2-2"}})
    body = md.convert("".join(segments))
    body = body.replace('面向领域低代码生成的 Java 文件合成与 7B 模型监督微调',
                        '面向领域低代码生成的<br>Java 文件合成与 7B 模型监督微调', 1)

    def embed(match):
        path = html.unescape(match[1])
        if path.startswith("data:"):
            return match[0]
        image = ROOT / path
        svg = image.with_suffix(".svg")
        if svg.exists():
            image = svg
        assert image.is_file(), image
        mime = "image/svg+xml" if image.suffix == ".svg" else "image/png"
        return 'src="data:' + mime + ';base64,' + base64.b64encode(image.read_bytes()).decode("ascii") + '"'
    body = re.sub(r'src="([^"]+)"', embed, body)
    # Keep figures, their captions, and the immediately following explanation near each other.
    body = re.sub(r'(<p><img(?![^>]*class="(?:math-inline|equation)")[^>]+></p>)\s*(<p><strong>图(?:(?!</p>).)*</p>)',
                  r'<figure>\1\2</figure>', body, flags=re.S)
    body = re.sub(r'(<p><strong>基础预测[^<]*</strong></p>)\s*(<pre>(?:(?!</pre>).)*</pre>)\s*(<p><strong>SFT 预测[^<]*</strong></p>)\s*(<pre>(?:(?!</pre>).)*</pre>)',
                  r'<div class="codecompare"><div>\1\2</div><div>\3\4</div></div>', body, flags=re.S)
    css = """
    @page { size: A4; margin: 17mm 17mm 18mm; @bottom-center { content: counter(page); font: 9pt sans-serif; color: #687781; } }
    * { box-sizing: border-box; }
    body { max-width: 970px; margin: 30px auto; padding: 22px 38px;
      color: #202b35; background: white; font: 15px/1.8 'Microsoft YaHei','SimSun',sans-serif; }
    h1 { font-size: 27px; line-height: 1.5; color: #13384b; margin: 0 0 28px; }
    h2 { font-size: 21px; border-bottom: 1px solid #b8cbd5; padding-bottom: 7px; margin: 35px 0 17px; }
    h3 { font-size: 17px; margin: 25px 0 12px; color: #194a60; }
    h4 { font-size: 15px; margin: 22px 0 10px; }
    p { margin: 0 0 15px; text-indent: 0; }
    a { color: #135b7b; text-decoration: none; overflow-wrap: anywhere; }
    img { display: block; max-width: 100%; max-height: 680px; height: auto; margin: 18px auto; }
    img.math-inline { display: inline; height: 1.24em; width: auto; max-width: none; margin: 0 1px; vertical-align: -0.23em; }
    img.equation { max-height: 58px; width: auto; margin: 22px auto; }
    figure { margin: 22px 0; break-inside: avoid; }
    figure p:last-child { font-size: 13px; line-height: 1.7; color: #42525c; }
    table { width: 100%; border-collapse: collapse; margin: 18px 0 23px; font-size: 13px; line-height: 1.65; }
    th { background: #e9f0f4; color: #173d50; text-align: left; }
    th,td { border-bottom: 1px solid #ccd8df; padding: 7px 8px; vertical-align: top; }
    tr { break-inside: avoid; }
    pre { break-inside: avoid; padding: 13px 16px; border: 1px solid #d6dfe4; border-left: 3px solid #4c829b;
      background: #f6f8fa; font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
    code { font-family: Consolas,'Microsoft YaHei',monospace; font-size: 0.91em; }
    :not(pre)>code { background: #f0f4f6; padding: 1px 3px; }
    .codecompare { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 16px 0 20px; break-inside: avoid; }
    .codecompare>div { min-width: 0; }
    .codecompare p { margin-bottom: 6px; font-size: 13px; }
    .codecompare pre { margin: 0; height: calc(100% - 28px); }
    .codecompare>div:last-child pre { border-left-color: #19807e; background: #f1f8f7; }
    p:has(>strong:only-child) { break-after: avoid; }
    blockquote { margin: 18px 0; padding: 0 17px; border-left: 3px solid #b5c9d3; color: #455762; }
    ul,ol { margin-top: 0; padding-left: 25px; }
    nav { padding: 16px 20px; background: #f5f8fa; margin: 25px 0; font-size: 13px; line-height: 1.65; }
    nav>strong { display: block; margin-bottom: 7px; }
    nav ul { list-style: none; padding: 0; columns: 2; margin: 0; }
    @media print {
      body { max-width: none; margin: 0; padding: 0; font-size: 10.3pt; line-height: 1.7; }
      h1 { font-size: 20pt; margin-bottom: 18pt; }
      h2 { font-size: 14pt; margin-top: 23pt; break-after: avoid; }
      h3 { font-size: 11.5pt; break-after: avoid; }
      p { margin-bottom: 9pt; orphans: 3; widows: 3; }
      img { max-height: 150mm; }
      table { font-size: 8.6pt; }
      th,td { padding: 4pt 5pt; }
      pre { font-size: 8.4pt; padding: 8pt 10pt; }
      figure p:last-child { font-size: 9pt; }
      nav { font-size: 9pt; padding: 10pt 12pt; }
      a { color: inherit; }
    }
    """
    # Insert the compact table of contents after the abstract and before chapter 1.
    first_chapter = re.search(r'<h2 id="[^"]+">1\s', body)
    if first_chapter:
        body = body[:first_chapter.start()] + '<nav><strong>目录</strong>' + md.toc + '</nav>' + body[first_chapter.start():]
    result = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>领域低代码生成实验报告</title><style>' + css + '</style></head><body>' + body + '</body></html>'
    target = ROOT / "实验报告.html"
    target.write_text(result, encoding="utf-8")
    print(f"HTML generated: {target.name}; {len(result):,} characters; all images embedded.")


if __name__ == "__main__":
    main()
