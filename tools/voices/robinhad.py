"""The ESPnet Ukrainian voices (robinhad/ukrainian-tts, MIT), trained on text with stresses: same sentences."""
import io, subprocess
from ukrainian_tts.tts import TTS, Voices, Stress
TEXT = ("Стежка звернула до потоку, і ми вперше почули, як шумить ліс над перевалом. "
        "Увечері туман піднявся з долини, а мати засвітила лампу. "
        "Двадцять третього листопада ми пройшли чотирнадцять кілометрів — і жодного разу не пошкодували. "
        "Чи підете ви завтра до бібліотеки? Там нова виставка українських видань. "
        "Черговий лікар сказав, що аналізи будуть готові в понеділок, о пів на десяту.")
tts = TTS(device="cpu")
for name, voice in (("tetiana", Voices.Tetiana.value), ("lada", Voices.Lada.value)):
    for sname, st in (("dictionary", Stress.Dictionary.value), ("model", Stress.Model.value)):
        with open(f"out/robinhad-{name}-{sname}.wav", "wb") as f:
            _, text = tts.tts(TEXT, voice, st, f)
        print(name, sname, text)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"out/robinhad-{name}-{sname}.wav", "-c:a", "aac", "-b:a", "96k", f"out/robinhad-{name}-{sname}.m4a"], check=True)
