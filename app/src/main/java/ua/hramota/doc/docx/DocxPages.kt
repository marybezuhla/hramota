package ua.hramota.doc.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.hramota.doc.PageBox
import ua.hramota.doc.TextPages

/**
 * A DOCX laid out on pages once, then drawn page by page at the width it is shown.
 * Create off the main thread: the layout of a long document takes a moment.
 */
class DocxPages(doc: DocxDocument, fonts: Fonts) : TextPages {
    private val pictures = PictureCache(doc)
    private val layout = DocxLayout(doc, fonts) { rel, w, h -> pictures.get(rel, w, h) }
    val pages: List<LaidOutPage> = layout.layout()
    private val ratio = (layout.pageHeight / layout.pageWidth).takeIf { it.isFinite() && it > 0f } ?: 1.414f
    private val lock = Mutex()
    private val painter = PagePainter()

    override val pageCount: Int get() = pages.size
    override fun aspect(index: Int): Float = ratio

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        lock.withLock { draw(index, widthPx)?.asImageBitmap() }
    }

    /** One paragraph slice on a page and where its text starts in the page's text. */
    private class Segment(val item: PageItem.Text, val pageStart: Int, val layoutStart: Int, val layoutEnd: Int)

    private val pageTexts = arrayOfNulls<Pair<String, List<Segment>>>(pages.size)

    /** The page's text in reading order (paragraphs joined by a line break) and where each slice sits in it. */
    private fun textOf(index: Int): Pair<String, List<Segment>> {
        pageTexts[index]?.let { return it }
        val sb = StringBuilder()
        val segments = ArrayList<Segment>()
        for (item in pages[index].items) {
            if (item !is PageItem.Text) continue
            val l = item.layout
            val a = l.getLineStart(item.first)
            val b = l.getLineEnd(item.last)
            if (sb.isNotEmpty()) sb.append('\n')
            segments += Segment(item, sb.length, a, b)
            sb.append(l.text, a, b)
        }
        // Pictures and tabs stay as placeholders, so positions keep matching the layouts
        return (sb.toString() to segments).also { pageTexts[index] = it }
    }

    override suspend fun pageText(index: Int): String = withContext(Dispatchers.Default) {
        if (index !in pages.indices) "" else lock.withLock { textOf(index).first }
    }

    override suspend fun boxes(index: Int, start: Int, end: Int): List<PageBox> = withContext(Dispatchers.Default) {
        if (index !in pages.indices || end <= start) return@withContext emptyList()
        lock.withLock {
            val out = ArrayList<PageBox>()
            for (seg in textOf(index).second) {
                val l = seg.item.layout
                val from = maxOf(start - seg.pageStart + seg.layoutStart, seg.layoutStart)
                val to = minOf(end - seg.pageStart + seg.layoutStart, seg.layoutEnd)
                if (from >= to) continue
                val sliceTop = l.getLineTop(seg.item.first)
                for (line in seg.item.first..seg.item.last) {
                    val ls = l.getLineStart(line); val le = l.getLineEnd(line)
                    val a = maxOf(from, ls); val b = minOf(to, le)
                    if (a >= b) continue
                    val x1 = l.getPrimaryHorizontal(a)
                    val x2 = if (b >= le) l.getLineRight(line) else l.getPrimaryHorizontal(b)
                    val top = seg.item.y + (l.getLineTop(line) - sliceTop)
                    val bottom = seg.item.y + (l.getLineBottom(line) - sliceTop)
                    out += PageBox(
                        ((seg.item.x + minOf(x1, x2)) / layout.pageWidth).coerceIn(0f, 1f), (top / layout.pageHeight).coerceIn(0f, 1f),
                        ((seg.item.x + maxOf(x1, x2)) / layout.pageWidth).coerceIn(0f, 1f), (bottom / layout.pageHeight).coerceIn(0f, 1f),
                    )
                }
            }
            out
        }
    }

    override suspend fun charAt(index: Int, x: Float, y: Float): Int = withContext(Dispatchers.Default) {
        if (index !in pages.indices) return@withContext -1
        lock.withLock {
            val ux = x * layout.pageWidth
            val uy = y * layout.pageHeight
            val tolerance = DocxLayout.U * 6
            for (seg in textOf(index).second) {
                val l = seg.item.layout
                val sliceTop = l.getLineTop(seg.item.first)
                val height = l.getLineBottom(seg.item.last) - sliceTop
                if (uy < seg.item.y - tolerance || uy > seg.item.y + height + tolerance) continue
                if (ux < seg.item.x - tolerance || ux > seg.item.x + l.width + tolerance) continue
                val line = l.getLineForVertical((sliceTop + (uy - seg.item.y)).toInt()).coerceIn(seg.item.first, seg.item.last)
                val off = l.getOffsetForHorizontal(line, ux - seg.item.x).coerceIn(seg.layoutStart, maxOf(seg.layoutStart, seg.layoutEnd - 1))
                return@withLock seg.pageStart + (off - seg.layoutStart)
            }
            -1
        }
    }

    /** Draws page [index] into a new bitmap [widthPx] wide (also used by tests). */
    fun draw(index: Int, widthPx: Int): Bitmap? {
        if (index !in pages.indices || widthPx <= 0) return null
        val w = widthPx.coerceAtMost(2048)
        val h = (w * ratio).toInt().coerceIn(1, 2048 * 3)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        canvas.scale(w / layout.pageWidth, w / layout.pageWidth)
        painter.draw(canvas, pages[index])
        return bitmap
    }
}

/** Pictures decoded at about the size they are shown, a few kept in memory. */
private class PictureCache(private val doc: DocxDocument) {
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    fun get(rel: String, w: Int, h: Int): Bitmap? {
        val key = "$rel@${w / 64}"
        cache[key]?.let { return it }
        val bytes = doc.picture(rel) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        cache[key] = bitmap
        return bitmap
    }
}
