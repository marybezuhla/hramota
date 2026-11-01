package ua.hramota.doc.docx

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.doc.Search
import java.io.File

/** Text of laid-out DOCX pages: what search and selection rely on. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocxTextTest {
    private val fonts = Fonts(ApplicationProvider.getApplicationContext<android.content.Context>().assets)
    private fun pages(name: String) =
        DocxPages(File(javaClass.classLoader!!.getResource("docs/$name")!!.toURI()).inputStream().use { DocxReader.read(it) }, fonts)

    @Test fun searchFindsTextAndBoxesSitOnIt() = runBlocking {
        val p = pages("05-flow.docx")
        val text = p.pageText(0)
        assertTrue(text.startsWith("Договір поставки № 12"))
        val hit = Search.find(text, "розділ договору").first()
        val boxes = p.boxes(0, hit.first, hit.last + 1)
        assertTrue(boxes.isNotEmpty())
        for (b in boxes) assertTrue("$b", b.left in 0f..1f && b.right in b.left..1f && b.top in 0f..1f && b.bottom in b.top..1f)
        // The middle of the first box points back into the match
        val b = boxes.first()
        val c = p.charAt(0, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
        assertTrue("charAt $c not in ${hit}", c in hit)
    }

    @Test fun tableCellsAreSearchable() = runBlocking {
        val p = pages("04-tables.docx")
        assertEquals(1, Search.find(p.pageText(0), "степлер настільний").size)
    }

    @Test fun nothingOutsideText() = runBlocking {
        val p = pages("01-fonts.docx")
        assertEquals(-1, p.charAt(0, 0.99f, 0.99f))
        assertTrue(p.boxes(0, 5, 5).isEmpty())
        assertTrue(p.boxes(99, 0, 3).isEmpty())
    }
}
