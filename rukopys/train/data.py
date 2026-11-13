"""Samples made ahead of training, on all processor cores: (features, text, strokes) for each line."""
import os
import random
import sys
from multiprocessing import Pool

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "synth"))
sys.path.insert(0, HERE)

import corpus  # noqa: E402
import features  # noqa: E402
import ink  # noqa: E402

_state = {}


def _init(words, real, alphabet):
    _state.update(words=words, real=real, alphabet=alphabet)


def _one(args):
    seed, font = args
    rnd = random.Random(seed)
    text = corpus.line(rnd, _state["words"], _state["real"], _state["alphabet"])
    try:
        strokes = ink.write(text, font, rnd)
    except Exception:
        return None
    f = features.features(strokes)
    if len(f) < 8 or len(f) >= features.MAX_POINTS:
        return None
    return f.astype(np.float16), text, [s.astype(np.float32) for s in strokes]


def make(n, fonts, seed, words, real, alphabet, keep_strokes=False):
    """[n] samples written in [fonts] (taken in turn)."""
    jobs = [(seed * 1_000_003 + i, fonts[i % len(fonts)]) for i in range(n)]
    with Pool(os.cpu_count(), initializer=_init, initargs=(words, real, alphabet)) as pool:
        out = [r for r in pool.imap(_one, jobs, chunksize=64) if r is not None]
    if not keep_strokes:
        out = [(f, t, None) for f, t, _ in out]
    return out
