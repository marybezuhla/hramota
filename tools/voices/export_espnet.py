"""The ESPnet Ukrainian voice for the phone: exported to ONNX (and an 8-bit copy), checked by reading the same
sentences from each, timed. Writes what the app needs: model, tokens, the Tetiana speaker vector."""
import os, subprocess, time, wave, numpy as np, torch, onnxruntime as ort
from ukrainian_tts.tts import TTS
from ukrainian_tts.formatter import preprocess_text
from ukrainian_tts.stress import sentence_to_stress, stress_dict

tts = TTS(device="cpu")
s2 = tts.synthesizer
gen = s2.model.tts.generator
gen.eval()
tokens = s2.train_args.token_list
open("out/tokens.txt", "w", encoding="utf-8").write("\n".join(tokens) + "\n")
spk = np.asarray(tts.xvectors["tetiana"][0], dtype=np.float32)
spk.tofile("out/tetiana.xvector.f32"); print("xvector", spk.shape, "fs", s2.fs)
print("config tokenizer:", s2.train_args.token_type, "cleaner:", getattr(s2.train_args, "cleaner", None), "g2p:", getattr(s2.train_args, "g2p", None))

class Wrap(torch.nn.Module):
    def __init__(self, g): super().__init__(); self.g = g
    def forward(self, text, text_lengths, spembs, scales):
        wav, _, dur = self.g.inference(text=text, text_lengths=text_lengths, spembs=spembs,
                                       noise_scale=scales[0], alpha=scales[1], noise_scale_dur=scales[2])
        return wav.view(1, -1), dur

TEXT = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
        "Увечері туман піднявся з долини, а мати засвітила лампу.")
t = sentence_to_stress(preprocess_text(TEXT), stress_dict)
ids = s2.preprocess_fn("<dummy>", dict(text=t))["text"]
print("stressed:", t); print("ids", len(ids), list(ids[:20]))
x = torch.tensor(ids, dtype=torch.long)[None]
args = (x, torch.tensor([x.shape[1]]), torch.tensor(spk)[None], torch.tensor([0.667, 1.0, 0.8]))
w = Wrap(gen)
with torch.no_grad():
    torch.onnx.export(w, args, "out/uk-tetiana.onnx", input_names=["text", "text_lengths", "spembs", "scales"],
                      output_names=["wav", "durations"], dynamic_axes={"text": {1: "T"}, "wav": {1: "N"}, "durations": {1: "T"}},
                      opset_version=17, dynamo=False)
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("out/uk-tetiana.onnx", "out/uk-tetiana.int8.onnx", weight_type=QuantType.QUInt8)
for f in ("uk-tetiana.onnx", "uk-tetiana.int8.onnx"):
    print(f, round(os.path.getsize("out/" + f) / 1e6, 1), "MB")

def save(a, name):
    a = (np.clip(a / max(1e-6, np.abs(a).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
    with wave.open(f"out/{name}.wav", "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(s2.fs); wf.writeframes(a.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "aac", "-b:a", "96k", f"out/{name}.m4a"], check=True)
    os.remove(f"out/{name}.wav")

with torch.no_grad():
    t0 = time.time(); ref = w(*args)[0].numpy().squeeze(); print("torch RTF", round((time.time() - t0) / (len(ref) / s2.fs), 3))
save(ref, "export-torch")
feed = {"text": x.numpy(), "text_lengths": np.array([x.shape[1]]), "spembs": spk[None], "scales": np.array([0.667, 1.0, 0.8], dtype=np.float32)}
for name in ("uk-tetiana.onnx", "uk-tetiana.int8.onnx"):
    o = ort.SessionOptions(); o.intra_op_num_threads = 2
    sess = ort.InferenceSession("out/" + name, o)
    t0 = time.time(); a = sess.run(["wav"], feed)[0].squeeze()
    print(name, "RTF (2 threads)", round((time.time() - t0) / (len(a) / s2.fs), 3), "seconds of audio", round(len(a) / s2.fs, 1))
    save(a, "export-" + name.replace(".onnx", ""))
# The full models are not pushed (too big for a branch); the small one is
os.remove("out/uk-tetiana.onnx")
