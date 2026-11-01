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

## Тестові файли DOC (`app/src/test/resources/legacy`)

Зразки документів Word 97–2003 (у тому числі пошкоджені, з фазингу) з тестових даних [Apache POI](https://github.com/apache/poi) (`test-data/document`), ліцензія Apache 2.0. Використовуються лише в тестах, у застосунок не входять.
