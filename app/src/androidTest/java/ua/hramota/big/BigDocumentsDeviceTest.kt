package ua.hramota.big

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.hramota.BigDocs
import ua.hramota.appFonts
import ua.hramota.diff.Diff
import ua.hramota.doc.Format
import ua.hramota.doc.Search
import ua.hramota.doc.TextExtractor
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.pdf.PdfiumPages
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

/**
 * Very large documents on a real Android system: a PDF of several hundred pages, a comparison
 * table of about a thousand pages, and comparing two versions of it. Times go to logcat (HramotaBig).
 */
@RunWith(AndroidJUnit4::class)
class BigDocumentsDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun log(line: String) = Log.i("HramotaBig", line)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - t0) / 1_000_000
    }

    private fun heapMb(): Long { val rt = Runtime.getRuntime(); return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) }

    /** A text PDF of [count] pages made by Android itself; the last page holds [BigDocs.NEEDLE]. */
    private fun makePdf(count: Int): File {
        val file = File(context.cacheDir, "big-$count.pdf")
        if (file.exists()) return file
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f; isAntiAlias = true }
        val r = Random(1)
        for (i in 0 until count) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            var y = 60f
            page.canvas.drawText("Сторінка ${i + 1}", 60f, 40f, paint)
            while (y < 800f) { page.canvas.drawText(BigDocs.sentence(r, 11), 60f, y, paint); y += 15f }
            if (i == count - 1) page.canvas.drawText(BigDocs.NEEDLE, 60f, 820f, paint)
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    @Test fun pdfOfSixHundredPages() = runBlocking {
        val file = makePdf(PDF_PAGES)
        val (pages, openMs) = timed { PdfiumPages.open(context, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
        try {
            assertEquals(PDF_PAGES, pages.pageCount)
            val (first, firstMs) = timed { pages.render(0, 1080) }
            val (last, lastMs) = timed { pages.render(PDF_PAGES - 1, 1080) }
            assertNotNull(first); assertNotNull(last)
            val (hit, searchMs) = timed { (0 until pages.pageCount).lastOrNull { Search.find(pages.pageText(it), BigDocs.NEEDLE).isNotEmpty() } }
            assertEquals(PDF_PAGES - 1, hit)
            log("PDF $PDF_PAGES pages, ${file.length() / 1024} KB: open $openMs ms, first page $firstMs ms, last page $lastMs ms, search all $searchMs ms, heap ${heapMb()} MB")
            assertTrue("open took $openMs ms", openMs < 15_000)
            assertTrue("search took $searchMs ms", searchMs < 120_000)
        } finally { pages.close() }
    }

    @Test fun thousandPageTable() = runBlocking {
        val bytes = BigDocs.docxTable(ROWS, 1)
        val before = heapMb()
        val (doc, readMs) = timed { DocxReader.read(ByteArrayInputStream(bytes)) }
        val (pages, layoutMs) = timed { DocxPages(doc, appFonts(context)) }
        val after = heapMb()
        val (bitmap, drawMs) = timed { pages.draw(pages.pageCount - 1, 1080) }
        val (hit, searchMs) = timed { (0 until pages.pageCount).lastOrNull { Search.find(pages.pageText(it), BigDocs.NEEDLE).isNotEmpty() } }
        val pss = Debug.getPss() / 1024
        log("DOCX table $ROWS rows: ${pages.pageCount} pages; read $readMs ms, layout $layoutMs ms, heap +${after - before} MB (PSS $pss MB), draw last $drawMs ms, search all $searchMs ms")
        assertTrue("pages: ${pages.pageCount}", pages.pageCount >= 900)
        assertNotNull(bitmap)
        assertTrue("found on $hit", hit != null && hit >= pages.pageCount - 2)
        assertTrue("open took ${readMs + layoutMs} ms", readMs + layoutMs < 60_000)
    }

    @Test fun compareThousandPages() {
        val a = TextExtractor.read(ByteArrayInputStream(BigDocs.docxTable(ROWS, 1)), Format.DOCX).map { it.text }
        val b = TextExtractor.read(ByteArrayInputStream(BigDocs.docxTable(ROWS, 2)), Format.DOCX).map { it.text }
        val (c, ms) = timed { Diff.compare(a, b) }
        log("Compare ${a.size} vs ${b.size} paragraphs: ${c.changes.size} changes in $ms ms, heap ${heapMb()} MB")
        assertFalse(c.coarse)
        assertEquals(BigDocs.changesExpected(ROWS), c.changes.size)
        assertTrue("compare took $ms ms", ms < 30_000)
    }

    companion object {
        const val PDF_PAGES = 600
        const val ROWS = 9000
    }
}
