package ua.hramota.doc.docx

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Lays out every test document and draws its pages to build/render (150 dpi for A4), where CI
 * compares them with LibreOffice's rendering of the same files.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocxRenderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fonts = Fonts(context.assets)

    private fun docs(): List<File> =
        File(javaClass.classLoader!!.getResource("docs")!!.toURI()).listFiles { f -> f.name.endsWith(".docx") }!!.sortedBy { it.name }

    @Test fun rendersEveryTestDocument() {
        val out = File("build/render").apply { mkdirs() }
        val summary = StringBuilder()
        for (file in docs()) {
            val doc = file.inputStream().use { DocxReader.read(it) }
            val pages = DocxPages(doc, fonts)
            assertTrue("${file.name}: no pages", pages.pageCount > 0)
            for (i in 0 until pages.pageCount) {
                val bmp = pages.draw(i, 1240)!!
                File(out, "${file.nameWithoutExtension}-${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            summary.append("${file.name}: ${pages.pageCount} pages\n")
        }
        File(out, "pages.txt").writeText(summary.toString())
    }

    /** The bundled fonts must really be used: Carlito is narrower than the system sans font. */
    @Test fun bundledFontsAreLoaded() {
        val sample = "Постачальник зобов'язується передати товар"
        fun width(t: android.graphics.Typeface) = Paint().apply { typeface = t; textSize = 100f }.measureText(sample)
        val carlito = width(fonts.typeface(Fonts.Family.CARLITO, false, false))
        val arimo = width(fonts.typeface(Fonts.Family.ARIMO, false, false))
        File("build/render").mkdirs()
        File("build/render/fonts.txt").writeText("carlito=$carlito arimo=$arimo\n")
        assertTrue("Carlito ($carlito) should be narrower than Arimo ($arimo)", carlito < arimo * 0.95f)
    }

    @Test fun pageBreakStartsNewPage() {
        val file = docs().first { it.name.startsWith("05-") }
        val doc = file.inputStream().use { DocxReader.read(it) }
        val pages = DocxPages(doc, fonts)
        // The break comes after "Додаток 1": the specification text must be on the last page, the heading before it
        fun texts(page: LaidOutPage) = page.items.filterIsInstance<PageItem.Text>().map { it.layout.text.toString() }
        assertTrue(texts(pages.pages.last()).any { it.startsWith("Специфікація") })
        assertTrue(texts(pages.pages[pages.pageCount - 2]).any { it.startsWith("Додаток 1") })
    }

    /** Rows taller than a page break across pages and lose no line of text. */
    @Test fun tallTableRowsBreakAcrossPages() {
        val file = docs().first { it.name.startsWith("06-") }
        val doc = file.inputStream().use { DocxReader.read(it) }
        val pages = DocxPages(doc, fonts)
        assertTrue("pages: ${pages.pageCount}", pages.pageCount >= 4)
        // Every line of every cell paragraph appears exactly once across the pages
        val placed = HashMap<android.text.Layout, MutableSet<Int>>()
        for (page in pages.pages) for (t in page.items.filterIsInstance<PageItem.Text>()) {
            val set = placed.getOrPut(t.layout) { HashSet() }
            for (line in t.first..t.last) assertTrue("line placed twice", set.add(line))
        }
        for ((layout, set) in placed) assertEquals("lines of a paragraph", layout.lineCount, set.size)
        // Nothing hangs below the bottom margin
        val bottom = doc.page.height - doc.page.bottom
        for (page in pages.pages) for (t in page.items.filterIsInstance<PageItem.Text>()) {
            val h = t.layout.getLineBottom(t.last) - t.layout.getLineTop(t.first)
            assertTrue("text below the margin", (t.y + h) / DocxLayout.U * 20 <= bottom + 20)
        }
        assertTrue(pages.pages.last().items.filterIsInstance<PageItem.Text>().any { it.layout.text.toString() == "КІНЕЦЬ ТАБЛИЦІ" })
    }

    @Test fun numberingLabels() {
        val n = Numbering.Companion
        assertEquals("iv", n.format(4, "lowerRoman"))
        assertEquals("в", n.format(3, "ukrainianLower"))
        assertEquals("c", n.format(3, "lowerLetter"))
        assertEquals("•", n.bullet(""))
    }
}
