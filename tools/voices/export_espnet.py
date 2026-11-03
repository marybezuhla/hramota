"""The ESPnet Ukrainian voice for the phone: exported to ONNX (and a smaller 8-bit copy), checked against the
original, timed, and read again from the export. Prints what the app needs: tokens, the speaker vectors, sizes."""
import inspect, json, os, subprocess, time, numpy as np
import ukrainian_tts.tts as U
from ukrainian_tts.tts import TTS, Voices, Stress

tts = TTS(device="cpu")
print("TTS attributes:", {k: type(v).__name__ for k, v in vars(tts).items()})
src = inspect.getsource(U)
print(src[:6000])
