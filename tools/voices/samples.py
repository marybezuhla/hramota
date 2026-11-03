"""Voice samples for choosing Hramota's reading voices (run in CI, which can reach Hugging Face).

Lists the free female Piper voices for Ukrainian and English, keeps their MODEL_CARD files (the licence),
and reads the same short texts with each, at speeds 1.0 and 0.9. Output: out/<voice>.ogg and out/cards/.
"""
import json, os, subprocess, sys, urllib.request

REPO = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
UK = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
      "Увечері туман піднявся з долини, а мати засвітила лампу. "
      "Двадцять третього березня ми пройшли 14 кілометрів — і жодного разу не пошкодували.")
EN = ("The path turned towards the stream, and for the first time we heard the forest above the pass. "
      "In the evening, mist rose from the valley, and mother lit the lamp. "
      "On the 23rd of March we walked 14 kilometres, and never once regretted it.")

voices = json.load(urllib.request.urlopen(REPO + "voices.json"))
wanted = []
for key, v in voices.items():
    lang = v["language"]["code"]
    if lang.startswith("uk_"):
        wanted.append(key)
    elif key in ("en_US-kristin-medium", "en_GB-cori-medium", "en_GB-cori-high", "en_US-ljspeech-medium", "en_US-ljspeech-high"):
        wanted.append(key)
os.makedirs("out/cards", exist_ok=True)
os.makedirs("models", exist_ok=True)
report = []
for key in sorted(wanted):
    v = voices[key]
    files = {os.path.basename(p): (p, f.get("size_bytes", 0)) for p, f in v["files"].items()}
    onnx = next(n for n in files if n.endswith(".onnx"))
    card = next((files[n][0] for n in files if n == "MODEL_CARD"), None)
    for name, (path, _) in files.items():
        if name.endswith(".onnx") or name.endswith(".onnx.json") or name == "MODEL_CARD":
            target = os.path.join("models", name if name != "MODEL_CARD" else key + ".MODEL_CARD")
            if not os.path.exists(target):
                urllib.request.urlretrieve(REPO + path, target)
    if card:
        with open(os.path.join("models", key + ".MODEL_CARD"), encoding="utf-8") as f, open(f"out/cards/{key}.txt", "w", encoding="utf-8") as o:
            o.write(f.read())
    speakers = v.get("speaker_id_map") or {"": None}
    text = UK if key.startswith("uk_") else EN
    for spk, sid in speakers.items():
        for speed, scale in (("1.0", 1.0), ("0.9", 1.0 / 0.9)):
            name = key + (f"-{spk}" if spk else "") + f"-x{speed}"
            cmd = [sys.executable, "-m", "piper", "-m", os.path.join("models", onnx), "-f", f"out/{name}.wav", "--length-scale", str(scale)]
            if sid is not None:
                cmd += ["-s", str(sid)]
            subprocess.run(cmd + ["--", text], check=True)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "libopus", "-b:a", "48k", f"out/{name}.ogg"], check=True)
            os.remove(f"out/{name}.wav")
    report.append({"voice": key, "speakers": list(speakers), "onnx_mb": round(files[onnx][1] / 1e6, 1),
                   "quality": v.get("quality"), "sample_rate": json.load(open(os.path.join("models", onnx + ".json")))["audio"]["sample_rate"],
                   "phoneme_type": json.load(open(os.path.join("models", onnx + ".json"))).get("phoneme_type", "espeak")})
json.dump(report, open("out/voices.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(report, ensure_ascii=False, indent=1))

# Smaller copies (weights in 8 bits): about a quarter of the size; heard side by side with the full ones
from onnxruntime.quantization import quantize_dynamic, QuantType
small = {"uk_UA-tetiana-high": None, "uk_UA-ukrainian_tts-medium": 2, "en_GB-cori-medium": None, "en_US-kristin-medium": None}
for key, sid in small.items():
    onnx = os.path.join("models", key + ".onnx")
    q = os.path.join("models", key + ".int8.onnx")
    quantize_dynamic(onnx, q, weight_type=QuantType.QUInt8)
    os.link(onnx + ".json", q + ".json") if not os.path.exists(q + ".json") else None
    text = UK if key.startswith("uk_") else EN
    speeds = (("1.0", 1.0),) if key.startswith("uk_") else (("1.0", 1.0), ("0.8", 1.25))
    for speed, scale in speeds:
        name = f"{key}-int8-x{speed}"
        cmd = [sys.executable, "-m", "piper", "-m", q, "-f", f"out/{name}.wav", "--length-scale", str(scale)]
        if sid is not None:
            cmd += ["-s", str(sid)]
        subprocess.run(cmd + ["--", text], check=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "libopus", "-b:a", "48k", f"out/{name}.ogg"], check=True)
        os.remove(f"out/{name}.wav")
    print(key, "int8 MB", round(os.path.getsize(q) / 1e6, 1))
