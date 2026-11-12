package ua.hramota.doc.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.TabStopSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.hramota.doc.PageBox
import ua.hramota.doc.TextPages
import ua.hramota.doc.docx.Fonts
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Slides drawn as pages: each slide is laid out once (in units of 1/[U] point, independent of the
 * screen), then drawn at the width it is shown. Text can be searched, selected and copied.
 */
class SlidePages(private val deck: Deck, private val fonts: Fonts) : TextPages, ua.hramota.doc.PrintPages {
    companion object { const val U = 8f }

    private val lock = Mutex()
    private val ratio = (deck.height / deck.width).takeIf { it.isFinite() && it > 0f } ?: 0.5625f
    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 18 * U
        typeface = fonts.typeface(Fonts.Family.CARLITO, bold = false, italic = false)
    }

    /** A paragraph laid out inside a text box: its layout and where its top-left is on the slide. */
    private class Block(val layout: StaticLayout, val x: Float, val y: Float)
    private class Laid(val shapes: List<SlideShape>, val blocks: List<List<Block>>)

    private val cache = object : LinkedHashMap<Int, Laid>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Laid>?) = size > 6
    }
    /** Decoded pictures, at most [PICTURE_BYTES] together. */
    private val pictures = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (values.sumOf { it.byteCount.toLong() } <= PICTURE_BYTES || size <= 1) return false
            return true
        }
    }
    private val PICTURE_BYTES = 64L * 1024 * 1024

    override val pageCount: Int get() = deck.slides.size
    override fun aspect(index: Int): Float = ratio

    private fun laid(index: Int): Laid = cache.getOrPut(index) {
        val slide = deck.slides[index]
        Laid(slide.shapes, slide.shapes.map { s -> if (s is SlideShape.Box) (try { layoutText(s) } catch (e: Exception) { emptyList() }) else emptyList() })
    }

    /** The paragraphs of a box, each laid out at the box's text width, placed by its vertical anchor. */
    private fun layoutText(b: SlideShape.Box): List<Block> {
        if (b.paragraphs.isEmpty()) return emptyList()
        // A box with sizes that make no sense (from a damaged file) shows no text rather than failing
        if (!(b.x.isFinite() && b.y.isFinite() && b.w.isFinite() && b.h.isFinite()) || b.inset.any { !it.isFinite() }) return emptyList()
        val width = if (b.wrap) max(U, (b.w - b.inset[0] - b.inset[2]) * U) else 20000 * U
        val laid = ArrayList<Pair<StaticLayout, Float>>()  // layout and the gap above it
        var total = 0f
        for ((i, p) in b.paragraphs.withIndex().take(500)) {
            val l = paragraph(p, width.roundToInt())
            val gap = if (i == 0) 0f else (b.paragraphs[i - 1].spaceAfter + p.spaceBefore) * U
            laid += l to gap
            total += gap + l.height
        }
        val boxH = (b.h - b.inset[1] - b.inset[3]) * U
        var y = (b.y + b.inset[1]) * U + when (b.anchor) {
            "center" -> (boxH - total) / 2
            "bottom" -> boxH - total
            else -> 0f
        }
        val x = (b.x + b.inset[0]) * U
        return laid.map { (l, gap) -> y += gap; Block(l, x, y).also { y += l.height } }
    }

    private fun paragraph(p: SlidePara, width: Int): StaticLayout {
        val text = SpannableStringBuilder()
        fun styled(start: Int, r: SlideRun) {
            val end = text.length
            if (end <= start) return
            val family = Fonts.familyFor(r.font)
            text.setSpan(TypefaceSpan(fonts.typeface(family, r.bold, r.italic)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(AbsoluteSizeSpan((r.size.coerceIn(1f, 400f) * U).roundToInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(r.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.strike) text.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val first = p.runs.firstOrNull { it.text.isNotEmpty() } ?: SlideRun("", p.emptySize)
        p.bullet?.let { bullet ->
            if (p.runs.any { it.text.isNotBlank() }) {
                val start = text.length
                text.append(bullet).append('\t')
                styled(start, first.copy(text = bullet, size = p.bulletSize ?: first.size, color = p.bulletColor ?: first.color, bold = false, italic = false, underline = false, strike = false))
            }
        }
        for (r in p.runs) {
            if (text.length > 20_000) break
            val start = text.length
            text.append(r.text.take(20_000).replace('\u000B', '\n'))
            styled(start, r)
        }
        if (text.isEmpty()) { text.append(' '); styled(0, SlideRun(" ", p.emptySize)) }
        // The first line starts at margin + indent (a bullet hangs left of the text), the others at the margin
        val firstIndent = ((p.marginLeft + p.indent) * U).coerceIn(0f, width * 0.9f)
        val restIndent = (p.marginLeft * U).coerceIn(0f, width * 0.9f)
        if (p.indent < 0) text.setSpan(TabStopSpan.Standard((restIndent - firstIndent).roundToInt().coerceAtLeast(1)), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        p.lineExact?.let { exact -> text.setSpan(LineHeightSpan { _, _, _, _, _, fm -> val h = (exact * U).roundToInt(); val d = h - (fm.descent - fm.ascent); fm.ascent -= d; fm.top = fm.ascent; fm.bottom = fm.descent }, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        val align = when (p.align) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val b = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, if (p.lineExact == null) p.lineSpacing.coerceIn(0.5f, 5f) else 1f)
            .setIndents(intArrayOf(firstIndent.roundToInt(), restIndent.roundToInt()), null)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (p.align == "justify") b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        lock.withLock { draw(index, widthPx)?.asImageBitmap() }
    }

    /** Draws slide [index] into a new bitmap [widthPx] wide (also used by tests). */
    fun draw(index: Int, widthPx: Int): Bitmap? = try { drawSlide(index, widthPx) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    override fun pointSize(index: Int): Pair<Float, Float> = deck.width to deck.height

    override suspend fun drawInto(index: Int, canvas: Canvas) = withContext(Dispatchers.Default) {
        lock.withLock {
            if (index !in deck.slides.indices) return@withLock
            canvas.save()
            // Pictures at print quality: about 200 dots per inch across the slide
            try { drawOn(canvas, index, 1f / U, 200f / 72f / U) } catch (e: Exception) {} catch (e: OutOfMemoryError) {}
            canvas.restore()
        }
    }

    private fun drawSlide(index: Int, widthPx: Int): Bitmap? {
        if (index !in deck.slides.indices || widthPx <= 0) return null
        val w = widthPx.coerceAtMost(2048)
        val h = (w * ratio).toInt().coerceIn(1, 2048 * 2)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = w / (deck.width * U)
        drawOn(canvas, index, scale, scale)
        return bitmap
    }

    /** Draws slide [index] in layout units scaled by [scale]; pictures decoded for [pictureScale] pixels per unit. */
    private fun drawOn(canvas: Canvas, index: Int, scale: Float, pictureScale: Float) {
        val slide = deck.slides[index]
        canvas.drawColor(slide.background)
        canvas.scale(scale, scale)
        val laid = laid(index)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for ((k, s) in laid.shapes.withIndex()) {
            val rect = RectF(s.x * U, s.y * U, (s.x + s.w) * U, (s.y + s.h) * U)
            canvas.save()
            if (s.rotation != 0f) canvas.rotate(s.rotation, rect.centerX(), rect.centerY())
            when (s) {
                is SlideShape.Picture -> picture(s.key, (s.w * U * pictureScale).roundToInt(), (s.h * U * pictureScale).roundToInt())?.let { canvas.drawBitmap(it, null, rect, fill.apply { color = 0xFF000000.toInt(); isFilterBitmap = true }) }
                is SlideShape.Box -> {
                    s.fill?.let { fill.color = it; shape(canvas, s.geometry, rect, fill) }
                    s.line?.let { stroke.color = it; stroke.strokeWidth = max(0.5f, s.lineWidth) * U; shape(canvas, s.geometry, rect, stroke) }
                    for (b in laid.blocks[k]) {
                        canvas.save()
                        canvas.translate(b.x, b.y)
                        b.layout.draw(canvas)
                        canvas.restore()
                    }
                }
            }
            canvas.restore()
        }
    }

    private fun shape(canvas: Canvas, geometry: String, r: RectF, paint: Paint) = when (geometry) {
        "ellipse" -> canvas.drawOval(r, paint)
        "roundRect" -> { val c = minOf(r.width(), r.height()) * 0.1667f; canvas.drawRoundRect(r, c, c, paint) }
        "line" -> canvas.drawLine(r.left, r.top, r.right, r.bottom, paint)
        else -> canvas.drawRect(r, paint)
    }

    /** A picture decoded at about the size it is shown. */
    private fun picture(key: String, w: Int, h: Int): Bitmap? {
        // Never decoded larger than the biggest page drawn
        val tw = w.coerceIn(1, 2048); val th = h.coerceIn(1, 2048)
        val cacheKey = "$key@${tw / 128}"
        pictures[cacheKey]?.let { return it }
        val bytes = deck.media[key] ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) } catch (_: OutOfMemoryError) { null } ?: return null
        pictures[cacheKey] = bmp
        return bmp
    }

    // ---- Text for search and selection ----
    private class Segment(val block: Block, val pageStart: Int)

    /** Page texts are kept (they are small); the laid-out paragraphs behind them only for recent slides. */
    private val pageTexts = HashMap<Int, String>()

    private fun textOf(index: Int): Pair<String, List<Segment>> = run {
        val sb = StringBuilder()
        val segments = ArrayList<Segment>()
        for (blocks in laid(index).blocks) for (b in blocks) {
            if (sb.isNotEmpty()) sb.append('\n')
            segments += Segment(b, sb.length)
            sb.append(b.layout.text)
        }
        sb.toString().also { pageTexts[index] = it } to segments
    }

    override suspend fun pageText(index: Int): String = withContext(Dispatchers.Default) {
        if (index !in deck.slides.indices) "" else lock.withLock { pageTexts[index] ?: try { textOf(index).first } catch (e: Exception) { "" } }
    }

    override suspend fun boxes(index: Int, start: Int, end: Int): List<PageBox> = withContext(Dispatchers.Default) {
        if (index !in deck.slides.indices || end <= start) return@withContext emptyList()
        lock.withLock {
            val pw = deck.width * U; val ph = deck.height * U
            val out = ArrayList<PageBox>()
            for (seg in textOf(index).second) {
                val l = seg.block.layout
                val from = (start - seg.pageStart).coerceAtLeast(0)
                val to = (end - seg.pageStart).coerceAtMost(l.text.length)
                if (from >= to) continue
                for (line in l.getLineForOffset(from)..l.getLineForOffset(to)) {
                    val ls = l.getLineStart(line); val le = l.getLineEnd(line)
                    val a = maxOf(from, ls); val b = minOf(to, le)
                    if (a >= b) continue
                    val x1 = l.getPrimaryHorizontal(a)
                    val x2 = if (b >= le) l.getLineRight(line) else l.getPrimaryHorizontal(b)
                    out += PageBox(
                        ((seg.block.x + minOf(x1, x2)) / pw).coerceIn(0f, 1f), ((seg.block.y + l.getLineTop(line)) / ph).coerceIn(0f, 1f),
                        ((seg.block.x + maxOf(x1, x2)) / pw).coerceIn(0f, 1f), ((seg.block.y + l.getLineBottom(line)) / ph).coerceIn(0f, 1f),
                    )
                }
            }
            out
        }
    }

    override suspend fun charAt(index: Int, x: Float, y: Float): Int = withContext(Dispatchers.Default) {
        if (index !in deck.slides.indices) return@withContext -1
        lock.withLock {
            val ux = x * deck.width * U; val uy = y * deck.height * U
            val tolerance = U * 6
            for (seg in textOf(index).second) {
                val b = seg.block; val l = b.layout
                if (uy < b.y - tolerance || uy > b.y + l.height + tolerance || ux < b.x - tolerance || ux > b.x + l.width + tolerance) continue
                val line = l.getLineForVertical((uy - b.y).toInt().coerceAtLeast(0))
                return@withLock seg.pageStart + l.getOffsetForHorizontal(line, ux - b.x).coerceIn(0, maxOf(0, l.text.length - 1))
            }
            -1
        }
    }
}
