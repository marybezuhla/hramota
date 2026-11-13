"""Р1 against Р2 on the held-out lines (fonts never trained on): the share of letters and words read
wrong by each. Р1 is read as Hramota reads it: the strokes drawn black on white and read by the
PP-OCRv5 Cyrillic model built into Hramota; Р2 by the new model from the strokes themselves.

  python3 rukopys/eval/compare.py out/ > out/report.json
"""
import json
import math
import os
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
sys.path.insert(0, os.path.join(HERE, "..", "train"))
import features  # noqa: E402

LOOK = dict(zip("ABCEHIKMOPTXYacikmopxyzZ", "АВСЕНІКМОРТХУасікмортхузЗ"))


def to_cyrillic(text):
    """As Hramota's Lookalikes.toCyrillic: words only in look-alike letters read as Cyrillic."""
    out = []
    for w in text.split(" "):
        letters = [c for c in w if c.isalpha()]
        out.append("".join(LOOK.get(c, c) for c in w) if letters and all(c in LOOK for c in letters) else w)
    return " ".join(out)


def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def render(strokes):
    """As InkText.render: black strokes on white, round ends, a margin; only the part written on."""
    pts = np.concatenate([np.asarray(s) for s in strokes])
    k, margin = 2.0, 24
    x0, y0 = pts.min(0)
    x1, y1 = pts.max(0)
    w, h = int(math.ceil((x1 - x0) * k + 2 * margin)), int(math.ceil((y1 - y0) * k + 2 * margin))
    img = Image.new("L", (max(w, 32), max(h, 32)), 255)
    d = ImageDraw.Draw(img)
    width = int(round(min(6, max(2.5, 2.2 * k))))
    for s in strokes:
        s = [((p[0] - x0) * k + margin, (p[1] - y0) * k + margin) for p in s]
        if len(s) == 1:
            d.ellipse([s[0][0] - width / 2, s[0][1] - width / 2, s[0][0] + width / 2, s[0][1] + width / 2], fill=0)
        else:
            d.line(s, fill=0, width=width, joint="curve")
    return img


def r1_reader():
    sess = ort.InferenceSession(os.path.join(ROOT, "app/src/main/assets/ocr/rec_cyrillic.onnx"))
    chars = open(os.path.join(ROOT, "app/src/main/assets/ocr/rec_cyrillic_dict.txt"), encoding="utf-8").read().split("\n")[:-1] + [" "]

    def read(img):
        # The line cut out as detection would: the writing with a small margin, 48 px high
        a = np.asarray(img)
        ys, xs = np.nonzero(a < 128)
        if not len(xs):
            return ""
        crop = img.crop((max(0, xs.min() - 6), max(0, ys.min() - 6), xs.max() + 7, ys.max() + 7))
        w = max(16, min(3200, int(round(crop.width * 48 / crop.height))))
        crop = crop.resize((w, 48), Image.BILINEAR).convert("RGB")
        x = np.asarray(crop, np.float32) / 127.5 - 1.0
        x = x.transpose(2, 0, 1)[None]
        p = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
        best = p.argmax(-1)
        out, prev = [], 0
        for b in best:
            if b != prev and b != 0:
                out.append(chars[b - 1] if b - 1 < len(chars) else "")
            prev = b
        return to_cyrillic("".join(out).strip())
    return read


def r2_reader(folder):
    sess = ort.InferenceSession(os.path.join(folder, "rukopys.onnx"))
    chars = open(os.path.join(folder, "rukopys_alphabet.txt"), encoding="utf-8").read().split("\n")[:-1]

    def read(strokes):
        f = features.features([np.asarray(s) for s in strokes])
        p = sess.run(None, {"points": f[None].astype(np.float32)})[0][0]
        best = p.argmax(-1)
        out, prev = [], 0
        for b in best:
            if b != prev and b != 0:
                out.append(chars[b - 1])
            prev = b
        return "".join(out).strip()
    return read


def main():
    folder = sys.argv[1]
    test = np.load(os.path.join(folder, "test.npy"), allow_pickle=True)
    r1, r2 = r1_reader(), r2_reader(folder)
    score = {"r1": [0, 0, 0], "r2": [0, 0, 0]}
    letters = words = 0
    examples = []
    for i, (text, strokes) in enumerate(test):
        text = text.replace("’", "'")
        got1 = r1(render(strokes)).replace("’", "'")
        got2 = r2(strokes).replace("’", "'")
        for k, got in (("r1", got1), ("r2", got2)):
            score[k][0] += distance(got, text)
            score[k][1] += distance(got.split(), text.split())
            score[k][2] += got == text
        letters += len(text)
        words += len(text.split())
        if i < 40:
            examples.append({"text": text, "r1": got1, "r2": got2})
    report = {k: {"letters_wrong": v[0] / letters, "words_wrong": v[1] / words, "lines_right": v[2] / len(test)} for k, v in score.items()}
    report["lines"] = len(test)
    report["r2_better"] = report["r2"]["letters_wrong"] < report["r1"]["letters_wrong"]
    report["note"] = "made-up writing (fonts traced into strokes) in fonts never trained on; real hands are checked on each phone"
    report["examples"] = examples
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
