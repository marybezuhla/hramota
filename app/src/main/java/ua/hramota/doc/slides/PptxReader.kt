package ua.hramota.doc.slides

import ua.hramota.doc.Inspection
import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.XmlNode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * PPTX presentations: slides with their shapes, text (sizes, colours, bullets, alignment, spacing,
 * autofit), pictures, groups and tables. Placeholders take their position and text style from the
 * slide layout and master, colours and fonts from the theme, as PowerPoint does. Animations, charts
 * and embedded media are not shown; nothing in the file is run.
 */
object PptxReader {
    const val MAX_PART = 16L * 1024 * 1024

    /** All XML parts together. */
    const val MAX_PARTS = 256L * 1024 * 1024

    /** [withMedia] false skips pictures (for comparing text). */
    fun read(input: InputStream, withMedia: Boolean = true): Deck {
        val parts = HashMap<String, ByteArray>()
        var partsTotal = 0L
        val media = HashMap<String, ByteArray>()
        var mediaTotal = 0L
        var macros = false
        val embedded = ArrayList<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.removePrefix("/")
                val lower = name.lowercase()
                when {
                    lower.endsWith("vbaproject.bin") -> macros = true
                    lower.startsWith("ppt/embeddings/") -> if (embedded.size < 100) embedded += name.substringAfterLast('/')
                    withMedia && lower.startsWith("ppt/media/") && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) -> {
                        if (mediaTotal < Deck.MAX_MEDIA) {
                            val bytes = readLimited(zip, minOf(64L * 1024 * 1024, Deck.MAX_MEDIA - mediaTotal))
                            mediaTotal += bytes.size
                            media[name] = bytes
                        }
                    }
                    lower.endsWith(".xml") || lower.endsWith(".rels") -> {
                        val bytes = readLimited(zip, MAX_PART)
                        partsTotal += bytes.size
                        if (partsTotal > MAX_PARTS) throw IOException("Presentation too large")
                        parts[name] = bytes
                    }
                }
            }
        }
        parts.values.forEach(Xml::checkNoDoctype)
        return Builder(parts, media).deck(Inspection(macros = macros, embedded = embedded.take(100)))
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val k = input.read(buf)
            if (k < 0) break
            n += k
            if (n > limit) throw IOException("Part too large")
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }
}

/** A finite number from a file ("NaN" and "Infinity" are refused). */
private fun String.num(): Float? = toFloatOrNull()?.takeIf { it.isFinite() }

/** Text properties for one outline level, merged from master, layout and shape (null: inherit). */
private data class Level(
    val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val color: Int? = null, val font: String? = null,
    val align: String? = null, val marginLeft: Float? = null, val indent: Float? = null,
    val spaceBefore: Float? = null, val spaceAfter: Float? = null, val lineSpacing: Float? = null, val lineExact: Float? = null,
    val bullet: String? = null, val bulletNone: Boolean? = null, val autoNumber: String? = null, val bulletColor: Int? = null,
    /** Spacing as a share of the line (spcPct), used when no points are given. */
    val spaceBeforePct: Float? = null, val spaceAfterPct: Float? = null,
) {
    fun with(o: Level?): Level = if (o == null) this else Level(
        o.size ?: size, o.bold ?: bold, o.italic ?: italic, o.color ?: color, o.font ?: font,
        o.align ?: align, o.marginLeft ?: marginLeft, o.indent ?: indent,
        if (o.spaceBeforePct != null) null else o.spaceBefore ?: spaceBefore, if (o.spaceAfterPct != null) null else o.spaceAfter ?: spaceAfter,
        if (o.lineExact != null) null else o.lineSpacing ?: lineSpacing, if (o.lineSpacing != null) null else o.lineExact ?: lineExact,
        if (o.bulletNone == true || o.autoNumber != null) null else o.bullet ?: bullet,
        if (o.bullet != null || o.autoNumber != null) false else o.bulletNone ?: bulletNone,
        if (o.bulletNone == true || o.bullet != null) null else o.autoNumber ?: autoNumber,
        o.bulletColor ?: bulletColor,
        if (o.spaceBefore != null) null else o.spaceBeforePct ?: spaceBeforePct,
        if (o.spaceAfter != null) null else o.spaceAfterPct ?: spaceAfterPct,
    )
}

/** A slide, layout or master: its shapes and relationships. */
private class Part(val path: String, val root: XmlNode, val rels: Map<String, String>)

private class Builder(private val parts: Map<String, ByteArray>, private val fileMedia: Map<String, ByteArray>) {
    /** The file's pictures, and charts drawn as pictures (by their chart part's path). */
    private val media = HashMap(fileMedia)
    private val trees = HashMap<String, XmlNode?>()
    /** Layouts, masters, themes and relationships are kept (slides share them); a slide's own tree is not. */
    private fun tree(path: String): XmlNode? {
        if (path.contains("/slides/slide")) return parts[path]?.let { Xml.tree(ByteArrayInputStream(it)) }
        return trees.getOrPut(path) { parts[path]?.let { Xml.tree(ByteArrayInputStream(it)) } }
    }

    /** Relationship id → target path, resolved against the part's folder. */
    private fun rels(path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/')
        val relPath = "$dir/_rels/${path.substringAfterLast('/')}.rels"
        return tree(relPath)?.all("Relationship").orEmpty().filter { it.attr("TargetMode") != "External" }.associate { r ->
            (r.attr("Id") ?: "") to resolve(dir, r.attr("Target") ?: "")
        }
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (dir.split('/') + target.split('/')).toMutableList()
        val out = ArrayList<String>()
        for (p in parts) when (p) { ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); ".", "" -> Unit; else -> out += p }
        return out.joinToString("/")
    }

    private fun part(path: String): Part? = tree(path)?.let { Part(path, it, rels(path)) }

    // Theme: colours by name, fonts for headings and body
    private var scheme = HashMap<String, Int>()
    private var clrMap = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
    private var majorFont = "Calibri Light"
    private var minorFont = "Calibri"

    private fun loadTheme(master: Part) {
        val themePath = master.rels.values.firstOrNull { it.contains("theme/") } ?: return
        val theme = tree(themePath) ?: return
        val cs = theme.find("clrScheme")
        val map = HashMap<String, Int>()
        cs?.children?.forEach { c ->
            val v = c.children.firstOrNull()
            val hex = v?.attr("lastClr") ?: v?.attr("val")
            hex?.toLongOrNull(16)?.let { map[c.name] = it.toInt() or 0xFF000000.toInt() }
        }
        scheme = map
        theme.find("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { majorFont = it }
        theme.find("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { minorFont = it }
        master.root.child("clrMap")?.attrs?.let { clrMap = it }
    }

    private fun font(face: String?): String? = when (face) {
        null -> null
        "+mj-lt", "+mj-ea", "+mj-cs" -> majorFont
        "+mn-lt", "+mn-ea", "+mn-cs" -> minorFont
        else -> face
    }

    /** A colour element (srgbClr, schemeClr, sysClr, prstClr) with luminance and tint changes. */
    private fun color(holder: XmlNode?): Int? {
        val c = holder?.children?.firstOrNull { it.name in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr") } ?: return null
        var rgb = when (c.name) {
            "srgbClr" -> c.attr("val")?.toLongOrNull(16)?.toInt()
            "sysClr" -> (c.attr("lastClr") ?: if (c.attr("val") == "window") "FFFFFF" else "000000").toLongOrNull(16)?.toInt()
            "schemeClr" -> { val n = c.attr("val") ?: "tx1"; scheme[clrMap[n] ?: n] ?: scheme[n] }
            "prstClr" -> when (c.attr("val")) { "white" -> 0xFFFFFF; "black" -> 0; "red" -> 0xFF0000; "blue" -> 0x0000FF; "green" -> 0x008000; "yellow" -> 0xFFFF00; else -> 0x808080 }
            else -> null
        } ?: return null
        rgb = rgb or 0xFF000000.toInt()
        var lumMod = 1.0; var lumOff = 0.0; var alpha = 1.0
        for (m in c.children) {
            val v = (m.attr("val")?.toDoubleOrNull() ?: continue) / 100000.0
            when (m.name) {
                "lumMod" -> lumMod = v; "lumOff" -> lumOff = v
                "tint" -> { rgb = mix(rgb, 0xFFFFFFFF.toInt(), 1 - v) }
                "shade" -> { rgb = mix(rgb, 0xFF000000.toInt(), 1 - v) }
                "alpha" -> alpha = v
            }
        }
        if (lumMod != 1.0 || lumOff != 0.0) rgb = lum(rgb, lumMod, lumOff)
        // Fully transparent is the same as no colour
        if (alpha <= 0.0) return null
        return (rgb and 0xFFFFFF) or ((alpha * 255).toInt().coerceIn(1, 255) shl 24)
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Luminance changed in HSL, as PowerPoint applies lumMod and lumOff. */
    private fun lum(c: Int, mod: Double, off: Double): Int {
        val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        var l = (max + min) / 2
        val d = max - min
        val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
        var h = when { d == 0.0 -> 0.0; max == r -> ((g - b) / d).mod(6.0); max == g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        l = (l * mod + off).coerceIn(0.0, 1.0)
        val cc = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = cc * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
        val m = l - cc / 2
        val (r1, g1, b1) = when { h < 60 -> Triple(cc, x, 0.0); h < 120 -> Triple(x, cc, 0.0); h < 180 -> Triple(0.0, cc, x); h < 240 -> Triple(0.0, x, cc); h < 300 -> Triple(x, 0.0, cc); else -> Triple(cc, 0.0, x) }
        fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    /** A fill: solid, the first stop of a gradient, nothing for noFill (null), or unset (NONE). */
    private fun fill(spPr: XmlNode?): Int? {
        spPr ?: return NONE
        if (spPr.child("noFill") != null) return null
        spPr.child("solidFill")?.let { return color(it) }
        spPr.child("gradFill")?.find("gs")?.let { return color(it) }
        return NONE
    }

    private fun level(pPr: XmlNode?, defRPr: XmlNode?, scale: Float = 1f): Level {
        val r = defRPr
        fun pts(n: XmlNode?) = n?.child("spcPts")?.attr("val")?.num()?.let { it / 100f }
        val lnSpc = pPr?.child("lnSpc")
        return Level(
            size = r?.attr("sz")?.num()?.let { (it / 100f * scale).coerceIn(1f, 400f) },
            bold = r?.attr("b")?.let { it == "1" || it == "true" },
            italic = r?.attr("i")?.let { it == "1" || it == "true" },
            color = r?.child("solidFill")?.let { color(it) },
            font = font(r?.child("latin")?.attr("typeface")),
            align = when (pPr?.attr("algn")) { null -> null; "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; else -> "left" },
            marginLeft = pPr?.attr("marL")?.toLongOrNull()?.let { emu(it) },
            indent = pPr?.attr("indent")?.toLongOrNull()?.let { emu(it) },
            spaceBefore = pts(pPr?.child("spcBef")),
            spaceAfter = pts(pPr?.child("spcAft")),
            lineSpacing = lnSpc?.child("spcPct")?.attr("val")?.num()?.let { it / 100000f },
            lineExact = pts(lnSpc),
            bullet = pPr?.child("buChar")?.attr("char")?.let { bulletChar(it) },
            bulletNone = if (pPr?.child("buNone") != null) true else null,
            autoNumber = pPr?.child("buAutoNum")?.attr("type"),
            bulletColor = pPr?.child("buClr")?.let { color(it) },
            spaceBeforePct = pPr?.child("spcBef")?.child("spcPct")?.attr("val")?.num()?.let { it / 100000f },
            spaceAfterPct = pPr?.child("spcAft")?.child("spcPct")?.attr("val")?.num()?.let { it / 100000f },
        )
    }

    /** Symbol-font bullets are private-use characters; show them as ordinary ones. */
    private fun bulletChar(c: String): String = when (val ch = c.firstOrNull()) {
        null -> "•"
        '\uF0B7', '\u00B7' -> "•"
        '\uF0A7', '\u00A7' -> "▪"
        '\uF0D8', '\u00D8' -> "➢"
        '\uF0FC', '\u00FC' -> "✓"
        'q' -> "❑"
        'v' -> "❖"
        else -> if (ch.code in 0xF000..0xF0FF) "•" else c
    }

    /** Levels 1–9 of a list style element (lstStyle, titleStyle, bodyStyle…). */
    private fun levels(list: XmlNode?): Array<Level?> = Array(9) { i ->
        list?.child("lvl${i + 1}pPr")?.let { level(it, it.child("defRPr")) }
    }

    private fun mergeLevels(vararg lists: Array<Level?>?): Array<Level> = Array(9) { i ->
        var l = Level()
        for (list in lists) l = l.with(list?.getOrNull(i))
        l
    }

    private class Ph(val type: String?, val idx: String?)

    private fun ph(sp: XmlNode): Ph? = sp.children.firstOrNull { it.name.startsWith("nv") }?.child("nvPr")?.child("ph")?.let { Ph(it.attr("type"), it.attr("idx")) }

    /** The layout or master shape a placeholder takes its place and style from. */
    private fun findPh(p: Part?, ph: Ph): XmlNode? {
        val shapes = p?.root?.child("cSld")?.child("spTree")?.children.orEmpty().filter { it.name == "sp" }
        fun type(t: String?) = when (t) { null, "obj" -> "body"; "ctrTitle" -> "title"; "subTitle" -> "body"; else -> t }
        if (ph.idx != null) shapes.firstOrNull { ph(it)?.idx == ph.idx }?.let { return it }
        return shapes.firstOrNull { ph(it)?.let { o -> type(o.type) == type(ph.type) } == true }
    }

    private val comments = HashMap<Int, List<String>>()

    /** A slide's comments, older (PowerPoint up to 2019) and newer (threads with replies): "Author: words". */
    private fun comments(slidePath: String): List<String> {
        val dir = slidePath.substringBeforeLast('/')
        val relTree = tree("$dir/_rels/${slidePath.substringAfterLast('/')}.rels") ?: return emptyList()
        val out = ArrayList<String>()
        fun words(n: XmlNode): String {
            val sb = StringBuilder(); val st = ArrayDeque<XmlNode>().apply { add(n) }
            while (st.isNotEmpty()) { val x = st.removeFirst(); if (x.name == "t" || x.name == "text") sb.append(x.text); if (x.name == "p" && sb.isNotEmpty()) sb.append(' '); st.addAll(x.children) }
            return sb.toString().trim()
        }
        val legacyAuthors = tree("ppt/commentAuthors.xml")?.all("cmAuthor").orEmpty().associate { (it.attr("id") ?: "") to (it.attr("name") ?: "") }
        val modernAuthors = tree("ppt/authors.xml")?.all("author").orEmpty().associate { (it.attr("id") ?: "") to (it.attr("name") ?: "") }
        for (r in relTree.all("Relationship")) {
            if (r.attr("Type")?.endsWith("/comments") != true || r.attr("TargetMode") == "External") continue
            val part = tree(resolve(dir, r.attr("Target") ?: continue)) ?: continue
            for (cm in part.all("cm").take(500)) {
                val who = (legacyAuthors[cm.attr("authorId")] ?: modernAuthors[cm.attr("authorId")])?.takeIf { it.isNotBlank() }
                val text = cm.child("text")?.text ?: cm.child("txBody")?.let(::words) ?: ""
                if (text.isNotBlank()) out += (if (who != null) "$who: " else "") + text.take(2000)
                for (reply in cm.child("replyLst")?.all("reply").orEmpty().take(100)) {
                    val rw = modernAuthors[reply.attr("authorId")]?.takeIf { it.isNotBlank() }
                    val rt = reply.child("txBody")?.let(::words) ?: continue
                    if (rt.isNotBlank()) out += "  ↳ " + (if (rw != null) "$rw: " else "") + rt.take(2000)
                }
            }
        }
        return out
    }

    fun deck(inspection: Inspection): Deck {
        val pres = tree("ppt/presentation.xml") ?: throw IOException("Not a presentation")
        val presRels = rels("ppt/presentation.xml")
        val sz = pres.child("sldSz")
        val width = emu(sz?.attr("cx")?.toLongOrNull() ?: 9144000L).coerceIn(72f, 5000f)
        val height = emu(sz?.attr("cy")?.toLongOrNull() ?: 6858000L).coerceIn(72f, 5000f)
        val slides = ArrayList<Slide>()
        val seen = HashSet<String>()
        for (id in pres.child("sldIdLst")?.all("sldId").orEmpty().take(Deck.MAX_SLIDES)) {
            val path = presRels[id.attr("id")] ?: continue
            if (!seen.add(path)) continue
            val slide = part(path) ?: continue
            if (slide.root.attr("show").let { it == "0" || it == "false" }) continue
            val layout = slide.rels.values.firstOrNull { it.contains("slideLayout") }?.let { part(it) }
            val master = layout?.rels?.values?.firstOrNull { it.contains("slideMaster") }?.let { part(it) }
            if (master != null) loadTheme(master)
            slides += slide(slide, layout, master)
            (try { comments(path) } catch (e: Exception) { emptyList() }).takeIf { it.isNotEmpty() }?.let { comments[slides.size - 1] = it }
        }
        if (slides.isEmpty()) throw IOException("No slides")
        return Deck(width, height, slides, media, if (comments.isEmpty()) inspection else inspection.copy(comments = comments.values.sumOf { it.size }), comments)
    }

    private fun slide(slide: Part, layout: Part?, master: Part?): Slide {
        val shapes = ArrayList<SlideShape>()
        val tx = master?.root?.child("txStyles")
        val styles = mapOf(
            "title" to levels(tx?.child("titleStyle")),
            "body" to levels(tx?.child("bodyStyle")),
            "other" to levels(tx?.child("otherStyle")),
        )
        // Background: the slide's, else the layout's, else the master's
        val background = listOfNotNull(slide, layout, master).firstNotNullOfOrNull { background(it) } ?: 0xFFFFFFFF.toInt()
        // Shapes of the master and layout that are not placeholders lie under the slide's own
        val showMaster = slide.root.attr("showMasterSp") != "0"
        if (showMaster && layout?.root?.attr("showMasterSp") != "0") master?.let { decorations(it, shapes, styles) }
        if (showMaster) layout?.let { decorations(it, shapes, styles) }
        tree(slide.root, slide, layout, master, styles, shapes, Transform())
        return Slide(background, shapes.take(Deck.MAX_SHAPES), frames(slide, layout, master))
    }

    /** Where each of the slide's own top-level shapes is (an empty placeholder too: it can be moved and filled). */
    private fun frames(p: Part, layout: Part?, master: Part?): List<Frame> {
        val spTree = p.root.child("cSld")?.child("spTree") ?: return emptyList()
        val out = ArrayList<Frame>()
        for (n in spTree.children.take(Deck.MAX_SHAPES)) {
            val nv = n.children.firstOrNull { it.name.startsWith("nv") }
            val id = nv?.child("cNvPr")?.attr("id") ?: continue
            val (kind, xfrm) = when (n.name) {
                "sp" -> {
                    val ph = ph(n)
                    val lph = ph?.let { findPh(layout, it) }
                    val mph = ph?.let { findPh(master, it) ?: lph?.let { l -> ph(l) }?.let { findPh(master, it) } }
                    "text" to (n.child("spPr")?.child("xfrm") ?: lph?.child("spPr")?.child("xfrm") ?: mph?.child("spPr")?.child("xfrm"))
                }
                "pic" -> "picture" to n.child("spPr")?.child("xfrm")
                "cxnSp" -> "line" to n.child("spPr")?.child("xfrm")
                "grpSp" -> "group" to n.child("grpSpPr")?.child("xfrm")
                "graphicFrame" -> "table" to n.child("xfrm")
                else -> continue
            }
            val r = rect(xfrm, Transform()) ?: continue
            out += Frame(id, kind, r.x, r.y, r.w, r.h, r.rot)
        }
        return out
    }

    private fun background(p: Part): Int? {
        val bg = p.root.child("cSld")?.child("bg") ?: return null
        bg.child("bgPr")?.let { pr -> fill(pr).let { if (it != NONE && it != null) return it } }
        bg.child("bgRef")?.let { return color(it) }
        return null
    }

    private fun decorations(p: Part, out: MutableList<SlideShape>, styles: Map<String, Array<Level?>>) {
        val spTree = p.root.child("cSld")?.child("spTree") ?: return
        for (n in spTree.children) {
            if (n.name == "sp" && ph(n) != null) continue
            shapeOf(n, p, null, null, styles, out, Transform(), decoration = true)
        }
    }

    /** Child coordinates of a group mapped onto the slide. */
    private class Transform(val ox: Float = 0f, val oy: Float = 0f, val cx: Float = 0f, val cy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f) {
        fun x(v: Float) = ox + (v - cx) * sx
        fun y(v: Float) = oy + (v - cy) * sy
    }

    private fun tree(root: XmlNode, p: Part, layout: Part?, master: Part?, styles: Map<String, Array<Level?>>, out: MutableList<SlideShape>, t: Transform) {
        val spTree = root.child("cSld")?.child("spTree") ?: return
        for (n in spTree.children) shapeOf(n, p, layout, master, styles, out, t, decoration = false)
    }

    private fun shapeOf(n: XmlNode, p: Part, layout: Part?, master: Part?, styles: Map<String, Array<Level?>>, out: MutableList<SlideShape>, t: Transform, decoration: Boolean, depth: Int = 0) {
        if (out.size >= Deck.MAX_SHAPES || depth > 20) return
        when (n.name) {
            "sp" -> box(n, p, layout, master, styles, t)?.let { out += it }
            "cxnSp" -> box(n, p, null, null, styles, t, connector = true)?.let { out += it }
            "pic" -> picture(n, p, t)?.let { out += it }
            "grpSp" -> {
                val x = n.child("grpSpPr")?.child("xfrm")
                val off = x?.child("off"); val ext = x?.child("ext"); val chOff = x?.child("chOff"); val chExt = x?.child("chExt")
                val gx = emu(off?.attr("x")?.toLongOrNull()); val gy = emu(off?.attr("y")?.toLongOrNull())
                val gw = emu(ext?.attr("cx")?.toLongOrNull()); val gh = emu(ext?.attr("cy")?.toLongOrNull())
                val cw = emu(chExt?.attr("cx")?.toLongOrNull()).takeIf { it > 0 } ?: gw
                val ch = emu(chExt?.attr("cy")?.toLongOrNull()).takeIf { it > 0 } ?: gh
                val inner = Transform(t.x(gx), t.y(gy), emu(chOff?.attr("x")?.toLongOrNull()), emu(chOff?.attr("y")?.toLongOrNull()),
                    (t.sx * (if (cw > 0) gw / cw else 1f)).coerceIn(1e-3f, 1e3f), (t.sy * (if (ch > 0) gh / ch else 1f)).coerceIn(1e-3f, 1e3f))
                for (c in n.children) shapeOf(c, p, layout, master, styles, out, inner, decoration, depth + 1)
            }
            "graphicFrame" -> if (n.find("chart") != null && n.find("tbl") == null) chart(n, p, t)?.let { out += it } else table(n, t, styles, out)
            "AlternateContent" -> n.child("Fallback")?.children?.forEach { shapeOf(it, p, layout, master, styles, out, t, decoration, depth + 1) }
        }
    }

    private class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float)

    private fun rect(xfrm: XmlNode?, t: Transform): Rect? {
        xfrm ?: return null
        val off = xfrm.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        val x = emu(off.attr("x")?.toLongOrNull()); val y = emu(off.attr("y")?.toLongOrNull())
        val w = emu(ext.attr("cx")?.toLongOrNull()); val h = emu(ext.attr("cy")?.toLongOrNull())
        return Rect(t.x(x), t.y(y), w * t.sx, h * t.sy, (xfrm.attr("rot")?.toLongOrNull() ?: 0L) / 60000f)
    }

    private fun box(sp: XmlNode, p: Part, layout: Part?, master: Part?, styles: Map<String, Array<Level?>>, t: Transform, connector: Boolean = false): SlideShape.Box? {
        val ph = ph(sp)
        val lph = ph?.let { findPh(layout, it) }
        val mph = ph?.let { findPh(master, it) ?: lph?.let { l -> ph(l) }?.let { findPh(master, it) } }
        val spPr = sp.child("spPr")
        val r = rect(spPr?.child("xfrm"), t) ?: rect(lph?.child("spPr")?.child("xfrm"), Transform()) ?: rect(mph?.child("spPr")?.child("xfrm"), Transform()) ?: return null
        val style = sp.child("style")
        var fillColor = fill(spPr)
        if (fillColor == NONE) fillColor = fill(lph?.child("spPr")).takeIf { it != NONE } ?: style?.child("fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { color(it) }
        val ln = spPr?.child("ln")
        val lineColor = when {
            ln?.child("noFill") != null -> null
            ln?.child("solidFill") != null -> color(ln.child("solidFill"))
            style?.child("lnRef")?.let { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 } == true -> color(style.child("lnRef"))
            connector -> 0xFF000000.toInt()
            else -> null
        }
        val lineWidth = ln?.attr("w")?.toLongOrNull()?.let { emu(it) } ?: 0.75f
        val geometry = when (val g = spPr?.child("prstGeom")?.attr("prst")) { "ellipse" -> "ellipse"; "roundRect" -> "roundRect"; "line", "straightConnector1" -> "line"; null -> "rect"; else -> g.takeIf { connector }?.let { "line" } ?: "rect" }

        // Text: master style for the kind of placeholder, then the master's and layout's placeholder, then the shape
        val body = sp.child("txBody")
        val kind = when (ph?.type) {
            "title", "ctrTitle" -> "title"
            null -> if (ph == null) "other" else "body"
            "body", "subTitle", "obj" -> "body"
            "dt", "ftr", "sldNum", "hdr" -> "other"
            else -> if (ph.idx != null) "body" else "other"
        }
        val bodyPr = body?.child("bodyPr")
        val lBodyPr = lph?.child("txBody")?.child("bodyPr"); val mBodyPr = mph?.child("txBody")?.child("bodyPr")
        fun bp(name: String) = bodyPr?.attr(name) ?: lBodyPr?.attr(name) ?: mBodyPr?.attr(name)
        val autofit = bodyPr?.child("normAutofit") ?: lBodyPr?.child("normAutofit")
        val scale = (autofit?.attr("fontScale")?.num() ?: 100000f) / 100000f
        val spacingCut = (autofit?.attr("lnSpcReduction")?.num() ?: 0f) / 100000f
        // A plain shape's text takes its colour from the shape's style (white on a blue shape), not the master's
        val fontRefColor = if (ph == null) color(style?.child("fontRef")) else null
        val base: Array<Level?>? = styles[kind]?.let { arr -> if (fontRefColor != null) Array<Level?>(9) { (arr[it] ?: Level()).copy(color = fontRefColor) } else arr }
        val levels = mergeLevels(base, levels(mph?.child("txBody")?.child("lstStyle")), levels(lph?.child("txBody")?.child("lstStyle")), levels(body?.child("lstStyle")))
        val paragraphs = ArrayList<SlidePara>()
        val counters = IntArray(9)
        for (pNode in body?.all("p").orEmpty().take(500)) {
            val pPr = pNode.child("pPr")
            val lvl = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val l = levels[lvl].with(level(pPr, null))
            val runs = ArrayList<SlideRun>()
            for (rn in pNode.children) {
                val text = when (rn.name) { "r", "fld" -> rn.child("t")?.text ?: ""; "br" -> "\n"; else -> continue }
                val rl = l.with(level(null, rn.child("rPr")))
                val rPr = rn.child("rPr")
                runs += SlideRun(
                    text = if (rPr?.attr("cap") == "all") text.uppercase() else text,
                    size = (rl.size ?: 18f) * (if (rPr?.attr("sz") != null) scale else scale),
                    bold = rl.bold == true, italic = rl.italic == true,
                    underline = rPr?.attr("u")?.let { it != "none" } == true,
                    strike = rPr?.attr("strike")?.let { it != "noStrike" } == true,
                    color = rl.color ?: color(style?.child("fontRef")) ?: 0xFF000000.toInt(),
                    font = rl.font ?: if (kind == "title") majorFont else minorFont,
                )
            }
            val endSize = pNode.child("endParaRPr")?.attr("sz")?.num()?.let { it / 100f } ?: l.size ?: 18f
            // Automatic numbers count paragraphs at the same level; a deeper level starts again
            val number = l.autoNumber?.let { type ->
                counters[lvl]++
                for (d in lvl + 1 until 9) counters[d] = 0
                autoNumber(type, counters[lvl] + (pPr?.child("buAutoNum")?.attr("startAt")?.toIntOrNull()?.minus(1) ?: 0))
            }
            if (l.autoNumber == null) counters[lvl] = 0
            paragraphs += SlidePara(
                runs = runs,
                align = l.align ?: "left",
                bullet = if (l.bulletNone == true) null else number ?: l.bullet,
                bulletColor = l.bulletColor,
                marginLeft = l.marginLeft ?: 0f,
                indent = l.indent ?: 0f,
                // A percentage is of the line: the font size times 1.2
                spaceBefore = (l.spaceBefore ?: (l.spaceBeforePct ?: 0f) * (runs.firstOrNull()?.size ?: endSize * scale) * 1.2f) * (1 - spacingCut),
                spaceAfter = (l.spaceAfter ?: (l.spaceAfterPct ?: 0f) * (runs.firstOrNull()?.size ?: endSize * scale) * 1.2f) * (1 - spacingCut),
                lineSpacing = ((l.lineSpacing ?: 1f) - spacingCut).coerceAtLeast(0.5f),
                lineExact = l.lineExact,
                emptySize = endSize * scale,
            )
        }
        // A placeholder with no text of its own shows nothing (its prompt text is for editing only)
        if (fillColor == null && lineColor == null && paragraphs.all { p -> p.runs.all { it.text.isEmpty() } }) return null
        if (ph != null && sp.child("txBody") == null && fillColor == null) return null
        val inset = floatArrayOf(
            emu(bp("lIns")?.toLongOrNull() ?: 91440L), emu(bp("tIns")?.toLongOrNull() ?: 45720L),
            emu(bp("rIns")?.toLongOrNull() ?: 91440L), emu(bp("bIns")?.toLongOrNull() ?: 45720L),
        )
        return SlideShape.Box(
            r.x, r.y, r.w, r.h, r.rot, geometry, fillColor, lineColor, lineWidth, paragraphs, inset,
            anchor = when (bp("anchor")) { "ctr" -> "center"; "b" -> "bottom"; else -> "top" },
            wrap = bp("wrap") != "none",
        )
    }

    private fun autoNumber(type: String, n: Int): String {
        val v = n.coerceIn(1, 9999)
        val body = when {
            type.startsWith("alphaLc") -> letters(v).lowercase()
            type.startsWith("alphaUc") -> letters(v)
            type.startsWith("romanLc") -> roman(v).lowercase()
            type.startsWith("romanUc") -> roman(v)
            else -> v.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($body)"
            type.endsWith("ParenR") -> "$body)"
            type.endsWith("Plain") -> body
            else -> "$body."
        }
    }

    private fun letters(n: Int): String { var x = n; val sb = StringBuilder(); while (x > 0) { sb.append('A' + (x - 1) % 26); x = (x - 1) / 26 }; return sb.reverse().toString() }
    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    /** A chart, drawn from the values saved with it, as a picture where it stands. */
    private fun chart(frame: XmlNode, p: Part, t: Transform): SlideShape.Picture? {
        val r = rect(frame.child("xfrm"), t) ?: return null
        val path = frame.find("chart")?.attr("id")?.let { p.rels[it] } ?: return null
        if (path !in media) {
            if (media.count { it.key.startsWith("chart:") } >= 50) return null
            val tree = tree(path) ?: return null
            val chart = try { ua.hramota.doc.Chart.parse(tree, scheme) } catch (e: Exception) { null } ?: return null
            // Drawn at twice the size in points: sharp enough on a phone and in a PDF
            media["chart:" + path] = try { chart.png((r.w * 2).toInt(), (r.h * 2).toInt()) } catch (e: Exception) { return null }
        }
        return SlideShape.Picture(r.x, r.y, r.w, r.h, r.rot, "chart:" + path)
    }

    private fun picture(pic: XmlNode, p: Part, t: Transform): SlideShape.Picture? {
        val r = rect(pic.child("spPr")?.child("xfrm"), t) ?: return null
        val embed = pic.child("blipFill")?.child("blip")?.attr("embed") ?: return null
        val path = p.rels[embed] ?: return null
        if (path !in media) return null
        return SlideShape.Picture(r.x, r.y, r.w, r.h, r.rot, path)
    }

    /** A table drawn as boxes: each cell with its fill, a thin outline and its text. */
    private fun table(frame: XmlNode, t: Transform, styles: Map<String, Array<Level?>>, out: MutableList<SlideShape>) {
        val tbl = frame.find("tbl") ?: return
        val xfrm = frame.child("xfrm")
        val r = rect(xfrm, t) ?: return
        val cols = tbl.child("tblGrid")?.all("gridCol").orEmpty().map { emu(it.attr("w")?.toLongOrNull()) * t.sx }
        var y = r.y
        val firstRowBold = tbl.child("tblPr")?.attr("firstRow") == "1"
        for ((ri, tr) in tbl.all("tr").take(500).withIndex()) {
            val h = emu(tr.attr("h")?.toLongOrNull()) * t.sy
            var x = r.x
            var col = 0
            val laid = ArrayList<SlideShape.Box>()
            for (tc in tr.all("tc").take(64)) {
                if (out.size + laid.size >= Deck.MAX_SHAPES) break
                val span = (tc.attr("gridSpan")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                val w = (col until minOf(col + span, cols.size)).sumOf { cols[it].toDouble() }.toFloat()
                if (tc.attr("hMerge") != "1" && tc.attr("vMerge") != "1") {
                    val tcPr = tc.child("tcPr")
                    val fillColor = fill(tcPr).takeIf { it != NONE } ?: if (ri == 0 && firstRowBold) scheme[clrMap["tx2"] ?: "dk2"] ?: 0xFF4472C4.toInt() else null
                    val paras = tc.child("txBody")?.all("p").orEmpty().map { pn ->
                        val l = level(pn.child("pPr"), null)
                        SlidePara(
                            runs = pn.children.filter { it.name == "r" }.map { rn ->
                                val rl = l.with(level(null, rn.child("rPr")))
                                SlideRun(rn.child("t")?.text ?: "", rl.size ?: 18f, rl.bold ?: (ri == 0 && firstRowBold), rl.italic == true,
                                    color = rl.color ?: if (ri == 0 && firstRowBold && fillColor != null) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), font = rl.font ?: minorFont)
                            },
                            align = l.align ?: "left",
                            emptySize = l.size ?: 18f,
                        )
                    }
                    laid += SlideShape.Box(x, y, w, h, 0f, "rect", fillColor, 0xFF7F7F7F.toInt(), 0.5f, paras)
                }
                x += w
                col += span
            }
            out += laid
            y += h
        }
    }

    companion object {
        /** "Not set here" for a fill, unlike null which means "no fill". */
        const val NONE = 0x00FFFFFF
    }
}
