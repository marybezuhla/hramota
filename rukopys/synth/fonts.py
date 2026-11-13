"""Fetches the handwriting fonts the made-up writing is drawn with: Google Fonts' families under the
SIL Open Font License that have every Ukrainian letter. Each font's file, licence and checksum are
written down (fonts.json) beside the model, so what it was trained on can be checked.

  python3 rukopys/synth/fonts.py fonts/
"""
import hashlib
import json
import os
import shutil
import sys
import urllib.request

from fontTools.ttLib import TTFont

# Families with Cyrillic in hand-written styles (printed letters, joined-up and in between)
HAND = ["caveat", "caveatbrush", "badscript", "marckscript", "neucha", "pangolin", "amaticsc", "shantellsans",
        "comforter", "underdog", "lobster", "greatvibes", "kurale", "pattaya", "gabriela"]
# Printed letters too: many people write so
PRINT_SYSTEM = ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf"]
NEEDED = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюяАБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ0123456789"
API = "https://api.github.com/repos/google/fonts/contents/ofl/"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "hramota-rukopys"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def covers(path):
    cmap = TTFont(path).getBestCmap()
    return all(ord(c) in cmap for c in NEEDED)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    lic = os.path.join(out, "licenses")
    os.makedirs(lic, exist_ok=True)
    record = []
    for family in HAND:
        try:
            listing = json.loads(get(API + family))
        except Exception as e:
            print("skipped", family, e)
            continue
        files = [f for f in listing if f["name"].lower().endswith((".ttf", ".otf")) and "italic" not in f["name"].lower()]
        if not files:
            continue
        f = files[0]
        data = get(f["download_url"])
        path = os.path.join(out, "hand-" + family + os.path.splitext(f["name"])[1])
        open(path, "wb").write(data)
        if not covers(path):
            print("no Ukrainian letters:", family)
            os.remove(path)
            continue
        for x in listing:
            if x["name"] == "OFL.txt":
                open(os.path.join(lic, family + "-OFL.txt"), "wb").write(get(x["download_url"]))
        record.append({"file": os.path.basename(path), "source": f["download_url"], "sha256": hashlib.sha256(data).hexdigest(), "license": "OFL-1.1"})
        print("font", family, f["name"])
    for p in PRINT_SYSTEM:
        if os.path.exists(p) and covers(p):
            dst = os.path.join(out, "print-" + os.path.basename(p))
            shutil.copy(p, dst)
            record.append({"file": os.path.basename(dst), "source": "Ubuntu fonts-dejavu-core", "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest(), "license": "Bitstream Vera / DejaVu"})
    json.dump(record, open(os.path.join(out, "fonts.json"), "w"), indent=1)
    print(len(record), "fonts")


if __name__ == "__main__":
    main()
