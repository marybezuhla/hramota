package ua.hramota.doc.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.slides.edit.PptxEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Shapes moved, sized, taken out, added (text boxes, pictures), and speaker notes, written into the file. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class SlideShapesTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private fun names(b: ByteArray): Set<String> = ZipInputStream(ByteArrayInputStream(b)).use { z -> val out = HashSet<String>(); while (true) { val e = z.nextEntry ?: break; out += e.name }; out }
    private fun part(b: ByteArray, name: String): String? = ZipInputStream(ByteArrayInputStream(b)).use { z ->
        while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return String(z.readBytes()) }
        @Suppress("UNREACHABLE_CODE") null
    }
    private fun keep(name: String, b: ByteArray, slides: Int) =
        File("build/edited").apply { mkdirs() }.let { File(it, name).writeBytes(b); File(it, name.substringBeforeLast('.') + ".pages").writeText(slides.toString()) }

    /** A tiny PNG made in the test. */
    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(8, 6, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5B92.toInt()) }
        return ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun shapesMovedAddedAndTakenOut() {
        val src = bytes("slides/01-project.pptx")
        val deck = PptxReader.read(ByteArrayInputStream(src))
        val frames = deck.slides[0].frames
        assertTrue(frames.toString(), frames.isNotEmpty())
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        val first = frames.first()
        val moved = longArrayOf(914400, 457200, (first.w * 12700).toLong() / 2, (first.h * 12700).toLong())
        val (box, paras) = edit.addTextBox(0, longArrayOf(1_000_000, 4_000_000, 3_000_000, 600_000), "uk-UA")
        val typed = paras[0].changed(text = "Нова примітка", marks = List(13) { paras[0].typing })
        val pic = edit.addPicture(1, png(), true, longArrayOf(500_000, 500_000, 800_000, 600_000))
        val gone = PptxEdit.ShapeRef(2, deck.slides[2].frames.last().id)
        val list = edit.paragraphs.filterNot { edit.boxes[it.box].let { b -> b.slide == 2 && b.shape == gone.id } } + typed
        val shapes = PptxEdit.Shapes(placed = mapOf(PptxEdit.ShapeRef(0, first.id) to moved), removed = setOf(gone), pictures = setOf(pic))
        val out = ByteArrayOutputStream().also { edit.save(list, (0 until edit.slideCount).toList(), ByteArrayInputStream(src), it, shapes) }.toByteArray()
        keep("shapes-01-project.pptx", out, edit.slideCount)
        val again = PptxReader.read(ByteArrayInputStream(out))
        // Moved and sized
        val f = again.slides[0].frames.first { it.id == first.id }
        assertEquals(72f, f.x, 0.5f); assertEquals(36f, f.y, 0.5f); assertEquals(first.w / 2, f.w, 0.5f)
        // The text box with its text, in front
        val tb = again.slides[0].frames.last()
        assertEquals(box.id, tb.id)
        assertTrue(again.slides[0].shapes.filterIsInstance<SlideShape.Box>().any { b -> b.paragraphs.any { p -> p.runs.joinToString("") { it.text } == "Нова примітка" } })
        // The picture, its file and its link
        assertTrue(again.slides[1].shapes.any { it is SlideShape.Picture })
        val media = names(out).filter { it.startsWith("ppt/media/hramota") }
        assertEquals(1, media.size)
        assertTrue(part(out, "ppt/slides/_rels/slide2.xml.rels")!!.contains(media[0].removePrefix("ppt/")))
        assertTrue(part(out, "[Content_Types].xml")!!.contains("Extension=\"png\""))
        // Taken out
        assertNull(again.slides[2].frames.firstOrNull { it.id == gone.id })
    }

    @Test fun anAddedTextBoxLeftEmptyOfParagraphsGoes() {
        val src = bytes("slides/01-project.pptx")
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        val (box, _) = edit.addTextBox(0, longArrayOf(1_000_000, 4_000_000, 3_000_000, 600_000), "uk-UA")
        val pic = edit.addPicture(0, png(), true, longArrayOf(0, 0, 100_000, 100_000))
        // As after undoing both: the box's paragraphs are not in the list, the picture not kept
        val out = ByteArrayOutputStream().also { edit.save(edit.paragraphs, (0 until edit.slideCount).toList(), ByteArrayInputStream(src), it) }.toByteArray()
        val again = PptxReader.read(ByteArrayInputStream(out))
        assertNull(again.slides[0].frames.firstOrNull { it.id == box.id || it.id == pic.id })
        assertTrue(names(out).none { it.startsWith("ppt/media/hramota") })
    }

    @Test fun speakerNotesAreEditedAndAdded() {
        val src = bytes("slides/02-notes.pptx")
        val edit = PptxEdit.open(ByteArrayInputStream(src))
        assertTrue(edit.hasNotes(0))
        val list = edit.paragraphs.toMutableList()
        val i = list.indexOfFirst { edit.boxes[it.box].kind == "notes" }
        assertEquals("Сказати про ремонт даху", list[i].text)
        list[i] = list[i].changed(text = "Сказати про дах і вікна", marks = List(23) { list[i].marks[0] })
        val added = edit.addNotes(1, "uk-UA")
        assertNotNull(added)
        list += added!![0].changed(text = "Нагадати про голосування", marks = List(24) { added[0].typing })
        val out = ByteArrayOutputStream().also { edit.save(list, listOf(0, 1), ByteArrayInputStream(src), it) }.toByteArray()
        keep("notes-02-notes.pptx", out, 2)
        val again = PptxEdit.open(ByteArrayInputStream(out))
        val notes = again.paragraphs.filter { again.boxes[it.box].kind == "notes" }.associate { again.boxes[it.box].slide to it.text }
        assertEquals("Сказати про дах і вікна", notes[0])
        assertEquals("Нагадати про голосування", notes[1])
        assertTrue(part(out, "[Content_Types].xml")!!.contains("notesSlide+xml"))
    }

    @Test fun noNotesWithoutANotesMaster() {
        val edit = PptxEdit.open(ByteArrayInputStream(ua.hramota.doc.Templates.pptx("uk-UA")))
        assertNull(edit.addNotes(0, "uk-UA"))
    }
}
