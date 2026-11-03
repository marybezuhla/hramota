"""The stress dictionary for the app, from ukrainian-word-stress (MIT; 2.9 million word forms).

stress.bin: "HRST" 1, then counts, then a block index (each block's first word and where it starts) and blocks of
64 entries, each block deflated on its own (the app inflates only the one it needs). Words are lower case, in a
one-byte alphabet. An entry: shared prefix length, suffix length, suffix, then the value: a count n; with the high
bit clear, n stress positions follow (the index the accent goes after, like the source); with it set, n options,
each a stress position, a tag count and tags (part of speech, case, number, gender: the source's byte codes)."""
import os, struct, sys, zlib, marisa_trie, ukrainian_word_stress as u

ALPHABET = "'-абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
CODE = {c: i + 1 for i, c in enumerate(ALPHABET)}
trie = marisa_trie.BytesTrie(); trie.load(os.path.join(os.path.dirname(u.__file__), "data/stress.trie"))
entries = {}
skipped = 0
for word, value in trie.items():
    w = word.lower().replace("’", "'").replace("ʼ", "'")
    if any(c not in CODE for c in w): skipped += 1; continue
    if w in entries and word != w: continue  # the lower-case form wins
    if b"\xfe" in value:
        opts = []
        for rec in value.split(b"\xff"):
            if not rec: continue
            pos, tags = rec.split(b"\xfe", 1)
            for p in pos: opts.append((p, tags))
        # the same position with different tags is one option for us when tags agree
        enc = bytes([0x80 | len(opts)]) + b"".join(bytes([p, len(t)]) + t for p, t in opts)
    else:
        enc = bytes([len(value)]) + value
    entries[w] = enc
words = sorted(entries, key=lambda w: bytes(CODE[c] for c in w))
B = 64
index, blocks = [], []
for i in range(0, len(words), B):
    chunk = words[i:i + B]
    out = bytearray(); prev = b""
    for w in chunk:
        e = bytes(CODE[c] for c in w)
        k = 0
        while k < min(len(prev), len(e), 255) and prev[k] == e[k]: k += 1
        out += bytes([k, len(e) - k]) + e[k:] + entries[w]
        prev = e
    blocks.append(zlib.compress(bytes(out), 9)[2:-4])  # raw deflate
    index.append(bytes(CODE[c] for c in chunk[0]))
head = bytearray(b"HRST" + struct.pack(">HII", 1, len(words), len(blocks)))
head += ALPHABET.encode("utf-8").ljust(80, b"\0")
off = 0; idx = bytearray()
for first, blk in zip(index, blocks):
    idx += struct.pack(">I", off) + bytes([len(first)]) + first
    off += len(blk)
idx += struct.pack(">I", off)
with open(sys.argv[1], "wb") as f:
    f.write(head + struct.pack(">I", len(idx)) + idx + b"".join(blocks))
amb = sum(1 for w in words if entries[w][0] & 0x80)
print(f"words {len(words)}, ambiguous {amb}, skipped {skipped}, blocks {len(blocks)}, bytes {os.path.getsize(sys.argv[1])}")
# Checks the app's tests repeat: a few words and their stresses
for w in ("мама", "замок", "черговий", "листопада", "шумить", "бібліотеки", "пошкодували", "п'ять"):
    print(w, list(entries.get(w, b"")))
