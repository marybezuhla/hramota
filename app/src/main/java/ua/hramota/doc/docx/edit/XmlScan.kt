package ua.hramota.doc.docx.edit

import java.io.IOException

/**
 * An element found in XML text, with where it is: [start] at its "<", [openEnd] after its start tag,
 * [closeStart] at its end tag (equal to [openEnd] when it closes itself) and [end] after it.
 */
class Elem(val name: String, val start: Int, val openEnd: Int, val closeStart: Int, val end: Int, val attrs: Map<String, String>) {
    /** Local name, without the prefix. */
    val local: String get() = name.substringAfter(':')
    val empty: Boolean get() = closeStart == openEnd
}

/**
 * Finds elements in XML text without changing a single character of it, so an edit can replace one
 * part exactly and leave everything else as the file had it. Depth is counted, never recursed.
 */
object XmlScan {
    /** The elements directly inside [from, to) (normally the inside of one element). */
    fun children(s: String, from: Int, to: Int): List<Elem> {
        val out = ArrayList<Elem>()
        var i = from
        // The open element at depth 1 we are inside of, and how deep we are
        var depth = 0
        var curName = ""; var curStart = 0; var curOpenEnd = 0; var curAttrs: Map<String, String> = emptyMap()
        while (i < to) {
            val lt = s.indexOf('<', i)
            if (lt < 0 || lt >= to) break
            when {
                s.startsWith("<!--", lt) -> { i = end(s, s.indexOf("-->", lt + 4), 3) }
                s.startsWith("<![CDATA[", lt) -> { i = end(s, s.indexOf("]]>", lt + 9), 3) }
                s.startsWith("<?", lt) -> { i = end(s, s.indexOf("?>", lt + 2), 2) }
                s.startsWith("<!", lt) -> throw IOException("Unexpected declaration")
                s.startsWith("</", lt) -> {
                    val gt = s.indexOf('>', lt)
                    if (gt < 0) throw IOException("Unclosed tag")
                    depth--
                    if (depth < 0) throw IOException("Unbalanced")
                    if (depth == 0) {
                        val name = s.substring(lt + 2, gt).trim()
                        if (name != curName) throw IOException("Mismatched $name")
                        out += Elem(curName, curStart, curOpenEnd, lt, gt + 1, curAttrs)
                    }
                    i = gt + 1
                }
                else -> {
                    val gt = tagEnd(s, lt)
                    val selfClosing = s[gt - 1] == '/'
                    if (depth == 0) {
                        val inner = s.substring(lt + 1, if (selfClosing) gt - 1 else gt)
                        val name = inner.takeWhile { !it.isWhitespace() }
                        val attrs = attributes(inner.substring(name.length))
                        if (selfClosing) out += Elem(name, lt, gt + 1, gt + 1, gt + 1, attrs)
                        else { curName = name; curStart = lt; curOpenEnd = gt + 1; curAttrs = attrs; depth = 1 }
                    } else if (!selfClosing) depth++
                    i = gt + 1
                }
            }
        }
        if (depth != 0) throw IOException("Unclosed element")
        return out
    }

    /** The children inside [e]. */
    fun children(s: String, e: Elem): List<Elem> = if (e.empty) emptyList() else children(s, e.openEnd, e.closeStart)

    private fun end(s: String, at: Int, len: Int): Int = if (at < 0) throw IOException("Unclosed") else at + len

    /** Where a start tag ends ("&gt;" inside quoted values does not count). */
    private fun tagEnd(s: String, lt: Int): Int {
        var i = lt + 1
        var quote = 0.toChar()
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            i++
        }
        throw IOException("Unclosed tag")
    }

    private fun attributes(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == '/')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val name = s.substring(i, eq).trim()
            var q = eq + 1
            while (q < s.length && s[q].isWhitespace()) q++
            if (q >= s.length) break
            val quote = s[q]
            val close = s.indexOf(quote, q + 1)
            if (close < 0) break
            out[name] = unescape(s.substring(q + 1, close))
            i = close + 1
        }
        return out
    }

    /** XML text as characters: the five named entities and numbered characters. */
    fun unescape(s: String): String {
        if ('&' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i && semi - i <= 10) {
                    val ent = s.substring(i + 1, semi)
                    val ch: String? = when {
                        ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (ch != null) { b.append(ch); i = semi + 1; continue }
                }
            }
            b.append(c); i++
        }
        return b.toString()
    }

    /** Text for XML, in elements or in quoted values; characters XML cannot hold are left out. */
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) when {
            c == '&' -> b.append("&amp;")
            c == '<' -> b.append("&lt;")
            c == '>' -> b.append("&gt;")
            c == '"' -> b.append("&quot;")
            c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
            c == '￾' || c == '￿' -> {}
            else -> b.append(c)
        }
        return b.toString()
    }
}
