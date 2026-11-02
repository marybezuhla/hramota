package ua.hramota.doc.docx.edit

import ua.hramota.doc.docx.Numbering
import ua.hramota.doc.docx.RunProps
import ua.hramota.doc.docx.Styles
import ua.hramota.doc.docx.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A run's formatting as the file has it ([rPr], raw), and what the person changed on top (null: unchanged). */
data class RunStyle(val rPr: String, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val strike: Boolean? = null)

/** How a stretch of text looks. */
data class Look(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false)

enum class Kind { NORMAL, H1, H2, H3, OTHER }
enum class ListKind { BULLET, NUMBER }

/**
 * One paragraph while editing. [original] is its XML exactly as in the file while it is unchanged;
 * any change makes a new paragraph without it, written anew on saving. [editable] is false for
 * paragraphs holding what cannot be edited yet (pictures, fields, tracked changes…): they stay as they are.
 */
class EditPara(
    val key: Long,
    val slot: Int,
    val text: String,
    val marks: List<RunStyle>,
    val pPr: String,
    val startTag: String,
    val head: String,
    val tail: String,
    val editable: Boolean,
    val original: String?,
    /** Formatting for text typed into an empty paragraph. */
    val typing: RunStyle,
) {
    init { require(marks.size == text.length) }

    fun changed(text: String = this.text, marks: List<RunStyle> = this.marks, pPr: String = this.pPr, head: String = this.head, tail: String = this.tail, key: Long = this.key) =
        EditPara(key, slot, text, marks, pPr, startTag, head, tail, editable, null, marks.lastOrNull() ?: typing)
}

/** What the editor shows for a paragraph besides its text. */
class Shown(val kind: Kind, val marker: String?, val list: ListKind?, val inTable: Boolean, val base: Look, val rowStart: Boolean = false, val cellStart: Boolean = false)

/**
 * A DOCX opened for editing. Only the paragraphs the person changes are written anew; everything else
 * in the file (other paragraphs to the byte, tables, pictures, headers, other parts) stays as it was.
 */
class DocxEdit private constructor(
    private val xml: String,
    private val digest: ByteArray,
    private val w: String,
    private val slots: List<Slot>,
    private var stylesXml: String?,
    private var numberingXml: String?,
    private var relsXml: String?,
    private var typesXml: String?,
    val paragraphs: List<EditPara>,
) {
    /** Where a paragraph is in the file; [row] is the table row it is in (0 outside tables). */
    private class Slot(val start: Int, val end: Int, val container: Int, val row: Int = 0)

    private var nextKey = paragraphs.size.toLong() + 1
    fun newKey(): Long = nextKey++

    // Parts changed besides the document itself, and parts added
    private val changedParts = HashMap<String, String>()
    private var styles: Styles = parseStyles()
    private var numbering: ua.hramota.doc.docx.XmlNode? = numberingXml?.let { tree(it) }
    private var ourBullets: String? = null
    private var ourNumberAbstract: String? = null

    val canStyle: Boolean get() = stylesXml != null

    // --- What is shown ---

    private val flagsCache = HashMap<String, RunProps>()

    private fun explicit(rPr: String): RunProps = flagsCache.getOrPut(rPr) {
        if (rPr.isEmpty()) RunProps() else try {
            val kids = XmlScan.children(rPr, XmlScan.children(rPr, 0, rPr.length).first())
            fun on(local: String): Boolean? = kids.firstOrNull { it.local == local }?.let { e -> e.attrs["$w:val"].let { it == null || it == "1" || it == "true" || it == "on" } }
            val u = kids.firstOrNull { it.local == "u" }
            RunProps(bold = on("b"), italic = on("i"), underline = u?.let { it.attrs["$w:val"] != "none" }, strike = on("strike") ?: on("dstrike"),
                font = kids.firstOrNull { it.local == "rStyle" }?.attrs?.get("$w:val"))
        } catch (e: Exception) { RunProps() }
    }

    /** The look of one character: what the person set, else the run, its character style and the paragraph style. */
    fun look(p: EditPara, m: RunStyle): Look {
        val e = explicit(m.rPr)
        val base = runOfStyle(styleOf(p.pPr), e.font)
        return Look(
            bold = m.bold ?: e.bold ?: base.bold ?: false,
            italic = m.italic ?: e.italic ?: base.italic ?: false,
            underline = m.underline ?: e.underline ?: base.underline ?: false,
            strike = m.strike ?: e.strike ?: base.strike ?: false,
        )
    }

    /** Kind, list marker and the paragraph's own look, for each paragraph in order. */
    fun shown(list: List<EditPara>): List<Shown> {
        val nums = numbering?.let { Numbering.parse(it) } ?: Numbering.EMPTY
        var prevContainer = -1; var prevRow = -1
        return list.map { p ->
            val style = styleOf(p.pPr)
            val (pp, rp) = styles.paragraph(style)
            val num = numOf(p.pPr) ?: pp.numId?.let { it to (pp.numLevel ?: 0) }
            val marker = num?.takeIf { it.first != "0" }?.let { (id, lvl) -> nums.next(id, lvl) }
            val listKind = num?.takeIf { it.first != "0" }?.let { (id, lvl) -> if (nums.level(id, lvl)?.format == "bullet") ListKind.BULLET else ListKind.NUMBER }
            val slot = slots[p.slot]
            val cellStart = slot.row != 0 && slot.container != prevContainer
            val rowStart = slot.row != 0 && slot.row != prevRow
            prevContainer = slot.container; prevRow = slot.row
            Shown(kindOf(style), marker, listKind, slot.row != 0, Look(rp.bold ?: false, rp.italic ?: false, rp.underline ?: false, rp.strike ?: false), rowStart, cellStart)
        }
    }

    private fun kindOf(style: String?): Kind {
        val name = styles.styleName(styles.paraStyleId(style))?.lowercase() ?: return Kind.NORMAL
        return when {
            name == "heading 1" || name == "title" -> Kind.H1
            name == "heading 2" -> Kind.H2
            name.startsWith("heading ") -> Kind.H3
            styles.paraStyleId(style) == styles.defaultParagraphStyle -> Kind.NORMAL
            else -> Kind.OTHER
        }
    }

    private fun pprKids(pPr: String): List<Elem> = if (pPr.isEmpty()) emptyList() else XmlScan.children(pPr, XmlScan.children(pPr, 0, pPr.length).first())

    /** A paragraph's style and list, read once per distinct pPr (most paragraphs share a few). */
    private class Info(val style: String?, val num: Pair<String, Int>?)
    private val infoCache = HashMap<String, Info>()

    private fun info(pPr: String): Info = infoCache.getOrPut(pPr) {
        val kids = try { pprKids(pPr) } catch (e: Exception) { emptyList() }
        val style = kids.firstOrNull { it.local == "pStyle" }?.attrs?.get("$w:val")
        val num = kids.firstOrNull { it.local == "numPr" }?.let { n ->
            val nk = XmlScan.children(pPr, n)
            nk.firstOrNull { it.local == "numId" }?.attrs?.get("$w:val")?.let { id -> id to (nk.firstOrNull { it.local == "ilvl" }?.attrs?.get("$w:val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0) }
        }
        if (infoCache.size > 50_000) infoCache.clear()
        Info(style, num)
    }

    private fun styleOf(pPr: String): String? = info(pPr).style

    private fun numOf(pPr: String): Pair<String, Int>? = info(pPr).num

    // What a paragraph style gives its text, worked out once per style
    private val styleRun = HashMap<String?, RunProps>()
    private fun runOfStyle(style: String?, charStyle: String?): RunProps =
        styleRun.getOrPut(style + "\u0000" + charStyle) { styles.paragraph(style).second.with(styles.character(charStyle)) }

    // --- Changes ---

    /** True when [b] (right after [a]) can be joined to it: both editable, side by side in one place, no section ends between. */
    fun canJoin(a: EditPara, b: EditPara): Boolean {
        if (!a.editable || !b.editable) return false
        if (a.slot != b.slot) {
            if (b.slot != a.slot + 1) return false
            val sa = slots[a.slot]; val sb = slots[b.slot]
            if (sa.container != sb.container) return false
            if ((sa.end until sb.start).any { !xml[it].isWhitespace() }) return false
        }
        return SECT !in a.pPr && SECT !in b.pPr
    }

    fun join(a: EditPara, b: EditPara): EditPara = a.changed(text = a.text + b.text, marks = a.marks + b.marks, head = a.head + b.head, tail = a.tail + b.tail)

    /** Splits at [at]: the second part keeps where the section ends; a heading split at its end is followed by ordinary text. */
    fun split(p: EditPara, at: Int): Pair<EditPara, EditPara> {
        val withoutSection = removeChild(p.pPr, "sectPr", PPR_ORDER)
        var secondPpr = p.pPr
        if (at == p.text.length && shownKindOf(p) in setOf(Kind.H1, Kind.H2, Kind.H3)) secondPpr = removeChild(secondPpr, "pStyle", PPR_ORDER)
        val first = p.changed(text = p.text.substring(0, at), marks = p.marks.subList(0, at).toList(), pPr = withoutSection, tail = "")
        val style = p.marks.getOrNull(at - 1) ?: p.marks.getOrNull(at) ?: p.typing
        val second = EditPara(newKey(), p.slot, p.text.substring(at), p.marks.subList(at, p.text.length).toList(), secondPpr, "<$w:p>", "", p.tail, true, null, style)
        return first to second
    }

    private fun shownKindOf(p: EditPara) = kindOf(styleOf(p.pPr))

    fun kind(p: EditPara): Kind = shownKindOf(p)

    /** The paragraph as ordinary text or a heading of level 1–3 (the document's own heading styles, added if missing). */
    fun withKind(p: EditPara, kind: Kind): EditPara {
        val pPr = when (kind) {
            Kind.NORMAL, Kind.OTHER -> removeChild(p.pPr, "pStyle", PPR_ORDER)
            else -> {
                val id = headingStyle(when (kind) { Kind.H1 -> 1; Kind.H2 -> 2; else -> 3 }) ?: return p
                setChild(p.pPr, "pStyle", "<$w:pStyle $w:val=\"${XmlScan.escape(id)}\"/>", PPR_ORDER)
            }
        }
        return p.changed(pPr = pPr)
    }

    /** A list item (continuing the list right before it, if of the same kind) or no longer one. */
    fun withList(p: EditPara, kind: ListKind?, before: EditPara?): EditPara {
        if (kind == null) {
            val style = styles.paragraph(styleOf(p.pPr)).first
            // A list given by the style is turned off by number 0; one set here is simply removed
            val pPr = if (style.numId != null && style.numId != "0") setChild(p.pPr, "numPr", "<$w:numPr><$w:ilvl $w:val=\"0\"/><$w:numId $w:val=\"0\"/></$w:numPr>", PPR_ORDER)
                else removeChild(p.pPr, "numPr", PPR_ORDER)
            return p.changed(pPr = pPr)
        }
        val nums = numbering?.let { Numbering.parse(it) }
        val prev = before?.let { numOf(it.pPr) }
        val prevKind = prev?.let { (id, lvl) -> nums?.level(id, lvl)?.format?.let { if (it == "bullet") ListKind.BULLET else ListKind.NUMBER } }
        val (id, lvl) = if (prev != null && prevKind == kind) prev else (ourList(kind) ?: return p) to 0
        // The list's own indents apply, as in Word (a first-line indent would push the marker inward)
        val pPr = removeChild(p.pPr, "ind", PPR_ORDER)
        return p.changed(pPr = setChild(pPr, "numPr", "<$w:numPr><$w:ilvl $w:val=\"$lvl\"/><$w:numId $w:val=\"${XmlScan.escape(id)}\"/></$w:numPr>", PPR_ORDER))
    }

    /** The character formatting of [m] with one thing changed. */
    fun restyle(m: RunStyle, bold: Boolean? = null, italic: Boolean? = null, underline: Boolean? = null, strike: Boolean? = null) =
        m.copy(bold = bold ?: m.bold, italic = italic ?: m.italic, underline = underline ?: m.underline, strike = strike ?: m.strike)

    // --- Styles and numbering added when needed ---

    private fun headingStyle(level: Int): String? {
        val s = stylesXml ?: return null
        val root = XmlScan.children(s, 0, s.length).firstOrNull { it.local == "styles" } ?: return null
        val all = XmlScan.children(s, root).filter { it.local == "style" }
        for (st in all) {
            if (st.attrs["$w:type"] != "paragraph") continue
            val name = XmlScan.children(s, st).firstOrNull { it.local == "name" }?.attrs?.get("$w:val")
            if (name.equals("heading $level", ignoreCase = true)) return st.attrs["$w:styleId"]
        }
        // Not in the document: added like Word's own, based on the ordinary text style
        val ids = all.mapNotNull { it.attrs["$w:styleId"] }.toSet()
        var id = "Heading$level"; var k = 2
        while (id in ids) id = "Heading$level-${k++}"
        val size = when (level) { 1 -> 32; 2 -> 28; else -> 24 }
        val based = styles.defaultParagraphStyle?.let { "<$w:basedOn $w:val=\"${XmlScan.escape(it)}\"/><$w:next $w:val=\"${XmlScan.escape(it)}\"/>" } ?: ""
        val def = "<$w:style $w:type=\"paragraph\" $w:styleId=\"$id\"><$w:name $w:val=\"heading $level\"/>$based<$w:uiPriority $w:val=\"9\"/><$w:qFormat/>" +
            "<$w:pPr><$w:keepNext/><$w:keepLines/><$w:spacing $w:before=\"${if (level == 1) 360 else 240}\" $w:after=\"120\"/><$w:outlineLvl $w:val=\"${level - 1}\"/></$w:pPr>" +
            "<$w:rPr><$w:b/><$w:bCs/><$w:sz $w:val=\"$size\"/><$w:szCs $w:val=\"$size\"/></$w:rPr></$w:style>"
        stylesXml = s.substring(0, root.closeStart) + def + s.substring(root.closeStart)
        changedParts["word/styles.xml"] = stylesXml!!
        styles = parseStyles()
        styleRun.clear()
        return id
    }

    /** A list of our own: one for all bullets; each new numbered list counts from 1 (a list of its own over the same levels). */
    private fun ourList(kind: ListKind): String? {
        if (kind == ListKind.BULLET) ourBullets?.let { return it }
        if (kind == ListKind.NUMBER) ourNumberAbstract?.let { return addNum(it) }
        var s = numberingXml ?: run {
            // No numbering in the document yet: a part of its own, known to the package
            val rels = relsXml ?: return null
            val types = typesXml ?: return null
            // Numbering kept under another name: not added a second time
            if ("relationships/numbering\"" in rels) return null
            val relsEnd = rels.lastIndexOf("</Relationships>").takeIf { it > 0 } ?: return null
            val typesEnd = types.lastIndexOf("</Types>").takeIf { it > 0 } ?: return null
            var rid = "rIdHramota1"; var k = 2
            while ("\"$rid\"" in rels) rid = "rIdHramota${k++}"
            relsXml = rels.substring(0, relsEnd) + "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" + rels.substring(relsEnd)
            if ("/word/numbering.xml" !in types) typesXml = types.substring(0, typesEnd) + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" + types.substring(typesEnd)
            changedParts["word/_rels/document.xml.rels"] = relsXml!!
            changedParts["[Content_Types].xml"] = typesXml!!
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<$w:numbering xmlns:$w=\"$MAIN\"></$w:numbering>"
        }
        val root = XmlScan.children(s, 0, s.length).firstOrNull { it.local == "numbering" } ?: return null
        val kids = XmlScan.children(s, root)
        val abstractId = (kids.filter { it.local == "abstractNum" }.mapNotNull { it.attrs["$w:abstractNumId"]?.toIntOrNull() }.maxOrNull() ?: -1) + 1
        val numId = (kids.filter { it.local == "num" }.mapNotNull { it.attrs["$w:numId"]?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val levels = (0..8).joinToString("") { l ->
            val (fmt, text) = if (kind == ListKind.BULLET) "bullet" to listOf("•", "◦", "▪")[l % 3] else listOf("decimal", "lowerLetter", "lowerRoman")[l % 3] to "%${l + 1}."
            "<$w:lvl $w:ilvl=\"$l\"><$w:start $w:val=\"1\"/><$w:numFmt $w:val=\"$fmt\"/><$w:lvlText $w:val=\"$text\"/><$w:lvlJc $w:val=\"left\"/>" +
                "<$w:pPr><$w:ind $w:left=\"${720 * (l + 1)}\" $w:hanging=\"360\"/></$w:pPr></$w:lvl>"
        }
        val abstract = "<$w:abstractNum $w:abstractNumId=\"$abstractId\"><$w:multiLevelType $w:val=\"hybridMultilevel\"/>$levels</$w:abstractNum>"
        val num = "<$w:num $w:numId=\"$numId\"><$w:abstractNumId $w:val=\"$abstractId\"/></$w:num>"
        // Schema order: pictures for bullets, abstract lists, then lists
        val firstNum = kids.firstOrNull { it.local == "num" || it.local == "numIdMacAtCleanup" }?.start ?: root.closeStart
        s = s.substring(0, firstNum) + abstract + s.substring(firstNum)
        val r2 = XmlScan.children(s, 0, s.length).first { it.local == "numbering" }
        val cleanup = XmlScan.children(s, r2).firstOrNull { it.local == "numIdMacAtCleanup" }?.start ?: r2.closeStart
        s = s.substring(0, cleanup) + num + s.substring(cleanup)
        numberingXml = s
        changedParts["word/numbering.xml"] = s
        numbering = tree(s)
        if (kind == ListKind.BULLET) ourBullets = numId.toString() else ourNumberAbstract = abstractId.toString()
        return numId.toString()
    }

    private fun addNum(abstractId: String): String? {
        var s = numberingXml ?: return null
        val root = XmlScan.children(s, 0, s.length).firstOrNull { it.local == "numbering" } ?: return null
        val kids = XmlScan.children(s, root)
        val numId = (kids.filter { it.local == "num" }.mapNotNull { it.attrs["$w:numId"]?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val at = kids.firstOrNull { it.local == "numIdMacAtCleanup" }?.start ?: root.closeStart
        s = s.substring(0, at) + "<$w:num $w:numId=\"$numId\"><$w:abstractNumId $w:val=\"$abstractId\"/></$w:num>" + s.substring(at)
        numberingXml = s
        changedParts["word/numbering.xml"] = s
        numbering = tree(s)
        return numId.toString()
    }

    private fun parseStyles(): Styles = stylesXml?.let { s -> tree(s)?.let { Styles.parse(it) } } ?: Styles.EMPTY

    // --- Saving ---

    /** The document part with every changed paragraph written anew and the rest as it was. */
    fun documentXml(list: List<EditPara>): String {
        val b = StringBuilder(xml.length + 4096)
        var pos = 0
        var i = 0
        for ((k, slot) in slots.withIndex()) {
            b.append(xml, pos, slot.start)
            while (i < list.size && list[i].slot == k) { write(list[i], b); i++ }
            pos = slot.end
        }
        b.append(xml, pos, xml.length)
        if (i != list.size) throw IOException("Paragraphs out of order")
        return b.toString()
    }

    private fun write(p: EditPara, b: StringBuilder) {
        p.original?.let { b.append(it); return }
        b.append(p.startTag).append(p.pPr).append(p.head)
        var i = 0
        while (i < p.text.length) {
            val m = p.marks[i]
            var j = i
            while (j < p.text.length && p.marks[j] == m) j++
            b.append("<$w:r>").append(rprOf(m))
            var k = i
            while (k < j) {
                when (p.text[k]) {
                    '\t' -> { b.append("<$w:tab/>"); k++ }
                    LINE -> { b.append("<$w:br/>"); k++ }
                    '­' -> { b.append("<$w:softHyphen/>"); k++ }
                    '‑' -> { b.append("<$w:noBreakHyphen/>"); k++ }
                    else -> {
                        var e = k
                        while (e < j && p.text[e] !in SPECIAL) e++
                        b.append("<$w:t xml:space=\"preserve\">").append(XmlScan.escape(p.text.substring(k, e))).append("</$w:t>")
                        k = e
                    }
                }
            }
            b.append("</$w:r>")
            i = j
        }
        b.append(p.tail).append("</$w:p>")
    }

    private fun rprOf(m: RunStyle): String {
        var r = m.rPr
        fun flag(local: String, cs: String?, on: Boolean?) {
            if (on == null) return
            r = setChild(r, local, if (on) "<$w:$local/>" else "<$w:$local $w:val=\"0\"/>", RPR_ORDER, "rPr")
            if (cs != null) r = setChild(r, cs, if (on) "<$w:$cs/>" else "<$w:$cs $w:val=\"0\"/>", RPR_ORDER, "rPr")
        }
        flag("b", "bCs", m.bold)
        flag("i", "iCs", m.italic)
        if (m.strike != null) { r = removeChild(r, "dstrike", RPR_ORDER, "rPr"); flag("strike", null, m.strike) }
        if (m.underline != null) r = setChild(r, "u", "<$w:u $w:val=\"${if (m.underline) "single" else "none"}\"/>", RPR_ORDER, "rPr")
        return if (r == "<$w:rPr></$w:rPr>") "" else r
    }

    /**
     * Writes the edited document: the package is read again from [source] and copied entry by entry,
     * with the changed parts put in. Refused if the document part is no longer the one opened.
     */
    fun save(list: List<EditPara>, source: InputStream, out: OutputStream) {
        val doc = documentXml(list).toByteArray(Charsets.UTF_8)
        val changedParts = HashMap(this.changedParts)
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
                when {
                    name == DOCUMENT -> {
                        val was = readAll(zip, MAX_DOCUMENT)
                        if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digest)) throw IOException("The document changed meanwhile")
                        z.write(doc)
                    }
                    changedParts.containsKey(name) -> z.write(changedParts.getValue(name).toByteArray(Charsets.UTF_8))
                    else -> {
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
            if (DOCUMENT !in written) throw IOException("No document part")
            for ((name, text) in changedParts) if (name !in written) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            z.finish()
            z.flush()
        }
    }

    companion object {
        const val MAIN = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val DOCUMENT = "word/document.xml"
        /** A line break within a paragraph (Shift+Enter in Word). */
        const val LINE = ' '
        private val SPECIAL = charArrayOf('\t', LINE, '­', '‑')
        /** The largest document part opened for editing: its text is held in memory twice. */
        const val MAX_DOCUMENT = 24L * 1024 * 1024
        private const val MAX_SMALL = 8L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val SECT = "sectPr"

        // Schema order of what a paragraph's and a run's properties hold (Word refuses another order)
        private val PPR_ORDER = listOf("pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers", "pBdr", "shd", "tabs",
            "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
            "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")
        private val RPR_ORDER = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss", "imprint", "noProof",
            "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign",
            "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath", "rPrChange")

        /** Opens a DOCX for editing. Call off the main thread. */
        fun open(input: InputStream): DocxEdit {
            var doc: ByteArray? = null
            var styles: String? = null; var numbering: String? = null; var rels: String? = null; var types: String? = null
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    when (e.name) {
                        DOCUMENT -> doc = readAll(zip, MAX_DOCUMENT)
                        "word/styles.xml" -> styles = text(readAll(zip, MAX_SMALL))
                        "word/numbering.xml" -> numbering = text(readAll(zip, MAX_SMALL))
                        "word/_rels/document.xml.rels" -> rels = text(readAll(zip, MAX_SMALL))
                        "[Content_Types].xml" -> types = text(readAll(zip, MAX_SMALL))
                    }
                }
            }
            val bytes = doc ?: throw IOException("No document part")
            val xml = text(bytes)
            val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "document" } ?: throw IOException("No document")
            // The prefix the document gives the main namespace (almost always "w")
            val w = root.attrs.entries.firstOrNull { it.value == MAIN && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: throw IOException("Unusual namespace")
            val body = XmlScan.children(xml, root).firstOrNull { it.name == "$w:body" } ?: throw IOException("No body")
            val slots = ArrayList<Slot>()
            val paras = ArrayList<EditPara>()
            var containers = 0
            var rows = 0
            val interned = HashMap<String, RunStyle>()
            fun collect(elems: List<Elem>, container: Int, row: Int) {
                for (e in elems) {
                    if (!e.name.startsWith("$w:")) continue
                    when (e.local) {
                        "p" -> { slots += Slot(e.start, e.end, container, row); paras += paragraph(xml, w, e, slots.size - 1, interned) }
                        "tbl" -> for (tr in XmlScan.children(xml, e)) if (tr.name == "$w:tr") { val r = ++rows; for (tc in XmlScan.children(xml, tr)) if (tc.name == "$w:tc") collect(XmlScan.children(xml, tc), ++containers, r) }
                        "sdt", "customXml" -> XmlScan.children(xml, e).firstOrNull { it.local == "sdtContent" }?.let { collect(XmlScan.children(xml, it), ++containers, row) }
                            ?: if (e.local == "customXml") collect(XmlScan.children(xml, e), ++containers, row) else Unit
                    }
                }
            }
            collect(XmlScan.children(xml, body), 0, 0)
            return DocxEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), w, slots, styles, numbering, rels, types, paras)
        }

        private fun paragraph(xml: String, w: String, e: Elem, slot: Int, interned: HashMap<String, RunStyle>): EditPara {
            val raw = xml.substring(e.start, e.end)
            val startTag = xml.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
            var pPr = ""
            val head = StringBuilder(); val tail = StringBuilder()
            val text = StringBuilder()
            val marks = ArrayList<RunStyle>()
            var editable = true
            for (c in XmlScan.children(xml, e)) {
                if (!c.name.startsWith("$w:")) { editable = false; continue }
                when (c.local) {
                    "pPr" -> { pPr = xml.substring(c.start, c.end); if ("PrChange" in pPr) editable = false }
                    "proofErr" -> {}
                    "bookmarkStart" -> head.append(xml, c.start, c.end)
                    "bookmarkEnd" -> tail.append(xml, c.start, c.end)
                    "r" -> {
                        var rPr = ""
                        for (rc in XmlScan.children(xml, c)) {
                            if (!rc.name.startsWith("$w:")) { editable = false; continue }
                            val ch: String? = when (rc.local) {
                                "rPr" -> { rPr = xml.substring(rc.start, rc.end); if ("PrChange" in rPr) editable = false; null }
                                "t" -> if (rc.empty) "" else XmlScan.unescape(xml.substring(rc.openEnd, rc.closeStart))
                                "tab" -> "\t"
                                "br" -> if (rc.attrs["$w:type"].let { it == null || it == "textWrapping" } && rc.attrs["$w:clear"] == null) LINE.toString() else { editable = false; null }
                                "cr" -> LINE.toString()
                                "softHyphen" -> "­"
                                "noBreakHyphen" -> "‑"
                                "lastRenderedPageBreak" -> null
                                else -> { editable = false; null }
                            }
                            if (ch != null) {
                                val clean = ch.replace("\n", " ").replace("\r", "")
                                val m = interned.getOrPut(rPr) { RunStyle(rPr) }
                                text.append(clean); repeat(clean.length) { marks += m }
                            }
                        }
                    }
                    else -> editable = false
                }
            }
            return EditPara(slot.toLong() + 1, slot, text.toString(), marks, pPr, startTag, head.toString(), tail.toString(), editable, raw, marks.lastOrNull() ?: RunStyle(""))
        }

        private fun text(b: ByteArray): String {
            Xml.checkNoDoctype(b)
            return String(b, Charsets.UTF_8).removePrefix("﻿")
        }

        private fun tree(s: String) = try { Xml.tree(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))) } catch (e: Exception) { null }

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

        /** [props] (a whole pPr or rPr element, or "") with its [local] child replaced by [child], in schema order. */
        internal fun setChild(props: String, local: String, child: String, order: List<String>, element: String = "pPr"): String {
            val w = child.substring(1).substringBefore(':')
            val p = props.ifEmpty { "<$w:$element></$w:$element>" }
            val outer = XmlScan.children(p, 0, p.length).first()
            val inner = if (outer.empty) "" else p.substring(outer.openEnd, outer.closeStart)
            val start = if (outer.selfClosing) p.substring(0, outer.start) + p.substring(outer.start, outer.end).removeSuffix("/>").trimEnd() + ">" else p.substring(0, outer.openEnd)
            val kids = XmlScan.children(inner, 0, inner.length)
            val rank = order.indexOf(local).let { if (it < 0) order.size else it }
            val b = StringBuilder(start)
            var placed = false
            var pos = 0
            for (k in kids) {
                val r = order.indexOf(k.local).let { if (it < 0) order.size else it }
                if (k.local == local) { b.append(inner, pos, k.start); pos = k.end; if (!placed) { b.append(child); placed = true }; continue }
                if (!placed && r > rank) { b.append(inner, pos, k.start); b.append(child); placed = true; pos = k.start }
            }
            b.append(inner, pos, inner.length)
            if (!placed) b.append(child)
            b.append("</${outer.name}>")
            return b.toString()
        }

        /** [props] without its [local] child (and without itself, if nothing is left). */
        internal fun removeChild(props: String, local: String, order: List<String>, element: String = "pPr"): String {
            if (props.isEmpty()) return props
            val outer = XmlScan.children(props, 0, props.length).first()
            if (outer.empty) return ""
            val inner = props.substring(outer.openEnd, outer.closeStart)
            val kids = XmlScan.children(inner, 0, inner.length)
            val drop = kids.filter { it.local == local }
            if (drop.isEmpty()) return props
            val b = StringBuilder(props.substring(0, outer.openEnd))
            var pos = 0
            for (k in drop) { b.append(inner, pos, k.start); pos = k.end }
            b.append(inner, pos, inner.length)
            val rest = b.substring(outer.openEnd)
            if (rest.isBlank()) return ""
            return b.append("</${outer.name}>").toString()
        }
    }
}
