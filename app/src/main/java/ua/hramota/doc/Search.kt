package ua.hramota.doc

/** A match on a page: characters [start, end) of the page's text. */
data class SearchHit(val page: Int, val start: Int, val end: Int)

/** Selected text on one page: from [anchor] to [focus] (character indexes, either order). */
data class TextSelection(val page: Int, val anchor: Int, val focus: Int) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus) + 1
}

/**
 * Text search that forgives what people do not notice: letter case, the three Ukrainian apostrophes
 * (' ’ ʼ), long and short dashes, no-break spaces, line breaks and runs of spaces, soft hyphens.
 */
object Search {
    /** Longest query searched; longer ones are cut. */
    const val MAX_QUERY = 200

    /** [text] made comparable, and for every character of the result its position in [text]. */
    fun normalize(text: CharSequence): Pair<String, IntArray> {
        val sb = StringBuilder(text.length)
        val map = IntArray(text.length + 1)
        var space = false
        for (i in text.indices) {
            val c = fold(text[i]) ?: continue
            if (c == ' ') {
                if (space || sb.isEmpty()) continue
                space = true
            } else space = false
            map[sb.length] = i
            sb.append(c)
        }
        return sb.toString() to map.copyOf(sb.length)
    }

    private fun fold(c: Char): Char? = when (c) {
        '­' -> null
        '’', 'ʼ', '‘', '`', '´', '′' -> '\''
        '–', '—', '‒', '−', '‑' -> '-'
        '«', '»', '“', '”', '„' -> '"'
        '\n', '\r', '\t', ' ', ' ', ' ', ' ' -> ' '
        '￼' -> ' '
        else -> if (c.isWhitespace()) ' ' else c.lowercaseChar()
    }

    /** Every non-overlapping match of [query] in [text], as character ranges of [text]. */
    fun find(text: CharSequence, query: String): List<IntRange> {
        val q = normalize(query.take(MAX_QUERY)).first.trim()
        if (q.isEmpty()) return emptyList()
        val (t, map) = normalize(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (out.size < 10_000) {
            val i = t.indexOf(q, from)
            if (i < 0) break
            out += map[i]..map[i + q.length - 1]
            from = i + q.length
        }
        return out
    }

    /** The word around character [index] of [text]: letters, digits, apostrophes and inner hyphens. */
    fun wordAt(text: CharSequence, index: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val i = index.coerceIn(0, text.length - 1)
        fun inWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == 'ʼ' || c == '-' || c == '­'
        if (!inWord(text[i])) return i..i
        var a = i
        var b = i
        while (a > 0 && inWord(text[a - 1])) a--
        while (b < text.length - 1 && inWord(text[b + 1])) b++
        // A hyphen or apostrophe at the edge is punctuation, not part of the word
        while (a < b && !text[a].isLetterOrDigit()) a++
        while (b > a && !text[b].isLetterOrDigit()) b--
        return a..b
    }
}
