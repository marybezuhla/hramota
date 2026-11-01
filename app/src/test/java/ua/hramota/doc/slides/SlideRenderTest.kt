package ua.hramota.doc.slides

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.doc.docx.Fonts
import java.io.File

/** Test presentations drawn to build/render-slides (compared with LibreOffice in CI); their text read as PowerPoint shows it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SlideRenderTest {
    private val fonts = Fonts(ApplicationProvider.getApplicationContext<android.content.Context>().assets)
    private fun dir() = File(javaClass.classLoader!!.getResource("slides")!!.toURI())

    @Test fun rendersPptx() {
        val out = File("build/render-slides").apply { mkdirs() }
        for (file in dir().listFiles { f -> f.name.endsWith(".pptx") || f.name.endsWith(".ppt") }!!) {
            val deck = file.inputStream().use { if (file.name.endsWith(".ppt")) PptReader.read(it) else PptxReader.read(it) }
            val pages = SlidePages(deck, fonts)
            for (i in 0 until pages.pageCount) {
                File(if (file.name.endsWith(".ppt")) File("build/render-slides-ppt").apply { mkdirs() } else out, "${file.nameWithoutExtension}-${i + 1}.png").outputStream().use { pages.draw(i, 1600)!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun textAsShown() = runBlocking {
        val deck = File(dir(), "01-project.pptx").inputStream().use { PptxReader.read(it) }
        assertEquals(4, deck.slides.size)
        assertEquals(960f, deck.width, 0.5f)
        val pages = SlidePages(deck, fonts)
        val first = pages.pageText(0)
        assertTrue(first, first.contains("Проєкт «Грамота»") && first.contains("Підсумки кварталу"))
        val second = pages.pageText(1)
        assertTrue(second, second.contains("•\tТаблиці Excel") || second.contains("•\tПерегляд"))
        // Title placeholders take their place from the layout: the title is near the top
        val title = deck.slides[1].shapes.filterIsInstance<SlideShape.Box>().first { b -> b.paragraphs.any { p -> p.runs.any { it.text == "Що зроблено" } } }
        assertTrue("title at ${title.y}", title.y < 100f)
        assertTrue(deck.slides[2].shapes.any { it is SlideShape.Picture })
        assertTrue(pages.pageText(3).contains("слайди"))
        assertTrue(pages.boxes(1, 0, 5).isNotEmpty())
    }

    // The same presentation saved as .ppt by LibreOffice: the same slides and text
    @Test fun pptLikePptx() = runBlocking {
        val ppt = File(dir(), "01-project.ppt").inputStream().use { PptReader.read(it) }
        val pptx = File(dir(), "01-project.pptx").inputStream().use { PptxReader.read(it) }
        assertEquals(pptx.slides.size, ppt.slides.size)
        assertEquals(pptx.width, ppt.width, 2f)
        val a = SlidePages(ppt, fonts); val b = SlidePages(pptx, fonts)
        for (i in 0 until ppt.slides.size) {
            fun words(t: String) = t.split(Regex("[\\s•–]+")).filter { it.isNotBlank() }.sorted()
            assertEquals("slide ${i + 1}", words(b.pageText(i)), words(a.pageText(i)))
        }
        assertTrue(ppt.slides[2].shapes.any { it is SlideShape.Picture })
    }
}
