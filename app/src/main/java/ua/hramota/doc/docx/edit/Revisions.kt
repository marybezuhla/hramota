package ua.hramota.doc.docx.edit

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Tracked changes in a Word document, all accepted or all rejected, written into a new file: added
 * and deleted text, moved text, and changed formatting. Everything else is copied as it is.
 */
object Revisions {
    private val PARTS = Regex("word/(document|header\\d*|footer\\d*|footnotes|endnotes|comments)\\.xml")
    private const val MAX_PART = 64L * 1024 * 1024

    /** How many changes [xml] has (added, deleted or moved pieces and formatting changes). */
    fun count(xml: String): Int = Regex("<w:(ins|del|moveFrom|moveTo|rPrChange|pPrChange)[\\s>]").findAll(xml).count()

    fun write(input: InputStream, out: OutputStream, accept: Boolean) {
        ZipInputStream(input).use { zip ->
            val z = ZipOutputStream(out)
            var n = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++n > 20_000) throw IOException("Too many entries")
                z.putNextEntry(ZipEntry(e.name))
                if (PARTS.matches(e.name.removePrefix("/"))) {
                    val bytes = readAll(zip)
                    val x = String(bytes, Charsets.UTF_8)
                    z.write(resolve(x, accept).toByteArray(Charsets.UTF_8))
                } else zip.copyTo(z)
                z.closeEntry()
            }
            z.finish(); z.flush()
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) { val k = input.read(buf); if (k < 0) break; total += k; if (total > MAX_PART) throw IOException("Part too large"); out.write(buf, 0, k) }
        return out.toByteArray()
    }

    /** One part with its changes accepted or rejected. */
    @Synchronized fun resolve(x: String, accept: Boolean): String {
        val sb = StringBuilder(x.length)
        work = 0L; budget = 40L * x.length + 1_000_000
        walk(x, 0, x.length, sb, accept, 0)
        return sb.toString()
    }

    // How much has been scanned: a deeply nested part is refused rather than scanned for minutes
    private var work = 0L
    private var budget = 0L

    /** Elements whose old properties are kept in a change inside them (rejecting puts them back). */
    private val WITH_OLD = setOf("rPr", "pPr", "sectPr", "tblPr", "trPr", "tcPr", "tblGrid")

    /** Whether paragraph [p]'s mark goes: deleted when accepting, added when rejecting (it joins the next paragraph). */
    private fun markGoes(x: String, p: Elem, accept: Boolean): Boolean {
        val ppr = XmlScan.children(x, p).firstOrNull { it.local == "pPr" } ?: return false
        val rpr = XmlScan.children(x, ppr).firstOrNull { it.local == "rPr" } ?: return false
        return XmlScan.children(x, rpr).any { it.local == (if (accept) "del" else "ins") || it.local == (if (accept) "moveFrom" else "moveTo") }
    }

    /** A paragraph's content after its properties, resolved. */
    private fun content(x: String, p: Elem, accept: Boolean, depth: Int): String {
        val b = StringBuilder()
        val ppr = XmlScan.children(x, p).firstOrNull { it.local == "pPr" }
        walk(x, ppr?.end ?: p.openEnd, p.closeStart, b, accept, depth + 1)
        return b.toString()
    }

    private val PROP_CHANGES = setOf("rPrChange", "pPrChange", "sectPrChange", "tblPrChange", "trPrChange", "tcPrChange", "tblGridChange", "numberingChange")
    /** Marks pairing with others elsewhere: kept when the text around them goes, so no pair is left half. */
    private val PAIRED = setOf("bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd")
    private val RANGE_MARKS = setOf("moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd")

    private fun walk(x: String, from: Int, to: Int, sb: StringBuilder, accept: Boolean, depth: Int) {
        if (depth > 200) throw IOException("Too deep")
        work += to - from
        if (work > budget) throw IOException("Too complex")
        var pos = from
        // A paragraph whose mark goes: its words wait to be put at the start of the next paragraph
        var waiting: Pair<Elem, String>? = null
        fun flush() {
            val (p, words) = waiting ?: return
            waiting = null
            // No paragraph follows here: it stays a paragraph of its own
            sb.append(x, p.start, p.openEnd)
            XmlScan.children(x, p).firstOrNull { it.local == "pPr" }?.let { walk(x, it.start, it.end, sb, accept, depth + 1) }
            sb.append(words).append("</").append(p.name).append('>')
        }
        for (e in XmlScan.children(x, from, to)) {
            if (e.local == "p" && !e.empty && !e.selfClosing) {
                if (markGoes(x, e, accept)) {
                    sb.append(x, pos, e.start); pos = e.end
                    val words = content(x, e, accept, depth)
                    waiting = waiting?.let { it.first to it.second + words } ?: (e to words)
                    continue
                }
                waiting?.let { (_, words) ->
                    waiting = null
                    sb.append(x, pos, e.start); pos = e.end
                    val ppr = XmlScan.children(x, e).firstOrNull { it.local == "pPr" }
                    sb.append(x, e.start, e.openEnd)
                    if (ppr != null) walk(x, ppr.start, ppr.end, sb, accept, depth + 1)
                    sb.append(words).append(content(x, e, accept, depth)).append(x, e.closeStart, e.end)
                    continue
                }
            } else if (waiting != null && e.local != "bookmarkStart" && e.local != "bookmarkEnd") flush()
            sb.append(x, pos, e.start)
            pos = e.end
            val kept = if (accept) setOf("ins", "moveTo") else setOf("del", "moveFrom")
            val dropped = if (accept) setOf("del", "moveFrom") else setOf("ins", "moveTo")
            when {
                e.local in RANGE_MARKS -> {}
                // A paragraph mark or a table row marked as added or deleted (an empty element)
                e.local in kept && e.empty -> {}
                e.local in dropped && e.empty -> {}
                // A table row added (rejected) or deleted (accepted): the whole row goes
                e.local == "tr" && rowMarked(x, e, if (accept) "del" else "ins") -> keepPaired(x, e, sb, depth)
                // A table with every row gone goes too (a table without rows is not allowed)
                e.local == "tbl" && XmlScan.children(x, e).let { k -> k.any { it.local == "tr" } && k.none { it.local == "tr" && !rowMarked(x, it, if (accept) "del" else "ins") } } -> keepPaired(x, e, sb, depth)
                e.local in kept -> walk(x, e.openEnd, e.closeStart, sb, accept, depth + 1)
                e.local in dropped -> keepPaired(x, e, sb, depth)
                e.local in PROP_CHANGES -> {}
                // Formatting changed (of text, a paragraph, a section, a table, its rows and cells): rejecting puts the old back
                !accept && e.local in WITH_OLD && hasChange(x, e) -> {
                    val change = XmlScan.children(x, e).first { it.local == e.local + "Change" }
                    val old = XmlScan.children(x, change).firstOrNull { it.local == e.local }
                    // What the old copy never holds stays: a paragraph's run marks and section, a section's headers and footers
                    val stays = when (e.local) { "pPr" -> setOf("rPr", "sectPr"); "sectPr" -> setOf("headerReference", "footerReference"); else -> emptySet() }
                    if (e.local == "sectPr") {
                        sb.append(x, e.start, e.openEnd)
                        for (k in XmlScan.children(x, e)) if (k.local in stays) walk(x, k.start, k.end, sb, accept, depth + 1)
                        if (old != null && !old.empty) walk(x, old.openEnd, old.closeStart, sb, accept, depth + 1)
                        sb.append("</").append(e.name).append('>')
                    } else if (stays.isNotEmpty()) {
                        sb.append(x, e.start, e.openEnd)
                        if (old != null && !old.empty) walk(x, old.openEnd, old.closeStart, sb, accept, depth + 1)
                        for (k in XmlScan.children(x, e)) if (k.local in stays) walk(x, k.start, k.end, sb, accept, depth + 1)
                        sb.append("</").append(e.name).append('>')
                    } else {
                        if (old == null || old.empty) sb.append("<").append(e.name).append("/>")
                        else sb.append(x, e.start, e.openEnd).append(x, old.openEnd, old.closeStart).append("</").append(e.name).append('>')
                    }
                }
                e.local == "delText" && !accept -> sb.append(rename(x, e, "t"))
                e.local == "delInstrText" && !accept -> sb.append(rename(x, e, "instrText"))
                e.empty || e.selfClosing -> sb.append(x, e.start, e.end)
                else -> { sb.append(x, e.start, e.openEnd); walk(x, e.openEnd, e.closeStart, sb, accept, depth + 1); sb.append(x, e.closeStart, e.end) }
            }
        }
        flush()
        sb.append(x, pos, to)
    }

    /** Of an element that goes, only the paired marks inside it (bookmarks, comment ranges) are written. */
    private fun keepPaired(x: String, e: Elem, sb: StringBuilder, depth: Int) {
        if (depth > 200) throw IOException("Too deep")
        if (e.empty || e.selfClosing) return
        for (k in XmlScan.children(x, e)) {
            if (k.local in PAIRED) sb.append(x, k.start, k.end) else keepPaired(x, k, sb, depth + 1)
        }
    }

    private fun rowMarked(x: String, tr: Elem, mark: String): Boolean =
        XmlScan.children(x, tr).firstOrNull { it.local == "trPr" }?.let { p -> XmlScan.children(x, p).any { it.local == mark } } == true

    private fun hasChange(x: String, e: Elem) = XmlScan.children(x, e).any { it.local == e.local + "Change" }

    /** An element with another local name (its prefix, attributes and text kept). */
    private fun rename(x: String, e: Elem, local: String): String {
        val prefix = e.name.substringBefore(':', "").let { if (it.isEmpty() || it == e.name) "" else "$it:" }
        val open = x.substring(e.start, e.openEnd).replaceFirst(e.name, prefix + local)
        if (e.selfClosing) return open
        return open + x.substring(e.openEnd, e.closeStart) + "</" + prefix + local + ">"
    }
}
