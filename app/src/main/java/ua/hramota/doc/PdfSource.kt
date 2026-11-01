package ua.hramota.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Pages of a PDF, drawn on demand at the width they are shown. */
interface PdfPages {
    val pageCount: Int
    /** Height divided by width of page [index]. */
    fun aspect(index: Int): Float
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?
}

/**
 * The phone's own PDF engine (PdfRenderer, PDFium inside Android). It handles one page at a time,
 * so every call goes through one lock; recently drawn pages are kept in a small cache.
 */
class PdfSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val aspects: FloatArray,
) : PdfPages, Closeable {
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > CACHE_PAGES
    }
    private var closed = false

    override val pageCount: Int get() = aspects.size
    override fun aspect(index: Int): Float = aspects[index]

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in aspects.indices || widthPx <= 0) return@withLock null
            // Cap the size: very wide screens or tiny pages must not make huge bitmaps
            val w = widthPx.coerceAtMost(MAX_WIDTH)
            val key = index.toLong() shl 32 or w.toLong()
            cache[key]?.let { return@withLock it }
            val h = (w * aspects[index]).toInt().coerceIn(1, MAX_WIDTH * 4)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bitmap.asImageBitmap().also { cache[key] = it }
        }
    }

    override fun close() {
        // Waits for a page being drawn, so the renderer is never closed under it
        kotlinx.coroutines.runBlocking {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                cache.clear()
                renderer.close()
                fd.close()
            }
        }
    }

    companion object {
        private const val CACHE_PAGES = 6
        private const val MAX_WIDTH = 2048

        /** Opens the PDF and reads every page's proportions, so scrolling never jumps. */
        fun open(fd: ParcelFileDescriptor): PdfSource {
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close(); throw e
            }
            val aspects = FloatArray(renderer.pageCount) { i ->
                renderer.openPage(i).use { p -> if (p.width > 0) p.height.toFloat() / p.width else 1.414f }
            }
            return PdfSource(fd, renderer, aspects)
        }
    }
}
