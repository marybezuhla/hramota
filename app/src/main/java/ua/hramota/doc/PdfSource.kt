package ua.hramota.doc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/**
 * The phone's own PDF engine (PdfRenderer, PDFium inside Android). It handles one page at a time,
 * so every call goes through one lock; recently drawn pages are kept in a small cache.
 */
class PdfSource private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val aspects: FloatArray,
) : Pages, Closeable {
    private val lock = Mutex()
    // Kept by size in bytes, not count: a very tall page is one big bitmap
    private val cache = LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true)
    private var cacheBytes = 0L
    private var closed = false

    override val pageCount: Int get() = aspects.size
    override fun aspect(index: Int): Float = aspects[index]

    /** Why a page could not be drawn (shown on it). */
    override var problem: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try { draw(index, widthPx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { problem = "phone engine: ${e.javaClass.simpleName}"; null } catch (e: OutOfMemoryError) { problem = "memory"; null }
    }

    private suspend fun draw(index: Int, widthPx: Int): ImageBitmap? = run {
        lock.withLock {
            if (closed || index !in aspects.indices || widthPx <= 0) return@withLock null
            // Cap the size: very wide screens or tiny pages must not make huge bitmaps
            val w = widthPx.coerceAtMost(MAX_WIDTH)
            val key = index.toLong() shl 32 or w.toLong()
            cache[key]?.let { return@withLock it }
            val h = (w * aspects[index]).toInt().coerceIn(1, MAX_WIDTH * 2)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bitmap.asImageBitmap().also {
                cache[key] = it
                cacheBytes += w.toLong() * h * 4
                val iter = cache.entries.iterator()
                while (cacheBytes > CACHE_BYTES && cache.size > 1 && iter.hasNext()) {
                    val old = iter.next().value
                    cacheBytes -= old.width.toLong() * old.height * 4
                    iter.remove()
                }
            }
        }
    }

    override fun close() {
        // Waits for a page being drawn, so the renderer is never closed under it
        kotlinx.coroutines.runBlocking {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                cache.clear()
                cacheBytes = 0
                renderer.close()
                fd.close()
            }
        }
    }

    companion object {
        private const val CACHE_BYTES = 48L * 1024 * 1024
        private const val MAX_WIDTH = 2048

        /** Opens the PDF and reads every page's proportions, so scrolling never jumps. */
        fun open(fd: ParcelFileDescriptor): PdfSource {
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close(); throw e
            }
            try {
                val aspects = FloatArray(renderer.pageCount) { i ->
                    renderer.openPage(i).use { p ->
                        val a = if (p.width > 0) p.height.toFloat() / p.width else 1.414f
                        // A page no taller than 4 widths and no flatter than a quarter of one
                        if (a.isFinite()) a.coerceIn(0.25f, 4f) else 1.414f
                    }
                }
                return PdfSource(fd, renderer, aspects)
            } catch (e: Throwable) {
                renderer.close(); fd.close(); throw e
            }
        }
    }
}
