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
import ua.hramota.doc.Pages

/**
 * A DOCX laid out on pages once, then drawn page by page at the width it is shown.
 * Create off the main thread: the layout of a long document takes a moment.
 */
class DocxPages(doc: DocxDocument, fonts: Fonts) : Pages {
    private val pictures = PictureCache(doc)
    private val layout = DocxLayout(doc, fonts) { rel, w, h -> pictures.get(rel, w, h) }
    val pages: List<LaidOutPage> = layout.layout()
    private val ratio = layout.pageHeight / layout.pageWidth
    private val lock = Mutex()

    override val pageCount: Int get() = pages.size
    override fun aspect(index: Int): Float = ratio

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        lock.withLock { draw(index, widthPx)?.asImageBitmap() }
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
        PagePainter.draw(canvas, pages[index])
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
