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
    fun resolve(x: String, accept: Boolean): String {
        val sb = StringBuilder(x.length)
        walk(x, 0, x.length, sb, accept, 0)
        return sb.toString()
    }

    private val PROP_CHANGES = setOf("rPrChange", "pPrChange", "sectPrChange", "tblPrChange", "trPrChange", "tcPrChange", "tblGridChange", "numberingChange")
    /** Marks pairing with others elsewhere: kept when the text around them goes, so no pair is left half. */
    private val PAIRED = setOf("bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd")
    private val RANGE_MARKS = setOf("moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd")

    private fun walk(x: String, from: Int, to: Int, sb: StringBuilder, accept: Boolean, depth: Int) {
        if (depth > 200) throw IOException("Too deep")
        var pos = from
        for (e in XmlScan.children(x, from, to)) {
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
                // Formatting changed: rejecting puts the old formatting back
                !accept && (e.local == "rPr" || e.local == "pPr") && hasChange(x, e) -> {
                    val change = XmlScan.children(x, e).first { it.local == (if (e.local == "rPr") "rPrChange" else "pPrChange") }
                    val old = XmlScan.children(x, change).firstOrNull { it.local == e.local }
                    if (e.local == "pPr") {
                        // The paragraph's own run marks (rPr inside pPr) and section stay; the rest is the old one
                        sb.append(x, e.start, e.openEnd)
                        if (old != null && !old.empty) walk(x, old.openEnd, old.closeStart, sb, accept, depth + 1)
                        for (k in XmlScan.children(x, e)) if (k.local == "rPr" || k.local == "sectPr") walk(x, k.start, k.end, sb, accept, depth + 1)
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

    private fun hasChange(x: String, e: Elem) = XmlScan.children(x, e).any { it.local == "rPrChange" || it.local == "pPrChange" }

    /** An element with another local name (its prefix, attributes and text kept). */
    private fun rename(x: String, e: Elem, local: String): String {
        val prefix = e.name.substringBefore(':', "").let { if (it.isEmpty() || it == e.name) "" else "$it:" }
        val open = x.substring(e.start, e.openEnd).replaceFirst(e.name, prefix + local)
        if (e.selfClosing) return open
        return open + x.substring(e.openEnd, e.closeStart) + "</" + prefix + local + ">"
    }
}
