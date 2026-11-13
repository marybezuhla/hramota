"""Trains the model on made-up writing, keeps the one that reads the held-out fonts best, and writes it
as ONNX with its alphabet.

  python3 rukopys/train/train.py --fonts fonts/ --held-out 2 --hours 4 --out out/
"""
import argparse
import glob
import json
import math
import os
import random
import sys
import time

import numpy as np
import torch
from torch import nn

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "synth"))

import corpus  # noqa: E402
import data  # noqa: E402
from model import Rukopys  # noqa: E402


def alphabet():
    chars = open(os.path.join(HERE, "..", "alphabet.txt"), encoding="utf-8").read().split("\n")[:-1]
    assert chars[0] == " " and len(set(chars)) == len(chars)
    return chars


def batches(samples, size, rnd):
    order = list(range(len(samples)))
    rnd.shuffle(order)
    # Lines of about the same length together: less padding to work through
    chunks = [order[i:i + size * 50] for i in range(0, len(order), size * 50)]
    for ch in chunks:
        ch.sort(key=lambda i: len(samples[i][0]))
        bs = [ch[i:i + size] for i in range(0, len(ch), size)]
        rnd.shuffle(bs)
        yield from bs


def tensors(samples, idx, index):
    xs = [torch.from_numpy(samples[i][0].astype(np.float32)) for i in idx]
    lengths = torch.tensor([len(x) for x in xs])
    x = nn.utils.rnn.pad_sequence(xs, batch_first=True)
    texts = [samples[i][1] for i in idx]
    targets = torch.tensor([index[c] for t in texts for c in t], dtype=torch.long)
    target_lengths = torch.tensor([len(t) for t in texts])
    return x, lengths, targets, target_lengths, texts


def greedy(logp, chars):
    best = logp.argmax(-1).tolist()
    out, prev = [], 0
    for b in best:
        if b != prev and b != 0:
            out.append(chars[b - 1])
        prev = b
    return "".join(out)


def distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def cer(model, samples, chars):
    model.eval()
    errors = total = 0
    with torch.no_grad():
        for f, text, _ in samples:
            logp = model(torch.from_numpy(f.astype(np.float32))[None])[0]
            errors += distance(greedy(logp, chars), text)
            total += len(text)
    model.train()
    return errors / max(total, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fonts", required=True, help="folder of .ttf/.otf fonts")
    ap.add_argument("--held-out", type=int, default=2, help="how many handwriting fonts only test (never trained on)")
    ap.add_argument("--train-n", type=int, default=150_000)
    ap.add_argument("--val-n", type=int, default=1500)
    ap.add_argument("--hours", type=float, default=4.0)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    torch.set_num_threads(os.cpu_count())
    torch.manual_seed(a.seed)
    rnd = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)

    chars = alphabet()
    index = {c: i + 1 for i, c in enumerate(chars)}
    words = corpus.dictionary_words(os.path.join(ROOT, "app/src/main/assets/voice/stress.bin"))
    # The practice sentences are what «Мій почерк» asks people to write: never trained on, so a phone's
    # check of Р2 on them is fair
    real = []
    fonts = sorted(glob.glob(os.path.join(a.fonts, "*.[ot]tf")))
    hand = [f for f in fonts if not os.path.basename(f).startswith("print-")]
    held = hand[-a.held_out:] if a.held_out else []
    train_fonts = [f for f in fonts if f not in held]
    # Too few fonts came (downloads refused): no fair test, no model
    if len(held) != a.held_out or len([f for f in train_fonts if f in hand]) < 3:
        sys.exit(f"too few handwriting fonts: {len(hand)}")
    print("training fonts:", [os.path.basename(f) for f in train_fonts], flush=True)
    print("held-out fonts:", [os.path.basename(f) for f in held], flush=True)

    t0 = time.time()
    train = data.make(a.train_n, train_fonts, a.seed, words, real, set(chars))
    val = data.make(a.val_n, held, a.seed + 7, words, real, set(chars), keep_strokes=True)
    # The first part picks the best checkpoint; the rest (never used for that) is the test
    pick, val = val[:min(500, len(val) // 3)], val[min(500, len(val) // 3):]
    print(f"made {len(train)} training and {len(val)} test lines in {time.time() - t0:.0f} s", flush=True)

    model = Rukopys(len(chars) + 1)
    print("weights:", sum(p.numel() for p in model.parameters()), flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    deadline = t0 + a.hours * 3600
    best = math.inf
    step = 0
    epoch = 0
    history = []
    lr0 = 2e-3
    while time.time() < deadline:
        epoch += 1
        for idx in batches(train, a.batch, rnd):
            if time.time() > deadline:
                break
            # Learning slows down as the time runs out
            left = max(0.0, (deadline - time.time()) / (deadline - t0))
            for g in opt.param_groups:
                g["lr"] = lr0 * (0.05 + 0.95 * left)
            x, lengths, targets, target_lengths, _ = tensors(train, idx, index)
            # A little more noise each time: the same line never looks quite the same
            x = x + torch.randn_like(x) * 0.01 * (x[..., 2:3] == 0)
            logp = model(x, lengths)
            loss = ctc(logp.transpose(0, 1), targets, Rukopys.steps(lengths), target_lengths)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            step += 1
            if step % 100 == 0:
                print(f"epoch {epoch} step {step} loss {loss.item():.3f}", flush=True)
            if step % 2000 == 0:
                c = cer(model, pick, chars)
                history.append({"step": step, "cer": c})
                print(f"  test letters wrong: {c:.3%}", flush=True)
                if c < best:
                    best = c
                    torch.save(model.state_dict(), os.path.join(a.out, "best.pt"))
    c = cer(model, pick, chars)
    if c < best:
        best = c
        torch.save(model.state_dict(), os.path.join(a.out, "best.pt"))
    model.load_state_dict(torch.load(os.path.join(a.out, "best.pt")))
    model.eval()
    dummy = torch.zeros(1, 200, 3)
    torch.onnx.export(model, (dummy,), os.path.join(a.out, "rukopys.onnx"), input_names=["points"], output_names=["logprobs"],
                      dynamic_axes={"points": {1: "n"}, "logprobs": {1: "t"}}, opset_version=17, dynamo=False)
    with open(os.path.join(a.out, "rukopys_alphabet.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(chars) + "\n")
    # The test lines kept for comparing with the first way of reading (eval/compare.py)
    np.save(os.path.join(a.out, "test.npy"), np.array([(t, [s.tolist() for s in st]) for _, t, st in val], dtype=object), allow_pickle=True)
    json.dump({"steps": step, "epochs": epoch, "best_cer": best, "history": history,
               "train_fonts": [os.path.basename(f) for f in train_fonts], "held_out_fonts": [os.path.basename(f) for f in held],
               "train_lines": len(train), "test_lines": len(val)}, open(os.path.join(a.out, "train.json"), "w"), ensure_ascii=False, indent=1)
    print("done; best test letters wrong:", best, flush=True)


if __name__ == "__main__":
    main()
