"""Makes docs/08-wrap.docx: a picture floating at the right with text flowing around it (square wrap).

Run from the repository root: python3 scripts/make_test_wrap.py
"""
import io
import re
import zipfile

from docx import Document
from docx.shared import Pt
from PIL import Image, ImageDraw

OUT = "app/src/test/resources/docs/08-wrap.docx"

img = Image.new("RGB", (300, 200), (70, 130, 180))
d = ImageDraw.Draw(img)
d.rectangle([20, 20, 280, 180], outline=(255, 255, 255), width=6)
buf = io.BytesIO()
img.save(buf, "PNG")
buf.seek(0)

doc = Document()
doc.add_heading("Обтікання малюнка", level=1)
p = doc.add_paragraph()
run = p.add_run()
run.add_picture(buf, width=Pt(150))
text = ("Текст обтікає малюнок праворуч: рядки поруч із ним коротші, а нижче малюнка знову на всю ширину. "
        "Так Word розташовує плаваючі малюнки з обтіканням «квадратом». ") * 6
p.add_run(text)
doc.add_paragraph("Наступний абзац іде вже під малюнком або поруч із ним, якщо малюнок вищий за попередній абзац. " * 3)
raw = io.BytesIO()
doc.save(raw)

# The picture made floating: inline → anchor at the right of the column, text on both sides
src = zipfile.ZipFile(io.BytesIO(raw.getvalue()))
out = zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED)
for item in src.infolist():
    data = src.read(item.filename)
    if item.filename == "word/document.xml":
        x = data.decode("utf-8")
        m = re.search(r'<wp:inline([^>]*)>(.*?)</wp:inline>', x, re.S)
        inner = m.group(2)
        ns = " ".join(re.findall(r'xmlns:\w+="[^"]*"', m.group(1)))
        extent = re.search(r'<wp:extent [^>]*/>', inner).group(0)
        rest = inner.replace(extent, "")
        anchor = ('<wp:anchor ' + ns + ' distT="0" distB="0" distL="114300" distR="114300" simplePos="0" relativeHeight="1" '
                  'behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/>'
                  '<wp:positionH relativeFrom="column"><wp:align>right</wp:align></wp:positionH>'
                  '<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>'
                  + extent + '<wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapSquare wrapText="bothSides"/>'
                  + re.sub(r'<wp:effectExtent[^>]*/>', '', rest) + '</wp:anchor>')
        x = x[:m.start()] + anchor + x[m.end():]
        data = x.encode("utf-8")
    out.writestr(item, data)
out.close()
print("wrote", OUT)
