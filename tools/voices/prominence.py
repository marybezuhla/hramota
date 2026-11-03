"""Which vowel does the voice actually stress? Each test word is read with the stress mark placed in different ways;
the loudest-longest-highest vowel nucleus is taken as the stressed one and compared with the right one."""
import json, re, subprocess, numpy as np, onnxruntime as ort, unicodedata

WORDS = {  # word: stressed vowel (0-based)
    "шумить": 1, "туман": 1, "кілометрів": 2, "пошкодували": 3, "виставка": 0, "черговий": 1,
    "листопада": 2, "увечері": 1, "засвітила": 2, "бібліотеки": 3, "дорога": 1, "понеділок": 2,
}
IPA_V = set("aeiouɛɪɔəɐʊɨ")
cfg = json.load(open("models/uk_UA-tetiana-high.onnx.json")); M = cfg["phoneme_id_map"]
sess = ort.InferenceSession("models/uk_UA-tetiana-high.onnx")
SR = 22050

def ipa(w): return subprocess.run(["espeak-ng", "-q", "-v", "uk", "--ipa", w], capture_output=True, text=True).stdout.strip()

def place(p, k, how):
    p = p.replace("ˈ", "").replace("ˌ", "")
    if how == "none": return p
    idx = [i for i, c in enumerate(p) if c in IPA_V]
    if k >= len(idx): return p
    v = idx[k]
    if how == "vowel": return p[:v] + "ˈ" + p[v:]
    if how == "onset":  # before the consonant(s) that start the syllable: one consonant before the vowel
        j = v
        if j > 0 and p[j - 1] not in IPA_V and p[j - 1] not in "ːʲ̪": j -= 1
        elif j > 1 and p[j - 1] in "ʲ̪": j -= 2
        return p[:j] + "ˈ" + p[j:]
    if how == "long": return p[:v + 1] + "ː" + p[v + 1:] if p[v + 1:v + 2] != "ː" else p
    return p

def speak(ph, seed=0):
    ids = M["^"] + M["_"]
    for c in unicodedata.normalize("NFD", ph):
        if c in M: ids = ids + M[c] + M["_"]
    ids = ids + M["$"]
    x = np.array([ids], dtype=np.int64)
    return sess.run(None, {"input": x, "input_lengths": np.array([x.shape[1]], dtype=np.int64),
                           "scales": np.array([0.333, 1.0, 0.333], dtype=np.float32)})[0].squeeze()

def nuclei(a, n):
    """The n most prominent vowel-like stretches: energy envelope peaks, with duration, loudness and pitch."""
    hop = 220; fr = 880
    e = np.array([np.sqrt((a[i:i + fr] ** 2).mean()) for i in range(0, len(a) - fr, hop)])
    e = np.convolve(e, np.ones(5) / 5, "same")
    # peaks separated by dips
    peaks = [i for i in range(1, len(e) - 1) if e[i] >= e[i - 1] and e[i] > e[i + 1] and e[i] > 0.15 * e.max()]
    merged = []
    for p in peaks:
        if merged and min(e[merged[-1]:p + 1]) > 0.7 * min(e[merged[-1]], e[p]): 
            if e[p] > e[merged[-1]]: merged[-1] = p
        else: merged.append(p)
    res = []
    for p in merged:
        lo = p; hi = p
        while lo > 0 and e[lo] > 0.6 * e[p]: lo -= 1
        while hi < len(e) - 1 and e[hi] > 0.6 * e[p]: hi += 1
        seg = a[lo * hop: hi * hop + fr]
        # pitch by autocorrelation
        f0 = 0
        if len(seg) > 800:
            s = seg[:2048] * np.hanning(min(2048, len(seg)))
            ac = np.correlate(s, s, "full")[len(s) - 1:]
            l, h = SR // 400, SR // 70
            if h < len(ac): f0 = SR / (l + np.argmax(ac[l:h]))
        res.append(((hi - lo) * hop / SR, e[p], f0))
    return res

summary = {}
for how in ("espeak", "none", "vowel", "onset", "long", "vowel+long"):
    ok = 0; detail = []
    for w, k in WORDS.items():
        base = ipa(w)
        if how == "espeak": ph = base
        elif how == "vowel+long": ph = place(place(base, k, "vowel").replace("ˈ", "§"), 0, "none").replace("§", "ˈ"); ph = ph[:ph.index("ˈ") + 2] + ("ː" if ph[ph.index("ˈ") + 2:ph.index("ˈ") + 3] != "ː" else "") + ph[ph.index("ˈ") + 2:]
        else: ph = place(base, k, how)
        a = speak(ph)
        nuc = nuclei(a, None)
        vowels = sum(c in IPA_V for c in base.replace("ˈ", ""))
        if len(nuc) != vowels:
            detail.append(f"{w}: {len(nuc)}/{vowels} nuclei ({ph})"); continue
        d = np.array([x[0] for x in nuc]); l = np.array([x[1] for x in nuc]); f = np.array([x[2] for x in nuc])
        z = lambda v: (v - v.mean()) / (v.std() + 1e-9)
        score = z(d) + z(l) + z(f)
        got = int(np.argmax(score))
        ok += got == k
        detail.append(f"{w}: {'OK ' if got == k else 'BAD'} heard {got} want {k} ({ph})")
    summary[how] = ok
    print(f"== {how}: {ok}/{len(WORDS)}"); print("  " + "\n  ".join(detail))
print(json.dumps(summary))
