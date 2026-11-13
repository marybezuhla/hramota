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

    @Test fun commentsAreAddedToSlides() {
        val edit = PptxEdit.open(ByteArrayInputStream(bytes()))
        edit.setComments(0, listOf("Перший <коментар>", "Другий & ще"))
        edit.setComments(2, listOf("Третій"))
        val out = ByteArrayOutputStream().also { edit.save(edit.paragraphs, ByteArrayInputStream(bytes()), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "slides-comments.pptx").writeBytes(out) }
        val deck = PptxReader.read(ByteArrayInputStream(out))
        assertEquals(listOf("Hramota: Перший <коментар>", "Hramota: Другий & ще"), deck.comments[0])
        assertEquals(listOf("Hramota: Третій"), deck.comments[2])
        assertTrue(part(out, "ppt/commentAuthors.xml")!!.contains("lastIdx=\"3\""))
        // No date is written with them
        assertTrue(!part(out, "ppt/comments/comment1.xml")!!.contains("dt="))
        // Added to again: into the same parts, the numbers going on
        val again = PptxEdit.open(ByteArrayInputStream(out))
        again.setComments(0, listOf("Ще один"))
        val out2 = ByteArrayOutputStream().also { again.save(again.paragraphs, ByteArrayInputStream(out), it) }.toByteArray()
        assertEquals(3, PptxReader.read(ByteArrayInputStream(out2)).comments[0]?.size)
        assertTrue(part(out2, "ppt/commentAuthors.xml")!!.contains("lastIdx=\"4\""))
        assertEquals(1, Regex("<p:cmAuthor ").findAll(part(out2, "ppt/commentAuthors.xml")!!).count())
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

    private fun texts(b: ByteArray) = PptxReader.read(ByteArrayInputStream(b)).slides.map { sl ->
        sl.shapes.filterIsInstance<SlideShape.Box>().flatMap { it.paragraphs }.joinToString(" | ") { p2 -> p2.runs.joinToString("") { it.text } }
    }

    private fun names(b: ByteArray): Set<String> = ZipInputStream(ByteArrayInputStream(b)).use { z ->
        val out = HashSet<String>(); while (true) { val e = z.nextEntry ?: break; out += e.name }; out
    }

    /** Types the text into a slide's first [n] empty paragraphs. */
    private fun fill(edit: PptxEdit, list: MutableList<ua.hramota.doc.slides.edit.SlidePara>, slide: Int, vararg text: String) {
        var k = 0
        for (i in list.indices) {
            if (k >= text.size) break
            val p = list[i]
            if (edit.boxes[p.box].slide != slide || !p.editable) continue
            list[i] = p.changed(text = text[k], marks = List(text[k].length) { p.typing }); k++
        }
    }

    @Test fun aNewPresentationGetsSlides() {
        val bytes = ua.hramota.doc.Templates.pptx("uk-UA")
        val edit = PptxEdit.open(ByteArrayInputStream(bytes))
        assertEquals(1, edit.slideCount)
        val list = edit.paragraphs.toMutableList()
        fill(edit, list, 0, "Звіт громади", "Листопад 2026")
        val (second, paras) = edit.addSlide(0, "uk-UA")
        list += paras
        fill(edit, list, second, "Що зроблено", "Світло в школі")
        val (third, more) = edit.addSlide(second, "uk-UA")
        list += more
        fill(edit, list, third, "Далі")
        val out = ByteArrayOutputStream().also { edit.save(list, listOf(0, second, third), ByteArrayInputStream(bytes), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "new-presentation.pptx").writeBytes(out); File(it, "new-presentation.pages").writeText("3") }
        val t = texts(out)
        assertEquals(t.toString(), 3, t.size)
        assertTrue(t[0], t[0].startsWith("Звіт громади") && t[0].contains("Листопад 2026"))
        assertTrue(t[1], t[1].startsWith("Що зроблено") && t[1].contains("Світло в школі"))
        assertTrue(t[2], t[2].startsWith("Далі"))
        // The new slides follow "Title and Content"; their language is the phone's
        assertTrue(part(out, "ppt/slides/_rels/slide2.xml.rels")!!.contains("slideLayout2.xml"))
        assertTrue(part(out, "ppt/slides/slide2.xml")!!.contains("lang=\"uk-UA\""))
        assertTrue(part(out, "[Content_Types].xml")!!.contains("/ppt/slides/slide3.xml"))
    }

    @Test fun slidesAreMovedAddedAndTakenOut() {
        val src = bytes()
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        val list = edit.paragraphs.toMutableList()
        val (added, paras) = edit.addSlide(0, "uk-UA")
        list += paras
        fill(edit, list, added, "Новий слайд")
        // Slide 3 (the one with the picture) taken out; slide 4 before slide 2; the new one after slide 1
        val kept = list.filter { edit.boxes[it.box].slide != 2 }
        val order = listOf(0, added, 3, 1)
        val arranged = order.flatMap { s -> kept.filter { edit.boxes[it.box].slide == s } }
        assertTrue(edit.canRemove(2))
        val out = ByteArrayOutputStream().also { edit.save(arranged, order, ByteArrayInputStream(src), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "restructured-slides.pptx").writeBytes(out); File(it, "restructured-slides.pages").writeText("4") }
        val t = texts(out)
        assertEquals(t.toString(), 4, t.size)
        assertTrue(t[0], t[0].startsWith("Проєкт «Грамота»"))
        assertTrue(t[1], t[1].startsWith("Новий слайд"))
        assertTrue(t[2], t[2].startsWith("Таблиця"))
        assertTrue(t[3], t[3].startsWith("Що зроблено"))
        // What only the slide taken out used is gone from the file, and from its lists
        val n = names(out)
        assertTrue(n.toString(), "ppt/slides/slide3.xml" !in n && "ppt/slides/_rels/slide3.xml.rels" !in n && "ppt/media/image1.png" !in n)
        val ct = part(out, "[Content_Types].xml")!!
        assertTrue(!ct.contains("/ppt/slides/slide3.xml\""))
        assertTrue(!part(out, "ppt/_rels/presentation.xml.rels")!!.contains("slides/slide3.xml"))
        // The new slide took a free name and is listed
        val pres = part(out, "ppt/presentation.xml")!!
        assertEquals(4, Regex("<p:sldId ").findAll(pres).count())
        // Untouched slides are as they were
        assertEquals(part(src, "ppt/slides/slide4.xml"), part(out, "ppt/slides/slide4.xml"))
    }

    @Test fun aSlideLinkedToIsNotTakenOut() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(bytes())).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var data = zin.readBytes()
                    if (e.name == "ppt/slides/_rels/slide1.xml.rels") data = String(data).replace("</Relationships>",
                        "<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slide2.xml\"/></Relationships>").toByteArray()
                    z.putNextEntry(java.util.zip.ZipEntry(e.name)); z.write(data); z.closeEntry()
                }
            }
        }
        val edit = PptxEdit.open(ByteArrayInputStream(out.toByteArray()))
        assertTrue(!edit.canRemove(1))
        assertTrue(edit.canRemove(2))
        // Once the slide with the link is taken out too, it can go
        assertTrue(edit.canRemove(1, listOf(2, 3)))
    }

    @Test fun theOutlineViewDoesNotHoldSlidesBack() {
        // PowerPoint's outline view lists every slide in viewProps.xml, through links of its own
        val ns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(bytes())).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var data = zin.readBytes()
                    if (e.name == "ppt/viewProps.xml") data = String(data).replace(Regex("</p:viewPr>\\s*$"),
                        "<p:outlineViewPr><p:cViewPr><p:origin x=\"0\" y=\"0\"/></p:cViewPr><p:sldLst><p:sld r:id=\"rId1\" collapse=\"1\"/><p:sld r:id=\"rId2\" collapse=\"1\"/></p:sldLst></p:outlineViewPr></p:viewPr>").toByteArray()
                    z.putNextEntry(java.util.zip.ZipEntry(e.name)); z.write(data); z.closeEntry()
                }
                z.putNextEntry(java.util.zip.ZipEntry("ppt/_rels/viewProps.xml.rels"))
                z.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$ns/slide\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId2\" Type=\"$ns/slide\" Target=\"slides/slide3.xml\"/></Relationships>").toByteArray())
                z.closeEntry()
            }
        }
        val src = out.toByteArray()
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        assertTrue(edit.canRemove(2))
        val order = listOf(0, 1, 3)
        val saved = ByteArrayOutputStream().also { edit.save(edit.paragraphs.filter { edit.boxes[it.box].slide != 2 }, order, ByteArrayInputStream(src), it) }.toByteArray()
        val v = part(saved, "ppt/_rels/viewProps.xml.rels")!!
        assertTrue(v, v.contains("slide1.xml") && !v.contains("slide3.xml"))
        val vx = part(saved, "ppt/viewProps.xml")!!
        assertTrue(vx, vx.contains("r:id=\"rId1\"") && !vx.contains("r:id=\"rId2\""))
        assertTrue("ppt/slides/slide3.xml" !in names(saved))
        assertEquals(3, texts(saved).size)
    }

    @Test fun sectionsFollowTheOrder() {
        val sections = "<p:extLst><p:ext uri=\"{521415D9-36F7-43E2-AB2F-B90AF26B5E84}\"><p14:sectionLst xmlns:p14=\"http://schemas.microsoft.com/office/powerpoint/2010/main\">" +
            "<p14:section name=\"Вступ\" id=\"{11111111-1111-1111-1111-111111111111}\"><p14:sldIdLst><p14:sldId id=\"256\"/><p14:sldId id=\"257\"/></p14:sldIdLst></p14:section>" +
            "<p14:section name=\"Дані\" id=\"{22222222-2222-2222-2222-222222222222}\"><p14:sldIdLst><p14:sldId id=\"258\"/><p14:sldId id=\"259\"/></p14:sldIdLst></p14:section>" +
            "</p14:sectionLst></p:ext></p:extLst></p:presentation>"
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(bytes())).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var data = zin.readBytes()
                    if (e.name == "ppt/presentation.xml") data = String(data).replace("</p:presentation>", sections).toByteArray()
                    z.putNextEntry(java.util.zip.ZipEntry(e.name)); z.write(data); z.closeEntry()
                }
            }
        }
        val src = out.toByteArray()
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        val (added, paras) = edit.addSlide(0, "uk-UA")
        // A slide added after the first joins its section
        val a = ByteArrayOutputStream().also { edit.save(edit.paragraphs + paras, listOf(0, added, 1, 2, 3), ByteArrayInputStream(src), it) }.toByteArray()
        val pa = part(a, "ppt/presentation.xml")!!
        assertTrue(pa, pa.contains("<p14:sldId id=\"256\"/><p14:sldId id=\"260\"/><p14:sldId id=\"257\"/></p14:sldIdLst>"))
        // A section broken in two: sections taken out, the slides kept
        val b = ByteArrayOutputStream().also { edit.save(edit.paragraphs, listOf(0, 2, 1, 3), ByteArrayInputStream(src), it) }.toByteArray()
        val pb = part(b, "ppt/presentation.xml")!!
        assertTrue(pb, !pb.contains("sectionLst") && Regex("<p:sldId ").findAll(pb).count() == 4)
        assertEquals(4, texts(b).size)
    }
}
