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

        fun closeAll() = synchronized(lock) {
            if (handle > 0) PdfiumNative.close(handle)
            handle = 0
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            synchronized(lock) {
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
                        val memory: SharedMemory? = if (data.readInt() == 1) SharedMemory.CREATOR.createFromParcel(data) else null
                        var ok = false
                        if (handle > 0 && memory != null) {
                            try {
                                val buffer = memory.mapReadWrite()
                                try {
                                    ok = PdfiumNative.render(handle, index, buffer, width, height, width * 4)
                                } finally {
                                    SharedMemory.unmap(buffer)
                                }
                            } finally {
                                memory.close()
                            }
                        }
                        reply?.writeNoException()
                        reply?.writeInt(if (ok) 1 else 0)
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
        /** Pages scanned for links by the inspector. */
        const val INSPECT_PAGES = 300
        const val MAX_TEXT = 200_000
    }
}
