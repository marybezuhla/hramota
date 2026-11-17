"""Words for the spelling check that the stress dictionary does not have, from the Ukrainian Hunspell
dictionary of dict_uk (brown-uk; GPL/LGPL/MPL, see the LICENSE file kept beside it).

  python3 tools/spell/build_spell.py words.txt app/src/main/assets/voice/stress.bin out/uk_extra.bin

words.txt: every word form, one a line (unmunch of the Hunspell dictionary, each form checked by
Hunspell itself). Written in the format of stress.bin (tools/voices/build_stress.py) with no stresses,
so the app reads it with the same StressDictionary: a word is known when it is in either file."""
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "rukopys", "synth"))
import corpus  # noqa: E402

ALPHABET = "'-абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
CODE = {c: i + 1 for i, c in enumerate(ALPHABET)}


def main():
    src, stress, out = sys.argv[1:4]
    known = set(corpus.dictionary_words(stress))
    extra, skipped = set(), 0
    for line in open(src, encoding="utf-8"):
        w = line.strip().split("/")[0].lower().replace("’", "'").replace("ʼ", "'")
        if not w:
            continue
        if any(c not in CODE for c in w) or len(w) > 255:
            skipped += 1
            continue
        if w not in known:
            extra.add(w)
    words = sorted(extra, key=lambda w: bytes(CODE[c] for c in w))
    B = 64
    index, blocks = [], []
    for i in range(0, len(words), B):
        chunk = words[i:i + B]
        buf, prev = bytearray(), b""
        for w in chunk:
            e = bytes(CODE[c] for c in w)
            k = 0
            while k < min(len(prev), len(e), 255) and prev[k] == e[k]:
                k += 1
            buf += bytes([k, len(e) - k]) + e[k:] + b"\x00"
            prev = e
        blocks.append(zlib.compress(bytes(buf), 9)[2:-4])
        index.append(bytes(CODE[c] for c in chunk[0]))
    head = bytearray(b"HRST" + struct.pack(">HII", 1, len(words), len(blocks)))
    head += ALPHABET.encode("utf-8").ljust(80, b"\0")
    off, idx = 0, bytearray()
    for first, blk in zip(index, blocks):
        idx += struct.pack(">I", off) + bytes([len(first)]) + first
        off += len(blk)
    idx += struct.pack(">I", off)
    with open(out, "wb") as f:
        f.write(head + struct.pack(">I", len(idx)) + idx + b"".join(blocks))
    print(f"stress dictionary {len(known)}, extra words {len(words)}, skipped {skipped}, bytes {os.path.getsize(out)}")
    for w in ("її", "їх", "вам", "налаштувань", "застосунків", "смартфона", "кого"):
        print(w, "stress" if w in known else "extra" if w in extra else "MISSING")


if __name__ == "__main__":
    main()
