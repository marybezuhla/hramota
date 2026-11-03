# Голос для читання вголос

Готується в CI (`.github/workflows/voice-assets.yml`, гілка `voice-prepare` → `voice`) і копіюється вручну в `app/src/main/assets/voice/` після перегляду.

- `uk-tetiana.onnx` — український жіночий голос «Тетяна» з [robinhad/ukrainian-tts](https://github.com/robinhad/ukrainian-tts), випуск v5.0.0 (VITS), ліцензія MIT; записи голосу — набір egorsmkv/ukrainian-tts-datasets, Apache-2.0. Переведено в ONNX і збережено у 8 бітах (`tools/voices/export_espnet.py`, `quantize.py`).
- `uk-tetiana.tokens.txt` — літери й знаки, які голос знає (`+` перед наголошеним голосним).
- `uk-tetiana.xvector.bin` — вектор голосу Тетяни (192 числа float32, little-endian).
- `stress.bin` — словник наголосів (2,84 млн словоформ) з [ukrainian-word-stress](https://github.com/lang-uk/ukrainian-word-stress) 2.1.0, MIT; формат описано в `tools/voices/build_stress.py`.

Контрольні суми: `SHA256SUMS` у гілці `voice`; суми джерел (model.pth, config.yaml, spk_xvector.ark) — `SOURCES.sha256` там само.
