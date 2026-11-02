package ua.hramota.doc.book

import ua.hramota.doc.Paragraph

/** Bold, italic or raised (superscript, a note mark) text from [start] to [end]. */
data class Span(val start: Int, val end: Int, val bold: Boolean = false, val italic: Boolean = false, val raised: Boolean = false)

/** What a book is made of, in reading order. */
sealed interface BookBlock {
    /** [kind]: paragraph, heading level 1–3, quote, poem line, centred subtitle, or a short break. */
    data class Text(val text: String, val spans: List<Span>, val kind: Kind) : BookBlock
    data class Image(val key: String) : BookBlock

    enum class Kind { PARAGRAPH, HEADING1, HEADING2, HEADING3, QUOTE, POEM, SUBTITLE, BREAK, /** The rest of a very long paragraph: no indent. */ CONTINUED }
}

/** A book read from EPUB or FB2: title, author, its blocks and pictures (by key). */
class Book(
    val title: String, val author: String, val blocks: List<BookBlock>, val images: Map<String, ByteArray>, val cover: String?,
    /** The book's language as written in it ("uk", "en-GB"…), for hyphenation; empty if not said. */
    val language: String = "",
) {
    /** The text, for comparing versions and the inspector-free reading of formats elsewhere. */
    fun paragraphs(): List<Paragraph> = blocks.filterIsInstance<BookBlock.Text>().map {
        Paragraph(it.text, when (it.kind) { BookBlock.Kind.HEADING1 -> 1; BookBlock.Kind.HEADING2 -> 2; BookBlock.Kind.HEADING3 -> 3; else -> 0 })
    }

    companion object {
        const val MAX_BLOCKS = 300_000
        const val MAX_TEXT = 40_000_000
        const val MAX_IMAGES = 96L * 1024 * 1024
    }
}

/** Builds blocks from inline text and styles as a reader walks through a document. */
internal class BlockBuilder {
    val blocks = ArrayList<BookBlock>()
    private val sb = StringBuilder()
    private val spans = ArrayList<Span>()
    private var total = 0
    var bold = 0
    var italic = 0
    var raised = 0
    private var styleStart = 0
    private var styleKey = 0

    val full: Boolean get() = blocks.size >= Book.MAX_BLOCKS || total >= Book.MAX_TEXT

    private fun key() = (if (bold > 0) 1 else 0) or (if (italic > 0) 2 else 0) or (if (raised > 0) 4 else 0)

    /** Call before a style changes, so the text so far keeps the style it had. */
    fun styleChanging() {
        if (sb.length > styleStart && styleKey != 0) spans += Span(styleStart, sb.length, styleKey and 1 != 0, styleKey and 2 != 0, styleKey and 4 != 0)
        styleStart = sb.length
    }
    fun styleChanged() { styleKey = key(); styleStart = sb.length }

    fun text(t: String) {
        if (full) return
        // Spaces and line ends in the source collapse to single spaces, as in a browser
        for (c in t) {
            // The text limit holds inside one huge piece of text too
            if (total + sb.length >= Book.MAX_TEXT) return
            val ch = if (c == '\n' || c == '\r' || c == '\t') ' ' else c
            if (ch == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) continue
            sb.append(ch)
        }
    }

    /** Text whose line ends are kept (preformatted). */
    fun preformatted(t: String) {
        val lines = t.split('\n')
        lines.forEachIndexed { i, line -> if (i > 0) { if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n') }; text(line) }
    }

    /** After a damaged part: no bold or italic carried into the rest of the book. */
    fun resetStyle() { styleChanging(); bold = 0; italic = 0; raised = 0; styleChanged() }

    fun lineBreak() { if (sb.isNotEmpty() && sb.last() != '\n') { if (sb.last() == ' ') sb.setLength(sb.length - 1); sb.append('\n') } }

    fun end(kind: BookBlock.Kind) {
        styleChanging()
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\n')) sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() || kind == BookBlock.Kind.BREAK) {
            val text = sb.toString()
            val all = spans.filter { it.start < text.length }.map { if (it.end > text.length) it.copy(end = text.length) else it }
            // A paragraph pages long is cut after a sentence, so no page waits for one huge layout
            var from = 0
            var k = 0
            if (text.isEmpty()) blocks += BookBlock.Text(text, emptyList(), kind)
            while (from < text.length) {
                var to = text.length
                if (to - from > LONG) {
                    to = text.lastIndexOf(". ", from + LONG - 2).takeIf { it > from + LONG / 2 }?.plus(2)
                        ?: text.lastIndexOf(' ', from + LONG - 1).takeIf { it > from + LONG / 2 }?.plus(1) ?: (from + LONG)
                    // Never between the two halves of one character (an emoji, say)
                    if (Character.isHighSurrogate(text[to - 1])) to--
                }
                val part = text.substring(from, to).trimEnd()
                // Spans are in order: one pass over them for all parts
                while (k < all.size && all[k].end <= from) k++
                val partSpans = ArrayList<Span>()
                var j = k
                while (j < all.size && all[j].start < to) {
                    val sp = all[j]
                    val a = maxOf(0, sp.start - from); val e = minOf(part.length, sp.end - from)
                    if (a < e) partSpans += Span(a, e, sp.bold, sp.italic, sp.raised)
                    j++
                }
                if (blocks.size >= Book.MAX_BLOCKS) break
                blocks += BookBlock.Text(part, partSpans, if (from == 0 || kind != BookBlock.Kind.PARAGRAPH) kind else BookBlock.Kind.CONTINUED)
                from = to
            }
            total += text.length
        }
        sb.clear(); spans.clear(); styleStart = 0
    }

    fun image(key: String) { if (!full) blocks += BookBlock.Image(key) }

    private companion object { const val LONG = 6000 }
}

/** Removes a DOCTYPE (with any internal subset) so no entity defined in the file can be expanded. */
internal fun stripDoctype(xml: ByteArray): ByteArray {
    // A file that declares its own entities is refused outright, in any of the encodings a parser
    // would recognise (UTF-8, UTF-16 either way round); then a DOCTYPE is cut out as a second guard
    if (declaresEntities(xml)) throw java.io.IOException("Entities declared in the file")
    val at = indexOf(xml, "<!DOCTYPE".toByteArray(), ignoreCase = true)
    if (at < 0) return xml
    var i = at
    var depth = 0
    var quote = 0
    while (i < xml.size) {
        val c = xml[i].toInt().toChar()
        when {
            quote != 0 -> if (c.code == quote) quote = 0
            c == '"' || c == '\'' -> quote = c.code
            c == '[' -> depth++
            c == ']' -> depth--
            c == '>' -> if (depth <= 0) break
        }
        i++
    }
    return xml.copyOfRange(0, at) + xml.copyOfRange(minOf(i + 1, xml.size), xml.size)
}

internal fun declaresEntities(xml: ByteArray): Boolean {
    val ascii = "<!ENTITY".toByteArray()
    if (indexOf(xml, ascii, ignoreCase = true) >= 0) return true
    val le = ByteArray(ascii.size * 2) { if (it % 2 == 0) ascii[it / 2] else 0 }
    val be = ByteArray(ascii.size * 2) { if (it % 2 == 1) ascii[it / 2] else 0 }
    return indexOf(xml, le, false) >= 0 || indexOf(xml, be, false) >= 0
}

private fun indexOf(data: ByteArray, pattern: ByteArray, ignoreCase: Boolean): Int {
    fun low(b: Byte) = if (ignoreCase && b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b
    val first = low(pattern[0])
    var i = 0
    val end = data.size - pattern.size
    while (i <= end) {
        if (low(data[i]) == first) {
            var k = 1
            while (k < pattern.size && low(data[i + k]) == low(pattern[k])) k++
            if (k == pattern.size) return i
        }
        i++
    }
    return -1
}

/** Named HTML characters common in books (the parser knows only the five XML ones). */
internal val HTML_ENTITIES = mapOf(
    "nbsp" to " ", "shy" to "­", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "copy" to "©", "reg" to "®", "trade" to "™",
    "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "minus" to "−", "sect" to "§", "para" to "¶", "euro" to "€",
    "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "‌", "zwj" to "‍", "prime" to "′", "Prime" to "″",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "iexcl" to "¡", "iquest" to "¿", "acute" to "´", "uml" to "¨",
    "lsaquo" to "‹", "rsaquo" to "›", "sbquo" to "‚", "dagger" to "†", "Dagger" to "‡", "permil" to "‰", "larr" to "←", "rarr" to "→",
    "uarr" to "↑", "darr" to "↓", "hArr" to "⇔", "ne" to "≠", "le" to "≤", "ge" to "≥", "infin" to "∞", "plusmn" to "±", "divide" to "÷",
    "cent" to "¢", "pound" to "£", "yen" to "¥", "curren" to "¤", "ordf" to "ª", "ordm" to "º", "sup1" to "¹", "sup2" to "²", "sup3" to "³",
    "micro" to "µ", "not" to "¬", "macr" to "¯", "cedil" to "¸", "brvbar" to "¦", "oline" to "‾", "circ" to "ˆ", "tilde" to "˜",
) + latinLetters()

/** The accented Latin letters by their HTML names (À–ÿ): "eacute", "Ouml", "ccedil"… */
private fun latinLetters(): Map<String, String> {
    val names = listOf(
        "Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc", "Iuml",
        "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "", "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig",
        "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil", "egrave", "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml",
        "eth", "ntilde", "ograve", "oacute", "ocirc", "otilde", "ouml", "", "oslash", "ugrave", "uacute", "ucirc", "uuml", "yacute", "thorn", "yuml",
    )
    return names.withIndex().filter { it.value.isNotEmpty() }.associate { (i, n) -> n to (0xC0 + i).toChar().toString() } +
        mapOf("OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š", "scaron" to "š", "Yuml" to "Ÿ", "fnof" to "ƒ")
}
