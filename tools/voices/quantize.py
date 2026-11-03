"""8-bit copy of the exported voice; both read the same sentences, timed with 2 threads (like a phone)."""
import os, subprocess, time, wave, numpy as np, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("out/uk.onnx", "out/uk.int8.onnx", weight_type=QuantType.QUInt8)
feed = np.load("out/feed.npy", allow_pickle=True).item(); tag = open("out/tag.txt").read()
for f in ("uk.onnx", "uk.int8.onnx"):
    o = ort.SessionOptions(); o.intra_op_num_threads = 2
    s = ort.InferenceSession("out/" + f, o)
    names = {i.name for i in s.get_inputs()}
    print(f, "inputs:", [(i.name, i.shape) for i in s.get_inputs()])
    t0 = time.time(); a = s.run(["wav"], {k: v for k, v in feed.items() if k in names})[0].squeeze()
    print(f, round(os.path.getsize("out/" + f) / 1e6, 1), "MB", "RTF", round((time.time() - t0) / (len(a) / 22050), 3))
    # A sentence of another length must work too (the voice must not be fixed to the test one)
    short = np.load("out/feed-short.npy", allow_pickle=True).item()
    b = s.run(["wav"], {k: v for k, v in short.items() if k in names})[0].squeeze()
    print(f, "short sentence:", len(short["text"][0]), "letters,", round(len(b) / 22050, 2), "s")
    assert 0.5 < len(b) / 22050 < 4, "the short sentence came out wrong"
    a = (np.clip(a / max(1e-6, np.abs(a).max()) * 0.9, -1, 1) * 32767).astype(np.int16)
    name = f"vits-{tag}-" + f.replace(".onnx", "")
    with wave.open(f"out/{name}.wav", "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(22050); wf.writeframes(a.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/{name}.wav", "-c:a", "aac", "-b:a", "96k", f"out/{name}.m4a"], check=True)
os.remove("out/uk.onnx"); os.remove("out/feed.npy"); os.remove("out/feed-short.npy")
