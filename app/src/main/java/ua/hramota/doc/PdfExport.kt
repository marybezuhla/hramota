package ua.hramota.doc

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Printing and "Save as PDF": the pages drawn at their own size into a PDF (text stays text). Printing
 * goes through the phone's own print window; Hramota itself still has no access to the network.
 */
object PdfExport {
    /** Waits until a document still being laid out has all its pages. */
    suspend fun allPages(pages: Pages) { while (!pages.complete) delay(100) }

    /** Writes every page into a PDF. Returns false if stopped. Call off the main thread. */
    suspend fun write(pages: PrintPages, out: OutputStream): Boolean {
        allPages(pages)
        // Laying out stopped early (the window closed, too little memory): no PDF with pages missing
        if (!pages.whole) return false
        val doc = PdfDocument()
        try {
            for (i in 0 until pages.pageCount) {
                if (!coroutineContext.isActive) return false
                val (w, h) = pages.pointSize(i)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w.roundToInt().coerceIn(1, 14400), h.roundToInt().coerceIn(1, 14400), i + 1).create())
                pages.drawInto(i, page.canvas)
                doc.finishPage(page)
            }
            withContext(Dispatchers.IO) { doc.writeTo(out); out.flush() }
            return true
        } finally {
            doc.close()
        }
    }

    /**
     * Opens the phone's print window for [name]. [pdf] gives a PDF file as it is (printed whole); else
     * [pages] are drawn into one.
     */
    fun print(context: Context, scope: CoroutineScope, name: String, pages: Pages, pdf: (() -> InputStream?)?) {
        val manager = context.getSystemService(PrintManager::class.java) ?: return
        val adapter = object : PrintDocumentAdapter() {
            var job: Job? = null
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
                val info = PrintDocumentInfo.Builder(safeName(name)).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .apply { if (pages.complete) setPageCount(pages.pageCount) }.build()
                callback.onLayoutFinished(info, old != new)
            }

            override fun onWrite(range: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
                // The print window is answered exactly once, whatever happens (stopped, failed, done)
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                fun answer(ok: Boolean) {
                    if (!answered.compareAndSet(false, true)) return
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        when {
                            cancel.isCanceled -> callback.onWriteCancelled()
                            ok -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                            else -> callback.onWriteFailed(null)
                        }
                    }
                }
                job = scope.launch(Dispatchers.Default) {
                    val ok = try {
                        FileOutputStream(destination.fileDescriptor).use { out ->
                            if (pdf != null) withContext(Dispatchers.IO) { pdf()?.use { it.copyTo(out); true } ?: false }
                            else write(pages as PrintPages, out)
                        }
                    } catch (e: Throwable) { false }
                    answer(ok)
                }
                job?.invokeOnCompletion { answer(false) }
                cancel.setOnCancelListener { job?.cancel() }
            }

            override fun onFinish() { job?.cancel() }
        }
        try { manager.print(safeName(name), adapter, null) } catch (e: Exception) {}
    }

    /** What printing can take: a PDF as it is, or pages Hramota draws. */
    fun printable(pages: Pages?, isPdf: Boolean) = pages != null && (isPdf || pages is PrintPages)

    private fun safeName(name: String) = name.filter { it >= ' ' }.take(100).ifEmpty { "document" }
}
