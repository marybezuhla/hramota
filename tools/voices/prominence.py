"""Which vowel does the voice actually stress? The model is asked for its own sound lengths (the durations it
decides per symbol), so each vowel's length, loudness and pitch are measured exactly where it is."""
import json, subprocess, numpy as np, onnx, onnxruntime as ort, unicodedata
from ukrainian_word_stress import Stressifier, StressSymbol
stress = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent)

WORDS = {  # word: stressed vowel (0-based); carrier sentences keep it in running speech
    "шумить": 1, "туман": 1, "кілометрів": 2, "пошкодували": 3, "виставка": 0, "листопада": 2,
    "увечері": 1, "засвітила": 2, "бібліотеки": 3, "дорога": 1, "понеділок": 2, "сказав": 1,
    "третього": 0, "жодного": 0, "пройшли": 1, "підете": 0, "видань": 1, "нова": 1, "готові": 1, "аналізи": 1,
}
SR = 22050; HOP = 256
IPA_V = set("aeiouɛɪɔəɐʊɨ"); UK_V = set("аеєиіїоуюя")

def with_durations(path):
    m = onnx.load(path)
    ceil = [n for n in m.graph.node if n.op_type == "Ceil"]
    out = ceil[0].output[0]
    m.graph.output.append(onnx.helper.make_tensor_value_info(out, onnx.TensorProto.FLOAT, None))
    return ort.InferenceSession(m.SerializeToString()), out

def run(sess, dur_name, ids, sid=None):
    x = np.array([ids], dtype=np.int64)
    feed = {"input": x, "input_lengths": np.array([len(ids)], dtype=np.int64), "scales": np.array([0.333, 1.0, 0.333], dtype=np.float32)}
    if sid is not None: feed["sid"] = np.array([sid], dtype=np.int64)
    audio, dur = sess.run(["output", dur_name], feed)
    return audio.squeeze(), dur.squeeze()

def f0(seg):
    if len(seg) < 600: return 0.0
    s = seg * np.hanning(len(seg)); ac = np.correlate(s, s, "full")[len(s) - 1:]
    lo, hi = SR // 400, min(SR // 70, len(ac) - 1)
    return SR / (lo + np.argmax(ac[lo:hi])) if hi > lo else 0.0

def stressed_by_voice(audio, dur, symbols, is_vowel):
    """symbols[i] is the symbol for input position i; returns the index (among vowels) the voice stresses most."""
    frames = np.cumsum(np.concatenate([[0], dur]))
    feats = []
    for i, s in enumerate(symbols):
        if not is_vowel(s): continue
        a, b = int(frames[i] * HOP), int(frames[i + 1] * HOP)
        seg = audio[a:b]
        feats.append((b - a, np.sqrt((seg ** 2).mean()) if len(seg) else 0, f0(seg)))
    if not feats: return None, []
    f = np.array(feats, dtype=float)
    z = lambda v: (v - v.mean()) / (v.std() + 1e-9)
    score = z(f[:, 0]) + z(f[:, 1]) + 0.5 * z(f[:, 2])
    return int(np.argmax(score)), feats

def espeak(w): return subprocess.run(["espeak-ng", "-q", "-v", "uk", "--ipa", w], capture_output=True, text=True).stdout.strip()

def place(p, k, how):
    p = p.replace("ˈ", "").replace("ˌ", "")
    if how == "none": return p
    idx = [i for i, c in enumerate(p) if c in IPA_V]
    if k >= len(idx): return p
    v = idx[k]
    if how == "vowel": return p[:v] + "ˈ" + p[v:]
    if how == "onset":
        j = v - 1
        while j > 0 and p[j] in "ːʲ̪": j -= 1
        return p[:max(j, 0)] + "ˈ" + p[max(j, 0):]
    return p

# The espeak-trained voice
cfg = json.load(open("models/uk_UA-tetiana-high.onnx.json")); M = cfg["phoneme_id_map"]
sess, dname = with_durations("models/uk_UA-tetiana-high.onnx")
def ids_and_symbols(ph):
    ids = M["^"] + M["_"]; sym = ["^", "_"]
    for c in unicodedata.normalize("NFD", ph):
        if c in M: ids += M[c] + M["_"]; sym += [c, "_"]
    ids += M["$"]; sym += ["$"]
    return ids, sym
res = {}
for how in ("espeak", "none", "vowel", "onset"):
    ok = 0; lines = []
    for w, k in WORDS.items():
        base = espeak(w)
        ph = base if how == "espeak" else place(base, k, how)
        ids, sym = ids_and_symbols(ph)
        got, _ = stressed_by_voice(*run(sess, dname, ids), sym, lambda s: s in IPA_V)
        ok += got == k; lines.append(f"{'OK ' if got == k else 'BAD'} {w}: voice {got}, right {k}  {ph}")
    res["tetiana-high/" + how] = ok
    print(f"== tetiana-high, mark {how}: {ok}/{len(WORDS)}"); print("  " + "\n  ".join(lines))

# The voice trained on text with stress marks
cfg2 = json.load(open("models/uk_UA-ukrainian_tts-medium.onnx.json")); M2 = cfg2["phoneme_id_map"]
sess2, dname2 = with_durations("models/uk_UA-ukrainian_tts-medium.onnx")
def text_ids(t):
    ids = M2["^"] + M2["_"]; sym = ["^", "_"]
    for c in unicodedata.normalize("NFD", t.lower()):
        if c in M2: ids += M2[c] + M2["_"]; sym += [c, "_"]
    ids += M2["$"]; sym += ["$"]
    return ids, sym
for how in ("plain", "marked"):
    ok = 0; lines = []
    for w, k in WORDS.items():
        t = w if how == "plain" else stress(w)
        ids, sym = text_ids(t)
        got, _ = stressed_by_voice(*run(sess2, dname2, ids, sid=2), sym, lambda s: s in UK_V)
        ok += got == k; lines.append(f"{'OK ' if got == k else 'BAD'} {w}: voice {got}, right {k}  {t}")
    res["ukrainian_tts-tetiana/" + how] = ok
    print(f"== ukrainian_tts tetiana, {how}: {ok}/{len(WORDS)}"); print("  " + "\n  ".join(lines))
print(json.dumps(res))
