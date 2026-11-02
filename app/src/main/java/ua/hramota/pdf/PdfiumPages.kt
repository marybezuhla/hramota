package ua.hramota.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /** Opens the file again for the phone's own PDF engine, used for a page the isolated engine cannot draw. */
    private val reopen: (() -> ParcelFileDescriptor?)?,
) : TextPages, Closeable {
    private var system: ua.hramota.doc.PdfSource? = null
    private var systemFailed = false
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

    /** True once shared memory failed for this document; pages then come in parts over Binder. */
    @Volatile private var inParts = false
    /** The isolated engine died on this document: nothing from it is tried anywhere else. */
    @Volatile private var engineDied = false
    /** Why a page could not be drawn; shown on the page (screen state, so it appears as soon as it is known). */
    override var problem: String? by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    override suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until pageCount || widthPx <= 0) return@withLock null
            if (engineDied || !binder.isBinderAlive) { engineDied = true; problem = "engine stopped"; return@withLock null }
            val w = widthPx.coerceAtMost(MAX_WIDTH)
            val key = index.toLong() shl 32 or w.toLong()
            cache[key]?.let { return@withLock it }
            val h = (w * aspect(index)).toInt().coerceIn(1, MAX_WIDTH * 2)
            val bitmap = (if (inParts) null else viaSharedMemory(index, w, h)) ?: viaParts(index, w, h)
            if (bitmap == null && !binder.isBinderAlive) engineDied = true
            // The phone's own engine only for a page the living isolated engine could not draw on this
            // phone; a file that crashed the isolated engine is never handed to an engine in this process
            bitmap?.asImageBitmap()?.also { remember(key, it, w, h) }
                ?: (if (engineDied) null else viaSystem(index, w)?.also { remember(key, it, w, h) })
        }
    }

    /**
     * The page from the phone's own PDF engine, when the isolated one cannot draw it on this phone.
     * Search and text still come from the isolated engine.
     */
    private suspend fun viaSystem(index: Int, w: Int): ImageBitmap? {
        if (systemFailed) return null
        val s = system ?: try {
            reopen?.invoke()?.let { ua.hramota.doc.PdfSource.open(it) }?.also { system = it }
        } catch (e: Exception) { null } ?: run { systemFailed = true; return null }
        return try { s.render(index, w) } catch (e: Exception) { problem = "system: ${e.javaClass.simpleName}"; null }
    }

    private fun viaSharedMemory(index: Int, w: Int, h: Int): Bitmap? {
        val memory = try { SharedMemory.create("page", w * h * 4) } catch (e: Exception) { inParts = true; problem = e.toString(); return null }
        try {
            val result = call(PdfEngineService.RENDER, { d ->
                d.writeInt(index); d.writeInt(w); d.writeInt(h)
                d.writeInt(1); memory.writeToParcel(d, 0)
            }) { it.readInt() to it.readString() }
            if (result == null) { engineDied = !binder.isBinderAlive; problem = "engine stopped"; return null }
            when (result.first) {
                PdfEngineService.R_OK -> Unit
                PdfEngineService.R_TRANSPORT -> { inParts = true; problem = result.second; return null }
                else -> { problem = result.second ?: "render ${result.first}"; return null }
            }
            // The engine process is not trusted: the memory must still hold the whole page
            if (memory.size < w * h * 4) { inParts = true; problem = "memory ${memory.size}"; return null }
            val buffer = memory.mapReadOnly()
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffer.limit(w * h * 4); it.copyPixelsFromBuffer(buffer) }
            } finally {
                SharedMemory.unmap(buffer)
            }
        } catch (e: Exception) {
            inParts = true; problem = e.toString(); return null
        } finally {
            memory.close()
        }
    }

    /** For tests: use the transfer in parts from now on. */
    internal fun useParts() { inParts = true; cache.clear(); cacheBytes = 0 }

    /** The page drawn in the engine's own memory and fetched in parts: slower, works everywhere. */
    private fun viaParts(index: Int, w: Int, h: Int): Bitmap? {
        val status = call(PdfEngineService.RENDER_KEEP, { d -> d.writeInt(index); d.writeInt(w); d.writeInt(h) }) { it.readInt() }
        if (status == null) { engineDied = !binder.isBinderAlive; problem = "engine stopped"; return null }
        if (status != PdfEngineService.R_OK) { problem = "parts: $status"; return null }
        val total = w * h * 4
        val buffer = java.nio.ByteBuffer.allocate(total)
        while (buffer.position() < total) {
            val part = call(PdfEngineService.PIXELS, { d -> d.writeInt(buffer.position()); d.writeInt(PdfEngineService.CHUNK) }) { it.createByteArray() }
            // An empty or oversized part from the engine ends the page
            if (part == null || part.isEmpty() || part.size > total - buffer.position()) { problem = "pixels"; return null }
            buffer.put(part)
        }
        buffer.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
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

    /**
     * Writes a copy of the PDF with [notes] as highlight annotations (their words as the comment) to
     * [out]. The engine does the writing in its own process; the open document is not changed.
     */
    suspend fun saveNotes(notes: List<ua.hramota.data.Note>, colors: (Int) -> Int, out: android.os.ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || engineDied) return@withLock false
            // Notes on pages only (one page each), longest call allowed for a big file
            val list = notes.filter { it.block == it.endBlock && it.end > it.start }.take(5000)
            call(PdfEngineService.SAVE_NOTES, { d ->
                d.writeFileDescriptor(out.fileDescriptor)
                d.writeInt(list.size)
                // Each note's words kept short: the whole request must fit one message between processes
                for (n in list) { d.writeInt(n.block); d.writeInt(n.start); d.writeInt(n.end - n.start); d.writeInt(colors(n.color)); d.writeString(n.text.take(if (list.size > 200) 200 else 2000).ifEmpty { null }) }
            }) { it.readInt() } == PdfEngineService.R_OK
        }
    }

    /** How many annotations a page has (for tests). */
    suspend fun annotations(index: Int): Int = withContext(Dispatchers.IO) {
        lock.withLock { if (closed) -1 else call(PdfEngineService.ANNOTS, { it.writeInt(index) }) { it.readInt() } ?: -1 }
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
        try { system?.close() } catch (_: Exception) {}
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
        suspend fun open(context: Context, pfd: ParcelFileDescriptor, reopen: (() -> ParcelFileDescriptor?)? = null): PdfiumPages {
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
                return PdfiumPages(app, connection, binder, sizes, inspection(info), reopen)
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
