#!/usr/bin/env python3
"""Made-up test presentations (no personal data) in app/src/test/resources/slides.
The .ppt copy is made by LibreOffice: soffice --headless --convert-to ppt 01-project.pptx"""
import io, os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from PIL import Image, ImageDraw

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "slides")
os.makedirs(out, exist_ok=True)
prs = Presentation()
# No author from the library's template
prs.core_properties.author = ""
prs.core_properties.last_modified_by = ""
prs.slide_width = Inches(13.333); prs.slide_height = Inches(7.5)

s = prs.slides.add_slide(prs.slide_layouts[0])
s.shapes.title.text = "Проєкт «Грамота»"
s.placeholders[1].text = "Підсумки кварталу · жовтень 2026"

s = prs.slides.add_slide(prs.slide_layouts[1])
s.shapes.title.text = "Що зроблено"
tf = s.placeholders[1].text_frame
tf.text = "Перегляд документів Word сторінками"
for text, level in [("DOCX і старий DOC", 1), ("Пошук і виділення тексту", 1), ("Таблиці Excel", 0), ("Порівняння версій", 0), ("Тисяча сторінок — за секунди", 1)]:
    p = tf.add_paragraph(); p.text = text; p.level = level

s = prs.slides.add_slide(prs.slide_layouts[5])
s.shapes.title.text = "Фігури й малюнок"
r = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(2), Inches(4), Inches(2))
r.fill.solid(); r.fill.fore_color.rgb = RGBColor(0x2F, 0x55, 0x97); r.text_frame.text = "Швидко"
r.text_frame.paragraphs[0].runs[0].font.size = Pt(28); r.text_frame.paragraphs[0].runs[0].font.bold = True
e = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(5.3), Inches(2), Inches(2.5), Inches(2.5))
e.fill.solid(); e.fill.fore_color.rgb = RGBColor(0xED, 0x7D, 0x31); e.text_frame.text = "Безпечно"
img = Image.new("RGB", (400, 260), (226, 239, 218)); d = ImageDraw.Draw(img)
for i in range(0, 400, 40): d.rectangle([i, 260 - (i // 2 + 40), i + 30, 260], fill=(84, 130, 53))
buf = io.BytesIO(); img.save(buf, "PNG"); buf.seek(0)
s.shapes.add_picture(buf, Inches(8.4), Inches(2), Inches(4))
tb = s.shapes.add_textbox(Inches(0.8), Inches(5.6), Inches(11.5), Inches(1))
tb.text_frame.text = "Текстове поле праворуч вирівняне й курсивом"
tb.text_frame.paragraphs[0].alignment = PP_ALIGN.RIGHT; tb.text_frame.paragraphs[0].runs[0].font.italic = True

s = prs.slides.add_slide(prs.slide_layouts[5])
s.shapes.title.text = "Таблиця"
rows, cols = 4, 3
t = s.shapes.add_table(rows, cols, Inches(1), Inches(2), Inches(11), Inches(3)).table
data = [["Формат", "Перегляд", "Пошук"], ["DOCX", "сторінками", "так"], ["XLSX", "сітка", "так"], ["PPTX", "слайди", "так"]]
for i in range(rows):
    for j in range(cols): t.cell(i, j).text = data[i][j]
prs.save(os.path.join(out, "01-project.pptx"))
