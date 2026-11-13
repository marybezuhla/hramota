package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.readingText
import java.io.ByteArrayOutputStream
import java.io.File

/** New Word documents written from scratch: notes, scans, letters. LibreOffice opens them in CI (build/edited). */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class DocxWriterTest {
    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5B92.toInt()) }
        return ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun aDocumentWithEverything() {
        val bytes = DocxWriter.write(listOf(
            WBlock.Para("Протокол зборів", "Title"),
            WBlock.Para("Порядок денний", "Heading1"),
            WBlock.Para(listOf(WRun("Звичайний текст, "), WRun("жирний", bold = true), WRun(", "), WRun("маркер", highlight = true), WRun(" & <знаки>"))),
            WBlock.Para(listOf(WRun("Перший пункт")), list = "bullet"),
            WBlock.Para(listOf(WRun("Другий пункт")), list = "number"),
            WBlock.Para(listOf(WRun("Купити хліб")), checked = false),
            WBlock.Para(listOf(WRun("Подзвонити")), checked = true),
            WBlock.Para("Цитата", "Quote"),
            WBlock.Picture(png(), true, 40, 30, "Синій прямокутник"),
            WBlock.PageBreak,
            WBlock.Para(listOf(WRun("Рядок\nдругий\tз табуляцією"))),
        ), "uk-UA", "Протокол")
        File("build/edited").apply { mkdirs() }.let { File(it, "writer-everything.docx").writeBytes(bytes) }
        val doc = DocxReader.read(bytes.inputStream())
        val text = doc.readingText().map { it.text }
        assertTrue(text.toString(), text.any { it.contains("Протокол зборів") })
        assertTrue(text.any { it.contains("жирний") && it.contains("& <знаки>") })
        assertTrue(text.any { it.contains("☐ Купити хліб") })
        assertTrue(text.any { it.contains("☑ Подзвонити") })
    }

    @Test fun anEmptyDocumentStillOpens() {
        val doc = DocxReader.read(DocxWriter.write(emptyList(), "uk-UA").inputStream())
        assertEquals(0, doc.readingText().count { it.text.isNotBlank() })
    }
}
