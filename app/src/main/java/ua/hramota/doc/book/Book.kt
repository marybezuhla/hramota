package ua.hramota.doc.book

import ua.hramota.doc.Paragraph

/** Bold, italic or raised (superscript, a note mark) text from [start] to [end]. */
data class Span(val start: Int, val end: Int, val bold: Boolean = false, val italic: Boolean = false, val raised: Boolean = false)

/** What a book is made of, in reading order. */
sealed interface BookBlock {
    /** [kind]: paragraph, heading level 1–3, quote, poem line, centred subtitle, or a short break. */
    data class Text(val text: String, val spans: List<Span>, val kind: Kind) : BookBlock
    data class Image(val key: String) : BookBlock

    enum class Kind { PARAGRAPH, HEADING1, HEADING2, HEADING3, QUOTE, POEM, SUBTITLE, BREAK }
}

/** A book read from EPUB or FB2: title, author, its blocks and pictures (by key). */
class Book(val title: String, val author: String, val blocks: List<BookBlock>, val images: Map<String, ByteArray>, val cover: String?) {
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
            val ch = if (c == '\n' || c == '\r' || c == '\t') ' ' else c
            if (ch == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) continue
            sb.append(ch)
        }
    }

    fun lineBreak() { if (sb.isNotEmpty() && sb.last() != '\n') { if (sb.last() == ' ') sb.setLength(sb.length - 1); sb.append('\n') } }

    fun end(kind: BookBlock.Kind) {
        styleChanging()
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\n')) sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() || kind == BookBlock.Kind.BREAK) {
            val text = sb.toString()
            blocks += BookBlock.Text(text, spans.filter { it.start < text.length }.map { if (it.end > text.length) it.copy(end = text.length) else it }, kind)
            total += text.length
        }
        sb.clear(); spans.clear(); styleStart = 0
    }

    fun image(key: String) { if (!full) blocks += BookBlock.Image(key) }
}

/** Removes a DOCTYPE (with any internal subset) so no entity defined in the file can be expanded. */
internal fun stripDoctype(xml: ByteArray): ByteArray {
    val head = String(xml, 0, minOf(xml.size, 4096), Charsets.ISO_8859_1)
    val at = head.indexOf("<!DOCTYPE", ignoreCase = true)
    if (at < 0) return xml
    var i = at
    var depth = 0
    while (i < xml.size) {
        when (xml[i].toInt().toChar()) {
            '[' -> depth++
            ']' -> depth--
            '>' -> if (depth <= 0) break
        }
        i++
    }
    return xml.copyOfRange(0, at) + xml.copyOfRange(minOf(i + 1, xml.size), xml.size)
}

/** Named HTML characters common in books (the parser knows only the five XML ones). */
internal val HTML_ENTITIES = mapOf(
    "nbsp" to " ", "shy" to "­", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "copy" to "©", "reg" to "®", "trade" to "™",
    "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "minus" to "−", "sect" to "§", "para" to "¶", "euro" to "€",
    "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "‌", "zwj" to "‍", "prime" to "′", "Prime" to "″",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "iexcl" to "¡", "iquest" to "¿", "acute" to "´", "uml" to "¨",
)
