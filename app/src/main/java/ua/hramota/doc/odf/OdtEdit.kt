package ua.hramota.doc.odf

import ua.hramota.doc.docx.DocxDocument
import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Comment
import ua.hramota.doc.docx.edit.EditPara
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.Kind
import ua.hramota.doc.docx.edit.ListKind
import ua.hramota.doc.docx.edit.Look
import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.docx.edit.Shown
import ua.hramota.doc.docx.edit.TextEdit
import ua.hramota.doc.docx.edit.XmlScan
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An OpenDocument text (ODT) opened for editing in the file itself: paragraphs' text, bold, italic,
 * underline and strike-through, headings, and paragraphs split and joined. Only the paragraphs changed
 * are written anew (with the text styles they need added among the document's own); everything else in
 * content.xml and every other part stays as the file had it.
 *
 * In a run's [RunStyle.rPr] is the name of the text style its span has ("" for none).
 */
class OdtEdit private constructor(
    private val xml: String,
    private val digest: ByteArray,
    private val ns: Prefixes,
    private val slots: List<Slot>,
    override val paragraphs: List<EditPara>,
    private val styles: OdfPackage.Styles,
    /** Text styles of the content (automatic ones): their attributes as written, to make new ones from. */
    private val autoText: Map<String, AutoStyle>,
    private val autoStyles: Elem?,
    private val bodyStart: Int,
) : TextEdit {
    /** The prefixes the file gives the OpenDocument namespaces. */
    class Prefixes(val office: String, val text: String, val style: String, val fo: String, val table: String)

    /** A text style of the content: its parent, and its text properties' attributes (prefixed names). */
    class AutoStyle(val parent: String?, val props: Map<String, String>)

    /** Where a paragraph is in the file; [row] is its table row (0 outside tables); [list] where it is in a list. */
    private class Slot(val start: Int, val end: Int, val container: Int, val row: Int = 0, val list: ListPos? = null)

    /** A paragraph in a list: the list style, its level (0 first), whether it begins its item, the item's element, and whether a list starts there. */
    private class ListPos(val style: String?, val level: Int, val firstInItem: Boolean, val item: String, val header: Boolean, val restart: Boolean)

    override val canStyle = true
    override val canList = false
    override val canComment = false
    override val extension = "odt"

    private var nextKey = paragraphs.size.toLong() + 1
    override fun newKey(): Long = nextKey++

    // --- What is shown ---

    private fun on(p: Map<String, String>, what: String): Boolean? = when (what) {
        "bold" -> p["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 }
        "italic" -> p["font-style"]?.let { it == "italic" || it == "oblique" }
        "underline" -> p["text-underline-style"]?.let { it != "none" }
        else -> p["text-line-through-style"]?.let { it != "none" }
    }

    private val lookCache = HashMap<String, Look>()

    private fun lookOf(paraStyle: String?, textStyle: String): Look = lookCache.getOrPut(paraStyle + "\u0000" + textStyle) {
        val para = styles.props(paraStyle, "paragraph")
        val text = if (textStyle.isEmpty()) emptyMap() else styles.props(textStyle, "text")
        fun f(w: String) = on(text, w) ?: on(para, w) ?: false
        Look(f("bold"), f("italic"), f("underline"), f("strike"))
    }

    override fun look(p: EditPara, m: RunStyle): Look {
        val base = lookOf(styleOf(p.startTag), m.rPr)
        return Look(m.bold ?: base.bold, m.italic ?: base.italic, m.underline ?: base.underline, m.strike ?: base.strike)
    }

    private fun attrsOf(tag: String): Map<String, String> = try { XmlScan.children(closed(tag), 0, closed(tag).length).firstOrNull()?.attrs ?: emptyMap() } catch (e: Exception) { emptyMap() }
    private fun closed(tag: String): String { val t = tag.substringAfterLast("<").let { "<$it" }; return t.removeSuffix(">") + "/>" }
    private fun styleOf(startTag: String): String? = attrsOf(startTag)["${ns.text}:style-name"]
    private fun elementOf(startTag: String): String = startTag.substringAfterLast("<").takeWhile { !it.isWhitespace() && it != '>' && it != '/' }

    override fun kind(p: EditPara): Kind {
        val tag = p.startTag
        if (elementOf(tag) != "${ns.text}:h") return Kind.NORMAL
        return when (attrsOf(tag)["${ns.text}:outline-level"]?.toIntOrNull() ?: 1) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
    }

    override fun shown(list: List<EditPara>): List<Shown> {
        var prevContainer = -1; var prevRow = -1
        val counters = HashMap<String, IntArray>()
        var prevSlot = -1
        return list.map { p ->
            val slot = slots[p.slot]
            val cellStart = slot.row != 0 && slot.container != prevContainer
            val rowStart = slot.row != 0 && slot.row != prevRow
            prevContainer = slot.container; prevRow = slot.row
            // A list item's first paragraph has its marker (an item made here by a split too)
            val l = slot.list
            val startsItem = l != null && !l.header && (l.firstInItem && p.slot != prevSlot || p.startTag.startsWith("</"))
            prevSlot = p.slot
            var marker: String? = null; var kind: ListKind? = null
            if (l != null && startsItem) {
                if (l.restart && l.firstInItem && !p.startTag.startsWith("</")) counters.remove(l.style ?: "")
                val (m, k) = label(l.style, l.level, counters)
                marker = m; kind = k
            }
            val ps = styles.props(styleOf(p.startTag), "paragraph")
            Shown(kind(p), marker, kind, slot.row != 0, Look(on(ps, "bold") ?: false, on(ps, "italic") ?: false, on(ps, "underline") ?: false, on(ps, "strike") ?: false), rowStart, cellStart)
        }
    }

    private fun label(style: String?, level: Int, counters: HashMap<String, IntArray>): Pair<String, ListKind> {
        val ls = styles.lists[style]
        val lvl = ls?.elements?.firstOrNull { (it.attr("level")?.toIntOrNull() ?: 1) == level + 1 }
        if (lvl == null || lvl.name == "list-level-style-bullet") return (lvl?.attr("bullet-char")?.takeIf { it.isNotBlank() } ?: "•") to ListKind.BULLET
        if (lvl.name != "list-level-style-number") return "" to ListKind.BULLET
        val c = counters.getOrPut(style ?: "") { IntArray(10) }
        val l = level.coerceIn(0, 9)
        c[l]++
        for (k in l + 1 until 10) c[k] = 0
        val start = lvl.attr("start-value")?.toIntOrNull() ?: 1
        val n = c[l] + start - 1
        val number = when (lvl.attr("num-format")) {
            "a" -> letters(n).lowercase(); "A" -> letters(n); "i" -> roman(n).lowercase(); "I" -> roman(n)
            "а" -> "абвгґдеєжзиіїйклмнопрстуфхцчшщюя".let { if (n in 1..it.length) it[n - 1].toString() else n.toString() }
            "" -> ""; else -> n.toString()
        }
        return ((lvl.attr("num-prefix") ?: "") + number + (lvl.attr("num-suffix") ?: ".")) to ListKind.NUMBER
    }

    private fun letters(n: Int): String { var x = n; val sb = StringBuilder(); while (x > 0) { sb.append('A' + (x - 1) % 26); x = (x - 1) / 26 }; return sb.reverse().toString() }
    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder(); for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }; return sb.toString()
    }

    // --- Changes ---

    override fun canJoin(a: EditPara, b: EditPara): Boolean {
        if (!a.editable || !b.editable) return false
        if (a.slot == b.slot) return true
        if (b.slot != a.slot + 1) return false
        val sa = slots[a.slot]; val sb = slots[b.slot]
        if (sa.container != sb.container) return false
        return (sa.end until sb.start).all { xml[it].isWhitespace() }
    }

    override fun join(a: EditPara, b: EditPara): EditPara = a.changed(text = a.text + b.text, marks = a.marks + b.marks, head = a.head + b.head, tail = a.tail + b.tail)

    override fun retyped(p: EditPara, text: String, marks: List<RunStyle>, from: Int, oldEnd: Int): EditPara = p.changed(text = text, marks = marks)

    /** The start tag without the attributes that must not be written twice (an element's own id). */
    private fun withoutIds(tag: String): String = tag.replace(Regex("\\s(?:xml:id|${Regex.escape(ns.text)}:id)=(?:\"[^\"]*\"|'[^']*')"), "")

    override fun split(p: EditPara, at: Int): Pair<EditPara, EditPara> {
        val first = p.changed(text = p.text.substring(0, at), marks = p.marks.subList(0, at).toList(), tail = "")
        // A heading split at its end is followed by ordinary text; a list item split makes a new item
        val own = if (p.startTag.startsWith("</")) p.startTag.substring(p.startTag.lastIndexOf('<')) else p.startTag
        var tag = if (at == p.text.length && kind(p) != Kind.NORMAL) bodyTag() else withoutIds(own)
        val l = slots[p.slot].list
        if (l != null && !l.header) tag = "</${l.item}><${l.item}>" + tag
        val style = p.marks.getOrNull(at - 1) ?: p.marks.getOrNull(at) ?: p.typing
        val second = EditPara(newKey(), p.slot, p.text.substring(at), p.marks.subList(at, p.text.length).toList(), "", tag, "", p.tail, true, null, style)
        return first to second
    }

    private fun bodyTag(): String {
        val name = listOf("Text_20_body", "Standard").firstOrNull { it in styles.named }
        return "<${ns.text}:p" + (name?.let { " ${ns.text}:style-name=\"$it\"" } ?: "") + ">"
    }

    override fun withKind(p: EditPara, kind: Kind): EditPara {
        // What comes before the paragraph's own tag (a list item made here) stays
        val lead = if (p.startTag.startsWith("</")) p.startTag.substring(0, p.startTag.lastIndexOf('<')) else ""
        val tag = when (kind) {
            Kind.NORMAL, Kind.OTHER -> bodyTag()
            else -> {
                val level = when (kind) { Kind.H1 -> 1; Kind.H2 -> 2; else -> 3 }
                val name = listOf("Heading_20_$level", "Heading").firstOrNull { it in styles.named }
                "<${ns.text}:h" + (name?.let { " ${ns.text}:style-name=\"$it\"" } ?: "") + " ${ns.text}:outline-level=\"$level\">"
            }
        }
        return EditPara(p.key, p.slot, p.text, p.marks, p.pPr, lead + tag, p.head, p.tail, p.editable, null, p.typing, p.anchors)
    }

    override fun withList(p: EditPara, kind: ListKind?, before: EditPara?): EditPara = p

    // Comments (LibreOffice's notes) are not changed here
    override fun comments(): List<Comment> = emptyList()
    override fun comment(p: EditPara, from: Int, to: Int, author: String, text: String): EditPara = p
    override fun uncomment(list: List<EditPara>, id: String): List<EditPara>? = null
    override fun commentPlace(list: List<EditPara>, id: String): Pair<Pair<Int, Int>, String>? = null

    // --- Saving ---

    /** Text styles made for this save: by what they stand for, their name and definition. */
    private class Made(val names: HashMap<String, String> = HashMap(), val defs: StringBuilder = StringBuilder())

    private fun styleFor(m: RunStyle, made: Made): String {
        if (m.bold == null && m.italic == null && m.underline == null && m.strike == null) return m.rPr
        val key = "${m.rPr}\u0000${m.bold}\u0000${m.italic}\u0000${m.underline}\u0000${m.strike}"
        made.names[key]?.let { return it }
        var n = made.names.size + 1
        var name = "HrT$n"
        while (name in styles.named || name in autoText || name in made.names.values) name = "HrT${++n}"
        // From the run's own style: an automatic one copied, a common one as the parent
        val auto = autoText[m.rPr]
        val parent = if (m.rPr.isEmpty()) null else auto?.parent ?: m.rPr.takeIf { auto == null }
        val props = LinkedHashMap(auto?.props ?: emptyMap())
        val fo = ns.fo; val st = ns.style
        m.bold?.let { b -> val v = if (b) "bold" else "normal"; props["$fo:font-weight"] = v; props["$st:font-weight-asian"] = v; props["$st:font-weight-complex"] = v }
        m.italic?.let { i -> val v = if (i) "italic" else "normal"; props["$fo:font-style"] = v; props["$st:font-style-asian"] = v; props["$st:font-style-complex"] = v }
        m.underline?.let { u ->
            if (u) { props["$st:text-underline-style"] = "solid"; props["$st:text-underline-width"] = "auto"; props["$st:text-underline-color"] = "font-color" }
            else props["$st:text-underline-style"] = "none"
        }
        m.strike?.let { s ->
            if (s) { props["$st:text-line-through-style"] = "solid"; props["$st:text-line-through-type"] = "single" } else props["$st:text-line-through-style"] = "none"
        }
        made.defs.append("<$st:style $st:name=\"$name\" $st:family=\"text\"")
        parent?.let { made.defs.append(" $st:parent-style-name=\"").append(XmlScan.escape(it)).append('"') }
        made.defs.append("><$st:text-properties")
        for ((k, v) in props) made.defs.append(' ').append(k).append("=\"").append(XmlScan.escape(v)).append('"')
        made.defs.append("/></$st:style>")
        made.names[key] = name
        return name
    }

    private fun write(p: EditPara, b: StringBuilder, made: Made) {
        p.original?.let { b.append(it); return }
        val t = ns.text
        b.append(p.startTag).append(p.head)
        var i = 0
        // Spaces: one between words is written as is; more, or one at the start or after another, as text:s
        var lastSpace = true
        while (i < p.text.length) {
            val m = p.marks[i]
            var j = i
            while (j < p.text.length && p.marks[j] == m) j++
            val name = styleFor(m, made)
            if (name.isNotEmpty()) b.append("<$t:span $t:style-name=\"").append(XmlScan.escape(name)).append("\">")
            var k = i
            while (k < j) {
                val c = p.text[k]
                when {
                    c == '\t' -> { b.append("<$t:tab/>"); lastSpace = false; k++ }
                    c == LINE -> { b.append("<$t:line-break/>"); lastSpace = false; k++ }
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
                        while (e < j && p.text[e] != ' ' && p.text[e] != '\t' && p.text[e] != LINE) e++
                        b.append(XmlScan.escape(p.text.substring(k, e)))
                        lastSpace = false
                        k = e
                    }
                }
            }
            if (name.isNotEmpty()) b.append("</$t:span>")
            i = j
        }
        b.append(p.tail).append("</").append(elementOf(p.startTag)).append('>')
    }

    /** content.xml with every changed paragraph written anew, and the text styles they use. */
    fun contentXml(list: List<EditPara>): String {
        val made = Made()
        val body = StringBuilder(xml.length - bodyStart + 4096)
        var pos = bodyStart
        var i = 0
        for ((k, slot) in slots.withIndex()) {
            body.append(xml, pos, slot.start)
            while (i < list.size && list[i].slot == k) { write(list[i], body, made); i++ }
            pos = slot.end
        }
        body.append(xml, pos, xml.length)
        if (i != list.size) throw IOException("Paragraphs out of order")
        val b = StringBuilder(xml.length + 4096)
        val a = autoStyles
        when {
            made.defs.isEmpty() -> b.append(xml, 0, bodyStart)
            a == null -> b.append(xml, 0, bodyStart).append("<${ns.office}:automatic-styles>").append(made.defs).append("</${ns.office}:automatic-styles>")
            a.selfClosing -> b.append(xml, 0, a.start).append("<${a.name}>").append(made.defs).append("</${a.name}>").append(xml, a.end, bodyStart)
            else -> b.append(xml, 0, a.closeStart).append(made.defs).append(xml, a.closeStart, bodyStart)
        }
        return b.append(body).toString()
    }

    override fun save(list: List<EditPara>, source: InputStream, out: OutputStream) {
        val content = contentXml(list).toByteArray(Charsets.UTF_8)
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
                    // The type of the file comes first and is stored as it is (so it can be read without unpacking)
                    "mimetype" -> {
                        val b = readAll(zip, 1024)
                        val crc = CRC32().apply { update(b) }
                        z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); this.crc = crc.value })
                        z.write(b)
                    }
                    CONTENT -> {
                        val was = readAll(zip, MAX_CONTENT)
                        if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digest)) throw IOException("The document changed meanwhile")
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

    override fun readBack(input: InputStream): DocxDocument = OdtReader.read(input)

    companion object {
        const val CONTENT = "content.xml"
        /** A line break within a paragraph, as the editor keeps it. */
        const val LINE = ua.hramota.doc.docx.edit.DocxEdit.LINE
        private const val MAX_CONTENT = 24L * 1024 * 1024
        private const val MAX_SMALL = 8L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"

        /** Opens an ODT for editing. Call off the main thread. */
        fun open(input: InputStream): OdtEdit {
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
            if (mime != null && mime != "application/vnd.oasis.opendocument.text") throw IOException("Not a text document")
            // A document with a password: its parts cannot be read here
            if (manifest?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) throw IOException("Encrypted")
            val bytes = content ?: throw IOException("No content")
            Xml.checkNoDoctype(bytes)
            stylesPart?.let(Xml::checkNoDoctype)
            val xml = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "document-content" } ?: throw IOException("No content")
            fun prefix(uri: String) = root.attrs.entries.firstOrNull { it.value == uri && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: throw IOException("Unusual namespaces")
            val ns = Prefixes(prefix(OFFICE), prefix(TEXT), prefix(STYLE), prefix(FO), runCatching { prefix(TABLE) }.getOrDefault("table"))
            val top = XmlScan.children(xml, root)
            val body = top.firstOrNull { it.name == "${ns.office}:body" } ?: throw IOException("No body")
            val text = XmlScan.children(xml, body).firstOrNull { it.name == "${ns.office}:text" } ?: throw IOException("No text")
            val auto = top.firstOrNull { it.name == "${ns.office}:automatic-styles" }
            if (auto != null && auto.start > body.start) throw IOException("Unusual order")
            // The content's own text styles, as written (new ones are made from them)
            val autoText = HashMap<String, AutoStyle>()
            if (auto != null) for (s in XmlScan.children(xml, auto)) {
                if (s.name != "${ns.style}:style" || s.attrs["${ns.style}:family"] != "text") continue
                val name = s.attrs["${ns.style}:name"] ?: continue
                val tp = XmlScan.children(xml, s).firstOrNull { it.name == "${ns.style}:text-properties" }
                autoText[name] = AutoStyle(s.attrs["${ns.style}:parent-style-name"], tp?.attrs?.filterKeys { !it.startsWith("xmlns") } ?: emptyMap())
            }
            val styles = OdfPackage.stylesOf(OdfPackage.tree(bytes), stylesPart?.let { OdfPackage.tree(it) })

            val slots = ArrayList<Slot>()
            val paras = ArrayList<EditPara>()
            val interned = HashMap<String, RunStyle>()
            val t = ns.text; val tb = ns.table
            // The paragraphs in reading order: in the text, sections, lists (each item a place of its own) and tables
            class Collector {
                var containers = 0
                var rows = 0
                fun paragraph(e: Elem, list: ListPos?, container: Int, row: Int) {
                    slots += Slot(e.start, e.end, container, row, list)
                    paras += readParagraph(xml, ns, e, slots.size - 1, interned)
                }
                fun collect(elems: List<Elem>, container: Int, row: Int, depth: Int) {
                    if (depth > 40) return
                    for (e in elems) when (e.name) {
                        "$t:p", "$t:h" -> paragraph(e, null, container, row)
                        "$t:list" -> list(e, null, 0, true, depth + 1)
                        "$t:section" -> collect(XmlScan.children(xml, e), ++containers, row, depth + 1)
                        "$tb:table" -> table(e, depth + 1)
                    }
                }
                fun list(e: Elem, inherited: String?, level: Int, top: Boolean, depth: Int) {
                    if (depth > 40) return
                    val style = e.attrs["$t:style-name"] ?: inherited
                    val continues = e.attrs["$t:continue-numbering"] == "true" || e.attrs["$t:continue-list"] != null
                    var firstItem = true
                    for (item in XmlScan.children(xml, e)) {
                        if (item.name != "$t:list-item" && item.name != "$t:list-header") continue
                        val container = ++containers
                        var first = true
                        for (c in XmlScan.children(xml, item)) when (c.name) {
                            "$t:p", "$t:h" -> {
                                paragraph(c, ListPos(style, level, first, item.name, item.name == "$t:list-header", top && firstItem && !continues), container, 0)
                                first = false
                            }
                            "$t:list" -> list(c, style, level + 1, false, depth + 1)
                        }
                        firstItem = false
                    }
                }
                fun table(e: Elem, depth: Int) {
                    if (depth > 40) return
                    for (r in XmlScan.children(xml, e)) when (r.name) {
                        "$tb:table-row" -> {
                            val rr = ++rows
                            for (c in XmlScan.children(xml, r)) if (c.name == "$tb:table-cell") collect(XmlScan.children(xml, c), ++containers, rr, depth + 1)
                        }
                        "$tb:table-header-rows", "$tb:table-rows", "$tb:table-row-group" -> table(r, depth + 1)
                    }
                }
            }
            Collector().collect(XmlScan.children(xml, text), 0, 0, 0)
            return OdtEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), ns, slots, paras, styles, autoText, auto, body.start)
        }

        private fun readParagraph(xml: String, ns: Prefixes, e: Elem, slot: Int, interned: HashMap<String, RunStyle>): EditPara {
            val raw = xml.substring(e.start, e.end)
            val startTag = xml.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
            val t = ns.text
            val text = StringBuilder()
            val marks = ArrayList<RunStyle>()
            val head = StringBuilder(); val tail = StringBuilder()
            var editable = true
            // Spaces in the file's text are one however many (the first in a paragraph none): written ones (text:s) are kept
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
                            // A span within a span: its look is the two together, not kept apart here
                            if (inSpan) { editable = false; continue }
                            val name = c.attrs["$t:style-name"] ?: ""
                            if (!c.empty) walk(c.openEnd, c.closeStart, interned.getOrPut(name) { RunStyle(name) }, true)
                        }
                        "$t:s" -> put(' ', (c.attrs["$t:c"]?.toIntOrNull() ?: 1).coerceIn(1, 1000), style)
                        "$t:tab" -> put('\t', 1, style)
                        "$t:line-break" -> put(LINE, 1, style)
                        "$t:soft-page-break" -> {}
                        "$t:bookmark", "$t:bookmark-start" -> if (inSpan) editable = false else head.append(xml, c.start, c.end)
                        "$t:bookmark-end" -> if (inSpan) editable = false else tail.append(xml, c.start, c.end)
                        // Links, notes, fields, pictures, tracked changes, comments: the paragraph stays as it is
                        else -> editable = false
                    }
                }
                add(XmlScan.unescape(xml.substring(pos, to)), style)
            }
            val plain = interned.getOrPut("") { RunStyle("") }
            if (!e.empty) walk(e.openEnd, e.closeStart, plain, false)
            return EditPara(slot.toLong() + 1, slot, text.toString(), marks, "", startTag, head.toString(), tail.toString(), editable, raw, marks.lastOrNull() ?: plain)
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
