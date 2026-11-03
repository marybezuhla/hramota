"""The ESPnet Ukrainian voice (Tacotron 2 + HiFi-GAN) for the phone, through espnet_onnx: encoder, one decoder step
(the loop runs in the app), postnet and vocoder as ONNX; an 8-bit copy; the same sentences read from the export."""
import glob, os, subprocess, time, wave, numpy as np, torch
from ukrainian_tts.tts import TTS
from ukrainian_tts.formatter import preprocess_text
from ukrainian_tts.stress import sentence_to_stress, stress_dict

tts = TTS(device="cpu")
s2 = tts.synthesizer
spk = np.asarray(tts.xvectors["tetiana"][0], dtype=np.float32)
print("tts:", type(s2.model.tts).__name__, "fs", s2.fs)
from espnet_onnx.export import TTSModelExport
m = TTSModelExport(cache_dir="exp")
try:
    m.export(s2, tag_name="uk", quantize=True, verbose=False)
    print("exported with espnet_onnx")
except Exception as e:
    import traceback; traceback.print_exc()
for f in sorted(glob.glob("exp/**/*", recursive=True)):
    if os.path.isfile(f): print(f, round(os.path.getsize(f) / 1e6, 2), "MB")
TEXT = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
        "Увечері туман піднявся з долини, а мати засвітила лампу.")
t = sentence_to_stress(preprocess_text(TEXT), stress_dict)
from espnet_onnx import Text2Speech as OnnxTTS
for q in (False, True):
    try:
        o = OnnxTTS(tag_name="uk", cache_dir="exp", use_quantized=q)
        t0 = time.time(); out = o(t, spembs=spk); wav = np.asarray(out["wav"]).squeeze()
        print("onnx quantized" if q else "onnx", "RTF", round((time.time() - t0) / (len(wav) / s2.fs), 3))
        a = (np.clip(wav / max(1e-6, np.abs(wav).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
        name = "export-int8" if q else "export-fp32"
        with wave.open(f"out/{name}.wav", "wb") as wf:
            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(s2.fs); wf.writeframes(a.tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "aac", "-b:a", "96k", f"out/{name}.m4a"], check=True)
    except Exception:
        import traceback; traceback.print_exc()
