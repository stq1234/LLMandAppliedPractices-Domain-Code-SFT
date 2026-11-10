"""Render disposable page previews and validate the generated PDF's basic contents."""
from pathlib import Path
import json

import fitz
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
PDF = ROOT / 'output/pdf/实验报告.pdf'
OUT = ROOT / 'tmp/pdf_qa'
OUT.mkdir(parents=True, exist_ok=True)
doc = fitz.open(PDF)
pages = []
for number, page in enumerate(doc):
    text = page.get_text()
    assert len(text.strip()) > 20, number + 1
    pix = page.get_pixmap(dpi=70, alpha=False)
    image = Image.frombytes('RGB', [pix.width, pix.height], pix.samples)
    pages.append(image)
    page.get_pixmap(dpi=120, alpha=False).save(OUT / f'page-{number+1:02d}.png')
for first in range(0, len(pages), 6):
    group = pages[first:first+6]
    width, height = pages[0].size
    canvas = Image.new('RGB', (width*2, (height+28)*3), '#d9e0e6')
    draw = ImageDraw.Draw(canvas)
    for index, image in enumerate(group):
        x, y = index % 2 * width, index // 2 * (height+28)
        draw.text((x+10,y+5), f'Page {first+index+1}', fill='black')
        canvas.paste(image,(x,y+28))
    canvas.save(OUT / f'contact-{first//6+1:02d}.png')
full = '\n'.join(page.get_text() for page in doc)
for expected in ['相关工作','需求生成提示','StatsUtils','DistributedBarrier','附录','76.82%']:
    assert expected in full, expected
result = {'page_count':len(doc), 'pdf_bytes':PDF.stat().st_size,
          'all_pages_have_text':True, 'contact_sheets':(len(pages)+5)//6,
          'figure_caption_pages': {f'图 {n}':[i+1 for i,p in enumerate(doc) if f'图 {n}' in p.get_text()] for n in range(1,9)}}
(ROOT / 'analysis/pdf_validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
