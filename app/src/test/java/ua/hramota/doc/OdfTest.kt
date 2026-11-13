package ua.hramota.doc

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.doc.docx.Block
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.Fonts
import ua.hramota.doc.odf.OdpReader
import ua.hramota.doc.odf.OdsReader
import ua.hramota.doc.odf.OdtReader
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.slides.PptxReader
import ua.hramota.doc.slides.SlidePages
import ua.hramota.readingText
import java.io.File

/**
 * OpenDocument files (made by LibreOffice from the Word, Excel and PowerPoint test files) hold the
 * same text, cells and slides as their originals; their first pages are kept in build/screens.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OdfTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private val fonts get() = Fonts(ApplicationProvider.getApplicationContext<android.app.Application>().assets)
    private fun keep(name: String, page: android.graphics.Bitmap) = File("build/screens").apply { mkdirs() }.let { d -> File(d, name).outputStream().use { page.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    @Test fun textDocuments(): Unit = runBlocking {
        for (n in listOf("02-paragraphs", "03-lists", "04-tables")) {
            val odt = OdtReader.read(bytes("odf/$n.odt").inputStream())
            val docx = DocxReader.read(bytes("docs/$n.docx").inputStream())
            val a = odt.readingText().map { norm(it.text) }.filter { it.isNotEmpty() }
            val b = docx.readingText().map { norm(it.text) }.filter { it.isNotEmpty() }
            // Every paragraph of the original is there (list numbers may be written differently)
            val missing = b.map { it.trimStart { c -> !c.isLetterOrDigit() } }.filter { p -> a.none { it.contains(p.takeLast(40)) } }
            assertTrue("$n: ${missing.take(3)}", missing.size <= b.size / 10)
            if (n == "04-tables") assertTrue(odt.blocks.any { it is Block.Table })
            val pages = DocxPages(odt, fonts)
            pages.render(0, 800)?.let { keep("odt-$n.png", it.asAndroidBitmap()) }
            pages.close()
        }
    }

    @Test fun spreadsheet() {
        val ods = OdsReader.read(bytes("odf/01-budget.ods").inputStream())
        val xlsx = XlsxReader.read(bytes("sheets/01-budget.xlsx").inputStream())
        assertEquals(xlsx.sheets.map { it.name }, ods.sheets.map { it.name })
        val o = ods.sheets[0]; val x = xlsx.sheets[0]
        // The text of the first rows is the same
        for (r in 0 until 6) {
            val xr = x.row(r) ?: continue
            for (k in xr.cols.indices) {
                val c = xr.cols[k]
                val or = o.row(r)!!; val ok = or.find(c)
                assertTrue("row $r col $c", ok >= 0)
                assertEquals(norm(x.text(xr, k)).replace(' ', ' ').filter { it.isLetterOrDigit() }, norm(o.text(or, ok)).replace(' ', ' ').filter { it.isLetterOrDigit() })
            }
        }
    }

    @Test fun presentation(): Unit = runBlocking {
        val odp = OdpReader.read(bytes("odf/01-project.odp").inputStream())
        val pptx = PptxReader.read(bytes("slides/01-project.pptx").inputStream())
        assertEquals(pptx.slides.size, odp.slides.size)
        val text = { d: ua.hramota.doc.slides.Deck -> d.slides.flatMap { s -> s.shapes.filterIsInstance<ua.hramota.doc.slides.SlideShape.Box>().flatMap { it.paragraphs } }.joinToString(" ") { p -> p.runs.joinToString("") { it.text } } }
        val words = norm(text(pptx)).split(' ').filter { it.length > 3 }.toSet()
        val got = norm(text(odp))
        assertTrue(words.filter { it !in got }.take(5).toString(), words.count { it in got } >= words.size * 9 / 10)
        val pages = SlidePages(odp, fonts)
        pages.render(0, 800)?.let { keep("odp-01-project.png", it.asAndroidBitmap()) }
    }
}
