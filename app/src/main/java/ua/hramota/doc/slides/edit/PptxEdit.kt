package ua.hramota.doc.slides.edit

import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.docx.edit.XmlScan
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One paragraph of a slide's text while editing. [box] is the text box it is in (its paragraphs are
 * joined and split only among themselves); [original] is its XML while it is unchanged.
 */
class SlidePara(
    val key: Long,
    /** The paragraph's start tag as written in the file ("" for a new one: "<a:p>"). */
    val startTag: String,
    val box: Int,
    val text: String,
    val marks: List<RunStyle>,
    /** The paragraph's own properties (a:pPr) and the end mark's (a:endParaRPr), raw. */
    val pPr: String,
    val end: String,
    val editable: Boolean,
    val original: String?,
    val typing: RunStyle,
) {
    init { require(marks.size == text.length) }

    fun changed(text: String = this.text, marks: List<RunStyle> = this.marks) =
        SlidePara(key, startTag, box, text, marks, pPr, end, editable, null, marks.lastOrNull() ?: typing)
}

/** A text box: which slide (in the order shown), what kind ("title", "body"…), where its paragraphs are. */
class TextBox(val slide: Int, val kind: String, internal val part: Int, internal val from: Int, internal val to: Int,
              /** Paragraphs counted by an animation (one by one): they are neither split nor joined here. */
              val fixed: Boolean = false)

/**
 * A PPTX opened for editing its text. Only the text boxes whose paragraphs changed are written anew
 * (their paragraphs, nothing else of the shape); every other part, slide and shape stays as it was.
 */
class PptxEdit private constructor(
    private val parts: List<Pair<String, String>>,
    private val digests: Map<String, ByteArray>,
    /** The prefix of DrawingML's namespace in each slide part ("a" almost always). */
    private val prefixes: List<String>,
    val boxes: List<TextBox>,
    val paragraphs: List<SlidePara>,
    /** How many slides are shown (hidden ones are left out, as in the viewer). */
    val slideCount: Int,
) {
    private var nextKey = paragraphs.size.toLong() + 1

    /** The look of a character as set on its run (what the slide's design adds is not counted). */
    fun bold(m: RunStyle) = m.bold ?: flag(m.rPr, "b")
    fun italic(m: RunStyle) = m.italic ?: flag(m.rPr, "i")
    fun underline(m: RunStyle) = m.underline ?: attr(m.rPr, "u").let { it != null && it != "none" }
    fun strike(m: RunStyle) = m.strike ?: attr(m.rPr, "strike").let { it != null && it != "noStrike" }

    private fun attr(rPr: String, name: String): String? = if (rPr.isEmpty()) null else XmlScan.children(rPr, 0, rPr.length).firstOrNull()?.attrs?.get(name)
    private fun flag(rPr: String, name: String) = attr(rPr, name).let { it == "1" || it == "true" }

    fun restyle(m: RunStyle, bold: Boolean? = null, italic: Boolean? = null, underline: Boolean? = null, strike: Boolean? = null) =
        m.copy(bold = bold ?: m.bold, italic = italic ?: m.italic, underline = underline ?: m.underline, strike = strike ?: m.strike)

    fun canJoin(a: SlidePara, b: SlidePara) = a.box == b.box && a.editable && b.editable && !boxes[a.box].fixed

    /** False where an animation counts the paragraphs: Enter does not split them. */
    fun canSplit(p: SlidePara) = p.editable && !boxes[p.box].fixed

    fun join(a: SlidePara, b: SlidePara) = a.changed(text = a.text + b.text, marks = a.marks + b.marks)

    /** Splits at [at]; the new paragraph has the same properties (level, bullet, alignment). */
    fun split(p: SlidePara, at: Int): Pair<SlidePara, SlidePara> {
        val first = p.changed(text = p.text.substring(0, at), marks = p.marks.subList(0, at).toList())
        val style = p.marks.getOrNull(at - 1) ?: p.marks.getOrNull(at) ?: p.typing
        val second = SlidePara(nextKey++, p.startTag, p.box, p.text.substring(at), p.marks.subList(at, p.text.length).toList(), p.pPr, p.end, true, null, style)
        return first to second
    }

    // --- Saving ---

    private fun write(p: SlidePara, a: String, b: StringBuilder) {
        p.original?.let { b.append(it); return }
        // The paragraph's own tag as the file had it (it may declare a namespace)
        b.append(p.startTag.ifEmpty { "<$a:p>" }).append(p.pPr)
        var i = 0
        while (i < p.text.length) {
            val m = p.marks[i]
            if (p.text[i] == LINE) { b.append("<$a:br>").append(rprOf(m, a)).append("</$a:br>"); i++; continue }
            var j = i
            while (j < p.text.length && p.marks[j] == m && p.text[j] != LINE) j++
            b.append("<$a:r>").append(rprOf(m, a)).append("<$a:t>").append(XmlScan.escape(p.text.substring(i, j))).append("</$a:t></$a:r>")
            i = j
        }
        b.append(p.end).append("</$a:p>")
    }

    /** A run's properties with what the person changed set as attributes (the rest as the file has it). */
    private fun rprOf(m: RunStyle, a: String): String {
        if (m.bold == null && m.italic == null && m.underline == null && m.strike == null) return m.rPr
        val r = m.rPr.ifEmpty { "<$a:rPr/>" }
        val e = XmlScan.children(r, 0, r.length).first()
        // The start tag made again from its attributes (read properly, whatever quotes the file used)
        val attrs = LinkedHashMap<String, String>()
        attrs.putAll(attrsInOrder(r.substring(e.start, e.openEnd)))
        m.bold?.let { attrs["b"] = if (it) "1" else "0" }
        m.italic?.let { attrs["i"] = if (it) "1" else "0" }
        m.underline?.let { attrs["u"] = if (it) "sng" else "none" }
        m.strike?.let { attrs["strike"] = if (it) "sngStrike" else "noStrike" }
        val tag = "<" + e.name + attrs.entries.joinToString("") { (k, v) -> " $k=\"${XmlScan.escape(v)}\"" } + if (e.selfClosing) "/>" else ">"
        return tag + r.substring(e.openEnd)
    }

    /** Attributes of a start tag, in their order, values unescaped. */
    private fun attrsInOrder(tag: String): List<Pair<String, String>> =
        Regex("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").findAll(tag).map { mm -> mm.groupValues[1] to XmlScan.unescape(mm.groupValues[3].ifEmpty { mm.groupValues[4] }) }.toList()

    /** Writes the edited presentation: the package is read again from [source] and copied entry by entry. */
    fun save(list: List<SlidePara>, source: InputStream, out: OutputStream) {
        val changed = HashMap<String, String>()
        val byBox = list.groupBy { it.box }
        val counts = paragraphs.groupingBy { it.box }.eachCount()
        val boxesOf = boxes.withIndex().groupBy { it.value.part }
        for ((pi, part) in parts.withIndex()) {
            val (path, xml) = part
            val mine = boxesOf[pi].orEmpty()
            val touched = mine.filter { (bi, _) -> byBox[bi].orEmpty().any { it.original == null } || byBox[bi].orEmpty().size != counts[bi] }
            if (touched.isEmpty()) continue
            val b = StringBuilder(xml.length + 2048)
            var pos = 0
            for ((bi, box) in touched.sortedBy { it.value.from }) {
                b.append(xml, pos, box.from)
                for (p in byBox[bi].orEmpty()) write(p, prefixes[pi], b)
                pos = box.to
            }
            b.append(xml, pos, xml.length)
            changed[path] = b.toString()
        }
        copy(source, out, changed)
    }

    private fun copy(source: InputStream, out: OutputStream, changed: Map<String, String>) {
        val written = HashSet<String>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            val z = ZipOutputStream(out)
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                val name = e.name
                if (!written.add(name)) continue
                z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                val replaced = changed[name.removePrefix("/")]
                // The presentation and every slide read must still be as opened (another app may have changed them)
                if (replaced == null && digests.containsKey(name.removePrefix("/"))) {
                    val was = readAll(zip, MAX_PART)
                    if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digests[name.removePrefix("/")])) throw IOException("$name changed meanwhile")
                    z.write(was)
                } else if (replaced != null) {
                    val was = readAll(zip, MAX_PART)
                    if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digests[name.removePrefix("/")])) throw IOException("$name changed meanwhile")
                    z.write(replaced.toByteArray(Charsets.UTF_8))
                } else {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw IOException("Package too large")
                        z.write(buf, 0, n)
                    }
                }
                z.closeEntry()
            }
            for (name in changed.keys) if (name !in written && "/$name" !in written) throw IOException("$name missing")
            z.finish(); z.flush()
        }
    }

    companion object {
        const val DML = "http://schemas.openxmlformats.org/drawingml/2006/main"
        /** A line break within a paragraph (Shift+Enter). */
        const val LINE = '\u2028'
        private const val MAX_PART = 16L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000

        /** Opens a PPTX for editing its text. Call off the main thread. */
        fun open(input: InputStream): PptxEdit {
            val raw = HashMap<String, ByteArray>()
            ZipInputStream(input).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    val name = e.name.removePrefix("/")
                    val lower = name.lowercase()
                    if (lower == "ppt/presentation.xml" || lower == "ppt/_rels/presentation.xml.rels" || lower.startsWith("ppt/slides/") && (lower.endsWith(".xml") || lower.endsWith(".rels"))) {
                        // The same name twice: which one is meant cannot be told; not edited
                        if (raw.containsKey(name)) throw IOException("Duplicate entry $name")
                        val b = readAll(zip, MAX_PART)
                        total += b.size
                        if (total > MAX_TOTAL / 4) throw IOException("Too large to edit")
                        raw[name] = b
                    }
                }
            }
            fun text(name: String) = raw[name]?.let { Xml.checkNoDoctype(it); String(it, Charsets.UTF_8).removePrefix("\uFEFF") }
            val pres = text("ppt/presentation.xml") ?: throw IOException("Not a presentation")
            val rels = HashMap<String, String>()
            text("ppt/_rels/presentation.xml.rels")?.let { r ->
                val root = XmlScan.children(r, 0, r.length).firstOrNull() ?: return@let
                for (e in XmlScan.children(r, root)) if (e.local == "Relationship") {
                    val target = e.attrs["Target"] ?: continue
                    rels[e.attrs["Id"] ?: continue] = if (target.startsWith("/")) target.removePrefix("/") else java.io.File("ppt", target).normalize().path.replace('\\', '/')
                }
            }
            val presRoot = XmlScan.children(pres, 0, pres.length).first { it.local == "presentation" }
            val ids = XmlScan.children(pres, presRoot).firstOrNull { it.local == "sldIdLst" }?.let { XmlScan.children(pres, it) }.orEmpty()
            val parts = ArrayList<Pair<String, String>>()
            val prefixes = ArrayList<String>()
            val boxes = ArrayList<TextBox>()
            val paras = ArrayList<SlidePara>()
            val interned = HashMap<String, RunStyle>()
            val seen = HashSet<String>()
            var shown = 0
            for (id in ids) {
                if (id.local != "sldId") continue
                if (shown >= 2000) break
                val rid = id.attrs.entries.firstOrNull { it.key.endsWith(":id") }?.value ?: continue
                val path = rels[rid] ?: continue
                if (!seen.add(path)) continue
                val xml = text(path) ?: continue
                val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "sld" } ?: continue
                // Hidden slides are left out, as the viewer leaves them out
                if (root.attrs["show"].let { it == "0" || it == "false" }) continue
                val a = root.attrs.entries.firstOrNull { it.value == DML && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: "a"
                val pi = parts.size
                parts += path to xml
                prefixes += a
                // Shapes whose paragraphs an animation counts: by paragraph (kept in number) or by character (kept as they are)
                val byParagraph = HashSet<String>(); val byCharacter = HashSet<String>()
                XmlScan.children(xml, root).firstOrNull { it.local == "timing" }?.let { timing ->
                    fun scan(e: Elem) {
                        for (k in XmlScan.children(xml, e)) {
                            when (k.local) {
                                "spTgt" -> {
                                    val spid = k.attrs["spid"] ?: continue
                                    val txEl = XmlScan.children(xml, k).firstOrNull { it.local == "txEl" }
                                    if (txEl != null) {
                                        if (XmlScan.children(xml, txEl).any { it.local == "charRg" }) byCharacter += spid else byParagraph += spid
                                    }
                                }
                                "bldP" -> if (k.attrs["build"] != "whole") k.attrs["spid"]?.let { byParagraph += it }
                            }
                            if (!k.empty) scan(k)
                        }
                    }
                    scan(timing)
                }
                // Every text body on the slide, in drawing order (shapes, groups, table cells)
                fun walk(e: Elem, kind: String, spid: String?, noEdit: Boolean) {
                    for (k in XmlScan.children(xml, e)) {
                        if (k.local == "txBody") {
                            val ps = XmlScan.children(xml, k).filter { it.local == "p" }
                            if (ps.isEmpty()) continue
                            val bi = boxes.size
                            boxes += TextBox(shown, kind, pi, ps.first().start, ps.last().end, fixed = spid != null && spid in byParagraph)
                            val locked = noEdit || (spid != null && spid in byCharacter)
                            for (p in ps) paras += paragraph(xml, a, p, bi, paras.size.toLong() + 1, interned, locked)
                        } else if (!k.empty && k.local !in setOf("nvSpPr", "spPr", "extLst", "AlternateContent", "nvGrpSpPr", "grpSpPr")) {
                            // Choices for other versions of PowerPoint (AlternateContent) would show one text twice: left as they are
                            val type = when (k.local) { "sp" -> placeholder(xml, k); "tbl" -> "table"; else -> kind }
                            if (k.local == "sp" || k.local == "graphicFrame") {
                                // The shape's id (animations name it) and whether its text is marked "do not edit"
                                val nv = XmlScan.children(xml, k).firstOrNull { it.local.startsWith("nv") }
                                val kids = nv?.let { XmlScan.children(xml, it) }.orEmpty()
                                val id = kids.firstOrNull { it.local == "cNvPr" }?.attrs?.get("id")
                                val lockText = kids.firstOrNull { it.local == "cNvSpPr" }?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spLocks" }?.attrs?.get("noTextEdit") }.let { it == "1" || it == "true" }
                                walk(k, type, id, lockText)
                            } else walk(k, type, spid, noEdit)
                        }
                    }
                }
                val cSld = XmlScan.children(xml, root).firstOrNull { it.local == "cSld" }
                cSld?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spTree" }?.let { walk(it, "body", null, false) } }
                shown++
            }
            if (parts.isEmpty()) throw IOException("No slides")
            val digests = parts.associate { (p, _) -> p to MessageDigest.getInstance("SHA-256").digest(raw[p]!!) }
            return PptxEdit(parts, digests, prefixes, boxes, paras, shown)
        }

        /** "title", "body", "table" or "text": what a shape is, for its label while editing. */
        private fun placeholder(xml: String, sp: Elem): String {
            val nv = XmlScan.children(xml, sp).firstOrNull { it.local == "nvSpPr" } ?: return "text"
            val nvPr = XmlScan.children(xml, nv).firstOrNull { it.local == "nvPr" } ?: return "text"
            val ph = XmlScan.children(xml, nvPr).firstOrNull { it.local == "ph" } ?: return "text"
            return when (ph.attrs["type"]) { "title", "ctrTitle" -> "title"; "subTitle" -> "subtitle"; null, "body", "obj" -> "body"; else -> "other" }
        }

        private fun paragraph(xml: String, a: String, e: Elem, box: Int, key: Long, interned: HashMap<String, RunStyle>, locked: Boolean): SlidePara {
            var pPr = ""; var end = ""
            val text = StringBuilder()
            val marks = ArrayList<RunStyle>()
            var editable = true
            for (c in XmlScan.children(xml, e)) {
                if (!c.name.startsWith("$a:")) { editable = false; continue }
                when (c.local) {
                    "pPr" -> pPr = xml.substring(c.start, c.end)
                    "endParaRPr" -> end = xml.substring(c.start, c.end)
                    "r", "br" -> {
                        // A run with attributes of its own, or text not written plainly, is kept as it is
                        if (c.attrs.isNotEmpty()) editable = false
                        var rPr = ""; var t = ""
                        for (k in XmlScan.children(xml, c)) when (k.local) {
                            "rPr" -> rPr = xml.substring(k.start, k.end)
                            "t" -> {
                                val inner = if (k.empty) "" else xml.substring(k.openEnd, k.closeStart)
                                if (k.attrs.isNotEmpty() || '<' in inner || '\n' in inner || '\r' in inner) editable = false
                                t = XmlScan.unescape(inner)
                            }
                            else -> editable = false
                        }
                        // Anything else in a run (hyperlinks are kept in its properties) is fine; text with line ends is not
                        val s = if (c.local == "br") LINE.toString() else t.replace("\n", " ").replace("\r", "")
                        val m = interned.getOrPut(rPr) { RunStyle(rPr) }
                        text.append(s); repeat(s.length) { marks += m }
                    }
                    // A slide number or date field, an equation…: kept as it is
                    else -> editable = false
                }
            }
            val start = xml.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
            return SlidePara(key, start, box, text.toString(), marks, pPr, end, editable && !locked, xml.substring(e.start, e.end), marks.lastOrNull() ?: RunStyle(""))
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
