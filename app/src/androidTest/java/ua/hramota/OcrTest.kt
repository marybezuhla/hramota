package ua.hramota

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.hramota.ink.InkPoint
import ua.hramota.ink.InkText
import ua.hramota.ink.Stroke
import ua.hramota.ocr.Reflow
import ua.hramota.ocr.Scans
import ua.hramota.ocr.TextRecognizer
import java.io.File

/** Text recognition on the phone: a printed page, the scanner's PDF, and letters drawn as strokes. */
@RunWith(AndroidJUnit4::class)
class OcrTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun page(lines: List<String>): Bitmap {
        val bmp = Bitmap.createBitmap(1200, 200 + 70 * lines.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f; typeface = Typeface.SERIF }
        lines.forEachIndexed { i, l -> c.drawText(l, 80f, 120f + 70 * i, p) }
        return bmp
    }

    @Test fun aPrintedPageIsRead(): Unit = runBlocking {
        val lines = TextRecognizer.get(context).recognize(page(listOf("Привіт, світе! Їжак і ґудзик.", "Київ, 13 листопада 2026 року")))
        val text = Reflow.paragraphs(lines).joinToString("\n")
        assertTrue(text, text.contains("Їжак") && text.contains("ґудзик") && text.contains("2026"))
    }

    @Test fun theScannersPdfHasEveryPage(): Unit = runBlocking {
        val p1 = Scans.save(context, page(listOf("Перша сторінка")))
        val p2 = Scans.save(context, page(listOf("Друга сторінка")))
        for (p in listOf(p1, p2)) { p.lines = TextRecognizer.get(context).recognize(android.graphics.BitmapFactory.decodeFile(p.file.path)); p.text = Reflow.paragraphs(p.lines).joinToString("\n\n") }
        val file = File(context.cacheDir, "scan-test.pdf")
        file.outputStream().use { Scans.pdf(listOf(p1, p2), it) }
        val n = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
        assertEquals(2, n)
        file.delete(); p1.file.delete(); p2.file.delete()
    }

    /** Printed capitals drawn as strokes, as a finger would: «ТАК». */
    @Test fun printedLettersWrittenByHandAreRead(): Unit = runBlocking {
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, t: Long) = Stroke((0..10).map { i -> InkPoint(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, t + i * 8L, 0.5f) })
        val h = 40f
        val strokes = listOf(
            // Т
            line(10f, 10f, 40f, 10f, 0), line(25f, 10f, 25f, 10f + h, 100),
            // А
            line(50f, 10f + h, 65f, 10f, 200), line(65f, 10f, 80f, 10f + h, 300), line(56f, 10f + h * 0.6f, 74f, 10f + h * 0.6f, 400),
            // К
            line(90f, 10f, 90f, 10f + h, 500), line(115f, 10f, 90f, 10f + h * 0.55f, 600), line(95f, 10f + h * 0.45f, 117f, 10f + h, 700),
        )
        val text = InkText.read(context, strokes, cyrillic = true)
        assertTrue("«$text»", text.uppercase().contains("ТАК"))
    }
}

/** A note as PDF: pages of A4, a long note on more than one. */
@RunWith(AndroidJUnit4::class)
class NotePdfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aLongNoteTakesPages() {
        val blocks = (1L..120L).map { ua.hramota.notebook.NBlock.Text(it, "Рядок номер $it, досить довгий, щоб займати місце на сторінці.") }
        val note = ua.hramota.notebook.Note(ua.hramota.notebook.NoteMeta("pdf-test", title = "Довгий запис"), blocks)
        val file = File(context.cacheDir, "note.pdf")
        file.outputStream().use { ua.hramota.notebook.NoteExport.pdf(context, note, it) }
        val sizes = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } } }
        assertTrue(sizes.toString(), sizes.size >= 3 && sizes.all { it == 595 to 842 })
        file.delete()
    }
}
