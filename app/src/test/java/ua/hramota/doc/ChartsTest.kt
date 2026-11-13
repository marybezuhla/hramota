package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.slides.PptxReader
import ua.hramota.doc.slides.SlideShape
import java.io.File

/** Charts in slides and sheets, drawn from their saved values (pictures kept in build/screens for review). */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChartsTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private fun keep(name: String, png: ByteArray) = File("build/screens").apply { mkdirs() }.let { File(it, name).writeBytes(png) }

    @Test fun slideCharts() {
        val deck = PptxReader.read(bytes("charts/charts.pptx").inputStream())
        val charts = deck.slides[0].shapes.filterIsInstance<SlideShape.Picture>().filter { it.key.startsWith("chart:") }
        assertEquals(2, charts.size)
        for ((i, c) in charts.withIndex()) {
            val png = deck.media[c.key]!!
            assertTrue(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) != null)
            keep("chart-slide-$i.png", png)
        }
    }

    @Test fun sheetChartsReadTheirCells() {
        val wb = XlsxReader.read(bytes("charts/charts.xlsx").inputStream())
        val pics = wb.charts[0]!!
        assertEquals(1, pics.size)
        keep("chart-sheet.png", pics[0])
        // Drawn: not a blank picture
        val b = android.graphics.BitmapFactory.decodeByteArray(pics[0], 0, pics[0].size)
        var coloured = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) { val p = b.getPixel(x, y); if (((p shr 16) and 0xFF) < 120 && (p and 0xFF) > 120) coloured++ }
        assertTrue("coloured: $coloured", coloured > 20)
    }
}
