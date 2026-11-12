package ua.hramota.doc.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** An old PowerPoint presentation saved as PPTX: the same slides, text and pictures, a file that opens. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class PptxWriterTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private fun text(d: Deck, i: Int) = d.slides[i].shapes.filterIsInstance<SlideShape.Box>().flatMap { it.paragraphs }.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }.replace(Regex("\\s+"), " ").trim()

    @Test fun pptBecomesPptx() {
        val old = PptReader.read(bytes("slides/01-project.ppt").inputStream())
        val out = ByteArrayOutputStream().also { PptxWriter.write(old, it, "uk-UA") }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "converted-01-project.pptx").writeBytes(out); File(it, "converted-01-project.pages").writeText(old.slides.size.toString()) }
        val new = PptxReader.read(out.inputStream())
        assertEquals(old.slides.size, new.slides.size)
        assertEquals(old.width, new.width, 1f)
        for (i in old.slides.indices) assertEquals("slide $i", text(old, i), text(new, i))
        assertEquals(old.slides.sumOf { s -> s.shapes.count { it is SlideShape.Picture } }, new.slides.sumOf { s -> s.shapes.count { it is SlideShape.Picture } })
        assertTrue(new.slides.all { it.frames.isNotEmpty() })
    }
}
