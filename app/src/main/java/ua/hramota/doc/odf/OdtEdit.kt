package ua.hramota.doc.odf

import ua.hramota.doc.docx.DocxDocument
import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Anchor
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
 * underline and strike-through, headings, lists, comments, and paragraphs split and joined. Only the
 * paragraphs changed are written anew (with the styles they need added among the document's own);
 * everything else in content.xml and every other part stays as the file had it.
 *
 * A run's [RunStyle.rPr] says what its text is in: a text style's name ("" for none); a link's start
 * tag and the style ([LINK] before each); or a whole object written as it was, a note, a field or a
 * picture ([ATOM], a number and its XML: the text shown for it is not changed into the file).
 * A paragraph's [EditPara.startTag] may begin with lists closed or opened before it, its [EditPara.pPr]
 * holds what closes or opens after it.
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
    private val fileComments: List<Comment>,
) : TextEdit {
    /** The prefixes the file gives the OpenDocument namespaces ([dc] null when it has none). */
    class Prefixes(val office: String, val text: String, val style: String, val fo: String, val table: String, val dc: String?)

    /** A text style of the content: its parent, and its text properties' attributes (prefixed names). */
    class AutoStyle(val parent: String?, val props: Map<String, String>)

    /** Where a paragraph is in the file; [row] is its table row (0 outside tables); [list] where it is in a list. */
    private class Slot(val start: Int, val end: Int, val container: Int, val row: Int = 0, val list: ListPos? = null)

    /** A paragraph in a list: the list style, its level (0 first), whether it begins its item, the item's element, and whether a list starts there. */
    private class ListPos(val style: String?, val level: Int, val firstInItem: Boolean, val item: String, val header: Boolean, val restart: Boolean)

    override val canStyle = true
    override val canList = true
    override val canComment get() = ns.dc != null
    override val extension = "odt"

    private var nextKey = paragraphs.size.toLong() + 1
    override fun newKey(): Long = nextKey++

    // --- What a run is in ---

    private fun spanOf(rPr: String): String = when {
        rPr.startsWith(LINK) -> rPr.substring(rPr.lastIndexOf(LINK) + 1)
        rPr.startsWith(ATOM) -> ""
        else -> rPr
    }
    private fun linkOf(rPr: String): String? = if (rPr.startsWith(LINK)) rPr.substring(1, rPr.lastIndexOf(LINK)) else null
    private fun atomOf(rPr: String): String? = if (rPr.startsWith(ATOM)) rPr.substring(rPr.indexOf(ATOM, 1) + 1) else null

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
        val base = lookOf(styleOf(p.startTag), spanOf(m.rPr))
        return Look(m.bold ?: base.bold, m.italic ?: base.italic, m.underline ?: base.underline, m.strike ?: base.strike)
    }

    /** The paragraph's own start tag (after any lists closed or opened before it). */
    private fun own(tag: String): String = tag.substring(tag.lastIndexOf('<'))
    /** What comes before the paragraph's own start tag. */
    private fun lead(tag: String): String = tag.substring(0, tag.lastIndexOf('<'))
    private fun attrsOf(tag: String): Map<String, String> = try { val t = own(tag).removeSuffix(">") + "/>"; XmlScan.children(t, 0, t.length).firstOrNull()?.attrs ?: emptyMap() } catch (e: Exception) { emptyMap() }
    private fun styleOf(startTag: String): String? = attrsOf(startTag)["${ns.text}:style-name"]
    private fun elementOf(startTag: String): String = own(startTag).drop(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }

    override fun kind(p: EditPara): Kind {
        val tag = p.startTag
        if (elementOf(tag) != "${ns.text}:h") return Kind.NORMAL
        return when (attrsOf(tag)["${ns.text}:outline-level"]?.toIntOrNull() ?: 1) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
    }

    // --- Lists ---

    private val t get() = ns.text
    private fun ourOpen(kind: ListKind, cont: Boolean) = "<$t:list $t:style-name=\"${if (kind == ListKind.BULLET) bulletStyle else numberStyle}\"" + (if (cont) " $t:continue-numbering=\"true\"" else "") + "><$t:list-item>"
    private val ourClose get() = "</$t:list-item></$t:list>"
    private val leave get() = "</$t:list-item></$t:list>"
    private fun reenter(style: String?) = "<$t:list" + (style?.let { " $t:style-name=\"${XmlScan.escape(it)}\"" } ?: "") + " $t:continue-numbering=\"true\"><$t:list-item>"
    private val ourOpenRe get() = Regex("<${Regex.escape(t)}:list ${Regex.escape(t)}:style-name=\"(${Regex.escape(bulletStyle)}|${Regex.escape(numberStyle)})\"(?: ${Regex.escape(t)}:continue-numbering=\"true\")?><${Regex.escape(t)}:list-item>$")

    // Names for the two lists added here, not taken in the document
    private val bulletStyle: String = generateSequence(1) { it + 1 }.map { if (it == 1) "HrB" else "HrB$it" }.first { it !in styles.lists && it !in styles.named }
    private val numberStyle: String = generateSequence(1) { it + 1 }.map { if (it == 1) "HrN" else "HrN$it" }.first { it !in styles.lists && it !in styles.named }

    /** The paragraph's list as the file has it, and its kind. */
    private fun fileList(p: EditPara): Pair<ListPos, ListKind>? {
        val l = slots[p.slot].list?.takeIf { !it.header } ?: return null
        return l to label(l.style, l.level, HashMap()).second
    }

    /** The list the paragraph is shown in now: one added here, none (taken out of the file's), or the file's. */
    private fun listNow(p: EditPara): ListKind? {
        val lead = lead(p.startTag)
        ourOpenRe.find(lead)?.let { return if (it.groupValues[1] == bulletStyle) ListKind.BULLET else ListKind.NUMBER }
        if (lead.endsWith(leave)) return null
        return fileList(p)?.second
    }

    override fun withList(p: EditPara, kind: ListKind?, before: EditPara?): EditPara {
        var lead = lead(p.startTag)
        // What was done here before comes off: back to the paragraph as the file has it
        ourOpenRe.find(lead)?.let { lead = lead.substring(0, it.range.first) }
        lead = lead.removeSuffix(leave)
        var after = ""
        val file = fileList(p)
        if (kind != null && file?.second == kind) {
            // Back in the file's own list
        } else {
            if (file != null) { lead += leave; after = reenter(file.first.style) }
            if (kind != null) {
                // Numbers go on from the paragraph before when it is in a list of this kind added here
                val cont = before != null && ourOpenRe.find(lead(before.startTag))?.groupValues?.get(1) == (if (kind == ListKind.BULLET) bulletStyle else numberStyle)
                lead += ourOpen(kind, cont)
                after = ourClose + after
            }
        }
        return EditPara(p.key, p.slot, p.text, p.marks, after, lead + own(p.startTag), p.head, p.tail, p.editable, null, p.typing, p.anchors)
    }

    override fun shown(list: List<EditPara>): List<Shown> {
        var prevContainer = -1; var prevRow = -1
        val counters = HashMap<String, IntArray>()
        var ours = 0
        var prevSlot = -1
        val open = LinkedHashSet<String>()
        return list.map { p ->
            val openHere = if (open.isEmpty()) emptySet() else open.toSet()
            for (a in p.anchors) when (a.kind) { Anchor.Kind.START -> open += a.comment; Anchor.Kind.END -> open -= a.comment; else -> {} }
            val slot = slots[p.slot]
            val cellStart = slot.row != 0 && slot.container != prevContainer
            val rowStart = slot.row != 0 && slot.row != prevRow
            prevContainer = slot.container; prevRow = slot.row
            var marker: String? = null
            val lead = lead(p.startTag)
            val mine = ourOpenRe.find(lead)
            val listKind = listNow(p)
            if (mine != null) {
                if (listKind == ListKind.NUMBER) { ours = if (mine.value.contains("continue-numbering")) ours + 1 else 1; marker = "$ours." } else marker = "•"
            } else if (listKind != null) {
                val l = slot.list!!
                // A list item's first paragraph has its marker (an item made here by a split too)
                if (l.firstInItem && p.slot != prevSlot || lead.startsWith("</")) {
                    if (l.restart && l.firstInItem && !lead.startsWith("</")) counters.remove(l.style ?: "")
                    marker = label(l.style, l.level, counters).first
                }
            }
            prevSlot = p.slot
            val ps = styles.props(styleOf(p.startTag), "paragraph")
            Shown(kind(p), marker, listKind, slot.row != 0, Look(on(ps, "bold") ?: false, on(ps, "italic") ?: false, on(ps, "underline") ?: false, on(ps, "strike") ?: false), rowStart, cellStart, openHere)
        }
    }

    private fun label(style: String?, level: Int, counters: HashMap<String, IntArray>): Pair<String, ListKind> {
        val ls = styles.lists[style]
        val lvl = ls?.elements?.firstOrNull { (it.attr("level")?.toIntOrNull() ?: 1) == level + 1 }
        if (lvl == null || lvl.name == "list-level-style-bullet") return (ua.hramota.doc.Bullets.shown(lvl?.attr("bullet-char")?.takeIf { it.isNotBlank() })) to ListKind.BULLET
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
        // Lists changed here around either: not joined (their tags would not pair)
        if (a.pPr.isNotEmpty() || lead(b.startTag).isNotEmpty() && b.slot != a.slot) return false
        if (a.slot == b.slot) return lead(b.startTag).let { it.isEmpty() || it == "</${slots[a.slot].list?.item}><${slots[a.slot].list?.item}>" }
        if (b.slot != a.slot + 1) return false
        val sa = slots[a.slot]; val sb = slots[b.slot]
        if (sa.container != sb.container) return false
        return (sa.end until sb.start).all { xml[it].isWhitespace() }
    }

    override fun join(a: EditPara, b: EditPara): EditPara = a.changed(text = a.text + b.text, marks = a.marks + b.marks, head = a.head + b.head, tail = a.tail + b.tail, pPr = b.pPr,
        anchors = a.anchors + b.anchors.map { it.moved(it.at + a.text.length) })

    override fun retyped(p: EditPara, text: String, marks: List<RunStyle>, from: Int, oldEnd: Int): EditPara {
        val delta = text.length - p.text.length
        val newEnd = oldEnd + delta
        // What is typed is never part of a note, field or picture (nor taken into one on saving)
        val fixed = if (newEnd > from && (from until newEnd).any { marks[it].rPr.startsWith(ATOM) }) {
            val plain = (from - 1 downTo 0).map { marks[it] }.firstOrNull { !it.rPr.startsWith(ATOM) }
                ?: (newEnd until marks.size).map { marks[it] }.firstOrNull { !it.rPr.startsWith(ATOM) } ?: RunStyle("")
            marks.mapIndexed { i, m -> if (i in from until newEnd && m.rPr.startsWith(ATOM)) plain else m }
        } else marks
        val anchors = p.anchors.map { a ->
            when {
                a.at <= from -> a
                a.at >= oldEnd -> a.moved(a.at + delta)
                a.kind == Anchor.Kind.START -> a.moved(from)
                else -> a.moved(newEnd)
            }
        }
        return p.changed(text = text, marks = fixed, anchors = anchors)
    }

    /** The start tag without the attributes that must not be written twice (an element's own id). */
    private fun withoutIds(tag: String): String = tag.replace(Regex("\\s(?:xml:id|${Regex.escape(ns.text)}:id)=(?:\"[^\"]*\"|'[^']*')"), "")

    override fun split(p: EditPara, at: Int): Pair<EditPara, EditPara> {
        val (keep, move) = p.anchors.partition { a ->
            a.at < at || (a.at == at && (a.kind != Anchor.Kind.START || p.anchors.any { it.comment == a.comment && it.kind != Anchor.Kind.START && it.at == at }))
        }
        val now = listNow(p)
        val mine = ourOpenRe.find(lead(p.startTag)) != null
        // A heading split at its end is followed by ordinary text
        val tag = if (at == p.text.length && kind(p) != Kind.NORMAL) bodyTag() else withoutIds(own(p.startTag))
        val l = slots[p.slot].list
        val style = p.marks.getOrNull(at - 1)?.takeIf { !it.rPr.startsWith(ATOM) } ?: p.marks.getOrNull(at)?.takeIf { !it.rPr.startsWith(ATOM) } ?: RunStyle(spanOf(p.typing.rPr))
        val secondAnchors = move.map { it.moved(it.at - at) }
        fun first(after: String) = EditPara(p.key, p.slot, p.text.substring(0, at), p.marks.subList(0, at).toList(), after, p.startTag, p.head, "", p.editable, null, p.typing, keep)
        fun second(lead: String, after: String) = EditPara(newKey(), p.slot, p.text.substring(at), p.marks.subList(at, p.text.length).toList(), after, lead + tag, "", p.tail, true, null, style, secondAnchors)
        return when {
            // In a list added here: the first part's list closes, the second part's goes on with it
            mine && now != null -> first(ourClose) to second(ourOpen(now, true), p.pPr.removePrefix(ourClose).let { ourClose + it })
            // In the file's list: a new item of it
            l != null && !l.header && now != null -> first(p.pPr) to second("</${l.item}><${l.item}>", "")
            // Taken out of the file's list: the second part stays out too (the list opens again after it)
            l != null && now == null && p.pPr.isNotEmpty() -> first("") to second("", p.pPr)
            else -> first(p.pPr) to second("", "")
        }
    }

    private fun bodyTag(): String {
        val name = listOf("Text_20_body", "Standard").firstOrNull { it in styles.named }
        return "<$t:p" + (name?.let { " $t:style-name=\"$it\"" } ?: "") + ">"
    }

    override fun withKind(p: EditPara, kind: Kind): EditPara {
        val tag = when (kind) {
            Kind.NORMAL, Kind.OTHER -> bodyTag()
            else -> {
                val level = when (kind) { Kind.H1 -> 1; Kind.H2 -> 2; else -> 3 }
                val name = listOf("Heading_20_$level", "Heading").firstOrNull { it in styles.named }
                "<$t:h" + (name?.let { " $t:style-name=\"$it\"" } ?: "") + " $t:outline-level=\"$level\">"
            }
        }
        return EditPara(p.key, p.slot, p.text, p.marks, p.pPr, lead(p.startTag) + tag, p.head, p.tail, p.editable, null, p.typing, p.anchors)
    }

    // --- Comments (LibreOffice's: office:annotation, and its end for a range) ---

    private val added = ArrayList<Comment>()
    private var nextComment = 1

    override fun comments(): List<Comment> = fileComments + added

    override fun comment(p: EditPara, from: Int, to: Int, author: String, text: String): EditPara {
        val dc = ns.dc ?: return p
        val o = ns.office
        var name: String
        do { name = "__Annotation__Hr${nextComment++}" } while (comments().any { it.id == name })
        val date = java.time.LocalDateTime.now().withNano(0).toString()
        added += Comment(name, author.take(100), date, text.take(20_000))
        val a = from.coerceIn(0, p.text.length); val b = to.coerceIn(a, p.text.length)
        val body = "<$o:annotation $o:name=\"$name\"><$dc:creator>${XmlScan.escape(author.take(100))}</$dc:creator><$dc:date>$date</$dc:date>" +
            text.take(20_000).split('\n').joinToString("") { "<$t:p>${XmlScan.escape(it)}</$t:p>" } + "</$o:annotation>"
        val anchors = p.anchors + listOf(Anchor(a, body, name, Anchor.Kind.START), Anchor(b, "<$o:annotation-end $o:name=\"$name\"/>", name, Anchor.Kind.END))
        return p.changed(anchors = anchors.withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value })
    }

    override fun uncomment(list: List<EditPara>, id: String): List<EditPara>? {
        if (list.any { !it.editable && it.anchors.any { a -> a.comment == id } }) return null
        if (list.none { p -> p.anchors.any { it.comment == id } }) return null
        return list.map { p -> if (p.anchors.none { it.comment == id }) p else p.changed(anchors = p.anchors.filter { it.comment != id }) }
    }

    override fun commentPlace(list: List<EditPara>, id: String): Pair<Pair<Int, Int>, String>? {
        var start: Pair<Int, Int>? = null
        val quoted = StringBuilder()
        for ((i, p) in list.withIndex()) {
            val s0 = p.anchors.firstOrNull { it.comment == id && it.kind == Anchor.Kind.START }
            val e0 = p.anchors.firstOrNull { it.comment == id && it.kind == Anchor.Kind.END }
            if (start == null && s0 == null && e0 == null) continue
            if (start == null) start = i to (s0 ?: e0)!!.at
            val from = s0?.at ?: 0
            val to = e0?.at ?: p.text.length
            if (quoted.isNotEmpty()) quoted.append(' ')
            quoted.append(p.text, from.coerceIn(0, p.text.length), to.coerceIn(from.coerceIn(0, p.text.length), p.text.length))
            if (e0 != null || quoted.length > 300) break
        }
        return start?.let { it to quoted.toString().take(300) }
    }

    // --- Saving ---

    /** Text styles made for this save: by what they stand for, their name and definition. */
    private class Made(val names: HashMap<String, String> = HashMap(), val defs: StringBuilder = StringBuilder())

    private fun styleFor(m: RunStyle, made: Made): String {
        val span = spanOf(m.rPr)
        if (m.bold == null && m.italic == null && m.underline == null && m.strike == null) return span
        val key = "$span\u0000${m.bold}\u0000${m.italic}\u0000${m.underline}\u0000${m.strike}"
        made.names[key]?.let { return it }
        var n = made.names.size + 1
        var name = "HrT$n"
        while (name in styles.named || name in autoText || name in made.names.values) name = "HrT${++n}"
        // From the run's own style: an automatic one copied, a common one as the parent
        val auto = autoText[span]
        val parent = if (span.isEmpty()) null else auto?.parent ?: span.takeIf { auto == null }
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

    /** The two lists added here, as list styles (written only when used). */
    private fun listStyles(content: String): String {
        val st = ns.style; val fo = ns.fo
        fun props(n: Int) = "<$st:list-level-properties $t:list-level-position-and-space-mode=\"label-alignment\"><$st:list-level-label-alignment $t:label-followed-by=\"listtab\" " +
            "$t:list-tab-stop-position=\"${0.635 * n}cm\" $fo:text-indent=\"-0.635cm\" $fo:margin-left=\"${0.635 * n}cm\"/></$st:list-level-properties>"
        val b = StringBuilder()
        if ("$t:style-name=\"$bulletStyle\"" in content) b.append("<$t:list-style $st:name=\"$bulletStyle\">")
            .append((1..10).joinToString("") { "<$t:list-level-style-bullet $t:level=\"$it\" $t:bullet-char=\"${if (it % 2 == 1) "•" else "◦"}\">${props(it)}</$t:list-level-style-bullet>" }).append("</$t:list-style>")
        if ("$t:style-name=\"$numberStyle\"" in content) b.append("<$t:list-style $st:name=\"$numberStyle\">")
            .append((1..10).joinToString("") { "<$t:list-level-style-number $t:level=\"$it\" $st:num-suffix=\".\" $st:num-format=\"1\">${props(it)}</$t:list-level-style-number>" }).append("</$t:list-style>")
        return b.toString()
    }

    private fun write(p: EditPara, b: StringBuilder, made: Made) {
        p.original?.let { b.append(it); return }
        b.append(p.startTag).append(p.head)
        val anchors = p.anchors.sortedBy { it.at.coerceIn(0, p.text.length) }
        var next = 0
        fun anchorsUpTo(pos: Int) { while (next < anchors.size && anchors[next].at.coerceIn(0, p.text.length) <= pos) { b.append(anchors[next].xml); next++ } }
        var i = 0
        // Spaces: one between words is written as is; more, or one at the start or after another, as text:s
        var lastSpace = true
        val atomsWritten = HashSet<String>()
        while (i < p.text.length) {
            anchorsUpTo(i)
            val m = p.marks[i]
            val stop = if (next < anchors.size) anchors[next].at.coerceIn(i + 1, p.text.length) else p.text.length
            var j = i
            while (j < stop && p.marks[j] == m) j++
            atomOf(m.rPr)?.let { atom ->
                // A note, field or picture: written once, as it was
                if (atomsWritten.add(m.rPr)) b.append(atom)
                lastSpace = false
                i = j; return@let
            } ?: run {
                val link = linkOf(m.rPr)
                link?.let { b.append(it) }
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
                if (link != null) b.append("</$t:a>")
                i = j
            }
        }
        anchorsUpTo(Int.MAX_VALUE)
        b.append(p.tail).append("</").append(elementOf(p.startTag)).append('>').append(p.pPr)
    }

    /** content.xml with every changed paragraph written anew, and the styles they use. */
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
        val defs = made.defs.toString() + listStyles(body.toString())
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
        /** Before a link's start tag and before the text style inside it. */
        const val LINK = '\u0001'
        /** Before an object's number and before its XML. */
        const val ATOM = '\u0002'
        private const val MAX_CONTENT = 24L * 1024 * 1024
        private const val MAX_SMALL = 8L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        private const val DC = "http://purl.org/dc/elements/1.1/"

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
            fun prefixOrNull(uri: String) = root.attrs.entries.firstOrNull { it.value == uri && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:")
            fun prefix(uri: String) = prefixOrNull(uri) ?: throw IOException("Unusual namespaces")
            val ns = Prefixes(prefix(OFFICE), prefix(TEXT), prefix(STYLE), prefix(FO), prefixOrNull(TABLE) ?: "table", prefixOrNull(DC))
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
            val comments = ArrayList<Comment>()
            val t = ns.text; val tb = ns.table
            // The paragraphs in reading order: in the text, sections, lists (each item a place of its own) and tables
            class Collector {
                var containers = 0
                var rows = 0
                var atoms = 0
                fun paragraph(e: Elem, list: ListPos?, container: Int, row: Int) {
                    slots += Slot(e.start, e.end, container, row, list)
                    paras += readParagraph(xml, ns, e, slots.size - 1, interned, comments) { ++atoms }
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
            return OdtEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), ns, slots, paras, styles, autoText, auto, body.start, comments)
        }

        private fun readParagraph(xml: String, ns: Prefixes, e: Elem, slot: Int, interned: HashMap<String, RunStyle>, comments: MutableList<Comment>, atom: () -> Int): EditPara {
            val raw = xml.substring(e.start, e.end)
            val startTag = xml.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
            val t = ns.text; val o = ns.office
            val text = StringBuilder()
            val marks = ArrayList<RunStyle>()
            val head = StringBuilder(); val tail = StringBuilder()
            val anchors = ArrayList<Anchor>()
            var editable = true
            // Spaces in the file's text are one however many (the first in a paragraph none): written ones (text:s) are kept
            var lastSpace = true
            fun style(link: String?, span: String) = interned.getOrPut(if (link == null) span else "$LINK$link$LINK$span") { RunStyle(if (link == null) span else "$LINK$link$LINK$span") }
            fun add(s: String, style: RunStyle) {
                for (c in s) {
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { if (lastSpace) continue; text.append(' '); marks += style; lastSpace = true }
                    else { text.append(c); marks += style; lastSpace = false }
                }
            }
            fun put(c: Char, n: Int, style: RunStyle) { repeat(n) { text.append(c); marks += style }; lastSpace = false }
            /** The text an object shows (a note's number, a field's value), or a mark where it has none. */
            fun shownText(c: Elem): String {
                if (c.local == "note") return XmlScan.children(xml, c).firstOrNull { it.local == "note-citation" }?.let { XmlScan.unescape(xml.substring(it.openEnd, it.closeStart)).trim() }?.ifEmpty { null } ?: "\uFFFC"
                if (c.local == "frame" || c.empty) return "\uFFFC"
                return XmlScan.unescape(xml.substring(c.openEnd, c.closeStart).replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim().ifEmpty { "\uFFFC" }.take(200)
            }
            fun walk(from: Int, to: Int, link: String?, span: String, depth: Int) {
                var pos = from
                val st = style(link, span)
                for (c in XmlScan.children(xml, from, to)) {
                    add(XmlScan.unescape(xml.substring(pos, c.start)), st)
                    pos = c.end
                    when {
                        c.name == "$t:span" -> {
                            // A span within a span: its look is the two together, not kept apart here
                            if (span.isNotEmpty() || depth > 2) { editable = false; continue }
                            if (!c.empty) walk(c.openEnd, c.closeStart, link, c.attrs["$t:style-name"] ?: "", depth + 1)
                        }
                        c.name == "$t:a" -> {
                            if (link != null || depth > 2) { editable = false; continue }
                            val tag = xml.substring(c.start, c.openEnd)
                            if (!c.empty) walk(c.openEnd, c.closeStart, if (c.selfClosing) null else tag, span, depth + 1)
                        }
                        c.name == "$t:s" -> put(' ', (c.attrs["$t:c"]?.toIntOrNull() ?: 1).coerceIn(1, 1000), st)
                        c.name == "$t:tab" -> put('\t', 1, st)
                        c.name == "$t:line-break" -> put(LINE, 1, st)
                        c.name == "$t:soft-page-break" -> {}
                        c.name == "$t:bookmark" || c.name == "$t:bookmark-start" -> if (depth > 0) editable = false else head.append(xml, c.start, c.end)
                        c.name == "$t:bookmark-end" -> if (depth > 0) editable = false else tail.append(xml, c.start, c.end)
                        // Comments: where they begin and end (the comment's words go with its beginning)
                        c.name == "$o:annotation" -> {
                            if (depth > 0) { editable = false; continue }
                            val id = c.attrs["$o:name"] ?: "#${slot}-${anchors.size}"
                            val kids = XmlScan.children(xml, c)
                            fun one(local: String) = kids.firstOrNull { it.local == local && !it.empty }?.let { XmlScan.unescape(xml.substring(it.openEnd, it.closeStart)) } ?: ""
                            val words = kids.filter { it.local == "p" }.joinToString("\n") { p -> if (p.empty) "" else XmlScan.unescape(xml.substring(p.openEnd, p.closeStart).replace(Regex("<[^>]*>"), "")) }
                            comments += Comment(id, one("creator"), one("date"), words.trim())
                            anchors += Anchor(text.length, xml.substring(c.start, c.end), id, Anchor.Kind.START)
                            // A comment on a point: it ends where it begins
                            if (c.attrs["$o:name"] == null) anchors += Anchor(text.length, "", id, Anchor.Kind.END)
                        }
                        c.name == "$o:annotation-end" -> {
                            if (depth > 0) { editable = false; continue }
                            anchors += Anchor(text.length, xml.substring(c.start, c.end), c.attrs["$o:name"] ?: "", Anchor.Kind.END)
                        }
                        // Notes, fields, pictures: each kept whole, shown by its text, written as it was
                        c.local == "note" || c.name.startsWith("$t:") && c.local !in BLOCKERS || c.local == "frame" -> {
                            val shown = shownText(c)
                            val m = RunStyle("$ATOM${atom()}$ATOM" + xml.substring(c.start, c.end))
                            repeat(shown.length) { marks += m }
                            text.append(shown); lastSpace = false
                        }
                        // Tracked changes and what is not known: the paragraph stays as it is (its text still shown)
                        else -> {
                            editable = false
                            if (!c.empty && c.local !in setOf("annotation", "changed-region")) walk(c.openEnd, c.closeStart, link, span, depth + 1)
                        }
                    }
                }
                add(XmlScan.unescape(xml.substring(pos, to)), st)
            }
            val plain = interned.getOrPut("") { RunStyle("") }
            if (!e.empty) walk(e.openEnd, e.closeStart, null, "", 0)
            return EditPara(slot.toLong() + 1, slot, text.toString(), marks, "", startTag, head.toString(), tail.toString(), editable, raw,
                marks.lastOrNull()?.takeIf { !it.rPr.startsWith(ATOM) } ?: plain, anchors)
        }

        /** Elements of the text namespace that are changes or structure, never kept as an object. */
        private val BLOCKERS = setOf("change", "change-start", "change-end", "ruby", "list", "p", "h", "meta", "meta-field", "ruby-base", "ruby-text", "span", "a", "s", "tab", "line-break")

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
