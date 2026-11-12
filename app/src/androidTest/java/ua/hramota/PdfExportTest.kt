package ua.hramota

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.hramota.doc.PdfExport
import ua.hramota.doc.Templates
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.slides.PptxReader
import ua.hramota.doc.slides.SlidePages
import java.io.File

/** "Save as PDF" (and printing, which writes the same PDF): every page, at its own size. */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun pdfPages(file: File): List<Pair<Int, Int>> =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
        }

    @Test fun wordPages() = runBlocking {
        val pages = DocxPages(DocxReader.read(BigDocs.docxProse(400).inputStream()), appFonts(context))
        val file = File(context.cacheDir, "export-word.pdf")
        assertTrue(file.outputStream().use { PdfExport.write(pages, it) })
        val got = pdfPages(file)
        assertEquals(pages.pageCount, got.size)
        // A4 in points
        assertTrue(got.toString(), got.all { (w, h) -> w in 590..600 && h in 836..848 })
        file.delete()
    }

    @Test fun slides() = runBlocking {
        val deck = PptxReader.read(Templates.pptx("uk-UA").inputStream())
        val pages = SlidePages(deck, appFonts(context))
        val file = File(context.cacheDir, "export-slides.pdf")
        assertTrue(file.outputStream().use { PdfExport.write(pages, it) })
        val got = pdfPages(file)
        assertEquals(1, got.size)
        // 16:9 (13.33 × 7.5 inches)
        assertTrue(got.toString(), got[0].first in 955..965 && got[0].second in 535..545)
        file.delete()
    }
}
