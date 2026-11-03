"""The robinhad/ukrainian-tts voices for the phone. v6 is Tacotron 2 + HiFi-GAN (a decoder loop, hard to move);
earlier releases may be VITS (one ONNX model, like Piper's). Lists the releases, exports the newest VITS one
with the Tetiana voice, and reads the test sentences from the export."""
import json, os, subprocess, time, urllib.request, wave, numpy as np, torch, yaml

# The release and its files are fixed and checked before anything is loaded (torch.load runs code from the file)
tag = "v5.0.0"
SOURCES = {
    "model.pth": "ed7a1f0add130e0a850795d9b31cf591b3c041ec663e637d30ffed345103eaf9",
    "config.yaml": "f4ca8d931f926c3904d0c5d8b42f4dc85b1788db30883749fa92291d330f2da6",
    "spk_xvector.ark": "fd0682096117f3b7bd617e4703f7698ffc050d167911306e99460532144ec6eb",
}
names = {n: f"https://github.com/robinhad/ukrainian-tts/releases/download/{tag}/{n}" for n in SOURCES}
os.makedirs("m", exist_ok=True)
import hashlib
for n, u in names.items():
    urllib.request.urlretrieve(u, "m/" + n)
    got = hashlib.sha256(open("m/" + n, "rb").read()).hexdigest()
    if got != SOURCES[n]: raise SystemExit(f"{n}: sha256 {got}, expected {SOURCES[n]}")
print("sources checked:", tag)
from espnet2.bin.tts_inference import Text2Speech
s2 = Text2Speech(train_config="m/config.yaml", model_file="m/model.pth", device="cpu")
gen = next(m for _, m in s2.model.named_modules() if type(m).__name__ == "VITSGenerator")
gen.eval()
spk = None
if os.path.exists("m/spk_xvector.ark"):
    from kaldiio import load_ark
    xv = {k: v for k, v in load_ark("m/spk_xvector.ark")}
    print("speakers:", list(xv))
    spk = np.asarray(xv["tetiana"] if "tetiana" in xv else next(iter(xv.values())), dtype=np.float32).reshape(-1)
    spk.tofile("out/tetiana.xvector.f32")
open("out/tokens.txt", "w", encoding="utf-8").write("\n".join(s2.train_args.token_list) + "\n")
from ukrainian_tts.formatter import preprocess_text
from ukrainian_tts.stress import sentence_to_stress, stress_dict
TEXT = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
        "Увечері туман піднявся з долини, а мати засвітила лампу. "
        "Чи підете ви завтра до бібліотеки? Там нова виставка українських видань.")
t = sentence_to_stress(preprocess_text(TEXT), stress_dict)
ids = s2.preprocess_fn("<dummy>", dict(text=t))["text"]
x = torch.tensor(ids, dtype=torch.long)[None]

# ESPnet makes its masks with Python numbers (lengths.max() as an int): traced, they would fix the sentence length
# for good (an exported voice that reads only sentences as long as the test one). A mask from tensors only:
def make_non_pad_mask(lengths, xs=None, length_dim=-1, maxlen=None):
    n = xs.size(length_dim) if xs is not None else lengths.max()
    seq = torch.arange(n, device=lengths.device)
    mask = seq.unsqueeze(0) < lengths.unsqueeze(1)
    if xs is not None and xs.dim() == 3 and length_dim == -1:
        mask = mask.unsqueeze(1).expand(-1, xs.size(1), -1)
    return mask
def make_pad_mask(lengths, xs=None, length_dim=-1, maxlen=None):
    return ~make_non_pad_mask(lengths, xs, length_dim, maxlen)
import sys as _sys
for _name, _mod in list(_sys.modules.items()):
    if _name.startswith(("espnet2.gan_tts", "espnet.nets.pytorch_backend")) and _mod is not None:
        if hasattr(_mod, "make_non_pad_mask"): setattr(_mod, "make_non_pad_mask", make_non_pad_mask)
        if hasattr(_mod, "make_pad_mask"): setattr(_mod, "make_pad_mask", make_pad_mask)

class Wrap(torch.nn.Module):
    def __init__(self, g): super().__init__(); self.g = g
    def forward(self, text, text_lengths, spembs, scales):
        wav, _, dur = self.g.inference(text=text, text_lengths=text_lengths, spembs=spembs,
                                       noise_scale=scales[0], alpha=scales[1], noise_scale_dur=scales[2])
        return wav.view(1, -1), dur
w = Wrap(gen)
args = (x, torch.tensor([x.shape[1]]), torch.tensor(spk), torch.tensor([0.667, 1.0, 0.8]))
with torch.no_grad():
    torch.onnx.export(w, args, "out/uk.onnx", input_names=["text", "text_lengths", "spembs", "scales"],
                      output_names=["wav", "durations"], dynamic_axes={"text": {1: "T"}, "wav": {1: "N"}, "durations": {1: "T"}},
                      opset_version=17, dynamo=False)
print("onnx MB", round(os.path.getsize("out/uk.onnx") / 1e6, 1))
def save(a, name):
    a = (np.clip(a / max(1e-6, np.abs(a).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
    with wave.open(f"out/{name}.wav", "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(s2.fs); wf.writeframes(a.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "aac", "-b:a", "96k", f"out/{name}.m4a"], check=True)
with torch.no_grad():
    t0 = time.time(); ref = w(*args)[0].numpy().squeeze()
print("torch RTF", round((time.time() - t0) / (len(ref) / s2.fs), 3))
save(ref, f"vits-{tag}-torch")
short = s2.preprocess_fn("<dummy>", dict(text=sentence_to_stress(preprocess_text("Чи підете ви завтра?"), stress_dict)))["text"]
np.save("out/feed-short.npy", {"text": np.array([short], dtype=np.int64), "text_lengths": np.array([len(short)]), "spembs": spk, "scales": np.array([0.667, 1.0, 0.8], dtype=np.float32)}, allow_pickle=True)
np.save("out/feed.npy", {"text": x.numpy(), "text_lengths": np.array([x.shape[1]]), "spembs": spk, "scales": np.array([0.667, 1.0, 0.8], dtype=np.float32)}, allow_pickle=True)
open("out/tag.txt", "w").write(tag)
