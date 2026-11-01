#!/usr/bin/env python3
"""Made-up test workbooks (no personal data): a budget with formats and a report with merges, dates and frozen panes.
Writes app/src/test/resources/sheets/*.xlsx; the .xls copies are made by LibreOffice (see the comment at the end)."""
import datetime, os
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "sheets")
os.makedirs(out, exist_ok=True)
thin = Side(style="thin", color="000000")
box = Border(left=thin, right=thin, top=thin, bottom=thin)

wb = Workbook()
ws = wb.active
ws.title = "Бюджет"
ws["A1"] = "Сімейний бюджет 2026"; ws["A1"].font = Font(bold=True, size=14, color="1F4E79")
ws.merge_cells("A1:E1")
head = ["Місяць", "Продукти", "Транспорт", "Комунальні", "Разом"]
for i, h in enumerate(head):
    c = ws.cell(row=3, column=i + 1, value=h)
    c.font = Font(bold=True, color="FFFFFF"); c.fill = PatternFill("solid", fgColor="2F5597"); c.alignment = Alignment(horizontal="center"); c.border = box
months = ["Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень"]
data = [(6200, 1100.5, 3400), (5900, 1050, 3600.25), (6400, 1200, 2900), (6100, 980, 2100), (6600, 1300, 1800), (9800, 4200, 1700)]
for r, (m, (a, b, c)) in enumerate(zip(months, data)):
    row = 4 + r
    ws.cell(row=row, column=1, value=m).border = box
    for k, v in enumerate((a, b, c)):
        cell = ws.cell(row=row, column=2 + k, value=v); cell.number_format = "#,##0.00"; cell.border = box
    t = ws.cell(row=row, column=5, value=f"=SUM(B{row}:D{row})"); t.number_format = "#,##0.00"; t.border = box; t.font = Font(bold=True)
ws.cell(row=11, column=1, value="Частка продуктів").font = Font(italic=True)
p = ws.cell(row=11, column=2, value=0.4123); p.number_format = "0.0%"
ws.cell(row=12, column=1, value="Дата звіту")
d = ws.cell(row=12, column=2, value=datetime.date(2026, 7, 1)); d.number_format = "dd.mm.yyyy"
ws.cell(row=13, column=1, value="Довгий коментар, що не вміщається в клітинку і переходить у сусідні порожні клітинки")
ws.cell(row=14, column=1, value="Перенесення в клітинці").alignment = Alignment(wrap_text=True)
ws.row_dimensions[14].height = 32
ws.column_dimensions["A"].width = 18
for col in "BCDE": ws.column_dimensions[col].width = 13
ws.freeze_panes = "B4"

ws2 = wb.create_sheet("Звіт")
ws2["A1"] = "Звіт про виконання"; ws2["A1"].font = Font(bold=True, size=13)
ws2.merge_cells("A1:D2"); ws2["A1"].alignment = Alignment(horizontal="center", vertical="center")
for i, h in enumerate(["№", "Завдання", "Строк", "Стан"]):
    c = ws2.cell(row=3, column=i + 1, value=h); c.font = Font(bold=True); c.fill = PatternFill("solid", fgColor="E2EFDA")
for i in range(1, 41):
    ws2.cell(row=3 + i, column=1, value=i)
    ws2.cell(row=3 + i, column=2, value=f"Завдання номер {i}")
    ws2.cell(row=3 + i, column=3, value=datetime.date(2026, 1, 1) + datetime.timedelta(days=7 * i)).number_format = "d mmmm yyyy"
    s = ws2.cell(row=3 + i, column=4, value="виконано" if i % 3 else "у роботі")
    if i % 3 == 0: s.font = Font(color="C00000")
ws2.column_dimensions["B"].width = 26; ws2.column_dimensions["C"].width = 18
wb.save(os.path.join(out, "01-budget.xlsx"))
# Then saved again by LibreOffice (so formulas carry their values) and copied to .xls:
#   soffice --headless --convert-to xlsx --outdir /tmp/x 01-budget.xlsx; cp /tmp/x/01-budget.xlsx .
#   soffice --headless --convert-to xls 01-budget.xlsx
