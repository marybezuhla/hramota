package ua.hramota.doc.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.slides.edit.PptxEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Editing a presentation's text: changed text boxes written anew, everything else as it was. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class PptxEditTest {
    private val path = "slides/01-project.pptx"
    private fun bytes() = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private fun part(b: ByteArray, name: String): String? = ZipInputStream(ByteArrayInputStream(b)).use { z ->
        while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return String(z.readBytes()) }
        @Suppress("UNREACHABLE_CODE") null
    }

    @Test fun textIsEditedAndTheRestKept() {
        val edit = PptxEdit.open(ByteArrayInputStream(bytes()))
        assertEquals(4, edit.slideCount)
        assertTrue(edit.boxes.any { it.kind == "table" })
        val same = ByteArrayOutputStream().also { edit.save(edit.paragraphs, ByteArrayInputStream(bytes()), it) }.toByteArray()
        assertEquals(part(bytes(), "ppt/slides/slide2.xml"), part(same, "ppt/slides/slide2.xml"))

        val list = edit.paragraphs.toMutableList()
        val i = list.indexOfFirst { it.text.startsWith("Перегляд") }
        val p = list[i]
        list[i] = p.changed(text = "Нове: " + p.text, marks = (List(6) { p.marks[0] } + p.marks).mapIndexed { k, m -> if (k < 4) edit.restyle(m, bold = true) else m })
        val (a, b) = edit.split(list[i], 6)
        list[i] = a; list.add(i + 1, b)
        val out = ByteArrayOutputStream().also { edit.save(list, ByteArrayInputStream(bytes()), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "edited-slides.pptx").writeBytes(out) }
        val deck = PptxReader.read(ByteArrayInputStream(out))
        val texts = deck.slides[1].shapes.filterIsInstance<SlideShape.Box>().flatMap { it.paragraphs }.map { p2 -> p2.runs.joinToString("") { it.text } }
        assertTrue(texts.toString(), texts.contains("Нове: ") && texts.contains("Перегляд документів Word сторінками"))
        assertTrue(part(out, "ppt/slides/slide2.xml")!!.contains("<a:rPr b=\"1\"/>"))
        // Other slides untouched
        assertEquals(part(bytes(), "ppt/slides/slide1.xml"), part(out, "ppt/slides/slide1.xml"))
    }
}
