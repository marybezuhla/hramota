"""Fetches the official PaddlePaddle PP-OCRv5 mobile models (text detection + East Slavic
recognition), converts them to ONNX, checks them on a rendered Ukrainian test image and writes
SHA256SUMS. Run it by hand (the models are in the repository; CI checks SHA256SUMS on every build).
"""
import hashlib, json, os, pathlib, subprocess, sys, yaml

OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
from huggingface_hub import HfApi, snapshot_download

api = HfApi()
models = {
    "det": "PaddlePaddle/PP-OCRv5_mobile_det",
    "rec_cyrillic": "PaddlePaddle/eslav_PP-OCRv5_mobile_rec",
    "rec_latin": "PaddlePaddle/latin_PP-OCRv5_mobile_rec",
}
info = {}
for name, repo in models.items():
    rev = api.model_info(repo).sha
    path = snapshot_download(repo, revision=rev)
    info[name] = {"repo": repo, "revision": rev, "files": sorted(os.listdir(path))}
    onnx = OUT / f"{name}.onnx"
    subprocess.run(["paddle2onnx", "--model_dir", path, "--model_filename", "inference.json",
                    "--params_filename", "inference.pdiparams", "--save_file", str(onnx),
                    "--opset_version", "14"], check=True)
    cfg = yaml.safe_load(open(os.path.join(path, "inference.yml")))
    info[name]["preprocess"] = cfg.get("PreProcess")
    if name.startswith("rec"):
        chars = cfg["PostProcess"]["character_dict"]
        (OUT / f"{name}_dict.txt").write_text("\n".join(chars) + "\n", encoding="utf-8")
        info[name]["dict_size"] = len(chars)
        info[name]["postprocess"] = {k: v for k, v in cfg["PostProcess"].items() if k != "character_dict"}

# A quick end-to-end check with onnxruntime on rendered lines of text
import numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 40)

def recognize(name, text):
    img = Image.new("RGB", (40 + 24 * len(text), 80), "white")
    ImageDraw.Draw(img).text((10, 15), text, fill="black", font=font)
    h = 48; w = int(img.width * h / img.height)
    x = np.asarray(img.resize((w, h)), dtype=np.float32) / 255.0
    x = ((x - 0.5) / 0.5).transpose(2, 0, 1)[None]
    sess = ort.InferenceSession(str(OUT / f"{name}.onnx"))
    probs = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
    chars = (OUT / f"{name}_dict.txt").read_text(encoding="utf-8").split("\n")[:-1] + [" "]
    out = []; prev = -1
    for i in probs.argmax(axis=1):
        if i != prev and i != 0:
            out.append(chars[i - 1] if i - 1 < len(chars) else "?")
        prev = i
    return {"input": text, "recognized": "".join(out), "rec_input": str(sess.get_inputs()[0].shape), "rec_output": str(probs.shape)}

info["check"] = {
    "det_inputs": [str(i.shape) for i in ort.InferenceSession(str(OUT / "det.onnx")).get_inputs()],
    "cyrillic": [recognize("rec_cyrillic", t) for t in ["Привіт, світе! Їжак ґудзик", "Київ 2026, тел. 067"]],
    "latin": [recognize("rec_latin", t) for t in ["Hello, world! Kyiv", "Zażółć gęślą jaźń"]],
}
sums = []
for f in sorted(OUT.iterdir()):
    if f.is_file():
        sums.append(f"{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}  {f.stat().st_size}")
(OUT / "SHA256SUMS").write_text("\n".join(sums) + "\n")
(OUT / "info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1, default=str))
print(json.dumps(info["check"], ensure_ascii=False, indent=1))
