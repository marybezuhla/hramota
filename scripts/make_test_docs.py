"""Builds the DOCX files used to check rendering accuracy (app/src/test/resources/docs).

Made-up texts only. Each file exercises one area: fonts, paragraph formatting, lists, tables,
page flow. Run: python3 scripts/make_test_docs.py  (needs python-docx)
"""
import os
from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING
from docx.shared import Pt, Cm, RGBColor
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "docs")

UK = ("Постачальник зобов'язується передати Покупцеві товар належної якості в кількості та асортименті, "
      "визначених специфікацією, а Покупець — прийняти товар і своєчасно його оплатити. Ґрунтовна перевірка "
      "партії виконується протягом трьох робочих днів з дати отримання; виявлені недоліки фіксуються в акті.")
EN = ("The supplier undertakes to deliver goods of proper quality in the quantity and range set out in the "
      "specification, and the buyer undertakes to accept the goods and pay for them on time.")


def base(doc, font="Calibri", size=11):
    st = doc.styles["Normal"]
    st.font.name = font
    st.font.size = Pt(size)
    rpr = st.element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        fonts = OxmlElement("w:rFonts"); rpr.append(fonts)
    for a in ("w:ascii", "w:hAnsi", "w:cs", "w:eastAsia"):
        fonts.set(qn(a), font)
    pf = st.paragraph_format
    pf.space_after = Pt(8)
    pf.line_spacing = 1.08
    for s in doc.sections:
        s.page_width, s.page_height = Cm(21), Cm(29.7)
        s.left_margin = s.right_margin = Cm(2.5)
        s.top_margin = s.bottom_margin = Cm(2)


def fonts_doc():
    d = Document(); base(d)
    d.add_heading("Шрифти документа", level=1)
    for name in ["Calibri", "Cambria", "Arial", "Times New Roman", "Courier New"]:
        p = d.add_paragraph()
        r = p.add_run(f"{name}, 11 пт. "); r.bold = True; r.font.name = name
        r = p.add_run(UK); r.font.name = name
    p = d.add_paragraph()
    for size in (8, 10, 12, 14, 18, 24):
        r = p.add_run(f"Розмір {size} "); r.font.size = Pt(size)
    p = d.add_paragraph()
    p.add_run("Звичайний, ")
    p.add_run("жирний, ").bold = True
    p.add_run("курсив, ").italic = True
    p.add_run("підкреслений, ").underline = True
    r = p.add_run("закреслений, "); r.font.strike = True
    r = p.add_run("кольоровий, "); r.font.color.rgb = RGBColor(0xC0, 0x30, 0x20)
    r = p.add_run("x"); r2 = p.add_run("2"); r2.font.superscript = True
    p.add_run(" і H"); r3 = p.add_run("2"); r3.font.subscript = True; p.add_run("O.")
    d.add_paragraph(EN)
    d.save(os.path.join(OUT, "01-fonts.docx"))


def paragraphs_doc():
    d = Document(); base(d)
    d.add_heading("Форматування абзаців", level=1)
    for align, label in [(WD_ALIGN_PARAGRAPH.LEFT, "Ліворуч"), (WD_ALIGN_PARAGRAPH.CENTER, "По центру"),
                         (WD_ALIGN_PARAGRAPH.RIGHT, "Праворуч"), (WD_ALIGN_PARAGRAPH.JUSTIFY, "За шириною")]:
        p = d.add_paragraph(f"{label}. {UK}"); p.alignment = align
    p = d.add_paragraph(f"Відступ першого рядка 1,25 см. {UK}"); p.paragraph_format.first_line_indent = Cm(1.25)
    p = d.add_paragraph(f"Відступи 2 см зліва й справа. {UK}")
    p.paragraph_format.left_indent = Cm(2); p.paragraph_format.right_indent = Cm(2)
    p = d.add_paragraph(f"Виступ 1 см. {UK}")
    p.paragraph_format.left_indent = Cm(1); p.paragraph_format.first_line_indent = Cm(-1)
    for spacing, label in [(1.0, "одинарний"), (1.5, "полуторний"), (2.0, "подвійний")]:
        p = d.add_paragraph(f"Інтервал {label}. {UK}"); p.paragraph_format.line_spacing = spacing
    p = d.add_paragraph(f"Точно 14 пт. {UK}")
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.EXACTLY; p.paragraph_format.line_spacing = Pt(14)
    p = d.add_paragraph(f"Інтервал перед 18 пт і після 0. {UK}")
    p.paragraph_format.space_before = Pt(18); p.paragraph_format.space_after = Pt(0)
    p = d.add_paragraph("Табуляція:\tперша\tдруга\tтретя")
    d.save(os.path.join(OUT, "02-paragraphs.docx"))


def lists_doc():
    d = Document(); base(d)
    d.add_heading("Списки", level=1)
    for t in ["Перший пункт маркованого списку", "Другий пункт, достатньо довгий, щоб перейти на наступний рядок і показати виступ тексту", "Третій пункт"]:
        d.add_paragraph(t, style="List Bullet")
    for t in ["Узгодити специфікацію", "Підписати договір і акт приймання-передачі товару разом з усіма додатками", "Оплатити рахунок"]:
        d.add_paragraph(t, style="List Number")
    d.add_heading("Заголовок другого рівня", level=2)
    d.add_paragraph(UK)
    d.add_heading("Заголовок третього рівня", level=3)
    d.add_paragraph(EN)
    d.save(os.path.join(OUT, "03-lists.docx"))


def tables_doc():
    d = Document(); base(d)
    d.add_heading("Таблиці", level=1)
    t = d.add_table(rows=1, cols=4); t.style = "Table Grid"
    for c, h in zip(t.rows[0].cells, ["№", "Товар", "Кількість", "Ціна, грн"]):
        c.text = ""; c.paragraphs[0].add_run(h).bold = True
    rows = [("1", "Папір офісний А4", "10", "1 450,00"), ("2", "Ручки кулькові, сині, набір з 50 штук", "2", "640,00"),
            ("3", "Степлер настільний", "1", "389,00")]
    for r in rows:
        cells = t.add_row().cells
        for c, v in zip(cells, r): c.text = v
    last = t.add_row().cells
    m = last[0].merge(last[2]); m.text = "Разом"
    last[3].text = "2 479,00"
    d.add_paragraph()
    d.add_paragraph("Таблиця без рамок:")
    t2 = d.add_table(rows=2, cols=2)
    t2.cell(0, 0).text = "Постачальник"; t2.cell(0, 1).text = "Покупець"
    t2.cell(1, 0).text = "ТОВ «Приклад»\nм. Київ"; t2.cell(1, 1).text = "ФОП Зразок\nм. Львів"
    d.add_paragraph(UK)
    d.save(os.path.join(OUT, "04-tables.docx"))


def flow_doc():
    d = Document(); base(d, font="Times New Roman", size=12)
    d.add_heading("Договір поставки № 12", level=1).alignment = WD_ALIGN_PARAGRAPH.CENTER
    for i in range(1, 9):
        d.add_heading(f"{i}. Розділ договору", level=2)
        for _ in range(2):
            p = d.add_paragraph(UK + " " + UK); p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            p.paragraph_format.first_line_indent = Cm(1.25)
    p = d.add_paragraph("Додаток 1")
    p.runs[0].add_break(WD_BREAK.PAGE)
    d.add_paragraph("Специфікація товару наведена на окремій сторінці після розриву.")
    d.save(os.path.join(OUT, "05-flow.docx"))


def long_table_doc():
    """A comparison table (old text | new text) whose rows are taller than a page."""
    d = Document(); base(d, font="Times New Roman", size=12)
    p = d.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("ПОРІВНЯЛЬНА ТАБЛИЦЯ"); r.bold = True
    t = d.add_table(rows=1, cols=2); t.style = "Table Grid"
    t.cell(0, 0).text = "Зміст положення чинного акта"; t.cell(0, 1).text = "Зміст відповідного положення проєкту"
    for k in range(1, 4):
        cells = t.add_row().cells
        for c, mark in zip(cells, ("", " (нова редакція)")):
            c.text = ""
            c.paragraphs[0].add_run(f"Стаття {k}.{mark}").bold = True
            for j in range(14):
                c.add_paragraph(f"{j + 1}) {UK}")
    last = t.add_row().cells
    last[0].text = "Останній рядок"; last[1].text = "КІНЕЦЬ ТАБЛИЦІ"
    d.save(os.path.join(OUT, "06-long-table.docx"))


def field(paragraph, code):
    """A field (PAGE, NUMPAGES) as Word writes it: begin, instruction, separate, a saved result, end."""
    def char(kind):
        r = paragraph.add_run(); e = OxmlElement("w:fldChar"); e.set(qn("w:fldCharType"), kind); r._r.append(e)
    char("begin")
    r = paragraph.add_run(); t = OxmlElement("w:instrText"); t.set(qn("xml:space"), "preserve"); t.text = f" {code} "; r._r.append(t)
    char("separate")
    paragraph.add_run("1")
    char("end")


def headers_doc():
    """Header and footer with page numbers; the first page has its own (title page)."""
    d = Document(); base(d, font="Times New Roman", size=12)
    s = d.sections[0]
    s.different_first_page_header_footer = True
    s.header.paragraphs[0].text = "ТОВ «Вигадана компанія» · Договір № 12"
    s.header.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    f = s.footer.paragraphs[0]; f.alignment = WD_ALIGN_PARAGRAPH.CENTER
    f.add_run("Сторінка "); field(f, "PAGE"); f.add_run(" з "); field(f, "NUMPAGES")
    s.first_page_header.paragraphs[0].text = "Титульна сторінка"
    s.first_page_header.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER
    d.add_heading("Договір поставки № 12", level=1).alignment = WD_ALIGN_PARAGRAPH.CENTER
    for i in range(1, 7):
        d.add_heading(f"{i}. Розділ договору", level=2)
        for _ in range(2):
            p = d.add_paragraph(UK + " " + UK); p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    d.save(os.path.join(OUT, "07-headers.docx"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    fonts_doc(); paragraphs_doc(); lists_doc(); tables_doc(); flow_doc(); long_table_doc(); headers_doc()
    print("written to", os.path.abspath(OUT))
