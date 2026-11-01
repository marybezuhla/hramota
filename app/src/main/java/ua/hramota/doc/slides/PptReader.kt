package ua.hramota.doc.slides

import ua.hramota.doc.DocReader
import ua.hramota.doc.EncryptedDocumentException
import ua.hramota.doc.Inspection
import ua.hramota.doc.OleFile
import java.io.IOException
import java.io.InputStream

/**
 * Old PowerPoint presentations (.ppt, PowerPoint 97–2003): slides with their shapes (position,
 * fill, outline, rotation), text with paragraph and character formatting from the slide and the
 * master's text styles, and pictures (PNG, JPEG). Every offset is checked.
 */
object PptReader {
    fun read(input: InputStream): Deck {
        val ole = OleFile(DocReader.readAll(input))
        val doc = ole.stream("PowerPoint Document") ?: throw IOException("Not a PowerPoint file")
        if (ole.stream("EncryptedSummary") != null) throw EncryptedDocumentException()
        val names = ole.names
        return Ppt(doc, ole.stream("Current User"), ole.stream("Pictures")).deck(Inspection(
            macros = names.any { it == "VBA" || it == "_VBA_PROJECT" },
            embedded = names.filter { it.startsWith("MBD") || it.startsWith("_") && it.drop(1).all(Char::isDigit) }.map { "OLE $it" }.take(100),
        ))
    }
}

/** A record: its type, instance, and where its contents are. */
private class Rec(val type: Int, val instance: Int, val at: Int, val len: Int, val container: Boolean)

private class Ppt(private val s: ByteArray, private val user: ByteArray?, private val pictures: ByteArray?) {
    private fun u8(at: Int): Int { if (at < 0 || at >= s.size) throw IOException("Read past end"); return s[at].toInt() and 0xFF }
    private fun u16(at: Int) = DocReader.u16(s, at)
    private fun s16(at: Int) = DocReader.u16(s, at).toShort().toInt()
    private fun i32(at: Int) = DocReader.i32(s, at)

    private fun rec(at: Int): Rec? {
        if (at < 0 || at > s.size - 8) return null
        val vi = u16(at)
        val len = i32(at + 4)
        if (len < 0 || at + 8L + len > s.size) return null
        return Rec(u16(at + 2), vi ushr 4, at + 8, len, vi and 0xF == 0xF)
    }

    /** The records directly inside a container. */
    private fun children(r: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var p = r.at
        val end = r.at + r.len
        while (p + 8 <= end && out.size < 100_000) {
            val c = rec(p) ?: break
            if (c.at + c.len > end) break
            out += c
            p = c.at + c.len
        }
        return out
    }

    private fun child(r: Rec, type: Int): Rec? = children(r).firstOrNull { it.type == type }

    /** Records of [type] anywhere inside [r], depth-first, not deeper than 12. */
    private fun find(r: Rec, type: Int, depth: Int = 0, out: MutableList<Rec> = ArrayList()): List<Rec> {
        if (depth > 12 || out.size > 20_000) return out
        for (c in children(r)) {
            if (c.type == type) out += c
            if (c.container) find(c, type, depth + 1, out)
        }
        return out
    }

    // ---- Where objects are: the persist directory of the latest edit, then older ones ----
    private val persist = HashMap<Int, Int>()
    private var documentRef = 0

    private fun loadPersist() {
        val u = user ?: throw IOException("Current User missing")
        if (u.size < 20) throw IOException("Current User damaged")
        // CurrentUserAtom: header (8), size (4), headerToken (4), offsetToCurrentEdit (4)
        if (DocReader.i32(u, 12) == 0xF3D1C4DF.toInt()) throw EncryptedDocumentException()
        var edit = DocReader.i32(u, 16)
        var guard = 0
        while (edit > 0 && guard++ < 1000) {
            val r = rec(edit) ?: break
            if (r.type != 0x0FF5 || r.len < 28) break
            if (documentRef == 0) documentRef = i32(r.at + 16)
            val last = i32(r.at + 8)
            val dir = rec(i32(r.at + 12))
            if (dir != null && dir.type == 0x1772) {
                var p = dir.at
                while (p + 4 <= dir.at + dir.len) {
                    val v = i32(p); p += 4
                    val start = v and 0xFFFFF
                    val count = (v ushr 20) and 0xFFF
                    for (k in 0 until count) {
                        if (p + 4 > dir.at + dir.len) break
                        persist.putIfAbsent(start + k, i32(p)); p += 4
                    }
                }
            }
            if (last <= 0 || last == edit) break
            edit = last
        }
        if (documentRef == 0) throw IOException("No document")
    }

    private fun obj(ref: Int): Rec? = persist[ref]?.let { rec(it) }

    // ---- Document-wide: fonts, slide size, pictures, master text styles and colours ----
    private val fonts = ArrayList<String>()
    private var width = 720f
    private var height = 540f
    private val blips = ArrayList<Int>()  // offsets into the Pictures stream, by picture number - 1
    private val scheme = IntArray(8) { listOf(0xFFFFFF, 0x000000, 0x808080, 0x000000, 0xBBE0E3, 0x333399, 0x009999, 0x99CC00)[it] or 0xFF000000.toInt() }

    /** A text type's style for each of five outline levels: (paragraph exception offset, character exception offset). */
    private val masterStyles = HashMap<Int, List<Pair<Props, Props>>>()

    fun deck(inspection: Inspection): Deck {
        loadPersist()
        val doc = obj(documentRef)?.takeIf { it.type == 0x03E8 } ?: throw IOException("Document missing")
        child(doc, 0x03E9)?.let { a -> if (a.len >= 8) { width = (i32(a.at) / 8f).coerceIn(72f, 5000f); height = (i32(a.at + 4) / 8f).coerceIn(72f, 5000f) } }
        child(doc, 0x03F2)?.let { env ->
            for (f in find(env, 0x0FB7)) if (f.len >= 64) fonts += String(s, f.at, 64, Charsets.UTF_16LE).substringBefore('\u0000')
            for (t in find(env, 0x0FA3)) masterStyles.putIfAbsent(t.instance, masterStyle(t))
        }
        child(doc, 0x040B)?.let { group ->
            for (fbse in find(group, 0xF007)) if (fbse.len >= 36) blips += i32(fbse.at + 28)
        }
        // Colours of the first master
        val lists = children(doc).filter { it.type == 0x0FF0 }
        lists.firstOrNull { it.instance == 1 }?.let { masters ->
            children(masters).firstOrNull { it.type == 0x03F3 }?.let { obj(i32(it.at)) }?.let { m ->
                child(m, 0x07F0)?.let { cs -> for (k in 0 until minOf(8, cs.len / 4)) scheme[k] = color(cs.at + k * 4) }
                for (t in find(m, 0x0FA3)) masterStyles[t.instance] = masterStyle(t)
            }
        }
        val slides = ArrayList<Slide>()
        val media = HashMap<String, ByteArray>()
        val list = lists.firstOrNull { it.instance == 0 }
        if (list != null) {
            // Each slide's persist record, then the text of its placeholders until the next slide
            var current: Int? = null
            var texts = ArrayList<TextBlock>()
            var header = 4
            fun flush() { current?.let { ref -> obj(ref)?.takeIf { it.type == 0x03EE }?.let { slides += slide(it, texts, media) } } }
            for (c in children(list)) {
                when (c.type) {
                    0x03F3 -> { flush(); current = i32(c.at); texts = ArrayList(); header = 4 }
                    0x0F9F -> header = if (c.len >= 4) i32(c.at) else 4
                    0x0FA0, 0x0FA8 -> texts += TextBlock(header, text(c), null)
                    0x0FA1 -> texts.lastOrNull()?.let { texts[texts.size - 1] = it.copy(styleAt = c) }
                }
                if (slides.size >= Deck.MAX_SLIDES) break
            }
            flush()
        }
        if (slides.isEmpty()) throw IOException("No slides")
        return Deck(width, height, slides, media, inspection)
    }

    private fun color(at: Int): Int = (0xFF shl 24) or (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)

    /** An OfficeArt colour (red, green, blue, flags): a scheme colour when the flags say so. */
    private fun artColor(v: Int): Int {
        val flags = (v ushr 24) and 0xFF
        if (flags and 0x08 != 0) return scheme[(v and 0xFF).coerceIn(0, 7)]
        return (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)
    }

    private fun text(r: Rec): String = if (r.type == 0x0FA0) String(s, r.at, r.len and 1.inv(), Charsets.UTF_16LE) else String(s, r.at, r.len, Charsets.ISO_8859_1)

    private data class TextBlock(val type: Int, val text: String, val styleAt: Rec?)

    /** Formatting from a paragraph or character exception (null: inherit). */
    private data class Props(
        val bullet: Boolean? = null, val bulletChar: Char? = null, val align: Int? = null,
        val lineSpacing: Int? = null, val spaceBefore: Int? = null, val spaceAfter: Int? = null,
        val margin: Int? = null, val indent: Int? = null,
        val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
        val font: Int? = null, val size: Int? = null, val color: Int? = null,
    ) {
        fun with(o: Props?): Props = if (o == null) this else Props(
            o.bullet ?: bullet, o.bulletChar ?: bulletChar, o.align ?: align, o.lineSpacing ?: lineSpacing,
            o.spaceBefore ?: spaceBefore, o.spaceAfter ?: spaceAfter, o.margin ?: margin, o.indent ?: indent,
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.font ?: font, o.size ?: size, o.color ?: color,
        )
    }

    /** A TextPFException at [at]; returns it and where the next thing starts. */
    private fun pf(at: Int, end: Int): Pair<Props, Int> {
        var p = at
        val mask = i32(p); p += 4
        fun has(bit: Int) = mask and (1 shl bit) != 0
        var bulletOn: Boolean? = null
        var bulletChar: Char? = null; var align: Int? = null; var line: Int? = null; var before: Int? = null; var after: Int? = null
        var margin: Int? = null; var indent: Int? = null
        if (mask and 0x0F != 0) { val f = u16(p); p += 2; if (has(0)) bulletOn = f and 1 != 0 }
        if (has(7)) { bulletChar = u16(p).toChar(); p += 2 }
        if (has(4)) p += 2
        if (has(6)) p += 2
        if (has(5)) p += 4
        if (has(11)) { align = u16(p); p += 2 }
        if (has(12)) { line = s16(p); p += 2 }
        if (has(13)) { before = s16(p); p += 2 }
        if (has(14)) { after = s16(p); p += 2 }
        if (has(8)) { margin = u16(p); p += 2 }
        if (has(10)) { indent = u16(p); p += 2 }
        if (has(9)) p += 2
        if (has(20)) { val n = u16(p).coerceAtMost(200); p += 2 + n * 4 }
        if (has(16)) p += 2
        if (mask and (7 shl 17) != 0) p += 2
        if (has(21)) p += 2
        if (p > end) throw IOException("Style past end")
        return Props(bullet = bulletOn, bulletChar = bulletChar, align = align, lineSpacing = line, spaceBefore = before, spaceAfter = after, margin = margin, indent = indent) to p
    }

    /** A TextCFException at [at]. */
    private fun cf(at: Int, end: Int): Pair<Props, Int> {
        var p = at
        val mask = i32(p); p += 4
        fun has(bit: Int) = mask and (1 shl bit) != 0
        var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null
        var font: Int? = null; var size: Int? = null; var color: Int? = null
        if (mask and 0xFFFF != 0) {
            val st = u16(p); p += 2
            if (has(0)) bold = st and 1 != 0
            if (has(1)) italic = st and 2 != 0
            if (has(2)) underline = st and 4 != 0
        }
        if (has(16)) { font = u16(p); p += 2 }
        if (has(21)) p += 2
        if (has(22)) p += 2
        if (has(23)) p += 2
        if (has(17)) { size = u16(p); p += 2 }
        if (has(18)) { val idx = u8(p + 3); color = if (idx == 0xFE) color(p) else if (idx < 8) scheme[idx] else null; p += 4 }
        if (has(19)) p += 2
        if (p > end) throw IOException("Style past end")
        return Props(bold = bold, italic = italic, underline = underline, font = font, size = size, color = color) to p
    }

    private fun masterStyle(t: Rec): List<Pair<Props, Props>> = try {
        val levels = u16(t.at).coerceAtMost(5)
        var p = t.at + 2
        val end = t.at + t.len
        val out = ArrayList<Pair<Props, Props>>()
        repeat(levels) {
            if (t.instance >= 5) p += 2
            val (para, next) = pf(p, end)
            val (chr, after) = cf(next, end)
            out += para to chr
            p = after
        }
        out
    } catch (e: IOException) { emptyList() }

    /** Default sizes when the file gives none: title 44 pt, body by level, other 18 pt. */
    private fun defaultSize(type: Int, level: Int) = when (type) {
        0, 6 -> 44
        1, 5, 7, 8 -> listOf(32, 28, 24, 20, 20)[level.coerceIn(0, 4)]
        else -> 18
    }

    private fun paragraphs(block: TextBlock): List<SlidePara> {
        val text = block.text
        val base = masterStyles[block.type] ?: masterStyles[when (block.type) { 6 -> 0; 5, 7, 8 -> 1; else -> 4 }] ?: emptyList()
        // Runs of paragraph and character formatting (each counts characters, the last includes the final break)
        val paraRuns = ArrayList<Triple<Int, Int, Props>>()  // length, level, props
        val charRuns = ArrayList<Pair<Int, Props>>()
        block.styleAt?.let { st ->
            try {
                var p = st.at
                val end = st.at + st.len
                var left = text.length + 1
                while (left > 0 && p + 10 <= end) {
                    val n = i32(p).coerceAtLeast(1); val lvl = u16(p + 4).coerceIn(0, 4)
                    val (pr, next) = pf(p + 6, end); p = next
                    paraRuns += Triple(n, lvl, pr); left -= n
                }
                left = text.length + 1
                while (left > 0 && p + 8 <= end) {
                    val n = i32(p).coerceAtLeast(1)
                    val (cr, next) = cf(p + 4, end); p = next
                    charRuns += n to cr; left -= n
                }
            } catch (e: IOException) { /* formatting read so far is kept */ }
        }
        val out = ArrayList<SlidePara>()
        var pos = 0
        var pr = 0; var prLeft = paraRuns.firstOrNull()?.first ?: Int.MAX_VALUE
        var cr = 0; var crLeft = charRuns.firstOrNull()?.first ?: Int.MAX_VALUE
        for (line in text.split('\r').take(500)) {
            val (_, level, pp) = paraRuns.getOrNull(pr) ?: Triple(0, 0, Props())
            val master = base.getOrNull(level)
            val para = (master?.first ?: Props()).with(pp)
            val runs = ArrayList<SlideRun>()
            val sb = StringBuilder()
            var runProps: Props? = null
            fun flushRun() {
                if (sb.isEmpty()) return
                val c = (master?.second ?: Props()).with(runProps)
                runs += SlideRun(
                    sb.toString().replace('\u000B', '\n'), size = (c.size ?: defaultSize(block.type, level)).toFloat().coerceIn(1f, 400f),
                    bold = c.bold == true, italic = c.italic == true, underline = c.underline == true,
                    color = c.color ?: scheme[if (block.type == 0 || block.type == 6) 3 else 1], font = c.font?.let { fonts.getOrNull(it) },
                )
                sb.clear()
            }
            for (ch in line + "\r") {
                val props = charRuns.getOrNull(cr)?.second
                if (props !== runProps) { flushRun(); runProps = props }
                if (ch != '\r') sb.append(ch)
                pos++
                if (--crLeft <= 0) { cr++; crLeft = charRuns.getOrNull(cr)?.first ?: Int.MAX_VALUE }
                if (--prLeft <= 0) { pr++; prLeft = paraRuns.getOrNull(pr)?.first ?: Int.MAX_VALUE }
            }
            flushRun()
            val size = runs.firstOrNull()?.size ?: defaultSize(block.type, level).toFloat()
            fun spacing(v: Int?) = when { v == null -> 0f; v >= 0 -> v / 100f * size * 1.2f; else -> -v / 8f }
            out += SlidePara(
                runs = runs,
                align = when (para.align) { 1 -> "center"; 2 -> "right"; 3 -> "justify"; else -> "left" },
                bullet = if (para.bullet == true) (para.bulletChar?.takeIf { it.code > 32 }?.let { bulletText(it) } ?: "•") else null,
                marginLeft = (para.margin ?: 0) / 8f,
                indent = ((para.indent ?: 0) - (para.margin ?: 0)) / 8f,
                spaceBefore = spacing(para.spaceBefore),
                spaceAfter = spacing(para.spaceAfter),
                lineSpacing = para.lineSpacing?.takeIf { it > 0 }?.let { it / 100f } ?: 1f,
                lineExact = para.lineSpacing?.takeIf { it < 0 }?.let { -it / 8f },
                emptySize = size,
            )
        }
        return out
    }

    private fun bulletText(c: Char): String = when {
        c == '' || c == '·' || c == '•' -> "•"
        c == '' || c == '§' -> "▪"
        c == '' || c == 'Ø' -> "➢"
        c.code in 0xF000..0xF0FF -> "•"
        else -> c.toString()
    }

    // ---- Shapes ----
    private class Group(val ox: Float, val oy: Float, val cx: Float, val cy: Float, val sx: Float, val sy: Float) {
        fun x(v: Float) = ox + (v - cx) * sx
        fun y(v: Float) = oy + (v - cy) * sy
    }

    private fun slide(r: Rec, texts: List<TextBlock>, media: MutableMap<String, ByteArray>): Slide {
        val shapes = ArrayList<SlideShape>()
        var background = 0xFFFFFFFF.toInt()
        val drawing = child(r, 0x040C)
        val dg = drawing?.let { child(it, 0xF002) }
        dg?.let { d ->
            // The background shape
            child(d, 0xF004)?.let { bg -> props(bg)[0x0181]?.let { background = artColor(it) } }
            child(d, 0xF003)?.let { group(it, Group(0f, 0f, 0f, 0f, 1f, 1f), texts, shapes, media, 0, top = true) }
        }
        return Slide(background, shapes.take(Deck.MAX_SHAPES))
    }

    /** The simple properties of a shape (id → value). */
    private fun props(sp: Rec): Map<Int, Int> {
        val opt = child(sp, 0xF00B) ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (k in 0 until minOf(opt.instance, opt.len / 6)) {
            val id = u16(opt.at + k * 6) and 0x3FFF
            out[id] = i32(opt.at + k * 6 + 2)
        }
        return out
    }

    private fun group(g: Rec, outer: Group, texts: List<TextBlock>, out: MutableList<SlideShape>, media: MutableMap<String, ByteArray>, depth: Int, top: Boolean) {
        if (depth > 16) return
        val items = children(g)
        var t = outer
        for ((i, c) in items.withIndex()) {
            if (out.size >= Deck.MAX_SHAPES) return
            if (c.type == 0xF003) { group(c, t, texts, out, media, depth + 1, top = false); continue }
            if (c.type != 0xF004) continue
            val fsp = child(c, 0xF00A) ?: continue
            val flags = if (fsp.len >= 8) i32(fsp.at + 4) else 0
            if (i == 0) {
                // The group's own record: its rectangle in the parent, and the child coordinate space
                if (!top) {
                    val anchor = anchor(c, outer)
                    child(c, 0xF009)?.takeIf { it.len >= 16 }?.let { fg ->
                        val l = i32(fg.at) / 8f; val tp = i32(fg.at + 4) / 8f; val rt = i32(fg.at + 8) / 8f; val b = i32(fg.at + 12) / 8f
                        if (anchor != null && rt > l && b > tp) t = Group(anchor[0], anchor[1], l, tp, anchor[2] / (rt - l), anchor[3] / (b - tp))
                    }
                }
                continue
            }
            if (flags and 0x0001 != 0 || flags and 0x0004 != 0) continue  // a group or the patriarch itself
            val a = anchor(c, t) ?: continue
            val p = props(c)
            val kind = fsp.instance
            val rot = (p[0x0004] ?: 0) / 65536f
            val pib = p[0x0104]
            if (pib != null && pib > 0) {
                picture(pib, media)?.let { key -> out += SlideShape.Picture(a[0], a[1], a[2], a[3], rot, key); continue }
            }
            val fillBits = p[0x01BF] ?: 0
            val filled = if (fillBits and (1 shl 20) != 0) fillBits and (1 shl 4) != 0 else p.containsKey(0x0181)
            val lineBits = p[0x01FF] ?: 0
            val lined = if (lineBits and (1 shl 19) != 0) lineBits and (1 shl 3) != 0 else p.containsKey(0x01C0)
            val fill = if (filled && kind != 202) p[0x0181]?.let { artColor(it) } else null
            val line = if (lined) p[0x01C0]?.let { artColor(it) } ?: 0xFF000000.toInt() else null
            val lineWidth = (p[0x01CB] ?: 9525) / 12700f
            // Text inside the shape, or a reference to the slide's placeholder text
            val box = child(c, 0xF00D)
            val block = box?.let { b ->
                val kids = children(b)
                val header = kids.firstOrNull { it.type == 0x0F9F }?.let { if (it.len >= 4) i32(it.at) else 4 } ?: 4
                val ref = kids.firstOrNull { it.type == 0x0F9E }?.let { if (it.len >= 4) i32(it.at) else -1 }
                if (ref != null && ref >= 0) texts.getOrNull(ref)
                else kids.firstOrNull { it.type == 0x0FA0 || it.type == 0x0FA8 }?.let { TextBlock(header, text(it), kids.firstOrNull { k -> k.type == 0x0FA1 }) }
            }
            val paragraphs = block?.let { paragraphs(it) }.orEmpty()
            if (fill == null && line == null && paragraphs.isEmpty()) continue
            val geometry = when (kind) { 2 -> "roundRect"; 3 -> "ellipse"; 20, 32 -> "line"; else -> "rect" }
            fun inset(id: Int, def: Int) = (p[id] ?: def) / 12700f
            out += SlideShape.Box(
                a[0], a[1], a[2], a[3], rot, geometry, fill, line, lineWidth, paragraphs,
                floatArrayOf(inset(0x0081, 91440), inset(0x0082, 45720), inset(0x0083, 91440), inset(0x0084, 45720)),
                anchor = when (p[0x0087] ?: 0) { 1, 4, 6, 8 -> "center"; 2, 5, 7, 9 -> "bottom"; else -> "top" },
            )
        }
    }

    /** A shape's rectangle on the slide, in points: x, y, width, height. */
    private fun anchor(sp: Rec, g: Group): FloatArray? {
        child(sp, 0xF010)?.let { a ->
            val (top, left, right, bottom) = when {
                a.len >= 16 -> listOf(i32(a.at), i32(a.at + 4), i32(a.at + 8), i32(a.at + 12))
                a.len >= 8 -> listOf(s16(a.at), s16(a.at + 2), s16(a.at + 4), s16(a.at + 6))
                else -> return null
            }
            return floatArrayOf(left / 8f, top / 8f, (right - left) / 8f, (bottom - top) / 8f)
        }
        child(sp, 0xF00F)?.takeIf { it.len >= 16 }?.let { a ->
            val l = i32(a.at) / 8f; val t = i32(a.at + 4) / 8f; val r = i32(a.at + 8) / 8f; val b = i32(a.at + 12) / 8f
            return floatArrayOf(g.x(l), g.y(t), (r - l) * g.sx, (b - t) * g.sy)
        }
        return null
    }

    /** Picture number [pib] from the Pictures stream, if it is a PNG or JPEG. */
    private fun picture(pib: Int, media: MutableMap<String, ByteArray>): String? {
        val key = "ppt$pib"
        if (key in media) return key
        val pics = pictures ?: return null
        val off = blips.getOrNull(pib - 1) ?: return null
        if (off < 0 || off > pics.size - 8) return null
        val len = DocReader.i32(pics, off + 4)
        val end = (off.toLong() + 8 + len).coerceAtMost(pics.size.toLong()).toInt()
        var i = off + 8
        val limit = minOf(end, off + 8 + 256)
        while (i + 4 < limit) {
            val png = pics[i] == 0x89.toByte() && pics[i + 1] == 'P'.code.toByte() && pics[i + 2] == 'N'.code.toByte()
            val jpg = pics[i] == 0xFF.toByte() && pics[i + 1] == 0xD8.toByte() && pics[i + 2] == 0xFF.toByte()
            if (png || jpg) { media[key] = pics.copyOfRange(i, end); return key }
            i++
        }
        return null
    }
}
