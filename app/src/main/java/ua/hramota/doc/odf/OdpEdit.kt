package ua.hramota.doc.odf

import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.docx.edit.XmlScan
import ua.hramota.doc.slides.Deck
import ua.hramota.doc.slides.edit.PptxEdit
import ua.hramota.doc.slides.edit.SlideEdit
import ua.hramota.doc.slides.edit.SlidePara
import ua.hramota.doc.slides.edit.TextBox
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An OpenDocument presentation (ODP) opened for editing in the file itself: the text of its slides'
 * frames and speaker notes (bold, italic, underline, strike-through; paragraphs split and joined), and
 * slides moved and taken out. Only the paragraphs changed are written anew; everything else stays as
 * the file had it. In a run's [RunStyle.rPr] is its text style's name ("" for none).
 */
class OdpEdit private constructor(
    private val xml: String,
    private val digest: ByteArray,
    private val ns: OdtEdit.Prefixes,
    private val pages: List<Elem>,
    private val presentation: Elem,
    private val slots: List<Slot>,
    private val slotOf: HashMap<Long, Int>,
    override val paragraphs: List<SlidePara>,
    override val boxes: List<TextBox>,
    private val styles: OdfPackage.Styles,
    private val autoText: Map<String, OdtEdit.AutoStyle>,
    private val autoStyles: Elem?,
    private val bodyStart: Int,
) : SlideEdit {
    /** Where a paragraph is: its place in the file, its page, and the list item it is in (its element's name), if any. */
    private class Slot(val start: Int, val end: Int, val page: Int, val item: String?, val container: Int)

    override val slideCount get() = pages.size
    override val extension = "odp"
    override val canAddSlides = false
    override val canPlace = false
    override val canNotes = false

    private var nextKey = paragraphs.size.toLong() + 1

    override fun addSlide(after: Int?, lang: String): Pair<Int, List<SlidePara>> = throw IOException("Not here")
    override fun canRemove(slide: Int, order: Collection<Int>) = order.size > 1
    override fun addNotes(slide: Int, lang: String): List<SlidePara>? = null
    override fun canComment(slide: Int) = false
    override fun setComments(slide: Int, list: List<String>) {}
    override fun addTextBox(slide: Int, rect: LongArray, lang: String): Pair<PptxEdit.ShapeRef, List<SlidePara>> = throw IOException("Not here")
    override fun addPicture(slide: Int, bytes: ByteArray, png: Boolean, rect: LongArray): PptxEdit.ShapeRef = throw IOException("Not here")
    override fun isAnimated(ref: PptxEdit.ShapeRef) = false

    // --- How text looks ---

    private val looks = HashMap<String, Map<String, String>>()
    private fun props(m: RunStyle) = looks.getOrPut(m.rPr) { if (m.rPr.isEmpty()) emptyMap() else styles.props(m.rPr, "text") }
    override fun bold(m: RunStyle) = m.bold ?: props(m)["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: false
    override fun italic(m: RunStyle) = m.italic ?: props(m)["font-style"]?.let { it == "italic" || it == "oblique" } ?: false
    override fun underline(m: RunStyle) = m.underline ?: props(m)["text-underline-style"]?.let { it != "none" } ?: false
    override fun strike(m: RunStyle) = m.strike ?: props(m)["text-line-through-style"]?.let { it != "none" } ?: false

    // --- Changes ---

    private fun slot(p: SlidePara) = slotOf[p.key] ?: throw IOException("Unknown paragraph")

    override fun canJoin(a: SlidePara, b: SlidePara): Boolean {
        if (!a.editable || !b.editable || a.box != b.box) return false
        val sa = slots[slot(a)]; val sb = slots[slot(b)]
        if (sa === sb) return true
        // Side by side in one place (one list item, or the frame itself), nothing between them
        return slot(b) == slot(a) + 1 && sa.container == sb.container && b.startTag.firstOrNull() == '<' && !b.startTag.startsWith("</") && (sa.end until sb.start).all { xml[it].isWhitespace() }
    }

    override fun canSplit(p: SlidePara) = p.editable

    override fun join(a: SlidePara, b: SlidePara) = a.changed(text = a.text + b.text, marks = a.marks + b.marks)

    private fun own(tag: String): String = tag.substring(tag.lastIndexOf('<'))

    override fun split(p: SlidePara, at: Int): Pair<SlidePara, SlidePara> {
        val first = p.changed(text = p.text.substring(0, at), marks = p.marks.subList(0, at).toList())
        val s = slots[slot(p)]
        val tag = own(p.startTag).replace(Regex("\\s(?:xml:id|${Regex.escape(ns.text)}:id)=(?:\"[^\"]*\"|'[^']*')"), "")
        // In a list (bullets): a new item of it
        val lead = s.item?.let { "</$it><$it>" } ?: ""
        val style = p.marks.getOrNull(at - 1) ?: p.marks.getOrNull(at) ?: p.typing
        val key = nextKey++
        slotOf[key] = slot(p)
        return first to SlidePara(key, lead + tag, p.box, p.text.substring(at), p.marks.subList(at, p.text.length).toList(), "", "", true, null, style)
    }

    // --- Saving ---

    private inner class Made {
        val names = HashMap<String, String>()
        val defs = StringBuilder()
        fun styleFor(m: RunStyle): String {
            if (m.bold == null && m.italic == null && m.underline == null && m.strike == null) return m.rPr
            val key = "${m.rPr}\u0000${m.bold}\u0000${m.italic}\u0000${m.underline}\u0000${m.strike}"
            names[key]?.let { return it }
            var n = names.size + 1
            var name = "HrT$n"
            while (name in styles.named || name in autoText || name in names.values) name = "HrT${++n}"
            val auto = autoText[m.rPr]
            val parent = if (m.rPr.isEmpty()) null else auto?.parent ?: m.rPr.takeIf { auto == null }
            val props = LinkedHashMap(auto?.props ?: emptyMap())
            val fo = ns.fo; val st = ns.style
            m.bold?.let { b -> val v = if (b) "bold" else "normal"; props["$fo:font-weight"] = v; props["$st:font-weight-asian"] = v; props["$st:font-weight-complex"] = v }
            m.italic?.let { i -> val v = if (i) "italic" else "normal"; props["$fo:font-style"] = v; props["$st:font-style-asian"] = v; props["$st:font-style-complex"] = v }
            m.underline?.let { u -> if (u) { props["$st:text-underline-style"] = "solid"; props["$st:text-underline-width"] = "auto"; props["$st:text-underline-color"] = "font-color" } else props["$st:text-underline-style"] = "none" }
            m.strike?.let { s -> if (s) { props["$st:text-line-through-style"] = "solid"; props["$st:text-line-through-type"] = "single" } else props["$st:text-line-through-style"] = "none" }
            defs.append("<$st:style $st:name=\"$name\" $st:family=\"text\"")
            parent?.let { defs.append(" $st:parent-style-name=\"").append(XmlScan.escape(it)).append('"') }
            defs.append("><$st:text-properties")
            for ((k, v) in props) defs.append(' ').append(k).append("=\"").append(XmlScan.escape(v)).append('"')
            defs.append("/></$st:style>")
            names[key] = name
            return name
        }
    }

    private fun write(p: SlidePara, b: StringBuilder, made: Made) {
        p.original?.let { b.append(it); return }
        val t = ns.text
        b.append(p.startTag)
        var i = 0
        var lastSpace = true
        while (i < p.text.length) {
            val m = p.marks[i]
            var j = i
            while (j < p.text.length && p.marks[j] == m) j++
            val name = made.styleFor(m)
            if (name.isNotEmpty()) b.append("<$t:span $t:style-name=\"").append(XmlScan.escape(name)).append("\">")
            var k = i
            while (k < j) {
                val c = p.text[k]
                when {
                    c == '\t' -> { b.append("<$t:tab/>"); lastSpace = false; k++ }
                    c == PptxEdit.LINE -> { b.append("<$t:line-break/>"); lastSpace = false; k++ }
                    c == ' ' -> {
                        var e = k
                        while (e < j && p.text[e] == ' ') e++
                        var count = e - k
                        if (!lastSpace) { b.append(' '); count-- }
                        if (count > 0) b.append(if (count == 1) "<$t:s/>" else "<$t:s $t:c=\"$count\"/>")
                        lastSpace = true
                        k = e
                    }
                    else -> {
                        var e = k
                        while (e < j && p.text[e] != ' ' && p.text[e] != '\t' && p.text[e] != PptxEdit.LINE) e++
                        b.append(XmlScan.escape(p.text.substring(k, e)))
                        lastSpace = false
                        k = e
                    }
                }
            }
            if (name.isNotEmpty()) b.append("</$t:span>")
            i = j
        }
        b.append("</").append(own(p.startTag).drop(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }).append('>')
    }

    /** content.xml with the slides in [order] and their changed paragraphs written anew. */
    fun contentXml(list: List<SlidePara>, order: List<Int>): String {
        val made = Made()
        val bySlot = list.groupBy { slot(it) }
        fun page(i: Int): String {
            val pg = pages[i]
            val b = StringBuilder(pg.end - pg.start)
            var pos = pg.start
            for ((k, s) in slots.withIndex()) {
                if (s.page != i) continue
                b.append(xml, pos, s.start)
                bySlot[k]?.forEach { write(it, b, made) }
                pos = s.end
            }
            return b.append(xml, pos, pg.end).toString()
        }
        val body = StringBuilder(xml.length - bodyStart + 4096)
        body.append(xml, bodyStart, pages.first().start)
        for (i in order) { if (i !in pages.indices) throw IOException("No such slide"); body.append(page(i)) }
        body.append(xml, pages.last().end, xml.length)
        val defs = made.defs.toString()
        val b = StringBuilder(xml.length + 4096)
        val a = autoStyles
        when {
            defs.isEmpty() -> b.append(xml, 0, bodyStart)
            a == null -> b.append(xml, 0, bodyStart).append("<${ns.office}:automatic-styles>").append(defs).append("</${ns.office}:automatic-styles>")
            a.selfClosing -> b.append(xml, 0, a.start).append("<${a.name}>").append(defs).append("</${a.name}>").append(xml, a.end, bodyStart)
            else -> b.append(xml, 0, a.closeStart).append(defs).append(xml, a.closeStart, bodyStart)
        }
        return b.append(body).toString()
    }

    override fun save(list: List<SlidePara>, order: List<Int>, source: InputStream, out: OutputStream, shapes: PptxEdit.Shapes) {
        if (shapes.placed.isNotEmpty() || shapes.removed.isNotEmpty() || shapes.pictures.isNotEmpty()) throw IOException("Shapes are not changed here")
        val content = contentXml(list, order).toByteArray(Charsets.UTF_8)
        val written = HashSet<String>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            val z = ZipOutputStream(out)
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                val name = e.name
                if (!written.add(name)) throw IOException("Duplicate entry")
                when (name) {
                    "mimetype" -> {
                        val b = readAll(zip, 1024)
                        z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().apply { update(b) }.value })
                        z.write(b)
                    }
                    CONTENT -> {
                        val was = readAll(zip, MAX_CONTENT)
                        if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digest)) throw IOException("The presentation changed meanwhile")
                        z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                        z.write(content)
                    }
                    else -> {
                        z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf); if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL) throw IOException("Package too large")
                            z.write(buf, 0, n)
                        }
                    }
                }
                z.closeEntry()
            }
            if (CONTENT !in written) throw IOException("No content")
            z.finish(); z.flush()
        }
    }

    override fun readBack(input: InputStream): Deck = OdpReader.read(input)

    companion object {
        const val CONTENT = "content.xml"
        private const val MAX_CONTENT = 24L * 1024 * 1024
        private const val MAX_SMALL = 8L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
        private const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"

        /** Opens an ODP for editing. Call off the main thread. */
        fun open(input: InputStream): OdpEdit {
            var content: ByteArray? = null; var stylesPart: ByteArray? = null; var manifest: ByteArray? = null; var mime: String? = null
            val names = HashSet<String>()
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    if (!names.add(e.name)) throw IOException("Duplicate entry")
                    when (e.name) {
                        CONTENT -> content = readAll(zip, MAX_CONTENT)
                        "styles.xml" -> stylesPart = readAll(zip, MAX_SMALL)
                        "META-INF/manifest.xml" -> manifest = readAll(zip, MAX_SMALL)
                        "mimetype" -> mime = String(readAll(zip, 1024), Charsets.US_ASCII).trim()
                    }
                }
            }
            if (mime != null && mime != "application/vnd.oasis.opendocument.presentation") throw IOException("Not a presentation")
            if (manifest?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) throw IOException("Encrypted")
            val bytes = content ?: throw IOException("No content")
            Xml.checkNoDoctype(bytes)
            stylesPart?.let(Xml::checkNoDoctype)
            val xml = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "document-content" } ?: throw IOException("No content")
            fun prefixOrNull(uri: String) = root.attrs.entries.firstOrNull { it.value == uri && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:")
            fun prefix(uri: String) = prefixOrNull(uri) ?: throw IOException("Unusual namespaces")
            val ns = OdtEdit.Prefixes(prefix(OFFICE), prefix(TEXT), prefix(STYLE), prefix(FO), "table", null)
            val draw = prefix(DRAW); val pres = prefixOrNull(PRESENTATION) ?: "presentation"
            val top = XmlScan.children(xml, root)
            val body = top.firstOrNull { it.name == "${ns.office}:body" } ?: throw IOException("No body")
            val presentation = XmlScan.children(xml, body).firstOrNull { it.name == "${ns.office}:presentation" } ?: throw IOException("No presentation")
            val auto = top.firstOrNull { it.name == "${ns.office}:automatic-styles" }
            if (auto != null && auto.start > body.start) throw IOException("Unusual order")
            val autoText = HashMap<String, OdtEdit.AutoStyle>()
            if (auto != null) for (s in XmlScan.children(xml, auto)) {
                if (s.name != "${ns.style}:style" || s.attrs["${ns.style}:family"] != "text") continue
                val name = s.attrs["${ns.style}:name"] ?: continue
                val tp = XmlScan.children(xml, s).firstOrNull { it.name == "${ns.style}:text-properties" }
                autoText[name] = OdtEdit.AutoStyle(s.attrs["${ns.style}:parent-style-name"], tp?.attrs?.filterKeys { !it.startsWith("xmlns") } ?: emptyMap())
            }
            val styles = OdfPackage.stylesOf(OdfPackage.tree(bytes), stylesPart?.let { OdfPackage.tree(it) })
            val pages = XmlScan.children(xml, presentation).filter { it.name == "$draw:page" }
            if (pages.isEmpty()) throw IOException("No slides")
            // Pages must stand together (nothing between them is lost when they are put in another order)
            for (k in 1 until pages.size) if ((pages[k - 1].end until pages[k].start).any { !xml[it].isWhitespace() }) throw IOException("Unusual slides")

            val slots = ArrayList<Slot>()
            val slotOf = HashMap<Long, Int>()
            val paras = ArrayList<SlidePara>()
            val boxes = ArrayList<TextBox>()
            val interned = HashMap<String, RunStyle>()
            val t = ns.text
            var containers = 0
            class Collector(val page: Int) {
                fun paragraphs(e: Elem, box: Int, item: String?, container: Int, depth: Int) {
                    if (depth > 30) return
                    for (c in XmlScan.children(xml, e)) when (c.name) {
                        "$t:p", "$t:h" -> {
                            slots += Slot(c.start, c.end, page, item, container)
                            val p = readParagraph(xml, ns, c, paras.size.toLong() + 1, box, interned)
                            slotOf[p.key] = slots.size - 1
                            paras += p
                        }
                        "$t:list" -> for (it in XmlScan.children(xml, c)) if (it.name == "$t:list-item" || it.name == "$t:list-header") paragraphs(it, box, it.name, ++containers, depth + 1)
                    }
                }
                fun shapes(e: Elem, depth: Int, notes: Boolean) {
                    if (depth > 20) return
                    for (c in XmlScan.children(xml, e)) when (c.name) {
                        "$draw:frame" -> {
                            val tb = XmlScan.children(xml, c).firstOrNull { it.name == "$draw:text-box" } ?: continue
                            val kind = when (c.attrs["$pres:class"]) { "title" -> "title"; "subtitle" -> "subtitle"; "notes" -> "notes"; else -> if (notes) "notes" else "body" }
                            boxes += TextBox(page, kind, 0, 0, 0)
                            paragraphs(tb, boxes.size - 1, null, ++containers, depth + 1)
                        }
                        "$draw:custom-shape", "$draw:rect", "$draw:ellipse" -> if (XmlScan.children(xml, c).any { it.name == "$t:p" || it.name == "$t:list" }) {
                            boxes += TextBox(page, "body", 0, 0, 0)
                            paragraphs(c, boxes.size - 1, null, ++containers, depth + 1)
                        }
                        "$draw:g" -> shapes(c, depth + 1, notes)
                        "$pres:notes" -> shapes(c, depth + 1, true)
                    }
                }
            }
            for ((i, pg) in pages.withIndex()) Collector(i).shapes(pg, 0, false)
            // A text box with no paragraph is left out (nothing to edit)
            return OdpEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), ns, pages, presentation, slots, slotOf, paras, boxes, styles, autoText, auto, body.start)
        }

        private fun readParagraph(xml: String, ns: OdtEdit.Prefixes, e: Elem, key: Long, box: Int, interned: HashMap<String, RunStyle>): SlidePara {
            val raw = xml.substring(e.start, e.end)
            val startTag = xml.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
            val t = ns.text
            val text = StringBuilder()
            val marks = ArrayList<RunStyle>()
            var editable = true
            var lastSpace = true
            fun add(s: String, style: RunStyle) {
                for (c in s) {
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { if (lastSpace) continue; text.append(' '); marks += style; lastSpace = true }
                    else { text.append(c); marks += style; lastSpace = false }
                }
            }
            fun put(c: Char, n: Int, style: RunStyle) { repeat(n) { text.append(c); marks += style }; lastSpace = false }
            fun walk(from: Int, to: Int, style: RunStyle, inSpan: Boolean) {
                var pos = from
                for (c in XmlScan.children(xml, from, to)) {
                    add(XmlScan.unescape(xml.substring(pos, c.start)), style)
                    pos = c.end
                    when (c.name) {
                        "$t:span" -> {
                            if (inSpan) { editable = false; continue }
                            val name = c.attrs["$t:style-name"] ?: ""
                            if (!c.empty) walk(c.openEnd, c.closeStart, interned.getOrPut(name) { RunStyle(name) }, true)
                        }
                        "$t:s" -> put(' ', (c.attrs["$t:c"]?.toIntOrNull() ?: 1).coerceIn(1, 1000), style)
                        "$t:tab" -> put('\t', 1, style)
                        "$t:line-break" -> put(PptxEdit.LINE, 1, style)
                        // Links, fields (a page's number, the date): the paragraph stays as it is (its text still shown)
                        else -> { editable = false; if (!c.empty) walk(c.openEnd, c.closeStart, style, true) }
                    }
                }
                add(XmlScan.unescape(xml.substring(pos, to)), style)
            }
            val plain = interned.getOrPut("") { RunStyle("") }
            if (!e.empty) walk(e.openEnd, e.closeStart, plain, false)
            return SlidePara(key, startTag, box, text.toString(), marks, "", "", editable, raw, marks.lastOrNull() ?: plain)
        }

        private fun readAll(input: InputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > limit) throw IOException("Part too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
