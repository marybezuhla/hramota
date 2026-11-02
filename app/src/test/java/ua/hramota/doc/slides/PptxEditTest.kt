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

    /** The test presentation with slide 2 changed by [change]. */
    private fun withSlide2(change: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(bytes())).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val data = zin.readBytes()
                    z.putNextEntry(java.util.zip.ZipEntry(e.name))
                    z.write(if (e.name == "ppt/slides/slide2.xml") change(String(data)).toByteArray() else data)
                    z.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test fun animatedParagraphsAreNotSplitAndQuotedAttributesStayValid() {
        val src = withSlide2 { x ->
            x.replace("<a:r><a:t>Перегляд", "<a:r><a:rPr lang='uk-UA' b='0'/><a:t>Перегляд")
                .replace("</p:sld>", "<p:timing><p:bldLst><p:bldP spid=\"3\" grpId=\"0\" build=\"p\"/></p:bldLst></p:timing></p:sld>")
        }
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        val i = edit.paragraphs.indexOfFirst { it.text.startsWith("Перегляд") }
        val p = edit.paragraphs[i]
        assertTrue(!edit.canSplit(p))
        assertTrue(!edit.canJoin(edit.paragraphs[i], edit.paragraphs[i + 1]))
        val list = edit.paragraphs.toMutableList()
        list[i] = p.changed(marks = p.marks.map { edit.restyle(it, bold = true) })
        val out = ByteArrayOutputStream().also { edit.save(list, ByteArrayInputStream(src), it) }.toByteArray()
        val x = part(out, "ppt/slides/slide2.xml")!!
        // One "b" attribute, the new one
        assertTrue(x, x.contains("<a:rPr lang=\"uk-UA\" b=\"1\"/>"))
        PptxReader.read(ByteArrayInputStream(out))
    }
}
