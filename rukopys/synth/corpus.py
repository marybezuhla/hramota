"""Lines of Ukrainian to write: real sentences (Hramota's practice text) and lines made of words from
the stress dictionary built into Hramota (2.9 million word forms, ukrainian-word-stress, MIT), with
capitals, numbers and punctuation now and then, as people write them.
"""
import random
import struct
import zlib


def dictionary_words(path):
    """Every word form in Hramota's stress dictionary (voice/stress.bin)."""
    data = open(path, "rb").read()
    assert data[:4] == b"HRST"
    pos = 4
    (version,) = struct.unpack_from(">h", data, pos); pos += 2
    assert version == 1
    _words, blocks = struct.unpack_from(">ii", data, pos); pos += 8
    alphabet = data[pos:pos + 80].rstrip(b"\0").decode("utf-8"); pos += 80
    (index_size,) = struct.unpack_from(">i", data, pos); pos += 4
    index_start = pos
    offsets = []
    for _ in range(blocks):
        (off,) = struct.unpack_from(">i", data, pos); pos += 4
        n = data[pos]; pos += 1 + n
        offsets.append(off)
    (last,) = struct.unpack_from(">i", data, pos)
    offsets.append(last)
    at = index_start + index_size
    words = []
    for k in range(blocks):
        raw = zlib.decompress(data[at + offsets[k]: at + offsets[k + 1]], -15)
        i = 0
        prev = b""
        while i < len(raw):
            shared, rest = raw[i], raw[i + 1]; i += 2
            word = prev[:shared] + raw[i:i + rest]; i += rest
            prev = word
            head = raw[i]; i += 1
            if head & 0x80 == 0:
                i += head
            else:
                for _ in range(head & 0x7F):
                    i += 1
                    n = raw[i]; i += 1 + n
            words.append("".join(alphabet[b - 1] for b in word))
    return words


def sentences(path):
    text = open(path, encoding="utf-8").read()
    return [s.strip() for s in text.replace("\n", " ").split(".") if len(s.strip()) > 3]


PUNCT = [",", ".", "!", "?", ":", ";", " –", "…"]


def line(rnd, words, real, alphabet):
    """One line to write (only letters the model knows)."""
    r = rnd.random()
    if r < 0.3 and real:
        s = rnd.choice(real)
        ws = s.split()
        if len(ws) > 6:
            k = rnd.randrange(len(ws) - 5)
            ws = ws[k:k + rnd.randint(2, 6)]
        text = " ".join(ws)
    else:
        n = rnd.choice([1, 1, 2, 2, 3, 3, 4, 5])
        ws = [rnd.choice(words) for _ in range(n)]
        for i in range(len(ws)):
            if rnd.random() < 0.15:
                ws[i] = ws[i].capitalize()
            if rnd.random() < 0.03:
                ws[i] = ws[i].upper()
            if rnd.random() < 0.06:
                ws[i] = str(rnd.choice([rnd.randint(0, 99), rnd.randint(100, 9999), rnd.randint(1, 31)]))
            if i < len(ws) - 1 and rnd.random() < 0.12:
                ws[i] += rnd.choice(PUNCT[:2])
        text = " ".join(ws)
        if rnd.random() < 0.3:
            text = text[0].upper() + text[1:]
        if rnd.random() < 0.3:
            text += rnd.choice(PUNCT)
        if rnd.random() < 0.04:
            text = "«" + text + "»"
    text = text.replace("'", "’") if rnd.random() < 0.5 else text.replace("’", "'")
    text = "".join(c for c in text if c in alphabet)
    return " ".join(text.split())[:48] or "так"
