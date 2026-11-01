"""Builds the small PDFs for the PDF engine tests (app/src/androidTest/assets).

sample.pdf: two pages with text, a web link, a document JavaScript action and an embedded file
(the engine must report them and never run them). broken.pdf: a damaged file that must fail cleanly.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets")

def build(objects):
    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for o in offsets:
        out += f"{o:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info 9 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)

def stream(data, extra=b""):
    return b"<< /Length " + str(len(data)).encode() + extra + b" >>\nstream\n" + data + b"\nendstream"

page_text = b"BT /F1 36 Tf 72 700 Td (Hramota test page) Tj ET 0 0 1 rg 72 600 300 40 re f"
objects = [
    b"<< /Type /Catalog /Pages 2 0 R /Names << /JavaScript 7 0 R /EmbeddedFiles 10 0 R >> >>",          # 1
    b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",                                                 # 2
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 6 0 R >> >> /Annots [11 0 R] >>",  # 3
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 792 612] /Contents 12 0 R /Resources << /Font << /F1 6 0 R >> >> >>",  # 4 (landscape)
    stream(page_text),                                                                                    # 5
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",                                            # 6
    b"<< /Names [(hello) 8 0 R] >>",                                                                      # 7
    b"<< /S /JavaScript /JS (app.alert\\('this must never run'\\);) >>",                                  # 8
    b"<< /Title (Test) /Author (Test Author) /Creator (make_test_pdf.py) >>",                             # 9
    b"<< /Names [(notes.txt) 13 0 R] >>",                                                                 # 10
    b"<< /Type /Annot /Subtype /Link /Rect [72 600 372 640] /Border [0 0 0] /A << /S /URI /URI (https://example.org/) >> >>",  # 11
    stream(b"BT /F1 24 Tf 72 500 Td (Second page) Tj ET"),                                               # 12
    b"<< /Type /Filespec /F (notes.txt) /UF (notes.txt) /EF << /F 14 0 R >> >>",                         # 13
    stream(b"hello", b" /Type /EmbeddedFile"),                                                            # 14
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    good = build(objects)
    open(os.path.join(OUT, "sample.pdf"), "wb").write(good)
    # Damaged: a valid start, then garbage where the objects and the cross-reference table should be
    open(os.path.join(OUT, "broken.pdf"), "wb").write(good[:300] + bytes((i * 37) % 256 for i in range(4000)))
    print("written to", os.path.abspath(OUT))
