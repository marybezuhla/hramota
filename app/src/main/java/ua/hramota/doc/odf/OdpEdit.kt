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
 * frames and speaker notes (bold, italic, underline, strike-through; paragraphs split and joined),
 * slides moved, taken out and added (laid out as the one before), speaker notes added. Only the paragraphs changed are written anew; everything else stays as
 * the file had it. In a run's [RunStyle.rPr] is its text style's name ("" for none).
 */
class OdpEdit private constructor(
    xml: String,
    private val digest: ByteArray,
    private val ns: OdtEdit.Prefixes,
    private val draw: String,
    /** The presentation and SVG namespaces' prefixes as the file declares them (null: not declared, notes are not added). */
    private val pres: String?,
    private val svg: String?,
    private val xlink: String?,
    filePages: List<Elem>,
    private val styles: OdfPackage.Styles,
    private val autoText: Map<String, OdtEdit.AutoStyle>,
    private val autoStyles: Elem?,
    private val bodyStart: Int,
) : SlideEdit {
    /**
     * Where a paragraph is: its place in its text ([src]: the file's content, or a slide or notes made
     * here), its page, its text box, and the list item it is in (its element's name), if any.
     */
    private class Slot(val start: Int, val end: Int, val page: Int, val item: String?, val container: Int, val level: Int = 0, val src: Int = 0, val box: Int = -1)

    /** A slide: where it is written ([src], [elem]), and what was put into it here ([splices]). */
    private class Page(val src: Int, val elem: Elem, val splices: MutableList<Splice> = ArrayList())

    /**
     * In a page's text, [start] to [end] written instead as text [src]: speaker notes added, or a
     * shape added ([shape]: its id; shapes go before notes at one place, [order]).
     */
    private class Splice(val start: Int, val end: Int, val src: Int, val shape: String? = null, val order: Int = 1)

    /** A shape added here: a text box (its paragraphs decide whether it stays) or a picture (with its file). */
    private class Added(val id: String, val page: Int, val src: Int, val picture: Pic?)
    private class Pic(val bytes: ByteArray, val path: String, val type: String)
    private val added = ArrayList<Added>()

    /** The texts slides are written from: the file's content first, then slides and notes made here. */
    private val texts = arrayListOf(xml)
    private val xml: String get() = texts[0]
    private val pages = ArrayList<Page>()
    private val fileStart = filePages.first().start
    private val fileEnd = filePages.last().end
    private val slots = ArrayList<Slot>()
    private val slotOf = HashMap<Long, Int>()
    private val initial = ArrayList<SlidePara>()
    private val boxList = ArrayList<TextBox>()
    private val interned = HashMap<String, RunStyle>()
    private var containers = 0
    private var nextKey = 1L

    init {
        for (e in filePages) { pages += Page(0, e); initial += collect(0, pages.size - 1, e) }
    }

    override val paragraphs: List<SlidePara> get() = initial
    override val boxes: List<TextBox> get() = boxList
    override val slideCount get() = pages.size
    override val extension = "odp"
    override val canPlace get() = svg != null
    override val canNotes get() = pres != null && svg != null

    // --- Reading text boxes (the file's, and those made here) ---

    private fun paragraphsIn(src: Int, page: Int, e: Elem, box: Int, item: String?, container: Int, depth: Int, level: Int, out: MutableList<SlidePara>) {
        if (depth > 30) return
        val x = texts[src]; val t = ns.text
        for (c in XmlScan.children(x, e)) when (c.name) {
            "$t:p", "$t:h" -> {
                slots += Slot(c.start, c.end, page, item, container, level.coerceAtLeast(0), src, box)
                val p = readParagraph(x, ns, c, nextKey++, box, interned)
                slotOf[p.key] = slots.size - 1
                out += p
            }
            "$t:list" -> for (it in XmlScan.children(x, c)) if (it.name == "$t:list-item" || it.name == "$t:list-header") paragraphsIn(src, page, it, box, it.name, ++containers, depth + 1, level + 1, out)
        }
    }

    private fun shape(src: Int, page: Int, c: Elem, depth: Int, notes: Boolean, out: MutableList<SlidePara>, top: String? = null, kindOf: String? = null) {
        if (depth > 20) return
        val x = texts[src]; val t = ns.text; val p = pres ?: "presentation"
        when (c.name) {
            "$draw:frame" -> {
                val tb = XmlScan.children(x, c).firstOrNull { it.name == "$draw:text-box" } ?: return
                val kind = kindOf ?: when (c.attrs["$p:class"]) { "title" -> "title"; "subtitle" -> "subtitle"; "notes" -> "notes"; else -> if (notes) "notes" else "body" }
                boxList += TextBox(page, kind, 0, 0, 0, shape = top)
                paragraphsIn(src, page, tb, boxList.size - 1, null, ++containers, depth + 1, -1, out)
            }
            "$draw:custom-shape", "$draw:rect", "$draw:ellipse" -> if (XmlScan.children(x, c).any { it.name == "$t:p" || it.name == "$t:list" }) {
                boxList += TextBox(page, "body", 0, 0, 0, shape = top)
                paragraphsIn(src, page, c, boxList.size - 1, null, ++containers, depth + 1, -1, out)
            }
            "$draw:g" -> for (k in XmlScan.children(x, c)) shape(src, page, k, depth + 1, notes, out, top)
            "$p:notes" -> for (k in XmlScan.children(x, c)) shape(src, page, k, depth + 1, true, out)
        }
    }

    /** A page's own shapes, numbered as the reader numbers them (their ids on the slide). */
    private fun shapesOf(x: String, page: Elem): List<Elem> =
        if (page.empty) emptyList() else XmlScan.children(x, page).filter { it.name.startsWith("$draw:") && it.local in OdpReader.SHAPES }

    /** The paragraphs of a slide's (or its notes') shapes; a text box with no paragraph is left out (nothing to edit). */
    private fun collect(src: Int, page: Int, e: Elem): List<SlidePara> {
        val out = ArrayList<SlidePara>()
        val own = shapesOf(texts[src], e).withIndex().associate { (i, el) -> el.start to i.toString() }
        for (c in XmlScan.children(texts[src], e)) shape(src, page, c, 0, false, out, own[c.start])
        return out
    }

    // --- Shapes placed, added, taken out ---

    private var addedCount = 0
    /** Where a shape is added on [pg]: after the page's last shape (before its animations and notes). */
    private fun addPlace(pg: Page): Int {
        val x = texts[pg.src]
        if (pg.elem.selfClosing) throw IOException("Empty slide")
        val kids = XmlScan.children(x, pg.elem)
        return shapesOf(x, pg.elem).lastOrNull()?.end ?: kids.firstOrNull { it.local == "forms" }?.end ?: pg.elem.openEnd
    }

    private fun cm(emu: Long) = String.format(java.util.Locale.ROOT, "%.3fcm", emu / 360000.0)
    private fun place(rect: LongArray) = "$svg:width=\"${cm(rect[2])}\" $svg:height=\"${cm(rect[3])}\" $svg:x=\"${cm(rect[0])}\" $svg:y=\"${cm(rect[1])}\""

    /** A shape's start tag at a new place and size. */
    private fun placedTag(tag: String, rect: LongArray): String {
        val s = svg ?: return tag
        val bare = tag.replace(Regex("\\s${Regex.escape(s)}:(?:x|y|width|height)=(?:\"[^\"]*\"|'[^']*')"), "")
        val end = if (bare.endsWith("/>")) bare.length - 2 else bare.length - 1
        return bare.substring(0, end).trimEnd() + " " + place(rect) + bare.substring(end)
    }

    /** The graphic style of shapes added here: no line, no fill, text from the top (made once, when written). */
    private val graphicStyle by lazy { var n = 1; while ("HrGr$n" in styles.named) n++; "HrGr$n" }

    private fun addShape(slide: Int, snippet: String, picture: Pic?): Pair<String, Int> {
        val pg = pages.getOrNull(slide) ?: throw IOException("No such slide")
        val at = addPlace(pg)
        val id = "a${++addedCount}"
        val src = texts.size
        texts += snippet
        pg.splices += Splice(at, at, src, id, 0)
        added += Added(id, slide, src, picture)
        return id to src
    }

    @Synchronized
    override fun addTextBox(slide: Int, rect: LongArray, lang: String): Pair<PptxEdit.ShapeRef, List<SlidePara>> {
        if (svg == null) throw IOException("No SVG namespace")
        val t = ns.text
        val (id, src) = addShape(slide, "<$draw:frame $draw:style-name=\"$graphicStyle\" $draw:layer=\"layout\" ${place(rect)}><$draw:text-box><$t:p></$t:p></$draw:text-box></$draw:frame>", null)
        val root = XmlScan.children(texts[src], 0, texts[src].length).first()
        val out = ArrayList<SlidePara>()
        shape(src, slide, root, 0, false, out, id, "text")
        return PptxEdit.ShapeRef(slide, id) to out
    }

    /** A picture ([bytes]: a PNG or JPEG file) at [rect] (EMU) on [slide]. */
    @Synchronized
    override fun addPicture(slide: Int, bytes: ByteArray, png: Boolean, rect: LongArray): PptxEdit.ShapeRef {
        val l = xlink ?: throw IOException("No link namespace")
        if (svg == null) throw IOException("No SVG namespace")
        val path = "Pictures/hramota-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) + if (png) ".png" else ".jpg"
        val (id, _) = addShape(slide, "<$draw:frame $draw:style-name=\"$graphicStyle\" $draw:layer=\"layout\" ${place(rect)}><$draw:image $l:href=\"$path\" $l:type=\"simple\" $l:show=\"embed\" $l:actuate=\"onLoad\"/></$draw:frame>",
            Pic(bytes, path, if (png) "image/png" else "image/jpeg"))
        return PptxEdit.ShapeRef(slide, id)
    }

    /** Whether an animation names the shape (it is not taken out: the animation would point at nothing). */
    override fun isAnimated(ref: PptxEdit.ShapeRef): Boolean {
        val pg = pages.getOrNull(ref.slide) ?: return false
        val x = texts[pg.src]
        val e = ref.id.toIntOrNull()?.let { shapesOf(x, pg.elem).getOrNull(it) } ?: return false
        val ids = e.attrs.entries.filter { it.key == "xml:id" || it.key == "$draw:id" }.map { it.value }
        val page = x.substring(pg.elem.start, pg.elem.end)
        return ids.any { id -> Regex("""targetElement\s*=\s*["']${Regex.escape(id)}["']""").containsMatchIn(page) }
    }

    /** The ids of the shapes written on each slide, in order (the last time the file was written), to tell them when read back. */
    @Volatile private var lastIds: List<List<String>> = emptyList()


    // --- Slides and notes added ---

    private val ids by lazy { Regex("\\s(?:xml:id|${Regex.escape(draw)}:id|${Regex.escape(ns.text)}:id)=(?:\"[^\"]*\"|'[^']*')") }
    private fun withoutIds(tag: String) = tag.replace(ids, "")
    /** An element's start tag, as one that has content. */
    private fun openTag(x: String, e: Elem) = withoutIds(x.substring(e.start, e.openEnd).let { if (e.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it })

    /** An empty paragraph where [box]'s text begins, in the lists it begins in (so it looks as the first one did). */
    private fun emptyFirst(x: String, box: Elem, depth: Int = 0): String {
        val t = ns.text
        val c = if (box.empty || depth > 10) null else XmlScan.children(x, box).firstOrNull { it.name == "$t:p" || it.name == "$t:h" || it.name == "$t:list" || it.name == "$t:list-item" }
        return when (c?.name) {
            null -> "<$t:p></$t:p>"
            "$t:list", "$t:list-item" -> openTag(x, c) + emptyFirst(x, c, depth + 1) + "</${c.name}>"
            else -> openTag(x, c) + "</${c.name}>"
        }
    }

    /**
     * A new slide after [after], laid out as it is (its master, layout, title and text placeholders,
     * empty; its pictures and other shapes are not copied). Returns its number and its paragraphs.
     */
    @Synchronized
    override fun addSlide(after: Int?, lang: String): Pair<Int, List<SlidePara>> {
        val base = pages.getOrNull(after ?: 0) ?: throw IOException("No such slide")
        val x = texts[base.src]; val e = base.elem
        val p = pres ?: throw IOException("No presentation namespace")
        if (e.selfClosing) throw IOException("Empty slide")
        // A name of its own (the slides' names are told apart by LibreOffice)
        val names = pages.mapNotNull { it.elem.attrs["$draw:name"] }.toHashSet()
        var n = pages.size + 1
        while ("page$n" in names) n++
        val start = openTag(x, e).replace(Regex("\\s${Regex.escape(draw)}:name=(?:\"[^\"]*\"|'[^']*')"), "")
        val b = StringBuilder(start.dropLast(1)).append(" $draw:name=\"page$n\">")
        for (c in XmlScan.children(x, e)) when {
            c.name == "$draw:frame" && c.attrs["$p:class"] in PLACEHOLDERS -> {
                val tb = XmlScan.children(x, c).firstOrNull { it.name == "$draw:text-box" } ?: continue
                b.append(openTag(x, c).replace(Regex("\\s$p:placeholder=(?:\"[^\"]*\"|'[^']*')"), ""))
                    .append(openTag(x, tb)).append(emptyFirst(x, tb)).append("</$draw:text-box></$draw:frame>")
            }
            c.name == "$p:notes" && !c.selfClosing -> {
                b.append(openTag(x, c))
                for (k in XmlScan.children(x, c)) when {
                    k.name == "$draw:page-thumbnail" -> b.append(withoutIds(x.substring(k.start, k.end)))
                    k.name == "$draw:frame" && k.attrs["$p:class"] == "notes" -> b.append(openTag(x, k)).append("<$draw:text-box/></$draw:frame>")
                }
                b.append("</${c.name}>")
            }
        }
        b.append("</${e.name}>")
        val src = texts.size
        texts += b.toString()
        val elem = XmlScan.children(texts[src], 0, texts[src].length).firstOrNull() ?: throw IOException("Slide not made")
        pages += Page(src, elem)
        return (pages.size - 1) to collect(src, pages.size - 1, elem)
    }

    /** Notes added here, by slide: their text and paragraph (to add them again after an undo). */
    private val notesMade = HashMap<Int, Pair<Int, Int>>()

    /** Speaker notes for a slide without them: one empty paragraph, in the notes' frame (made when there is none). */
    @Synchronized
    override fun addNotes(slide: Int, lang: String): List<SlidePara>? {
        val p = pres ?: return null; val sv = svg ?: return null
        val pg = pages.getOrNull(slide) ?: return null
        val t = ns.text
        notesMade[slide]?.let { (src, slot) ->
            // Added before and undone: the same paragraph again
            val s = slots[slot]; val x = texts[src]
            val e = XmlScan.children(x, 0, x.length).let { all -> fun find(l: List<Elem>): Elem? { for (c in l) { if (c.start == s.start) return c; if (!c.empty) find(XmlScan.children(x, c))?.let { return it } }; return null }; find(all) } ?: return null
            val para = readParagraph(x, ns, e, nextKey++, s.box, interned)
            slotOf[para.key] = slot
            return listOf(para)
        }
        if (slots.any { it.page == slide && it.box >= 0 && boxList[it.box].kind == "notes" }) return null
        if (pg.elem.selfClosing) return null
        val x = texts[pg.src]
        val frame = "<$draw:frame $p:class=\"notes\" $sv:width=\"16.799cm\" $sv:height=\"13.364cm\" $sv:x=\"2.1cm\" $sv:y=\"14.107cm\"><$draw:text-box><$t:p></$t:p></$draw:text-box></$draw:frame>"
        val notes = XmlScan.children(x, pg.elem).firstOrNull { it.name == "$p:notes" }
        val box = notes?.takeIf { !it.selfClosing }?.let { XmlScan.children(x, it).firstOrNull { f -> f.name == "$draw:frame" && f.attrs["$p:class"] == "notes" } }
            ?.let { XmlScan.children(x, it).firstOrNull { c -> c.name == "$draw:text-box" } }
        val (snippet, splice) = when {
            // The notes' frame is there, empty: a paragraph put in it
            box != null -> (openTag(x, box) + "<$t:p></$t:p></$draw:text-box>") to (box.start to box.end)
            notes == null -> "<$p:notes>$frame</$p:notes>" to (pg.elem.closeStart to pg.elem.closeStart)
            notes.selfClosing -> (openTag(x, notes) + frame + "</${notes.name}>") to (notes.start to notes.end)
            else -> frame to (notes.closeStart to notes.closeStart)
        }
        val src = texts.size
        texts += snippet
        pg.splices += Splice(splice.first, splice.second, src)
        val root = XmlScan.children(snippet, 0, snippet.length).firstOrNull() ?: return null
        val out = ArrayList<SlidePara>()
        if (root.name == "$draw:text-box") {
            boxList += TextBox(slide, "notes", 0, 0, 0)
            paragraphsIn(src, slide, root, boxList.size - 1, null, ++containers, 0, -1, out)
        } else shape(src, slide, root, 0, true, out)
        out.firstOrNull()?.let { notesMade[slide] = src to slotOf.getValue(it.key) }
        return out
    }

    override fun canRemove(slide: Int, order: Collection<Int>) = order.size > 1
    override fun canComment(slide: Int) = false
    override fun setComments(slide: Int, list: List<String>) {}

    // --- How text looks ---

    private val looks = HashMap<String, Map<String, String>>()
    private fun props(m: RunStyle) = looks.getOrPut(m.rPr) { if (m.rPr.isEmpty()) emptyMap() else styles.props(m.rPr, "text") }
    override fun bold(m: RunStyle) = m.bold ?: props(m)["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: false
    override fun italic(m: RunStyle) = m.italic ?: props(m)["font-style"]?.let { it == "italic" || it == "oblique" } ?: false
    override fun underline(m: RunStyle) = m.underline ?: props(m)["text-underline-style"]?.let { it != "none" } ?: false
    override fun strike(m: RunStyle) = m.strike ?: props(m)["text-line-through-style"]?.let { it != "none" } ?: false

    // --- Changes ---

    private fun slot(p: SlidePara) = slotOf[p.key] ?: throw IOException("Unknown paragraph")

    /** How deep in lists the paragraph is (0: the first level, or not in a list). */
    override fun level(p: SlidePara): Int = slotOf[p.key]?.let { slots[it].level } ?: 0

    override fun canJoin(a: SlidePara, b: SlidePara): Boolean {
        if (!a.editable || !b.editable || a.box != b.box) return false
        val sa = slots[slot(a)]; val sb = slots[slot(b)]
        if (sa === sb) return true
        // Side by side in one place (one list item, or the frame itself), nothing between them
        return slot(b) == slot(a) + 1 && sa.src == sb.src && sa.container == sb.container && b.startTag.firstOrNull() == '<' && !b.startTag.startsWith("</") && (sa.end until sb.start).all { texts[sa.src][it].isWhitespace() }
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
    @Synchronized
    fun contentXml(list: List<SlidePara>, order: List<Int>, shapes: PptxEdit.Shapes = PptxEdit.Shapes()): String {
        val made = Made()
        val bySlot = list.groupBy { slot(it) }
        val boxesUsed = list.mapTo(HashSet()) { it.box }
        fun kept(a: Added) = PptxEdit.ShapeRef(a.page, a.id) !in shapes.removed &&
            if (a.picture != null) PptxEdit.ShapeRef(a.page, a.id) in shapes.pictures else boxList.withIndex().any { (i, b) -> b.slide == a.page && b.shape == a.id && i in boxesUsed }
        val keptAdded = added.filter { kept(it) }.mapTo(HashSet()) { it.id }
        val bySlide = HashMap<Int, MutableList<Int>>()
        for ((k, s) in slots.withIndex()) bySlide.getOrPut(s.page) { ArrayList() } += k
        // [src] from [from] to [to], its paragraphs (on page [i]) written as they now are, what was put in written in its place
        fun rewrite(src: Int, from: Int, to: Int, i: Int, b: StringBuilder) {
            val x = texts[src]
            val events = ArrayList<Triple<Int, Int, () -> Unit>>()
            for (k in bySlide[i].orEmpty()) { val s = slots[k]; if (s.src == src && s.start >= from && s.end <= to) events += Triple(s.start, s.end) { bySlot[k]?.forEach { write(it, b, made) } } }
            val pg = pages[i]
            if (pg.src == src) {
                for (sp in pg.splices) if (sp.start >= from && sp.end <= to) {
                    if (sp.shape != null && sp.shape !in keptAdded) continue
                    events += Triple(sp.start, sp.end) { rewrite(sp.src, 0, texts[sp.src].length, i, b) }
                }
                // The page's own shapes moved or taken out
                if (from <= pg.elem.start && to >= pg.elem.end) for ((n, e) in shapesOf(x, pg.elem).withIndex()) {
                    val ref = PptxEdit.ShapeRef(i, n.toString())
                    when {
                        ref in shapes.removed -> events += Triple(e.start, e.end) {}
                        ref in shapes.placed -> events += Triple(e.start, e.openEnd) { b.append(placedTag(x.substring(e.start, e.openEnd), shapes.placed.getValue(ref))) }
                    }
                }
            } else added.firstOrNull { it.src == src }?.let { a ->
                // A shape added here, moved
                shapes.placed[PptxEdit.ShapeRef(a.page, a.id)]?.let { r -> val e = XmlScan.children(x, 0, x.length).first(); events += Triple(e.start, e.openEnd) { b.append(placedTag(x.substring(e.start, e.openEnd), r)) } }
            }
            // (shapes before notes where both are put at one place; a shape taken out takes its text with it)
            events.sortWith(compareBy<Triple<Int, Int, () -> Unit>>({ it.first }, { if (it.second == it.first) 0 else 1 }))
            var pos = from
            for ((start, end, w) in events) { if (start < pos) continue; b.append(x, pos, start); w(); pos = maxOf(pos, end) }
            b.append(x, pos, to)
        }
        val body = StringBuilder(xml.length - bodyStart + 4096)
        body.append(xml, bodyStart, fileStart)
        for (i in order) { val pg = pages.getOrNull(i) ?: throw IOException("No such slide"); rewrite(pg.src, pg.elem.start, pg.elem.end, i, body) }
        body.append(xml, fileEnd, xml.length)
        // The shapes as written on each slide (the reader numbers them so)
        lastIds = order.map { i ->
            val pg = pages[i]
            shapesOf(texts[pg.src], pg.elem).indices.map { it.toString() }.filter { PptxEdit.ShapeRef(i, it) !in shapes.removed } +
                added.filter { it.page == i && it.id in keptAdded }.map { it.id }
        }
        if (keptAdded.isNotEmpty()) made.defs.append("<${ns.style}:style ${ns.style}:name=\"$graphicStyle\" ${ns.style}:family=\"graphic\"><${ns.style}:graphic-properties $draw:stroke=\"none\" $draw:fill=\"none\" $draw:auto-grow-height=\"true\" $draw:textarea-vertical-align=\"top\"/></${ns.style}:style>")
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
        val content = contentXml(list, order, shapes).toByteArray(Charsets.UTF_8)
        // Pictures added that stay: their files, and the package's list of them
        val pics = synchronized(this) { added.filter { it.picture != null && PptxEdit.ShapeRef(it.page, it.id) in shapes.pictures && PptxEdit.ShapeRef(it.page, it.id) !in shapes.removed }.map { it.picture!! } }
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
                    "META-INF/manifest.xml" -> {
                        var m = String(readAll(zip, MAX_SMALL), Charsets.UTF_8)
                        if (pics.isNotEmpty()) {
                            val root = XmlScan.children(m, 0, m.length).firstOrNull { it.local == "manifest" } ?: throw IOException("Unusual manifest")
                            val mp = root.name.substringBefore(':', "").let { if (it.isEmpty() || it == root.name) "" else "$it:" }
                            val entries = pics.joinToString("") { "<${mp}file-entry ${mp}full-path=\"${it.path}\" ${mp}media-type=\"${it.type}\"/>" }
                            m = m.substring(0, root.closeStart) + entries + m.substring(root.closeStart)
                        }
                        z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                        z.write(m.toByteArray(Charsets.UTF_8))
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
            for (p in pics) {
                if (!written.add(p.path)) continue
                z.putNextEntry(ZipEntry(p.path)); z.write(p.bytes); z.closeEntry()
            }
            z.finish(); z.flush()
        }
    }

    /** A presentation read back; just after it was written here, its shapes carry the ids they have here. */
    override fun readBack(input: InputStream): Deck {
        val deck = OdpReader.read(input)
        val ids = lastIds
        if (ids.size != deck.slides.size) return deck
        return Deck(deck.width, deck.height, deck.slides.mapIndexed { j, sl ->
            val names = ids[j]
            ua.hramota.doc.slides.Slide(sl.background, sl.shapes, sl.frames.mapNotNull { f -> f.id.toIntOrNull()?.let { names.getOrNull(it) }?.let { f.copy(id = it) } })
        }, deck.media, deck.inspection)
    }

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
        private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
        private const val XLINK = "http://www.w3.org/1999/xlink"
        /** Frames a new slide keeps (empty): the layout's title and text. */
        private val PLACEHOLDERS = setOf("title", "subtitle", "outline", "text")

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
            val draw = prefix(DRAW)
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

            return OdpEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), ns, draw, prefixOrNull(PRESENTATION), prefixOrNull(SVG), prefixOrNull(XLINK), pages, styles, autoText, auto, body.start)
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
