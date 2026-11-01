package ua.hramota.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ua.hramota.doc.Inspection
import ua.hramota.doc.PageBox
import ua.hramota.doc.TextPages
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** The PDF needs a password (not supported yet). */
class PdfPasswordException : IOException("Password required")

/** The isolated engine could not be started at all (not a problem with the file). */
class PdfEngineUnavailableException(message: String) : IOException(message)

/**
 * A PDF opened by PDFium in its own isolated process (one process per document). Pages come back
 * through shared memory; if the engine process dies (a broken or hostile file), pages stop drawing
 * and nothing else is affected.
 */
class PdfiumPages private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val binder: IBinder,
    private val sizes: FloatArray,
    val inspection: Inspection,
) : TextPages, Closeable {
    private val lock = Mutex()
    private val cache = LinkedHashMap<Long, ImageBitmap>(16, 0.75f, true)
    private var cacheBytes = 0L
    @Volatile private var closed = false

    override val pageCount: Int get() = sizes.size / 2
    override fun aspect(index: Int): Float {
        val w = sizes[2 * index]; val h = sizes[2 * index + 1]
        val a = if (w > 0f) h / w else 1.414f
        return if (a.isFinite()) a.coerceIn(0.25f, 4f) else 1.414f
    }

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until pageCount || widthPx <= 0 || !binder.isBinderAlive) return@withLock null
            val w = widthPx.coerceAtMost(MAX_WIDTH)
            val key = index.toLong() shl 32 or w.toLong()
            cache[key]?.let { return@withLock it }
            val h = (w * aspect(index)).toInt().coerceIn(1, MAX_WIDTH * 2)
            val memory = SharedMemory.create("page", w * h * 4)
            try {
                val ok = call(PdfEngineService.RENDER, { d ->
                    d.writeInt(index); d.writeInt(w); d.writeInt(h)
                    d.writeInt(1); memory.writeToParcel(d, 0)
                }) { it.readInt() } == 1
                // The engine process is not trusted: the memory must still be exactly the page's size
                if (!ok || memory.size != w * h * 4) return@withLock null
                memory.setProtect(android.system.OsConstants.PROT_READ)
                val buffer = memory.mapReadOnly()
                val bitmap = try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
                } finally {
                    SharedMemory.unmap(buffer)
                }
                bitmap.asImageBitmap().also { remember(key, it, w, h) }
            } finally {
                memory.close()
            }
        }
    }

    private fun remember(key: Long, image: ImageBitmap, w: Int, h: Int) {
        cache[key] = image
        cacheBytes += w.toLong() * h * 4
        val iter = cache.entries.iterator()
        while (cacheBytes > CACHE_BYTES && cache.size > 1 && iter.hasNext()) {
            val old = iter.next().value
            cacheBytes -= old.width.toLong() * old.height * 4
            iter.remove()
        }
    }

    /** Text of a page, for search (null if the engine is gone). */
    suspend fun text(index: Int): String? = withContext(Dispatchers.IO) {
        lock.withLock { if (closed) null else call(PdfEngineService.TEXT, { it.writeInt(index) }) { it.readString() } }
    }

    // Pages' text is asked for again and again while searching: keep it (bounded by the engine's limit per page)
    private val texts = HashMap<Int, String>()

    override suspend fun pageText(index: Int): String {
        synchronized(texts) { texts[index] }?.let { return it }
        val t = text(index)?.take(PdfEngineService.MAX_TEXT) ?: return ""
        synchronized(texts) { if (texts.size > 500) texts.clear(); texts[index] = t }
        return t
    }

    override suspend fun boxes(index: Int, start: Int, end: Int): List<PageBox> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || end <= start || index !in 0 until pageCount) return@withLock emptyList()
            val v = call(PdfEngineService.RECTS, { d -> d.writeInt(index); d.writeInt(start); d.writeInt(end - start) }) { it.createFloatArray() }
                ?: return@withLock emptyList()
            val g = PdfEngineService.GRID.toFloat()
            // The engine is not trusted: at most 4096 boxes, values kept on the page
            (0 until minOf(v.size / 4, 4096)).map { i ->
                fun f(k: Int) = (v[4 * i + k] / g).coerceIn(0f, 1f)
                PageBox(f(0), f(1), f(2), f(3))
            }
        }
    }

    override suspend fun charAt(index: Int, x: Float, y: Float): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until pageCount) return@withLock -1
            val g = PdfEngineService.GRID
            call(PdfEngineService.CHAR_AT, { d -> d.writeInt(index); d.writeInt((x * g).toInt()); d.writeInt((y * g).toInt()) }) { it.readInt() } ?: -1
        }
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? = Companion.call(binder, code, write, read)

    /** Ends the document's engine process. Never waits for it: the process may be busy or stuck. */
    override fun close() {
        if (closed) return
        closed = true
        try { context.unbindService(connection) } catch (_: Exception) {}
        cache.clear()
    }

    companion object {
        private const val MAX_WIDTH = 2048
        private const val CACHE_BYTES = 48L * 1024 * 1024
        private val executor = Executors.newSingleThreadExecutor()

        /**
         * Starts a fresh isolated engine process and opens the PDF there. Call off the main thread.
         * Throws [PdfPasswordException] for protected files, IOException if it cannot be opened.
         */
        suspend fun open(context: Context, pfd: ParcelFileDescriptor): PdfiumPages {
            val app = context.applicationContext
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) { connected.complete(service) }
                override fun onServiceDisconnected(name: ComponentName?) {}
                override fun onBindingDied(name: ComponentName?) { connected.completeExceptionally(IOException("PDF engine stopped")) }
                override fun onNullBinding(name: ComponentName?) { connected.completeExceptionally(PdfEngineUnavailableException("PDF engine unavailable")) }
            }
            val intent = Intent(app, PdfEngineService::class.java)
            // A separate isolated process for every document
            val bound = try {
                app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "doc_" + UUID.randomUUID().toString().replace("-", ""), executor, connection)
            } catch (e: SecurityException) { false }
            if (!bound) { pfd.close(); throw PdfEngineUnavailableException("PDF engine unavailable") }
            try {
                // A slow start is an error, not a reason to drop the isolation
                val binder = try {
                    withTimeout(15_000) { connected.await() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw IOException("PDF engine did not start in time")
                }
                val result = call(binder, PdfEngineService.OPEN, { d -> d.writeFileDescriptor(pfd.fileDescriptor); d.writeString(null) }) { it.readLong() }
                    ?: throw IOException("PDF engine stopped")
                if (result == -4L) throw PdfPasswordException()
                if (result <= 0L) throw IOException("Cannot open PDF ($result)")
                val sizes = call(binder, PdfEngineService.SIZES) { it.createFloatArray() } ?: FloatArray(0)
                if (sizes.isEmpty()) throw IOException("No pages")
                val info = call(binder, PdfEngineService.INSPECT) { it.createStringArray() }
                return PdfiumPages(app, connection, binder, sizes, inspection(info))
            } catch (e: Throwable) {
                try { app.unbindService(connection) } catch (_: Exception) {}
                throw e
            } finally {
                // Our copy of the file is not needed once the engine has its own (or on any failure)
                try { pfd.close() } catch (_: Exception) {}
            }
        }

        /** One call to the engine; null if its process is gone. */
        private fun <T> call(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(PdfEngineService.DESCRIPTOR)
                write(data)
                if (!binder.transact(code, data, reply, 0)) return null
                // Only "no error" is accepted; anything else from the engine is treated as a failure,
                // never turned into an exception here (a compromised engine must not crash the app)
                if (reply.readInt() != 0) return null
                read(reply)
            } catch (e: Exception) {
                null
            } finally {
                data.recycle(); reply.recycle()
            }
        }

        /** [javascript, attachments, form type, uri links, launch actions, author, creator, producer, attachment names...] */
        private fun inspection(v: Array<String?>?): Inspection {
            if (v == null || v.size < 8) return Inspection()
            fun n(i: Int) = v[i]?.toIntOrNull() ?: 0
            return Inspection(
                javascript = n(0),
                embedded = v.drop(8).filterNotNull().map { it.take(200) }.ifEmpty { if (n(1) > 0) List(minOf(n(1), 50)) { "?" } else emptyList() },
                forms = n(2) > 0,
                links = n(3),
                launchActions = n(4),
                author = v[5]?.takeIf { it.isNotBlank() }?.take(200),
            )
        }
    }
}
