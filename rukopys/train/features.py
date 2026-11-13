"""What the model reads: strokes turned into a sequence of small moves.

The same steps are done in Hramota (app/src/main/java/ua/hramota/ink/Rukopys.kt); any change here
must be made there too, and the model trained again.

1. The size of the writing: the height between the 5th and 95th percentile of all points' y
   (at least a tiny number), so that one long tail does not shrink the rest.
2. Points scaled by it: x from the leftmost point, y from the median.
3. Each stroke resampled along its path every STEP (in those units); a stroke shorter than that is
   one point (a dot).
4. For each point: how far it is from the point before (dx, dy; the first point of a stroke from the
   last point of the stroke before, clipped), and whether the pen was just put down (1) or not (0).
"""
import numpy as np

STEP = 0.1
CLIP = 5.0
MAX_POINTS = 3000


def size_of(strokes):
    ys = np.concatenate([s[:, 1] for s in strokes if len(s)])
    lo, hi = np.percentile(ys, 5), np.percentile(ys, 95)
    return max(float(hi - lo), 1e-3)


def resample(stroke, step):
    """Points every [step] along the stroke's path (its first point kept)."""
    if len(stroke) < 2:
        return stroke[:1].copy()
    d = np.sqrt((np.diff(stroke, axis=0) ** 2).sum(axis=1))
    total = float(d.sum())
    if total < step:
        return stroke[:1].copy()
    at = np.concatenate([[0.0], np.cumsum(d)])
    n = int(total // step) + 1
    want = np.arange(n) * step
    x = np.interp(want, at, stroke[:, 0])
    y = np.interp(want, at, stroke[:, 1])
    return np.stack([x, y], axis=1)


def features(strokes):
    """[N, 3] float32: dx, dy, pen put down. [strokes]: list of [k, 2] arrays (x, y), in writing order."""
    strokes = [np.asarray(s, dtype=np.float64).reshape(-1, 2) for s in strokes if len(s)]
    if not strokes:
        return np.zeros((0, 3), np.float32)
    h = size_of(strokes)
    x0 = min(float(s[:, 0].min()) for s in strokes)
    ym = float(np.median(np.concatenate([s[:, 1] for s in strokes])))
    out = []
    prev = None
    for s in strokes:
        pts = np.stack([(s[:, 0] - x0) / h, (s[:, 1] - ym) / h], axis=1)
        pts = resample(pts, STEP)
        for i, p in enumerate(pts):
            if prev is None:
                dx, dy = 0.0, 0.0
            else:
                dx, dy = p[0] - prev[0], p[1] - prev[1]
            out.append((np.clip(dx, -CLIP, CLIP), np.clip(dy, -CLIP, CLIP), 1.0 if i == 0 else 0.0))
            prev = p
            if len(out) >= MAX_POINTS:
                return np.asarray(out, np.float32)
    return np.asarray(out, np.float32)
