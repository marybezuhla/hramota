package ua.hramota.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import ua.hramota.doc.DocxWriter
import ua.hramota.doc.WBlock
import ua.hramota.doc.WRun
import ua.hramota.ink.InkTool
import ua.hramota.ink.Stroke
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** A note as Word, Markdown, PDF or one long picture. */
object NoteExport {
    const val HIGHLIGHT = 0xFFFFE066.toInt()

    // ---------------------------------------------------------------- Word

    fun docx(context: Context, note: Note, lang: String): ByteArray {
        val out = ArrayList<WBlock>()
        if (note.meta.title.isNotBlank()) out += WBlock.Para(note.meta.title, "Title")
        for (b in note.blocks) when (b) {
            is NBlock.Text -> out += WBlock.Para(
                runs(b),
                style = when (b.style) { "h1" -> "Heading1"; "h2" -> "Heading2"; "h3" -> "Heading3"; "quote" -> "Quote"; else -> null },
                list = when (b.style) { "bullet" -> "bullet"; "number" -> "number"; else -> null },
                checked = if (b.style == "check") b.checked else null,
            )
            is NBlock.Ink -> inkPicture(b.strokes)?.let { (bytes, w, h) -> out += WBlock.Picture(bytes, true, w / 2, h / 2, b.recognized) }
            is NBlock.Picture -> Notebook.picture(context, b.file)?.let { out += WBlock.Picture(it, b.file.endsWith(".png"), b.w, b.h, b.alt) }
        }
        return DocxWriter.write(out, lang, note.meta.title)
    }

    /** The paragraph's text cut where its look changes. */
    fun runs(b: NBlock.Text): List<WRun> {
        if (b.text.isEmpty()) return emptyList()
        val cuts = (listOf(0, b.text.length) + b.spans.flatMap { listOf(it.start, it.end) }).filter { it in 0..b.text.length }.toSortedSet().toList()
        return cuts.zipWithNext().map { (s, e) ->
            fun has(k: String) = b.spans.any { it.kind == k && it.start <= s && it.end >= e }
            WRun(b.text.substring(s, e), bold = has("b"), italic = has("i"), underline = has("u"), strike = has("s"), highlight = has("hl"))
        }
    }

    // ---------------------------------------------------------------- Markdown

    fun markdown(note: Note): String {
        val sb = StringBuilder()
        if (note.meta.title.isNotBlank()) sb.append("# ").append(note.meta.title.trim()).append("\n\n")
        var number = 0
        for ((i, b) in note.blocks.withIndex()) {
            val prev = note.blocks.getOrNull(i - 1)
            val inList = b is NBlock.Text && b.style in LIST_STYLES
            // Items of one list stay together; anything else is a paragraph of its own
            if (i > 0 && !(inList && prev is NBlock.Text && prev.style in LIST_STYLES)) sb.append('\n')
            when (b) {
                is NBlock.Text -> {
                    number = if (b.style == "number") (if (prev is NBlock.Text && prev.style == "number") number + 1 else 1) else 0
                    val text = mdRuns(b)
                    sb.append(when (b.style) {
                        "h1" -> "## $text"; "h2" -> "### $text"; "h3" -> "#### $text"
                        "bullet" -> "- $text"; "number" -> "$number. $text"
                        "check" -> (if (b.checked) "- [x] " else "- [ ] ") + text
                        "quote" -> "> $text"
                        else -> text
                    }).append('\n')
                }
                // Handwriting as the text read from it (a picture cannot go into a Markdown file of its own)
                is NBlock.Ink -> if (b.recognized.isNotBlank()) sb.append(b.recognized.lines().joinToString("\n") { "> ✍ $it" }).append('\n')
                is NBlock.Picture -> sb.append("*[").append(b.alt.ifBlank { "🖼" }.replace("]", ")")).append("]*\n")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    private val LIST_STYLES = setOf("bullet", "number", "check")

    private fun mdEscape(s: String) = s.replace(Regex("([\\\\`*_\\[\\]#<>|])"), "\\\\$1")

    private fun mdRuns(b: NBlock.Text): String = runs(b).joinToString("") { r ->
        var t = mdEscape(r.text)
        if (t.isBlank()) return@joinToString t
        // Marks around the words only (Markdown does not allow a space just inside them)
        val lead = t.takeWhile { it == ' ' }; val trail = t.takeLastWhile { it == ' ' }
        t = t.trim()
        if (r.bold) t = "**$t**"
        if (r.italic) t = "*$t*"
        if (r.strike) t = "~~$t~~"
        if (r.highlight) t = "==$t=="
        lead + t + trail
    }

    // ---------------------------------------------------------------- drawing (PDF and picture)

    /** Handwriting as a PNG (twice the size in dp), with its width and height; null if nothing is written. */
    fun inkPicture(strokes: List<Stroke>): Triple<ByteArray, Int, Int>? {
        val bmp = inkBitmap(strokes, 2f) ?: return null
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Triple(bytes, bmp.width, bmp.height).also { bmp.recycle() }
    }

    private fun inkBounds(strokes: List<Stroke>): RectF? {
        val pts = strokes.flatMap { it.points }
        if (pts.isEmpty()) return null
        val pad = strokes.maxOf { it.width } * 3 + 4
        return RectF(pts.minOf { it.x } - pad, pts.minOf { it.y } - pad, pts.maxOf { it.x } + pad, pts.maxOf { it.y } + pad)
    }

    /** Handwriting on a transparent picture, [k] pixels per dp, cut to what is written. */
    fun inkBitmap(strokes: List<Stroke>, k: Float): Bitmap? {
        val r = inkBounds(strokes) ?: return null
        val w = max(1, (r.width() * k).toInt()); val h = max(1, (r.height() * k).toInt())
        if (w.toLong() * h > 40_000_000L) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.translate(-r.left * k, -r.top * k)
        drawInk(c, strokes, k)
        return bmp
    }

    fun drawInk(c: Canvas, strokes: List<Stroke>, k: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in strokes) {
            if (s.points.isEmpty() || s.tool == InkTool.ERASER) continue
            paint.color = s.color
            if (s.tool == InkTool.MARKER) { paint.alpha = 90; paint.strokeCap = Paint.Cap.SQUARE } else paint.strokeCap = Paint.Cap.ROUND
            if (s.stylus && s.tool == InkTool.PEN) {
                for (i in 1 until s.points.size) {
                    val a = s.points[i - 1]; val b = s.points[i]
                    paint.strokeWidth = s.width * k * (0.45f + 1.1f * (a.p + b.p) / 2)
                    c.drawLine(a.x * k, a.y * k, b.x * k, b.y * k, paint)
                }
                if (s.points.size == 1) { paint.strokeWidth = s.width * k; c.drawPoint(s.points[0].x * k, s.points[0].y * k, paint) }
            } else {
                paint.strokeWidth = s.width * k
                val p = Path()
                s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x * k, pt.y * k) else p.lineTo(pt.x * k, pt.y * k) }
                if (s.points.size == 1) c.drawPoint(s.points[0].x * k, s.points[0].y * k, paint) else c.drawPath(p, paint)
            }
        }
    }

    /** One thing to draw, laid out at a width: its height, and drawing it from a given top (part of it, for a page). */
    private interface Piece {
        val height: Float
        /** Where it may be cut between pages (the tops of its lines); empty if it is not cut. */
        val cuts: List<Float>
        fun draw(c: Canvas, from: Float, to: Float)
    }

    private fun spanned(b: NBlock.Text, prefix: String): CharSequence {
        val s = SpannableString(prefix + b.text)
        val o = prefix.length
        for (sp in b.spans) {
            val st = (sp.start + o).coerceIn(o, s.length); val en = (sp.end + o).coerceIn(st, s.length)
            if (st == en) continue
            val what: Any = when (sp.kind) {
                "b" -> StyleSpan(Typeface.BOLD); "i" -> StyleSpan(Typeface.ITALIC); "u" -> UnderlineSpan(); "s" -> StrikethroughSpan()
                "hl" -> BackgroundColorSpan(HIGHLIGHT); else -> continue
            }
            s.setSpan(what, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /**
     * The note laid out [width] units wide ([unit] units per dp of handwriting). Pictures are read
     * from the notebook as they are drawn.
     */
    private fun pieces(context: Context, note: Note, width: Float, unit: Float, base: Float): List<Piece> {
        val out = ArrayList<Piece>()
        fun text(cs: CharSequence, size: Float, bold: Boolean, italic: Boolean, color: Int, indent: Float, gapBefore: Float) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = Typeface.create(Typeface.SERIF, when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
            }
            val w = max(1, (width - indent).toInt())
            val layout = StaticLayout.Builder.obtain(cs, 0, cs.length, paint, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            out += object : Piece {
                override val height = gapBefore + layout.height
                override val cuts = (1 until layout.lineCount).map { gapBefore + layout.getLineTop(it).toFloat() }
                override fun draw(c: Canvas, from: Float, to: Float) {
                    c.save()
                    c.clipRect(0f, 0f, width, to - from)
                    c.translate(indent, gapBefore - from)
                    layout.draw(c)
                    c.restore()
                }
            }
        }
        val ink = 0xFF1B1B1F.toInt()
        if (note.meta.title.isNotBlank()) text(note.meta.title, base * 1.7f, true, false, ink, 0f, 0f)
        var number = 0
        for ((i, b) in note.blocks.withIndex()) {
            val prev = note.blocks.getOrNull(i - 1)
            when (b) {
                is NBlock.Text -> {
                    number = if (b.style == "number") (if (prev is NBlock.Text && prev.style == "number") number + 1 else 1) else 0
                    val prefix = when (b.style) { "bullet" -> "•  "; "number" -> "$number.  "; "check" -> if (b.checked) "☑  " else "☐  "; else -> "" }
                    val size = when (b.style) { "h1" -> base * 1.45f; "h2" -> base * 1.25f; "h3" -> base * 1.1f; else -> base }
                    val gap = when (b.style) { "h1", "h2", "h3" -> base * 0.8f; "bullet", "number", "check" -> base * 0.15f; else -> base * 0.45f }
                    val color = if (b.style == "check" && b.checked) 0xFF6B6B6B.toInt() else if (b.style == "quote") 0xFF404040.toInt() else ink
                    text(spanned(b, prefix), size, b.style in setOf("h1", "h2", "h3"), b.style == "quote", color, if (b.style == "quote") base * 1.5f else 0f, gap)
                }
                is NBlock.Ink -> {
                    val r = inkBounds(b.strokes) ?: continue
                    // As written: the same size as on the screen, smaller only to fit the page
                    val k = min(unit, width / max(1f, r.right))
                    out += object : Piece {
                        override val height = r.bottom * k + base * 0.4f
                        override val cuts = emptyList<Float>()
                        override fun draw(c: Canvas, from: Float, to: Float) {
                            c.save(); c.clipRect(0f, 0f, width, to - from); c.translate(0f, base * 0.4f - from); drawInk(c, b.strokes, k); c.restore()
                        }
                    }
                }
                is NBlock.Picture -> {
                    val w = min(width, b.w.toFloat() * unit / 2).coerceAtLeast(1f)
                    val h = w * b.h / max(1, b.w)
                    out += object : Piece {
                        override val height = h + base * 0.6f
                        override val cuts = emptyList<Float>()
                        override fun draw(c: Canvas, from: Float, to: Float) {
                            val bytes = Notebook.picture(context, b.file) ?: return
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                                var s = 1; while (b.w / (s * 2) >= w * 2) s *= 2; inSampleSize = s
                            }) ?: return
                            c.save(); c.clipRect(0f, 0f, width, to - from); c.translate(0f, base * 0.6f - from)
                            c.drawBitmap(bmp, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
                            bmp.recycle()
                        }
                    }
                }
            }
        }
        return out
    }

    /** A4 pages, 2 cm margins. Handwriting at its size on the screen (1 dp = 0,75 pt). */
    fun pdf(context: Context, note: Note, out: OutputStream) {
        val pw = 595f; val ph = 842f; val m = 56f
        val width = pw - 2 * m; val height = ph - 2 * m
        val doc = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            var y = 0f
            var n = 0
            fun newPage() { page?.let { doc.finishPage(it) }; n++; page = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), n).create()); y = 0f }
            newPage()
            for (piece in pieces(context, note, width, 0.75f, 11.5f)) {
                var from = 0f
                while (from < piece.height) {
                    val room = height - y
                    var to = piece.height
                    if (to - from > room) {
                        // Cut at a line top that fits, or start on a new page; a piece taller than a page is cut where it must
                        val cut = piece.cuts.lastOrNull { it > from && it - from <= room }
                        if (cut != null) to = cut
                        else if (y > 0f) { newPage(); continue }
                        else to = from + room
                    }
                    val c = page!!.canvas
                    c.save(); c.translate(m, m + y); piece.draw(c, from, to); c.restore()
                    y += to - from
                    from = to
                    if (from < piece.height) newPage()
                }
            }
            page?.let { doc.finishPage(it) }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    /** The whole note as one picture, [widthPx] wide (a tall note is cut at about 16 000 px). */
    fun png(context: Context, note: Note, widthPx: Int = 1080): ByteArray {
        val margin = widthPx * 0.06f
        val width = widthPx - 2 * margin
        val unit = widthPx / 400f
        val pieces = pieces(context, note, width, unit, widthPx / 26f)
        val total = (pieces.sumOf { it.height.toDouble() }.toFloat() + 2 * margin).coerceIn(widthPx * 0.5f, 16_000f)
        val bmp = Bitmap.createBitmap(widthPx, total.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = margin
        for (p in pieces) {
            if (y > total) break
            c.save(); c.translate(margin, y); p.draw(c, 0f, p.height); c.restore()
            y += p.height
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bmp.recycle() }
    }
}
