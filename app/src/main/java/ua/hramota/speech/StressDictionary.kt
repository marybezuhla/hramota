package ua.hramota.speech

import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Where the stress falls in Ukrainian words: 2.9 million word forms (ukrainian-word-stress, MIT), in the compact form
 * `tools/voices/build_stress.py` writes. Only the block index stays in memory; a block (64 words) is inflated when a
 * word in it is looked up, and the last few are kept.
 */
class StressDictionary(private val data: ByteBuffer) {
    /** One way a word can be said: [positions] are the indices the stress mark goes after; [tags] as in the source. */
    class Option(val positions: IntArray, val tags: ByteArray) {
        fun has(tag: Int) = tags.any { (it.toInt() and 0xFF) == tag }
    }

    private val alphabet: String
    private val code = IntArray(0x500)
    private val firsts: Array<ByteArray>
    private val offsets: IntArray
    private val blocksAt: Int

    init {
        val b = data.duplicate()
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "HRST") { "not a stress dictionary" }
        require(b.short.toInt() == 1) { "unknown version" }
        b.int // words
        val blocks = b.int
        val alpha = ByteArray(80).also { b.get(it) }
        alphabet = String(alpha, Charsets.UTF_8).trimEnd('\u0000')
        alphabet.forEachIndexed { i, c -> if (c.code < code.size) code[c.code] = i + 1 }
        val indexSize = b.int
        val indexStart = b.position()
        firsts = Array(blocks) { ByteArray(0) }
        offsets = IntArray(blocks + 1)
        for (k in 0 until blocks) {
            offsets[k] = b.int
            val n = b.get().toInt() and 0xFF
            firsts[k] = ByteArray(n).also { b.get(it) }
        }
        offsets[blocks] = b.int
        blocksAt = indexStart + indexSize
    }

    /** The word in the dictionary's alphabet, or null when it has a letter the dictionary does not. */
    private fun encode(word: String): ByteArray? {
        val out = ByteArray(word.length)
        for ((i, ch) in word.withIndex()) {
            val c = when (ch) { '’', 'ʼ', '`' -> '\''; else -> ch.lowercaseChar() }
            val v = if (c.code < code.size) code[c.code] else 0
            if (v == 0) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (d != 0) return d }
        return a.size - b.size
    }

    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 24
    }

    private fun block(k: Int): ByteArray = synchronized(cache) {
        cache[k] ?: run {
            val packed = ByteArray(offsets[k + 1] - offsets[k])
            data.duplicate().apply { position(blocksAt + offsets[k]) }.get(packed)
            val inflater = Inflater(true)
            try {
                inflater.setInput(packed)
                val out = java.io.ByteArrayOutputStream(packed.size * 4)
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } finally { inflater.end() }
        }.also { cache[k] = it }
    }

    /** The ways [word] can be stressed (one for nearly all words), or empty when it is not known. */
    fun lookup(word: String): List<Option> {
        val key = encode(word) ?: return emptyList()
        // The last block whose first word is not after the key
        var lo = 0; var hi = firsts.size - 1; var at = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (compare(firsts[mid], key) <= 0) { at = mid; lo = mid + 1 } else hi = mid - 1 }
        if (at < 0) return emptyList()
        val b = block(at)
        var i = 0
        var prev = ByteArray(0)
        while (i < b.size) {
            val shared = b[i++].toInt() and 0xFF
            val rest = b[i++].toInt() and 0xFF
            val word2 = ByteArray(shared + rest)
            System.arraycopy(prev, 0, word2, 0, shared)
            System.arraycopy(b, i, word2, shared, rest); i += rest
            prev = word2
            val head = b[i++].toInt() and 0xFF
            val options = ArrayList<Option>()
            if (head and 0x80 == 0) {
                val pos = IntArray(head) { b[i + it].toInt() and 0xFF }; i += head
                options += Option(pos, ByteArray(0))
            } else {
                repeat(head and 0x7F) {
                    val p = b[i++].toInt() and 0xFF
                    val n = b[i++].toInt() and 0xFF
                    options += Option(intArrayOf(p), b.copyOfRange(i, i + n)); i += n
                }
            }
            val c = compare(word2, key)
            if (c == 0) return options
            if (c > 0) return emptyList()
        }
        return emptyList()
    }

    companion object {
        // Tag codes of the source dictionary (ukrainian_word_stress/tags.py)
        const val SING = 0x11; const val PLUR = 0x12
        const val NOM = 0x20; const val GEN = 0x21; const val DAT = 0x22; const val ACC = 0x23; const val INS = 0x24; const val LOC = 0x25; const val VOC = 0x26
        const val NEUT = 0x30; const val MASC = 0x31; const val FEM = 0x32
        const val INF = 0x41; const val CONV = 0x42
        const val NOUN = 0x61; const val ADJ = 0x62; const val PRON = 0x66; const val VERB = 0x67; const val PROPN = 0x68; const val ADV = 0x69; const val NOUN2 = 0x6A; const val NUM = 0x6B
    }
}
