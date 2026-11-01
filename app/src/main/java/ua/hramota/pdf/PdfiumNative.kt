package ua.hramota.pdf

import java.nio.ByteBuffer

/** PDFium calls (pdf_engine.c). Loaded and used only inside the isolated ":pdf" process. */
internal object PdfiumNative {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("hramota_pdf")
        init()
    }

    @JvmStatic external fun init()
    /** A document handle, or a negative PDFium error code (-4: password needed or wrong). */
    @JvmStatic external fun open(fd: Int, password: String?): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun pageCount(handle: Long): Int
    @JvmStatic external fun pageSizes(handle: Long): FloatArray?
    @JvmStatic external fun render(handle: Long, index: Int, buffer: ByteBuffer, width: Int, height: Int, stride: Int): Boolean
    @JvmStatic external fun inspect(handle: Long, maxPages: Int): Array<String?>?
    @JvmStatic external fun text(handle: Long, index: Int, maxChars: Int): String?
}
