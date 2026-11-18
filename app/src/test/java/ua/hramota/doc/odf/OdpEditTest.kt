package ua.hramota.doc.odf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** OpenDocument presentations edited in the file itself: slides' text, marks, items split; slides moved and taken out. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class OdpEditTest {
    private val bytes = javaClass.classLoader!!.getResource("odf/01-project.odp")!!.readBytes()

    @Test fun slidesAreEdited() {
        val edit = OdpEdit.open(ByteArrayInputStream(bytes))
        val n = edit.slideCount
        assertTrue(n >= 3)
        val title = edit.paragraphs.first { edit.boxes[it.box].slide == 0 && edit.boxes[it.box].kind == "title" }
        assertEquals("Проєкт «Грамота»", title.text)
        val list = edit.paragraphs.toMutableList()
        val i = list.indexOf(title)
        val text = title.text + " 2026"
        list[i] = title.changed(text = text, marks = title.marks + List(5) { edit.restyle(title.marks.last(), italic = true) })
        // A bullet of the second slide split in two
        val j = list.indexOfFirst { it.text == "Перегляд документів Word сторінками" }
        val (a, b) = edit.split(list[j], 19)
        list[j] = a; list.add(j + 1, b)
        // The second slide first, the third taken out
        val order = listOf(1, 0) + (3 until n)
        val out = ByteArrayOutputStream()
        edit.save(list, order, ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "odp-project.odp").writeBytes(saved); java.io.File(it, "odp-project.pages").writeText("${n - 1}") }
        ZipInputStream(ByteArrayInputStream(saved)).use { z -> val e = z.nextEntry!!; assertEquals("mimetype", e.name); assertEquals(ZipEntry.STORED, e.method) }
        val again = OdpEdit.open(ByteArrayInputStream(saved))
        assertEquals(n - 1, again.slideCount)
        assertEquals("Що зроблено", again.paragraphs.first { again.boxes[it.box].slide == 0 && again.boxes[it.box].kind == "title" }.text)
        val t2 = again.paragraphs.first { again.boxes[it.box].slide == 1 && again.boxes[it.box].kind == "title" }
        assertEquals("Проєкт «Грамота» 2026", t2.text)
        assertTrue(again.italic(t2.marks.last()))
        assertTrue(again.paragraphs.any { it.text == "Перегляд документів" } && again.paragraphs.any { it.text == " Word сторінками" })
        assertEquals(n - 1, edit.readBack(ByteArrayInputStream(saved)).slides.size)
    }

    @Test fun slidesAndNotesAreAdded() {
        val edit = OdpEdit.open(ByteArrayInputStream(bytes))
        val n = edit.slideCount
        assertTrue(edit.canAddSlides && edit.canNotes)
        // A slide laid out as the second: its title and its list, empty
        val (id, made) = edit.addSlide(1, "uk")
        assertEquals(n, id)
        val title = made.first { edit.boxes[it.box].kind == "title" }
        val body = made.first { edit.boxes[it.box].kind == "body" }
        assertEquals("", title.text); assertEquals("", body.text)
        val plain = title.typing
        val list = edit.paragraphs.toMutableList()
        list += title.changed(text = "Що далі", marks = List(7) { plain })
        list += body.changed(text = "Нотатки й слайди", marks = List(16) { body.typing })
        // Speaker notes for the first slide (it has an empty notes frame) and for the new one
        val notes = edit.addNotes(0, "uk")!!
        assertEquals(1, notes.size)
        assertEquals("notes", edit.boxes[notes[0].box].kind)
        list += notes[0].changed(text = "Привітатися", marks = List(11) { notes[0].typing })
        val newNotes = edit.addNotes(id, "uk")!!
        list += newNotes[0].changed(text = "Розповісти про плани", marks = List(20) { newNotes[0].typing })
        val order = listOf(0, 1, id) + (2 until n)
        val out = ByteArrayOutputStream()
        edit.save(list, order, ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "odp-added.odp").writeBytes(saved); java.io.File(it, "odp-added.pages").writeText("${n + 1}") }
        val again = OdpEdit.open(ByteArrayInputStream(saved))
        assertEquals(n + 1, again.slideCount)
        fun text(slide: Int, kind: String) = again.paragraphs.filter { again.boxes[it.box].slide == slide && again.boxes[it.box].kind == kind }.joinToString("|") { it.text }
        assertEquals("Що далі", text(2, "title"))
        assertEquals("Нотатки й слайди", text(2, "body"))
        assertEquals("Розповісти про плани", text(2, "notes"))
        assertEquals("Привітатися", text(0, "notes"))
        assertEquals("Проєкт «Грамота»", text(0, "title"))
        // Its own name, no ids taken twice
        val c = String(ZipInputStream(ByteArrayInputStream(saved)).use { z -> while (true) { val e = z.nextEntry!!; if (e.name == "content.xml") return@use z.readBytes() }; ByteArray(0) }, Charsets.UTF_8)
        val names = Regex("<draw:page\\b[^>]*?\\sdraw:name=\"([^\"]*)\"").findAll(c).map { it.groupValues[1] }.toList()
        assertEquals(n + 1, names.size); assertEquals(n + 1, names.toSet().size)
        assertEquals(n + 1, edit.readBack(ByteArrayInputStream(saved)).slides.size)
        // Notes undone and added again: the same place
        assertEquals(1, edit.addNotes(0, "uk")!!.size)
    }
}
