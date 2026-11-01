package ua.hramota.diff

/** One piece of a changed paragraph. */
data class Segment(val text: String, val kind: Kind) {
    enum class Kind { SAME, ADDED, REMOVED }
}

/** One paragraph of the comparison, in reading order. */
sealed interface Block {
    data class Same(val text: String) : Block
    data class Added(val text: String) : Block
    data class Removed(val text: String) : Block
    /** The same paragraph, edited: word-level pieces. */
    data class Changed(val segments: List<Segment>) : Block
}

data class Comparison(
    val blocks: List<Block>,
    /** Indexes into [blocks] of the paragraphs a reader steps through with "next change". */
    val changes: List<Int>,
    /** True when the middle part was too large for a paragraph-by-paragraph match. */
    val coarse: Boolean,
)

/**
 * Compares two versions of a text: first by paragraphs, then by words inside paragraphs that
 * were edited, so changes read the way they do in Word's "Compare".
 */
object Diff {
    /** Largest LCS table (cells) built for one comparison; bigger middles are shown as replaced. */
    private const val MAX_CELLS = 4_000_000L

    /** Edited paragraphs share at least this much of their words; otherwise removed + added. */
    private const val SIMILAR = 0.4

    fun compare(older: List<String>, newer: List<String>): Comparison {
        val blocks = ArrayList<Block>()
        var coarse = false
        val ops = lcsOps(older, newer) { a, b -> a == b }
        if (ops == null) {
            coarse = true
            coarseOps(older, newer, blocks)
        } else {
            var i = 0
            while (i < ops.size) {
                val op = ops[i]
                if (op is Op.Keep) { blocks += Block.Same(op.a); i++; continue }
                // A run of removals and additions: pair up edited paragraphs, keep the rest as is.
                val removed = ArrayList<String>()
                val added = ArrayList<String>()
                while (i < ops.size && ops[i] !is Op.Keep) {
                    when (val o = ops[i]) {
                        is Op.Del -> removed += o.a
                        is Op.Ins -> added += o.b
                        is Op.Keep -> Unit
                    }
                    i++
                }
                pairRun(removed, added, blocks)
            }
        }
        val changes = blocks.indices.filter { blocks[it] !is Block.Same }
        return Comparison(blocks, changes, coarse)
    }

    private fun pairRun(removed: List<String>, added: List<String>, out: MutableList<Block>) {
        var r = 0
        var a = 0
        while (r < removed.size || a < added.size) {
            when {
                r < removed.size && a < added.size && similarity(removed[r], added[a]) >= SIMILAR -> {
                    out += Block.Changed(words(removed[r], added[a])); r++; a++
                }
                r < removed.size && (a >= added.size || removed.size - r >= added.size - a) -> {
                    out += Block.Removed(removed[r]); r++
                }
                else -> { out += Block.Added(added[a]); a++ }
            }
        }
    }

    private fun coarseOps(older: List<String>, newer: List<String>, out: MutableList<Block>) {
        var start = 0
        while (start < older.size && start < newer.size && older[start] == newer[start]) start++
        var endO = older.size
        var endN = newer.size
        while (endO > start && endN > start && older[endO - 1] == newer[endN - 1]) { endO--; endN-- }
        for (k in 0 until start) out += Block.Same(older[k])
        for (k in start until endO) out += Block.Removed(older[k])
        for (k in start until endN) out += Block.Added(newer[k])
        for (k in endO until older.size) out += Block.Same(older[k])
    }

    /** Word-level pieces of an edited paragraph; spaces stay attached to the word before them. */
    fun words(older: String, newer: String): List<Segment> {
        val a = tokens(older)
        val b = tokens(newer)
        val ops = lcsOps(a, b) { x, y -> x.trimEnd() == y.trimEnd() }
            ?: return listOf(Segment(older, Segment.Kind.REMOVED), Segment(newer, Segment.Kind.ADDED))
        val out = ArrayList<Segment>()
        fun add(text: String, kind: Segment.Kind) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = Segment(last.text + text, kind)
            else out += Segment(text, kind)
        }
        // Within a run of changes, show everything removed first, then everything added.
        val removed = StringBuilder()
        val added = StringBuilder()
        fun flush() {
            if (removed.isNotEmpty()) add(removed.toString(), Segment.Kind.REMOVED)
            if (added.isNotEmpty()) add(added.toString(), Segment.Kind.ADDED)
            removed.clear(); added.clear()
        }
        for (op in ops) when (op) {
            is Op.Keep -> { flush(); add(op.b, Segment.Kind.SAME) }
            is Op.Del -> removed.append(op.a)
            is Op.Ins -> added.append(op.b)
        }
        flush()
        return out
    }

    /** Splits text into words, each with the spaces that follow it. */
    fun tokens(text: String): List<String> = Regex("\\S+\\s*|\\s+").findAll(text).map { it.value }.toList()

    /** Share of words two paragraphs have in common (0..1). */
    fun similarity(a: String, b: String): Double {
        val wa = a.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wb = b.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        for (w in wa) counts[w] = (counts[w] ?: 0) + 1
        var common = 0
        for (w in wb) {
            val c = counts[w] ?: 0
            if (c > 0) { common++; counts[w] = c - 1 }
        }
        return 2.0 * common / (wa.size + wb.size)
    }

    private sealed interface Op<T> {
        data class Keep<T>(val a: T, val b: T) : Op<T>
        data class Del<T>(val a: T) : Op<T>
        data class Ins<T>(val b: T) : Op<T>
    }

    /**
     * Longest common subsequence as a list of operations. The shared start and end are trimmed
     * first; returns null when the middle would need more than [MAX_CELLS] table cells.
     */
    private fun <T> lcsOps(a: List<T>, b: List<T>, same: (T, T) -> Boolean): List<Op<T>>? {
        var start = 0
        while (start < a.size && start < b.size && same(a[start], b[start])) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && same(a[endA - 1], b[endB - 1])) { endA--; endB-- }
        val n = endA - start
        val m = endB - start
        if (n.toLong() * m > MAX_CELLS) return null

        val ops = ArrayList<Op<T>>(a.size + b.size)
        for (k in 0 until start) ops += Op.Keep(a[k], b[k])
        // table[i][j] = LCS length of a[start+i..endA) and b[start+j..endB)
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (same(a[start + i], b[start + j])) table[i + 1][j + 1] + 1
            else maxOf(table[i + 1][j], table[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                same(a[start + i], b[start + j]) -> { ops += Op.Keep(a[start + i], b[start + j]); i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { ops += Op.Del(a[start + i]); i++ }
                else -> { ops += Op.Ins(b[start + j]); j++ }
            }
        }
        while (i < n) { ops += Op.Del(a[start + i]); i++ }
        while (j < m) { ops += Op.Ins(b[start + j]); j++ }
        for (k in 0 until a.size - endA) ops += Op.Keep(a[endA + k], b[endB + k])
        return ops
    }
}
