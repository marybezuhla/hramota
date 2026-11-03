"""Stresses for the espeak-trained Ukrainian voice (tetiana-high): eSpeak gives the sounds, the stress dictionary
says which vowel is stressed, and the stress mark is moved there before the voice reads it. Prototype of the app's way."""
import json, os, re, subprocess, sys, unicodedata
import numpy as np, onnxruntime as ort
from ukrainian_word_stress import Stressifier, StressSymbol
stress = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent)

TEXT = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
        "Увечері туман піднявся з долини, а мати засвітила лампу. "
        "Двадцять третього листопада ми пройшли чотирнадцять кілометрів — і жодного разу не пошкодували. "
        "Чи підете ви завтра до бібліотеки? Там нова виставка українських видань. "
        "Черговий лікар сказав, що аналізи будуть готові в понеділок, о пів на десяту.")
VOWEL_LETTERS = set("аеєиіїоуюяАЕЄИІЇОУЮЯ")
IPA_VOWELS = set("aeiouɛɪɔəɐʊɨ")

def ipa(text):
    return subprocess.run(["espeak-ng", "-q", "-v", "uk", "--ipa", text], capture_output=True, text=True).stdout.strip()

def stressed_vowel(word):
    """Index of the stressed vowel (0-based among the word's vowels), or None."""
    n = -1
    for i, c in enumerate(word):
        if c in VOWEL_LETTERS: n += 1
        if c == "́": return n
    return None

def fix(word_marked, word_ipa):
    k = stressed_vowel(word_marked)
    if k is None: return word_ipa
    plain = word_ipa.replace("ˈ", "").replace("ˌ", "")
    n = -1; out = []
    for c in plain:
        if c in IPA_VOWELS:
            n += 1
            if n == k: out.append("ˈ")
        out.append(c)
    return "".join(out) if n >= k else word_ipa

def phonemes(sentence, use_dict):
    """Words become sounds; commas, dashes and the end mark stay, so the voice pauses and rises as it learned to."""
    marked = stress(sentence)
    out = []
    for tok in re.findall(r"[\w'’ʼ\u0301-]+|[,;:.?!—]", marked):
        if tok in ",;:.?!—":
            mark = "," if tok in ";:—" else tok
            if out: out[-1] += mark
            continue
        p = ipa(tok.replace("\u0301", ""))
        out.append(fix(tok, p) if use_dict else p)
    return " ".join(out)

cfg = json.load(open("models/uk_UA-tetiana-high.onnx.json"))
ids_map = cfg["phoneme_id_map"]
sess = ort.InferenceSession("models/uk_UA-tetiana-high.int8.onnx")

def speak(ph):
    ids = list(ids_map["^"]) + list(ids_map["_"])
    for c in unicodedata.normalize("NFD", ph):
        if c in ids_map: ids += ids_map[c] + ids_map["_"]
    ids += ids_map["$"]
    x = np.array([ids], dtype=np.int64)
    audio = sess.run(None, {"input": x, "input_lengths": np.array([x.shape[1]], dtype=np.int64),
                            "scales": np.array([0.667, 1.0, 0.8], dtype=np.float32)})[0].squeeze()
    return audio

sentences = re.split(r"(?<=[.?!])\s+", TEXT)
for name, use in (("eSpeak", False), ("dictionary", True)):
    chunks, log = [], []
    for s in sentences:
        ph = phonemes(s, use)
        log.append(ph)
        chunks.append(speak(ph)); chunks.append(np.zeros(int(0.35 * 22050), dtype=np.float32))
    a = np.concatenate(chunks)
    a = (np.clip(a / max(1e-6, np.abs(a).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
    import wave
    with wave.open(f"out/g2p-{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050); w.writeframes(a.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/g2p-{name}.wav", "-c:a", "aac", "-b:a", "96k", f"out/g2p-{name}.m4a"], check=True)
    os.remove(f"out/g2p-{name}.wav")
    open(f"out/g2p-{name}.txt", "w", encoding="utf-8").write("\n".join(log) + "\n")
    print(name, *log, sep="\n  ")
