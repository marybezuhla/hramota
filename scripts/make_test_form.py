# A small PDF form for the device test: a text field, a check box, radio buttons and a drop-down.
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
import sys
c = canvas.Canvas(sys.argv[1], pagesize=A4)
c.setTitle("Form test")
c.drawString(72, 780, "Application form")
form = c.acroForm
c.drawString(72, 740, "Name:")
form.textfield(name="name", tooltip="Full name", x=150, y=730, width=300, height=22, value="")
c.drawString(72, 700, "Agree:")
form.checkbox(name="agree", tooltip="I agree", x=150, y=695, size=18, checked=False, buttonStyle="check")
c.drawString(72, 660, "Size:")
form.radio(name="size", tooltip="Size", value="small", selected=True, x=150, y=655, size=16)
form.radio(name="size", tooltip="Size", value="large", selected=False, x=200, y=655, size=16)
c.drawString(72, 620, "City:")
form.choice(name="city", tooltip="City", value="Kyiv", options=["Kyiv", "Lviv", "Odesa"], x=150, y=612, width=150, height=22)
c.showPage()
c.save()
