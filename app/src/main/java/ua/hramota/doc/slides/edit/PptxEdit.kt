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
              val fixed: Boolean = false,
              /** The id of the shape placed on the slide that holds it (a group's for a shape inside one). */
              val shape: String? = null)

/**
 * A PPTX opened for editing its text. Only the text boxes whose paragraphs changed are written anew
 * (their paragraphs, nothing else of the shape); every other part, slide and shape stays as it was.
 */
class PptxEdit private constructor(
    private val parts: MutableList<Pair<String, String>>,
    private val digests: Map<String, ByteArray>,
    /** The prefix of DrawingML's namespace in each slide part ("a" almost always). */
    private val prefixes: MutableList<String>,
    private val boxList: ArrayList<TextBox>,
    val paragraphs: List<SlidePara>,
    /** How many slides are shown (hidden ones are left out, as in the viewer). */
    val slideCount: Int,
    /** What adding, taking out and moving slides needs: the package's lists and links as opened. */
    private val pack: Pack,
    /** The slide each part belongs to (a slide's own part, or its speaker notes). */
    private val partSlide: MutableList<Int>,
    /** Parts that are speaker notes. */
    private val notesParts: MutableSet<Int>,
    /** The notes master (new speaker notes follow it); null when the presentation has none. */
    private val notesMaster: String?,
) {
    /** Every text box, of the slides as opened and of those added since. */
    val boxes: List<TextBox> get() = boxList
    private var nextKey = paragraphs.size.toLong() + 1

    /** A slide added while editing: its part (in [parts]) and the layout it follows. */
    private class Added(val slide: Int, val part: Int, val path: String, val layout: String)
    private val added = ArrayList<Added>()

    /** Paragraphs of the slides added while editing, by slide. */
    private val addedParagraphs = HashMap<Int, List<SlidePara>>()

    /**
     * Adds an empty slide (a title and a text box to fill in, as the presentation's own "Title and
     * Content" layout places them) to be put [after] that slide. Returns its number among the slides
     * (the slides as opened are 0 until [slideCount]) and its paragraphs. Nothing is written until saving.
     */
    @Synchronized
    fun addSlide(after: Int?, lang: String): Pair<Int, List<SlidePara>> {
        val layout = when {
            after != null && after >= slideCount -> added.firstOrNull { it.slide == after }?.layout
            else -> pack.layoutFor(after?.let { pack.shown[it].path })
        } ?: throw IOException("No layout")
        val id = slideCount + added.size
        val taken = pack.names + added.map { it.path }
        var n = 1
        while ("ppt/slides/slide$n.xml" in taken || !pack.isFree("ppt/slides/slide$n.xml")) n++
        val path = "ppt/slides/slide$n.xml"
        val xml = pack.newSlideXml(layout, lang)
        val pi = parts.size
        parts += path to xml
        prefixes += "a"
        partSlide += id
        val root = XmlScan.children(xml, 0, xml.length).first { it.local == "sld" }
        val paras = ArrayList<SlidePara>()
        readSlide(xml, root, "a", pi, id, boxList, paras, HashMap(), { nextKey++ })
        val typing = RunStyle("<a:rPr lang=\"${XmlScan.escape(lang)}\" dirty=\"0\"/>")
        val list = paras.map { SlidePara(it.key, it.startTag, it.box, it.text, it.marks, it.pPr, it.end, it.editable, it.original, typing) }
        added += Added(id, pi, path, layout)
        addedParagraphs[id] = list
        return id to list
    }

    /**
     * Whether a slide can be taken out: not while another slide links to it (a link to a slide that
     * is gone would make the file broken in PowerPoint).
     */
    fun canRemove(slide: Int, order: Collection<Int> = (0 until slideCount).toList()): Boolean {
        if (slide >= slideCount) return true
        val from = pack.linkSources(pack.shown[slide].path) ?: return false
        if (from.isEmpty()) return true
        // Links from slides also taken out (and their notes) do not count
        val gone = pack.shown.indices.filter { it !in order && it != slide }.flatMapTo(HashSet()) { pack.ownParts(pack.shown[it].path) }
        return from.all { it in gone }
    }

    // ---- Speaker notes ----

    /** Whether [slide] has speaker notes (its own, or ones added here). */
    fun hasNotes(slide: Int) = boxList.any { it.slide == slide && it.kind == "notes" }

    /** New speaker notes added to a slide: the part made here, written only if it keeps its paragraph. */
    private class NewNotes(val slide: Int, val part: Int, val path: String)
    private val newNotes = ArrayList<NewNotes>()

    /** Empty speaker notes for [slide] (one paragraph to type into), or null when the presentation has no notes master. */
    @Synchronized
    fun addNotes(slide: Int, lang: String): List<SlidePara>? {
        if (notesMaster == null || hasNotes(slide)) return null
        val taken = pack.names + newNotes.map { it.path }
        var n = 1
        while ("ppt/notesSlides/notesSlide$n.xml" in taken || !pack.isFree("ppt/notesSlides/notesSlide$n.xml")) n++
        val path = "ppt/notesSlides/notesSlide$n.xml"
        val l = XmlScan.escape(lang)
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<p:notes xmlns:a=\"$DML\" xmlns:r=\"$REL\" xmlns:p=\"$PML\"><p:cSld><p:spTree>" + ua.hramota.doc.Templates.GROUP +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Slide Image Placeholder 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"sldImg\"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>" +
            ua.hramota.doc.Templates.placeholder(3, "Notes Placeholder 2", "<p:ph type=\"body\" idx=\"1\"/>", lang) +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"
        val pi = parts.size
        parts += path to xml
        prefixes += "a"
        partSlide += slide
        notesParts += pi
        newNotes += NewNotes(slide, pi, path)
        val root = XmlScan.children(xml, 0, xml.length).first { it.local == "notes" }
        val paras = ArrayList<SlidePara>()
        readNotes(xml, root, "a", pi, slide, boxList, paras, HashMap()) { nextKey++ }
        val typing = RunStyle("<a:rPr lang=\"$l\" dirty=\"0\"/>")
        return paras.map { SlidePara(it.key, it.startTag, it.box, it.text, it.marks, it.pPr, it.end, it.editable, it.original, typing) }
    }

    // ---- Shapes: moved, sized, taken out; text boxes and pictures added ----

    /** A shape placed on a slide: the slide's number (as [addSlide] gives it) and the shape's id there. */
    data class ShapeRef(val slide: Int, val id: String)

    /**
     * What happened to shapes, kept by the editor (one value per step to undo): new places and sizes in
     * EMU (x, y, width, height), shapes taken out, and the pictures added that stay.
     */
    data class Shapes(val placed: Map<ShapeRef, LongArray> = emptyMap(), val removed: Set<ShapeRef> = emptySet(), val pictures: Set<ShapeRef> = emptySet())

    /** A shape added here: a text box (its paragraphs decide whether it stays) or a picture (with its file). */
    private class AddedShape(val ref: ShapeRef, val part: Int, val start: Int, val end: Int, val picture: Picture?)
    private class Picture(val bytes: ByteArray, val ext: String, val rel: String)
    private val addedShapes = ArrayList<AddedShape>()

    private fun partOf(slide: Int): Int = if (slide < slideCount) slide else added.firstOrNull { it.slide == slide }?.part ?: throw IOException("No slide")

    private fun prefixOf(xml: String, root: Elem, ns: String): String? =
        root.attrs.entries.firstOrNull { it.value == ns && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:")

    /** A free shape id on the slide (ids are numbers, unique within the slide). */
    private fun freeId(xml: String): Int = (Regex("""<(?:[\w.-]+:)?cNvPr\s[^>]*\bid\s*=\s*["'](\d+)["']""").findAll(xml)
        .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1) + 1

    /** Puts [shape] last on the slide (in front of everything), keeping every text box before it where it was. */
    private fun append(pi: Int, shape: (p: String, a: String, r: String?, id: Int) -> String): Triple<Int, Int, Int> {
        val (path, xml) = parts[pi]
        val root = XmlScan.children(xml, 0, xml.length).first { it.local == "sld" }
        val tree = XmlScan.children(xml, root).firstOrNull { it.local == "cSld" }?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spTree" } }
            ?: throw IOException("No shape tree")
        val p = prefixOf(xml, root, PML) ?: throw IOException("No prefix")
        val a = prefixes[pi]
        val r = prefixOf(xml, root, REL)
        val id = freeId(xml)
        val text = shape(p, a, r, id)
        val at = tree.closeStart
        parts[pi] = path to (xml.substring(0, at) + text + xml.substring(at))
        return Triple(id, at, at + text.length)
    }

    private fun xfrm(a: String, rect: LongArray) =
        "<$a:xfrm><$a:off x=\"${rect[0]}\" y=\"${rect[1]}\"/><$a:ext cx=\"${rect[2]}\" cy=\"${rect[3]}\"/></$a:xfrm>"

    /** A text box at [rect] (EMU) on [slide], one empty paragraph to type into; its paragraphs and its reference. */
    @Synchronized
    fun addTextBox(slide: Int, rect: LongArray, lang: String): Pair<ShapeRef, List<SlidePara>> {
        val pi = partOf(slide)
        val l = XmlScan.escape(lang)
        val (id, start, end) = append(pi) { p, a, _, id ->
            "<$p:sp><$p:nvSpPr><$p:cNvPr id=\"$id\" name=\"TextBox $id\"/><$p:cNvSpPr txBox=\"1\"/><$p:nvPr/></$p:nvSpPr>" +
                "<$p:spPr>${xfrm(a, rect)}<$a:prstGeom prst=\"rect\"><$a:avLst/></$a:prstGeom><$a:noFill/></$p:spPr>" +
                "<$p:txBody><$a:bodyPr wrap=\"square\" rtlCol=\"0\"><$a:spAutoFit/></$a:bodyPr><$a:lstStyle/><$a:p><$a:endParaRPr lang=\"$l\" dirty=\"0\"/></$a:p></$p:txBody></$p:sp>"
        }
        val xml = parts[pi].second
        val sp = XmlScan.children(xml, start, end).first()
        val body = XmlScan.children(xml, sp).first { it.local == "txBody" }
        val para = XmlScan.children(xml, body).first { it.local == "p" }
        val bi = boxList.size
        boxList += TextBox(slide, "text", pi, para.start, para.end, shape = id.toString())
        val typing = RunStyle("<${prefixes[pi]}:rPr lang=\"$l\" dirty=\"0\"/>")
        val p0 = paragraph(xml, prefixes[pi], para, bi, nextKey++, HashMap(), false)
        val ref = ShapeRef(slide, id.toString())
        addedShapes += AddedShape(ref, pi, start, end, null)
        return ref to listOf(SlidePara(p0.key, p0.startTag, p0.box, p0.text, p0.marks, p0.pPr, p0.end, p0.editable, p0.original, typing))
    }

    /** A picture ([bytes]: a PNG or JPEG file) at [rect] (EMU) on [slide]. */
    @Synchronized
    fun addPicture(slide: Int, bytes: ByteArray, png: Boolean, rect: LongArray): ShapeRef {
        val pi = partOf(slide)
        val rel = "rIdHp${addedShapes.size + 1}"
        val (id, start, end) = append(pi) { p, a, r, id ->
            r ?: throw IOException("No relationship prefix")
            "<$p:pic><$p:nvPicPr><$p:cNvPr id=\"$id\" name=\"Picture $id\"/><$p:cNvPicPr><$a:picLocks noChangeAspect=\"1\"/></$p:cNvPicPr><$p:nvPr/></$p:nvPicPr>" +
                "<$p:blipFill><$a:blip $r:embed=\"$rel\"/><$a:stretch><$a:fillRect/></$a:stretch></$p:blipFill>" +
                "<$p:spPr>${xfrm(a, rect)}<$a:prstGeom prst=\"rect\"><$a:avLst/></$a:prstGeom></$p:spPr></$p:pic>"
        }
        val ref = ShapeRef(slide, id.toString())
        addedShapes += AddedShape(ref, pi, start, end, Picture(bytes, if (png) "png" else "jpeg", rel))
        return ref
    }

    /** Whether a shape is one added here (a picture: kept only while listed in [Shapes.pictures]). */
    fun isAddedPicture(ref: ShapeRef) = addedShapes.any { it.ref == ref && it.picture != null }

    /**
     * The slide part with shapes moved or taken out (on the text already written): [moved] in EMU,
     * [cut] by id. Shapes keep everything else.
     */
    private fun reshaped(xml: String, a: String, moved: Map<String, LongArray>, cut: Set<String>): String {
        if (moved.isEmpty() && cut.isEmpty()) return xml
        val root = XmlScan.children(xml, 0, xml.length).first { it.local == "sld" }
        val tree = XmlScan.children(xml, root).firstOrNull { it.local == "cSld" }?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spTree" } } ?: return xml
        val b = StringBuilder(xml.length)
        var pos = 0
        for (k in XmlScan.children(xml, tree)) {
            val nv = XmlScan.children(xml, k).firstOrNull { it.local.startsWith("nv") } ?: continue
            val id = XmlScan.children(xml, nv).firstOrNull { it.local == "cNvPr" }?.attrs?.get("id") ?: continue
            if (id in cut) { b.append(xml, pos, k.start); pos = k.end; continue }
            val rect = moved[id] ?: continue
            // Where the place is written: the shape's properties (or the frame of a table)
            val holder = when (k.local) {
                "sp", "pic", "cxnSp" -> XmlScan.children(xml, k).firstOrNull { it.local == "spPr" }
                "grpSp" -> XmlScan.children(xml, k).firstOrNull { it.local == "grpSpPr" }
                "graphicFrame" -> null
                else -> continue
            }
            if (k.local == "graphicFrame") {
                val x = XmlScan.children(xml, k).firstOrNull { it.local == "xfrm" } ?: continue
                b.append(xml, pos, x.start)
                b.append(placedXfrm(xml, x, rect, x.name.substringBefore(':', "").let { if (it.isEmpty()) a else it }, a))
                pos = x.end
                continue
            }
            holder ?: continue
            val old = XmlScan.children(xml, holder).firstOrNull { it.local == "xfrm" }
            when {
                old != null -> { b.append(xml, pos, old.start); b.append(placedXfrm(xml, old, rect, a, a)); pos = old.end }
                holder.empty -> {
                    // "<p:spPr/>": opened, with the place inside
                    b.append(xml, pos, holder.start)
                    b.append("<${holder.name}>").append(xfrm(a, rect)).append("</${holder.name}>")
                    pos = holder.end
                }
                else -> { b.append(xml, pos, holder.openEnd); b.append(xfrm(a, rect)); pos = holder.openEnd }
            }
        }
        b.append(xml, pos, xml.length)
        return b.toString()
    }

    /** An xfrm with a new place and size; its turn, flips and (in a group) the children's frame stay. */
    private fun placedXfrm(xml: String, old: Elem, rect: LongArray, own: String, a: String): String {
        val open = xml.substring(old.start, old.openEnd).removeSuffix("/>").removeSuffix(">")
        val kids = if (old.empty) emptyList() else XmlScan.children(xml, old).filter { it.local == "chOff" || it.local == "chExt" }.map { xml.substring(it.start, it.end) }
        return "$open><$a:off x=\"${rect[0]}\" y=\"${rect[1]}\"/><$a:ext cx=\"${rect[2]}\" cy=\"${rect[3]}\"/>" + kids.joinToString("") + "</${old.name}>"
    }

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
    fun save(list: List<SlidePara>, source: InputStream, out: OutputStream) = save(list, (0 until slideCount).toList(), source, out)

    /**
     * Writes the edited presentation with its slides in [order] (numbers as [addSlide] gives them; a slide
     * as opened that is not in it is taken out, with what only it used: its notes, comments, pictures).
     */
    @Synchronized
    fun save(list: List<SlidePara>, order: List<Int>, source: InputStream, out: OutputStream, shapes: Shapes = Shapes()) {
        if (order.isEmpty() || order.toSet().size != order.size || order.any { it !in 0 until slideCount + added.size }) throw IOException("Bad slide order")
        val changed = HashMap<String, String>()
        val byBox = list.groupBy { it.box }
        val counts = paragraphs.groupingBy { it.box }.eachCount() + addedParagraphs.values.flatten().groupingBy { it.box }.eachCount() +
            boxList.withIndex().filter { (_, b) -> newNotes.any { it.part == b.part } }.associate { it.index to 1 } +
            boxList.withIndex().filter { (_, b) -> addedShapes.any { it.part == b.part && b.from >= it.start && b.to <= it.end } }.associate { it.index to 1 }
        val boxesOf = boxes.withIndex().groupBy { it.value.part }
        val kept = order.toSet()
        val newParts = added.filter { it.slide in kept }.associateBy { it.part }
        // Shapes added here that stay: a text box while it has its paragraphs, a picture while it is listed
        val present = list.mapTo(HashSet()) { it.box }
        val stays = addedShapes.filter { a ->
            a.ref !in shapes.removed && if (a.picture != null) a.ref in shapes.pictures
            else boxList.withIndex().any { (bi, b) -> b.part == a.part && b.shape == a.ref.id && bi in present }
        }.toSet()
        // New speaker notes that keep their paragraph, on slides that stay
        val notesKept = newNotes.filter { nn -> nn.slide in kept && boxList.withIndex().any { (bi, b) -> b.part == nn.part && bi in present } }
        for ((pi, part) in parts.withIndex()) {
            val (path, xml) = part
            val mine = boxesOf[pi].orEmpty()
            val slide = partSlide[pi]
            // A slide taken out is not written (nor its notes); one added always is
            if (slide !in kept) continue
            val made = newNotes.firstOrNull { it.part == pi }
            if (made != null && notesKept.none { it === made }) continue
            val isSlide = pi !in notesParts
            // Shapes moved or taken out on this slide (and those added here that do not stay)
            val moved = if (isSlide) shapes.placed.filterKeys { it.slide == slide }.mapKeys { it.key.id } else emptyMap()
            val cut = if (isSlide) shapes.removed.filter { it.slide == slide }.mapTo(HashSet()) { it.id } +
                addedShapes.filter { it.part == pi && it !in stays }.map { it.ref.id } else emptySet()
            val reshape = moved.isNotEmpty() || cut.isNotEmpty() || addedShapes.any { it.part == pi }
            val fresh = pi in newParts || made != null
            val touched = if (fresh) mine else mine.filter { (bi, _) -> byBox[bi].orEmpty().any { it.original == null } || byBox[bi].orEmpty().size != counts[bi] }
            if (touched.isEmpty()) { if (fresh || reshape) changed[path] = reshaped(xml, prefixes[pi], moved, cut); continue }
            val b = StringBuilder(xml.length + 2048)
            var pos = 0
            for ((bi, box) in touched.sortedBy { it.value.from }) {
                b.append(xml, pos, box.from)
                val ps = byBox[bi].orEmpty()
                // A box left with no paragraph keeps one empty (a text body needs one); its shape is taken out anyway if it goes
                if (ps.isEmpty()) b.append("<${prefixes[pi]}:p/>") else for (p in ps) write(p, prefixes[pi], b)
                pos = box.to
            }
            b.append(xml, pos, xml.length)
            changed[path] = reshaped(b.toString(), prefixes[pi], moved, cut)
        }
        var drop = emptySet<String>()
        val created = HashSet<String>()
        if (order != (0 until slideCount).toList()) {
            val removed = (0 until slideCount).filter { it !in kept }
            for (r in removed) if (!canRemove(r, order)) throw IOException("A slide being taken out is linked to")
            val restructured = pack.restructure(order.map { id -> if (id < slideCount) Pack.Slide.Kept(id) else newParts.values.first { it.slide == id }.let { a -> Pack.Slide.New(a.path, a.layout) } })
            changed.putAll(restructured.parts)
            created.addAll(restructured.created)
            drop = restructured.drop
        }
        // Pictures added: their files, the slides' links to them, their kind in the list of part types
        val files = HashMap<String, ByteArray>()
        val pictures = stays.filter { it.picture != null && partSlide[it.part] in kept }
        if (pictures.isNotEmpty()) {
            var n = 1
            val links = pictures.map { a ->
                while (!pack.isFree("ppt/media/hramota$n.${a.picture!!.ext}") || "ppt/media/hramota$n.${a.picture.ext}" in files) n++
                val media = "ppt/media/hramota$n.${a.picture.ext}"
                files[media] = a.picture.bytes
                Triple(parts[a.part].first, a.picture.rel, media)
            }
            pack.linkPictures(links, changed, created)
        }
        // New speaker notes: their part, their links (to the notes master and the slide), the slide's link to them
        if (notesKept.isNotEmpty()) {
            val slidePath = { slide: Int -> parts[partOf(slide)].first }
            for (nn in notesKept) created += nn.path
            pack.linkNotes(notesKept.map { Triple(slidePath(it.slide), it.path, notesMaster!!) }, changed, created)
        }
        copy(source, out, changed, drop, created, files)
    }

    private fun copy(source: InputStream, out: OutputStream, changed: Map<String, String>, drop: Set<String> = emptySet(), created: Set<String> = emptySet(), files: Map<String, ByteArray> = emptyMap()) {
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
                // Parts only a slide taken out used are left out
                if (name.removePrefix("/") in drop) continue
                if (name.removePrefix("/") in created) throw IOException("$name already there")
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
            for (name in changed.keys) if (name !in created && name !in written && "/$name" !in written) throw IOException("$name missing")
            // The new parts (slides added, their links)
            for (name in created.sorted()) {
                z.putNextEntry(ZipEntry(name))
                z.write((changed[name] ?: throw IOException("$name empty")).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            for ((name, bytes) in files.toSortedMap()) {
                if (name in written || "/$name" in written || name in created) throw IOException("$name already there")
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            z.finish(); z.flush()
        }
    }

    companion object {
        const val DML = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
        private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        /** A line break within a paragraph (Shift+Enter). */
        const val LINE = '\u2028'
        private const val MAX_PART = 16L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000

        /** Opens a PPTX for editing its text. Call off the main thread. */
        fun open(input: InputStream): PptxEdit {
            val raw = HashMap<String, ByteArray>()
            val names = HashSet<String>()
            ZipInputStream(input).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    val name = e.name.removePrefix("/")
                    names += name
                    val lower = name.lowercase()
                    // The slides, and what adding or taking out slides changes or follows: the lists of parts and
                    // links (all of them: what only a slide taken out used is found through them) and the layouts
                    if (lower == "ppt/presentation.xml" || lower == "[content_types].xml" || lower.endsWith(".rels") ||
                        lower == "ppt/viewprops.xml" || lower.startsWith("ppt/slides/") && lower.endsWith(".xml") || lower.startsWith("ppt/slidelayouts/") && lower.endsWith(".xml") ||
                        lower.startsWith("ppt/notesslides/") && lower.endsWith(".xml")) {
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
            val shownSlides = ArrayList<Pack.Shown>()
            // Each entry of the slide list, and which shown slide it is (null: hidden, or not a slide read here)
            val entries = ArrayList<Pack.Entry>()
            var shown = 0
            for (id in ids) {
                val entry = pres.substring(id.start, id.end)
                fun other() { entries += Pack.Entry(entry, null) }
                if (id.local != "sldId" || shown >= 2000) { other(); continue }
                val rid = id.attrs.entries.firstOrNull { it.key.endsWith(":id") }?.value ?: run { other(); null } ?: continue
                val path = rels[rid] ?: run { other(); null } ?: continue
                if (!seen.add(path)) { other(); continue }
                val xml = text(path) ?: run { other(); null } ?: continue
                val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "sld" } ?: run { other(); null } ?: continue
                // Hidden slides are left out, as the viewer leaves them out
                if (root.attrs["show"].let { it == "0" || it == "false" }) { other(); continue }
                val a = root.attrs.entries.firstOrNull { it.value == DML && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: "a"
                val pi = parts.size
                parts += path to xml
                prefixes += a
                readSlide(xml, root, a, pi, shown, boxes, paras, interned) { paras.size.toLong() + 1 }
                entries += Pack.Entry(entry, shown)
                shownSlides += Pack.Shown(path, rid, id.attrs["id"]?.toLongOrNull() ?: 0L)
                shown++
            }
            if (parts.isEmpty()) throw IOException("No slides")
            // Speaker notes of the slides shown (after all the slides' own parts)
            val partSlide = ArrayList<Int>((0 until shown).toList())
            val notesParts = HashSet<Int>()
            for ((i, sh) in shownSlides.withIndex()) {
                val dir = sh.path.substringBeforeLast('/', "")
                val r = text("$dir/_rels/${sh.path.substringAfterLast('/')}.rels") ?: continue
                val rroot = XmlScan.children(r, 0, r.length).firstOrNull() ?: continue
                val target = XmlScan.children(r, rroot).firstOrNull { it.local == "Relationship" && it.attrs["Type"]?.endsWith("/notesSlide") == true && it.attrs["TargetMode"] != "External" }
                    ?.attrs?.get("Target") ?: continue
                val notesPath = if (target.startsWith("/")) target.removePrefix("/") else java.io.File(dir, target).normalize().path.replace('\\', '/')
                val nx = text(notesPath) ?: continue
                val nroot = XmlScan.children(nx, 0, nx.length).firstOrNull { it.local == "notes" } ?: continue
                val a = nroot.attrs.entries.firstOrNull { it.value == DML && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: "a"
                val pi = parts.size
                parts += notesPath to nx
                prefixes += a
                partSlide += i
                notesParts += pi
                readNotes(nx, nroot, a, pi, i, boxes, paras, interned) { paras.size.toLong() + 1 }
            }
            val notesMaster = text("ppt/_rels/presentation.xml.rels")?.let { r ->
                val rroot = XmlScan.children(r, 0, r.length).firstOrNull() ?: return@let null
                XmlScan.children(r, rroot).firstOrNull { it.local == "Relationship" && it.attrs["Type"]?.endsWith("/notesMaster") == true }?.attrs?.get("Target")
                    ?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else java.io.File("ppt", t).normalize().path.replace('\\', '/') }
            }?.takeIf { it in names }
            val digests = raw.mapValues { (_, b) -> MessageDigest.getInstance("SHA-256").digest(b) }
            val pack = Pack(
                pres = pres, entries = entries, shown = shownSlides, names = names,
                texts = raw.keys.filter { it.lowercase().endsWith(".rels") || it.lowercase() == "[content_types].xml" || it.lowercase() == "ppt/viewprops.xml" || it.lowercase().startsWith("ppt/slidelayouts/") && !it.lowercase().endsWith(".rels") }
                    .associateWith { text(it)!! },
            )
            return PptxEdit(parts, digests, prefixes, boxes, paras, shown, pack, partSlide, notesParts, notesMaster)
        }

        /** Reads the text of speaker notes (their body placeholder only: not the slide picture or the number). */
        internal fun readNotes(xml: String, root: Elem, a: String, pi: Int, slide: Int, boxes: MutableList<TextBox>, paras: MutableList<SlidePara>, interned: HashMap<String, RunStyle>, key: () -> Long) {
            val tree = XmlScan.children(xml, root).firstOrNull { it.local == "cSld" }?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spTree" } } ?: return
            for (sp in XmlScan.children(xml, tree)) {
                if (sp.local != "sp") continue
                val nv = XmlScan.children(xml, sp).firstOrNull { it.local == "nvSpPr" } ?: continue
                val ph = XmlScan.children(xml, nv).firstOrNull { it.local == "nvPr" }?.let { n -> XmlScan.children(xml, n).firstOrNull { it.local == "ph" } } ?: continue
                if (ph.attrs["type"] != "body") continue
                val body = XmlScan.children(xml, sp).firstOrNull { it.local == "txBody" } ?: continue
                val ps = XmlScan.children(xml, body).filter { it.local == "p" }
                if (ps.isEmpty()) continue
                val bi = boxes.size
                boxes += TextBox(slide, "notes", pi, ps.first().start, ps.last().end, shape = XmlScan.children(xml, nv).firstOrNull { it.local == "cNvPr" }?.attrs?.get("id"))
                for (p in ps) paras += paragraph(xml, a, p, bi, key(), interned, false)
                return
            }
        }

        /** Reads one slide's text boxes and paragraphs (the slide numbered [slide] among those shown, part [pi]). */
        internal fun readSlide(xml: String, root: Elem, a: String, pi: Int, slide: Int, boxes: MutableList<TextBox>, paras: MutableList<SlidePara>, interned: HashMap<String, RunStyle>, key: () -> Long) {
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
            fun walk(e: Elem, kind: String, spid: String?, noEdit: Boolean, top: String? = null, level: Int = 0) {
                for (k in XmlScan.children(xml, e)) {
                    // The shape placed on the slide this text belongs to
                    val topId = if (level == 0) XmlScan.children(xml, k).firstOrNull { it.local.startsWith("nv") }
                        ?.let { nv -> XmlScan.children(xml, nv).firstOrNull { it.local == "cNvPr" }?.attrs?.get("id") } else top
                    if (k.local == "txBody") {
                        val ps = XmlScan.children(xml, k).filter { it.local == "p" }
                        if (ps.isEmpty()) continue
                        val bi = boxes.size
                        boxes += TextBox(slide, kind, pi, ps.first().start, ps.last().end, fixed = spid != null && spid in byParagraph, shape = top)
                        val locked = noEdit || (spid != null && spid in byCharacter)
                        for (p in ps) paras += paragraph(xml, a, p, bi, key(), interned, locked)
                    } else if (!k.empty && k.local !in setOf("nvSpPr", "spPr", "extLst", "AlternateContent", "nvGrpSpPr", "grpSpPr")) {
                        // Choices for other versions of PowerPoint (AlternateContent) would show one text twice: left as they are
                        val type = when (k.local) { "sp" -> placeholder(xml, k); "tbl" -> "table"; else -> kind }
                        if (k.local == "sp" || k.local == "graphicFrame") {
                            // The shape's id (animations name it) and whether its text is marked "do not edit"
                            val nv = XmlScan.children(xml, k).firstOrNull { it.local.startsWith("nv") }
                            val kids = nv?.let { XmlScan.children(xml, it) }.orEmpty()
                            val id = kids.firstOrNull { it.local == "cNvPr" }?.attrs?.get("id")
                            val lockText = kids.firstOrNull { it.local == "cNvSpPr" }?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spLocks" }?.attrs?.get("noTextEdit") }.let { it == "1" || it == "true" }
                            walk(k, type, id, lockText, topId, level + 1)
                        } else walk(k, type, spid, noEdit, topId, level + 1)
                    }
                }
            }
            val cSld = XmlScan.children(xml, root).firstOrNull { it.local == "cSld" }
            cSld?.let { c -> XmlScan.children(xml, c).firstOrNull { it.local == "spTree" }?.let { walk(it, "body", null, false) } }
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
