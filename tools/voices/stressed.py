"""Ukrainian with stress marks: the ukrainian_tts voice was trained on text with them (U+0301), so it can be told.
Reads the same sentences plain and with stresses from the ukrainian-word-stress dictionary (MIT)."""
import json, os, subprocess, sys
from ukrainian_word_stress import Stressifier, StressSymbol
stress = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent)
TEXTS = [
    "Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом.",
    "Увечері туман піднявся з долини, а мати засвітила лампу.",
    "Двадцять третього листопада ми пройшли чотирнадцять кілометрів — і жодного разу не пошкодували.",
    "Чи підете ви завтра до бібліотеки? Там нова виставка українських видань.",
    "Черговий лікар сказав, що аналізи будуть готові в понеділок, о пів на десяту.",
]
plain = " ".join(TEXTS)
marked = " ".join(stress(t) for t in TEXTS)
os.makedirs("out", exist_ok=True)
open("out/stressed.txt", "w", encoding="utf-8").write(marked + "\n")
print(marked)
model = "models/uk_UA-ukrainian_tts-medium.int8.onnx"
for name, text in (("plain", plain), ("stressed", marked)):
    for spk, sid in (("tetiana", 2), ("lada", 0)):
        wav = f"out/uk-tts-{spk}-{name}.wav"
        subprocess.run([sys.executable, "-m", "piper", "-m", model, "-s", str(sid), "-f", wav, "--", text], check=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "aac", "-b:a", "96k", wav[:-4] + ".m4a"], check=True)
        os.remove(wav)
# The espeak voice for comparison (its stresses come from eSpeak, which ignores the marks)
subprocess.run([sys.executable, "-m", "piper", "-m", "models/uk_UA-tetiana-high.int8.onnx", "-f", "out/tetiana-high-plain.wav", "--", plain], check=True)
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", "out/tetiana-high-plain.wav", "-c:a", "aac", "-b:a", "96k", "out/tetiana-high-plain.m4a"], check=True)
os.remove("out/tetiana-high-plain.wav")
