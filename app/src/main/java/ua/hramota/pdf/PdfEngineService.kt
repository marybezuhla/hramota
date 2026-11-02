package ua.hramota.pdf

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory

/**
 * PDFium in its own isolated process: no permissions, no access to the app's files or the network,
 * one process per open document (bindIsolatedService with a separate instance name). A document
 * built to break the PDF engine can at worst crash this process; the viewer then says it cannot
 * open the file. Calls arrive on Binder threads and go through one lock (PDFium is single-threaded).
 */
class PdfEngineService : Service() {
    private val binder = Engine()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        binder.closeAll()
        super.onDestroy()
    }

    private class Engine : Binder() {
        private val lock = Any()
        private var handle = 0L
        /** When the call now inside PDFium started (0 = none). */
        @Volatile private var busySince = 0L
        /** The last thing that went wrong, sent back so the app can say why a page is missing. */
        private var problem: String? = null
        /** A page drawn for fetching in parts. */
        private var kept: java.nio.ByteBuffer? = null

        init {
            // Watchdog: a file that makes PDFium loop or work for too long ends this process,
            // so the app gets an error instead of waiting forever (this process serves one document)
            Thread({
                while (true) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                    val since = busySince
                    if (since != 0L && android.os.SystemClock.elapsedRealtime() - since > CALL_LIMIT_MS) {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }, "pdf-watchdog").apply { isDaemon = true }.start()
        }

        fun closeAll() = synchronized(lock) {
            if (handle > 0) PdfiumNative.close(handle)
            handle = 0
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            synchronized(lock) {
                busySince = android.os.SystemClock.elapsedRealtime()
                try { return dispatch(code, data, reply) } finally { busySince = 0L }
            }
        }

        private fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
            run {
                when (code) {
                    OPEN -> {
                        val pfd: ParcelFileDescriptor? = data.readFileDescriptor()
                        val password = data.readString()
                        if (handle > 0) { PdfiumNative.close(handle); handle = 0 }
                        val result = pfd?.use { PdfiumNative.open(it.fd, password) } ?: -2L
                        if (result > 0) handle = result
                        reply?.writeNoException()
                        reply?.writeLong(if (result > 0) PdfiumNative.pageCount(handle).toLong() else result)
                    }
                    SIZES -> {
                        reply?.writeNoException()
                        reply?.writeFloatArray(if (handle > 0) PdfiumNative.pageSizes(handle) else FloatArray(0))
                    }
                    RENDER -> {
                        val index = data.readInt()
                        val width = data.readInt()
                        val height = data.readInt()
                        val memory: SharedMemory? = try {
                            if (data.readInt() == 1) SharedMemory.CREATOR.createFromParcel(data) else null
                        } catch (e: Exception) { problem = e.toString(); null }
                        var status = if (memory == null) R_TRANSPORT else R_FAILED
                        if (handle <= 0) status = R_NO_DOCUMENT
                        if (handle > 0 && memory != null) {
                            try {
                                val buffer = memory.mapReadWrite()
                                try {
                                    if (PdfiumNative.render(handle, index, buffer, width, height, width * 4)) status = R_OK
                                } finally {
                                    SharedMemory.unmap(buffer)
                                }
                            } catch (e: Exception) {
                                // Shared memory could not be used here: the app switches to sending pixels in parts
                                problem = e.toString(); status = R_TRANSPORT
                            } finally {
                                memory.close()
                            }
                        }
                        reply?.writeNoException()
                        reply?.writeInt(status)
                        reply?.writeString(if (status == R_OK) null else problem?.take(300))
                    }
                    RENDER_KEEP -> {
                        // Draws into memory of this process; the app then fetches it in parts (PIXELS)
                        val index = data.readInt(); val width = data.readInt(); val height = data.readInt()
                        kept = null
                        var status = R_NO_DOCUMENT
                        if (handle > 0 && width in 1..MAX_SIDE && height in 1..MAX_SIDE * 2) {
                            status = try {
                                val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4)
                                if (PdfiumNative.render(handle, index, buffer, width, height, width * 4)) { kept = buffer; R_OK } else R_FAILED
                            } catch (e: Throwable) { problem = e.toString(); R_FAILED }
                        }
                        reply?.writeNoException()
                        reply?.writeInt(status)
                    }
                    PIXELS -> {
                        val offset = data.readInt(); val length = data.readInt().coerceIn(0, CHUNK)
                        val buffer = kept
                        reply?.writeNoException()
                        if (buffer == null || offset < 0 || offset > buffer.capacity()) { reply?.writeByteArray(ByteArray(0)); return true }
                        val n = minOf(length, buffer.capacity() - offset)
                        val out = ByteArray(n)
                        buffer.position(offset); buffer.get(out, 0, n)
                        if (offset + n >= buffer.capacity()) kept = null
                        reply?.writeByteArray(out)
                    }
                    INSPECT -> {
                        reply?.writeNoException()
                        reply?.writeStringArray(if (handle > 0) PdfiumNative.inspect(handle, INSPECT_PAGES) else null)
                    }
                    TEXT -> {
                        val index = data.readInt()
                        reply?.writeNoException()
                        reply?.writeString(if (handle > 0) PdfiumNative.text(handle, index, MAX_TEXT) else null)
                    }
                    RECTS -> {
                        val index = data.readInt(); val start = data.readInt(); val count = data.readInt()
                        reply?.writeNoException()
                        reply?.writeFloatArray(if (handle > 0) PdfiumNative.rects(handle, index, start, count.coerceIn(0, MAX_TEXT), GRID, GRID) else null)
                    }
                    CHAR_AT -> {
                        val index = data.readInt(); val x = data.readInt(); val y = data.readInt()
                        reply?.writeNoException()
                        reply?.writeInt(if (handle > 0) PdfiumNative.charAt(handle, index, x, y, GRID, GRID) else -1)
                    }
                    SAVE_NOTES -> {
                        // A copy with the notes as highlights, written to the file the app passes
                        val out: ParcelFileDescriptor? = data.readFileDescriptor()
                        val n = data.readInt().coerceIn(0, 5000)
                        val pages = IntArray(n); val starts = IntArray(n); val counts = IntArray(n); val colors = IntArray(n)
                        val texts = arrayOfNulls<String>(n)
                        for (i in 0 until n) { pages[i] = data.readInt(); starts[i] = data.readInt(); counts[i] = data.readInt(); colors[i] = data.readInt(); texts[i] = data.readString() }
                        val ok = out?.use { handle > 0 && PdfiumNative.saveNotes(handle, it.fd, pages, starts, counts, colors, texts) } ?: false
                        reply?.writeNoException()
                        reply?.writeInt(if (ok) R_OK else R_FAILED)
                    }
                    ANNOTS -> {
                        val index = data.readInt()
                        reply?.writeNoException()
                        reply?.writeInt(if (handle > 0) PdfiumNative.annotCount(handle, index) else -1)
                    }
                    CLOSE -> {
                        if (handle > 0) PdfiumNative.close(handle)
                        handle = 0
                        reply?.writeNoException()
                    }
                    else -> return false
                }
            }
            return true
        }
    }

    companion object {
        const val DESCRIPTOR = "ua.hramota.pdf.Engine"
        const val OPEN = IBinder.FIRST_CALL_TRANSACTION
        const val SIZES = IBinder.FIRST_CALL_TRANSACTION + 1
        const val RENDER = IBinder.FIRST_CALL_TRANSACTION + 2
        const val INSPECT = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TEXT = IBinder.FIRST_CALL_TRANSACTION + 4
        const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 5
        const val RECTS = IBinder.FIRST_CALL_TRANSACTION + 6
        const val CHAR_AT = IBinder.FIRST_CALL_TRANSACTION + 7
        const val RENDER_KEEP = IBinder.FIRST_CALL_TRANSACTION + 8
        const val PIXELS = IBinder.FIRST_CALL_TRANSACTION + 9
        const val SAVE_NOTES = IBinder.FIRST_CALL_TRANSACTION + 10
        const val ANNOTS = IBinder.FIRST_CALL_TRANSACTION + 11
        /** Render results. */
        const val R_OK = 0
        const val R_FAILED = 1
        const val R_TRANSPORT = 2
        const val R_NO_DOCUMENT = 3
        /** Bytes sent per PIXELS call (well under the Binder transaction limit). */
        const val CHUNK = 256 * 1024
        const val MAX_SIDE = 2048
        /** Positions on a page are exchanged on a GRID x GRID raster of the whole page (fractions × GRID). */
        const val GRID = 100_000
        /** Pages scanned for links by the inspector. */
        const val INSPECT_PAGES = 300
        const val MAX_TEXT = 200_000
        /** Longest a single PDFium call may take before the engine process ends itself. */
        const val CALL_LIMIT_MS = 20_000L
    }
}
