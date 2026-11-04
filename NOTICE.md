# Сторонні складники

## Шрифти (`app/src/main/assets/fonts`)

Метрично сумісні з офісними шрифтами: літери мають ту саму ширину, тож рядки й сторінки документів не зсуваються. Узято з [google/fonts](https://github.com/google/fonts) (ревізія `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`). Arimo — статичні накреслення Regular/Bold, отримані з варіативного файлу (`fonttools varLib.instancer`). Тексти ліцензій — у `app/src/main/assets/fonts/licenses`.

| Шрифт | Замінює | Ліцензія |
|---|---|---|
| Carlito | Calibri | SIL OFL 1.1 |
| Caladea | Cambria (кирилиця — з Tinos) | SIL OFL 1.1 |
| Arimo | Arial, Helvetica | SIL OFL 1.1 |
| Tinos | Times New Roman | SIL OFL 1.1 |
| Cousine | Courier New | SIL OFL 1.1 |

## PDFium (`pdfium/`)

Рушій PDF із Chromium (BSD-3-Clause; ліцензії складників — у `pdfium/licenses`), готова збірка з [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) (MIT), реліз `chromium/8125`, без V8. Походження перевірено атестацією GitHub (`pdfium/ATTESTATION.txt`).

## Голоси для читання вголос (`app/src/main/voice`, `app/src/main/assets/voice`)

- **«Тетяна»** — модель [robinhad/ukrainian-tts](https://github.com/robinhad/ukrainian-tts) v5.0.0 (MIT; записи — ukrainian-tts-datasets, Apache 2.0), перетворена в ONNX (`tools/voices/export_espnet.py`, джерела перевіряються за SHA-256).
- **Словник наголосів** — [ukrainian-word-stress](https://github.com/lang-uk/ukrainian-word-stress) 2.1.0 (MIT), перетворений `tools/voices/build_stress.py`.
- **Cori** — `en_GB-cori-medium` з [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) (MIT; записи LibriVox — суспільне надбання); SHA-256 перевіряє збірка.
- **eSpeak NG** 1.52.0 (GPL-3.0 або новіша), зібраний із сирцевого коду [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng/tree/4870adfa25b1a32b4361592f1be8a40337c58d6c) у `.github/workflows/espeak.yml`; готові бібліотеки й дані — у `espeak/` із сумами `SHA256SUMS`.
- **ONNX Runtime** 1.22.0 (MIT, Microsoft).

## Тестові файли DOC (`app/src/test/resources/legacy`)

Зразки документів Word 97–2003 (у тому числі пошкоджені, з фазингу) з тестових даних [Apache POI](https://github.com/apache/poi) (`test-data/document`), ліцензія Apache 2.0. Використовуються лише в тестах, у застосунок не входять.
