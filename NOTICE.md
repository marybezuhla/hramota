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

## Голоси для читання вголос (`app/src/main/voice`, `app/src/main/assets/voice`, `app/src/debug/assets/voice`)

Моделі голосів не входять у застосунок для людей: їх завантажують окремо (Тетяна — з випуску Писанки v1.0.0, Cori — з Hugging Face за ревізією 293cad05), і Грамота приймає лише точний файл (`VoiceFiles`). У репозиторії вони лишаються для тестів на пристрої й як джерело файлу Тетяни.

- **«Тетяна»** — модель [robinhad/ukrainian-tts](https://github.com/robinhad/ukrainian-tts) v5.0.0 (MIT; записи — ukrainian-tts-datasets, Apache 2.0), перетворена в ONNX (`tools/voices/export_espnet.py`, джерела перевіряються за SHA-256).
- **Словник наголосів** — [ukrainian-word-stress](https://github.com/lang-uk/ukrainian-word-stress) 2.1.0 (MIT), перетворений `tools/voices/build_stress.py`.
- **Cori** — `en_GB-cori-medium` з [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) (MIT; записи LibriVox — суспільне надбання); SHA-256 перевіряє збірка й Грамота під час копіювання вибраного файлу.
- **eSpeak NG** 1.52.0 (GPL-3.0 або новіша), зібраний із сирцевого коду [espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng/tree/4870adfa25b1a32b4361592f1be8a40337c58d6c) у `.github/workflows/espeak.yml`; готові бібліотеки й дані — у `espeak/` із сумами `SHA256SUMS`.
- **ONNX Runtime** 1.22.0 (MIT, Microsoft).

## Розпізнавання тексту (`app/src/main/assets/ocr`)

Моделі PP-OCRv5 mobile від PaddlePaddle ([PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR), Apache 2.0): виявлення тексту, розпізнавання східнослов'янських і латинських літер. Перетворені в ONNX без зміни ваг (`scripts/ocr_models.py`; джерела й ревізії — у `SOURCES.json`, суми — у `SHA256SUMS`, їх перевіряє кожна збірка). Ті самі файли, що й у Писанці; код розпізнавання (`ua.hramota.ocr`) перенесено з Писанки того ж автора.

## Рукопис (`rukopys/`)

Код і модель розпізнавання рукопису (`assets/ink/rukopys.onnx`) — Apache 2.0, власні дані — CC BY 4.0 (див. `rukopys/README.md`). Модель навчена на штучному письмі, зробленому з рукописних шрифтів під SIL Open Font License 1.1 (Google Fonts; список і суми — у гілці `rukopys-model`, `fonts.json`); самих шрифтів у Грамоті немає.

## Тестові файли DOC (`app/src/test/resources/legacy`)

Зразки документів Word 97–2003 (у тому числі пошкоджені, з фазингу) з тестових даних [Apache POI](https://github.com/apache/poi) (`test-data/document`), ліцензія Apache 2.0. Використовуються лише в тестах, у застосунок не входять.
