"""Compares Hramota's page renders with LibreOffice's (both 150 dpi PNG) and writes a report.

python3 scripts/compare_render.py <ours dir> <libreoffice dir> <out dir>
Pages are matched by document name and page number. For each page: share of "ink" pixels that
differ (lower is better) and a picture: ours | LibreOffice | overlay (red = only ours, blue = only
LibreOffice, black = both).
"""
import os, re, sys
from PIL import Image, ImageChops

ours_dir, ref_dir, out_dir = sys.argv[1:4]
os.makedirs(out_dir, exist_ok=True)

def pages(d):
    found = {}
    for f in os.listdir(d):
        m = re.match(r"(.+)-0*(\d+)\.png$", f)
        if m: found.setdefault(m.group(1), {})[int(m.group(2))] = os.path.join(d, f)
    return found

def ink(path, size=None):
    im = Image.open(path).convert("L")
    if size and im.size != size: im = im.resize(size)
    return im.point(lambda v: 255 if v < 160 else 0)

ours, ref = pages(ours_dir), pages(ref_dir)
rows = ["| Документ | Сторінок (наш / LO) | Сторінка | Розбіжність |", "|---|---|---|---|"]
for doc in sorted(set(ours) | set(ref)):
    a, b = ours.get(doc, {}), ref.get(doc, {})
    for n in sorted(set(a) | set(b)):
        if n not in a or n not in b:
            rows.append(f"| {doc} | {len(a)} / {len(b)} | {n} | немає пари |"); continue
        ia = ink(a[n]); ib = ink(b[n], ia.size)
        diff = ImageChops.logical_xor(ia.convert("1"), ib.convert("1"))
        union = ImageChops.logical_or(ia.convert("1"), ib.convert("1"))
        u = union.histogram()[-1]
        dv = diff.histogram()[-1]
        score = dv / u if u else 0.0
        rows.append(f"| {doc} | {len(a)} / {len(b)} | {n} | {score:.1%} |")
        w, h = ia.size
        ba, bb = ia.convert("1"), ib.convert("1")
        both = ImageChops.logical_and(ba, bb)
        only_a = ImageChops.logical_and(ba, ImageChops.invert(bb))
        only_b = ImageChops.logical_and(bb, ImageChops.invert(ba))
        overlay = Image.new("RGB", (w, h), "white")
        overlay.paste((220, 40, 40), mask=only_a)
        overlay.paste((40, 90, 220), mask=only_b)
        overlay.paste((0, 0, 0), mask=both)
        sheet = Image.new("RGB", (w * 3 + 40, h + 20), (150, 150, 150))
        sheet.paste(Image.open(a[n]).convert("RGB"), (10, 10))
        sheet.paste(Image.open(b[n]).convert("RGB").resize((w, h)), (w + 20, 10))
        sheet.paste(overlay, (2 * w + 30, 10))
        sheet.resize((sheet.width // 2, sheet.height // 2)).save(os.path.join(out_dir, f"{doc}-{n}.png"))
with open(os.path.join(out_dir, "report.md"), "w") as f:
    f.write("# Точність рендеру проти LibreOffice\n\n" + "\n".join(rows) + "\n")
print("\n".join(rows))
