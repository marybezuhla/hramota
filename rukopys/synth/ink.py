"""Writing made up from fonts: a line of text drawn with a handwriting font, thinned to one-pixel
lines and traced back into pen strokes, then bent the way hands bend writing.

The traced strokes follow the letters' shapes, not a person's real movements: the pieces are
joined at crossings where the line goes on most straight, written left to right, sometimes
reversed, so the model does not rely on the order or direction of the pen.
"""
import math
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from skimage.morphology import skeletonize

NB = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def render(text, font_path, size):
    font = ImageFont.truetype(font_path, size)
    left, top, right, bottom = font.getbbox(text)
    w, h = right - left + 2 * size, bottom - top + 2 * size
    img = Image.new("L", (max(w, 8), max(h, 8)), 0)
    ImageDraw.Draw(img).text((size - left, size - top), text, font=font, fill=255)
    return np.asarray(img) > 127


def trace(mask, min_size=48):
    """The thinned picture as paths of (x, y) points between ends and crossings."""
    sk = skeletonize(mask)
    ys, xs = np.nonzero(sk)
    on = set(zip(ys.tolist(), xs.tolist()))
    if not on:
        return []

    def nbrs(p):
        y, x = p
        return [(y + dy, x + dx) for dy, dx in NB if (y + dy, x + dx) in on]

    degree = {p: len(nbrs(p)) for p in on}
    nodes = {p for p, d in degree.items() if d != 2}
    used = set()
    paths = []

    def walk(a, b):
        path = [a, b]
        used.add(frozenset((a, b)))
        prev, cur = a, b
        while cur not in nodes:
            nxt = [n for n in nbrs(cur) if n != prev and frozenset((cur, n)) not in used]
            if not nxt:
                break
            n = nxt[0]
            used.add(frozenset((cur, n)))
            path.append(n)
            prev, cur = cur, n
            if cur == a:
                break
        return path

    for a in sorted(nodes, key=lambda p: (p[1], p[0])):
        for b in nbrs(a):
            if frozenset((a, b)) not in used:
                paths.append(walk(a, b))
    # Loops with no end (an "о")
    for p in sorted(on, key=lambda q: (q[1], q[0])):
        for b in nbrs(p):
            if frozenset((p, b)) not in used:
                paths.append(walk(p, b))
    # Little spurs thinning leaves at corners: a short piece from a crossing to a free end
    spur = max(4, int(0.15 * min_size))
    def is_spur(path):
        if len(path) >= spur:
            return False
        a, b = path[0], path[-1]
        return (degree[a] >= 3 and degree[b] == 1) or (degree[b] >= 3 and degree[a] == 1)
    paths = [p for p in paths if not is_spur(p)]
    # Links inside a crossing (its pixels next to one another): not strokes of their own
    paths = [p for p in paths if not (len(p) <= 3 and degree[p[0]] >= 3 and degree[p[-1]] >= 3)]
    # Single dots
    for p in on:
        if degree[p] == 0:
            paths.append([p])
    return [np.array([(x, y) for y, x in path], dtype=np.float64) for path in paths if len(path) >= 1]


def direction(path, at_end, k=6):
    pts = path[-k:] if at_end else path[:k][::-1]
    v = pts[-1] - pts[0]
    n = math.hypot(*v)
    return v / n if n > 0 else np.zeros(2)


def chain(paths):
    """Pieces joined into pen strokes: at each end the piece that goes on most straight."""
    paths = [p for p in paths if len(p)]
    tiny = [p for p in paths if len(p) < 3]
    paths = [p for p in paths if len(p) >= 3]
    strokes = []
    left = sorted(paths, key=lambda p: p[:, 0].min())
    while left:
        cur = left.pop(0)
        if cur[0][0] > cur[-1][0]:
            cur = cur[::-1]
        while True:
            end = cur[-1]
            d = direction(cur, True)
            best, best_cos, best_rev = None, -0.6, False
            for i, p in enumerate(left):
                for rev in (False, True):
                    q = p[::-1] if rev else p
                    if np.abs(q[0] - end).max() <= 3.5:
                        c = float(np.dot(d, -direction(q, False)))
                        if c > best_cos:
                            best, best_cos, best_rev = i, c, rev
            if best is None:
                break
            q = left.pop(best)
            q = q[::-1] if best_rev else q
            cur = np.concatenate([cur, q[1:]])
        strokes.append(cur)
    strokes += tiny
    strokes.sort(key=lambda s: s[:, 0].min())
    return strokes


def smooth(s, k=3):
    if len(s) < 2 * k + 1:
        return s
    kernel = np.ones(k) / k
    x = np.convolve(s[:, 0], kernel, mode="same")
    y = np.convolve(s[:, 1], kernel, mode="same")
    x[:k] = s[:k, 0]; y[:k] = s[:k, 1]; x[-k:] = s[-k:, 0]; y[-k:] = s[-k:, 1]
    return np.stack([x, y], axis=1)


def bend(strokes, rnd):
    """A hand's ways: slant, a tilt, letters wider or narrower, a wavy baseline, a shaky pen."""
    shear = rnd.uniform(-0.35, 0.25)
    angle = math.radians(rnd.uniform(-4, 4))
    sx = rnd.uniform(0.8, 1.25)
    wave_a, wave_f = rnd.uniform(0, 3.0), rnd.uniform(0.002, 0.02)
    jitter = rnd.uniform(0.0, 0.8)
    out = []
    for s in strokes:
        x, y = s[:, 0] * sx, s[:, 1]
        x = x - shear * y
        y = y + wave_a * np.sin(x * wave_f * 2 * math.pi)
        xr = x * math.cos(angle) - y * math.sin(angle)
        yr = x * math.sin(angle) + y * math.cos(angle)
        pts = np.stack([xr, yr], axis=1)
        if jitter > 0:
            pts = pts + np.random.default_rng(rnd.randrange(1 << 30)).normal(0, jitter, pts.shape)
        out.append(pts)
    return out


def write(text, font_path, rnd):
    """Strokes (list of [k, 2] arrays, y down) of [text] written with the font, bent at random."""
    size = rnd.choice([40, 48, 56, 64])
    strokes = chain(trace(render(text, font_path, size), size))
    strokes = [smooth(s) for s in strokes]
    # Some strokes written the other way, and a dot or accent sometimes left for the end of the word
    strokes = [s[::-1] if rnd.random() < 0.2 else s for s in strokes]
    if rnd.random() < 0.5:
        small = [s for s in strokes if len(s) < 12]
        big = [s for s in strokes if len(s) >= 12]
        strokes = big + small
    return bend(strokes, rnd)
