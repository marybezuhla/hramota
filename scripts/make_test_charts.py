"""Test files with charts: a slide with a column chart and a pie, a workbook with a line chart.
Run: python3 scripts/make_test_charts.py app/src/test/resources/charts"""
import sys, pathlib
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.enum.chart import XL_CHART_TYPE
from pptx.util import Inches
from openpyxl import Workbook
from openpyxl.chart import LineChart, Reference

out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
prs = Presentation()
s = prs.slides.add_slide(prs.slide_layouts[5])
s.shapes.title.text = "Продажі"
d = CategoryChartData(); d.categories = ["Січень", "Лютий", "Березень"]
d.add_series("Київ", (12.0, 15.5, 9.0)); d.add_series("Львів", (8.0, 11.0, 14.0))
s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(0.5), Inches(1.5), Inches(5), Inches(4), d)
p = CategoryChartData(); p.categories = ["Так", "Ні", "Не знаю"]; p.add_series("Відповіді", (60, 30, 10))
s.shapes.add_chart(XL_CHART_TYPE.PIE, Inches(5.6), Inches(1.5), Inches(4), Inches(4), p)
prs.save(out / "charts.pptx")

wb = Workbook(); ws = wb.active; ws.title = "Дані"
ws.append(["Місяць", "Витрати"])
for m, v in [("Січень", 100), ("Лютий", 140), ("Березень", 90), ("Квітень", 160)]: ws.append([m, v])
ch = LineChart(); ch.title = "Витрати"
ch.add_data(Reference(ws, min_col=2, min_row=1, max_row=5), titles_from_data=True)
ch.set_categories(Reference(ws, min_col=1, min_row=2, max_row=5))
ws.add_chart(ch, "D2")
wb.save(out / "charts.xlsx")
