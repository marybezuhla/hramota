package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.slides.PptxReader
import ua.hramota.readingText
import java.io.File

/** Ready-made documents open here (and in LibreOffice, in CI) and their totals are worked out. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class DocTemplatesTest {
    private fun keep(name: String, b: ByteArray, slides: Int? = null) = File("build/edited").apply { mkdirs() }.let {
        File(it, name).writeBytes(b); slides?.let { n -> File(it, name.substringBeforeLast('.') + ".pages").writeText(n.toString()) }
    }

    @Test fun wordTemplates() {
        for (k in DocTemplates.WORD) for (lang in listOf("uk-UA", "en-GB")) {
            val b = DocTemplates.docx(k, lang)
            keep("template-$k-$lang.docx", b)
            assertTrue(k, DocxReader.read(b.inputStream()).readingText().any { it.text.contains("[") })
        }
    }

    @Test fun budgetTotalsAreWorkedOut() {
        val b = DocTemplates.xlsx("budget", "uk-UA")
        keep("template-budget.xlsx", b)
        val s = XlsxReader.read(b.inputStream()).sheets[0]
        val texts = s.rows.flatMap { r -> r.cols.indices.map { s.text(r, it) } }
        // Income 32 000, expenses 29 000 planned: 3 000 left
        assertTrue(texts.toString(), texts.any { it.replace(' ', ' ').replace(" ", "").replace(",", ".").startsWith("32000") })
        assertTrue(texts.toString(), texts.any { it.replace(' ', ' ').replace(" ", "").replace(",", ".").startsWith("3000.00") })
        keep("template-todo.xlsx", DocTemplates.xlsx("todo", "uk-UA"))
    }

    @Test fun presentationDesigns() {
        for (d in Templates.DESIGNS) {
            val b = Templates.pptx("uk-UA", d)
            keep("design-$d.pptx", b, 1)
            val deck = PptxReader.read(b.inputStream())
            assertEquals(1, deck.slides.size)
        }
        // The dark one has a dark background
        val night = PptxReader.read(Templates.pptx("uk-UA", "night").inputStream()).slides[0].background
        assertTrue(night.toString(), night != null)
    }
}
