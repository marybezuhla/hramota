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

    @Test fun shapesArePlacedAddedAndTakenOut() {
        val edit = OdpEdit.open(ByteArrayInputStream(bytes))
        assertTrue(edit.canPlace)
        val n = edit.slideCount
        // The first slide: title (0), subtitle (1); the second: title, list (1)
        val first = edit.readBack(ByteArrayInputStream(bytes)).slides[1].frames
        assertTrue(first.toString(), first.any { it.id == "1" && it.kind == "text" })
        val ref = ua.hramota.doc.slides.edit.PptxEdit.ShapeRef(1, "1")
        val (box, paras) = edit.addTextBox(0, longArrayOf(360000, 360000, 3600000, 720000), "uk")
        val list = edit.paragraphs.toMutableList()
        list += paras[0].changed(text = "Новий напис", marks = List(11) { paras[0].typing })
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
        val pic = edit.addPicture(0, png, true, longArrayOf(7200000, 3600000, 1800000, 1800000))
        // The subtitle taken out, with its text
        val subtitle = ua.hramota.doc.slides.edit.PptxEdit.ShapeRef(0, "1")
        list.removeAll { edit.boxes[it.box].let { b -> b.slide == 0 && b.shape == "1" } }
        val shapes = ua.hramota.doc.slides.edit.PptxEdit.Shapes(placed = mapOf(ref to longArrayOf(720000, 1440000, 7200000, 3600000)), removed = setOf(subtitle), pictures = setOf(pic))
        val out = ByteArrayOutputStream()
        edit.save(list, (0 until n).toList(), ByteArrayInputStream(bytes), out, shapes)
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "odp-shapes.odp").writeBytes(saved); java.io.File(it, "odp-shapes.pages").writeText("$n") }
        val deck = edit.readBack(ByteArrayInputStream(saved))
        assertEquals(listOf("0", box.id, pic.id), deck.slides[0].frames.map { it.id })
        val moved = deck.slides[1].frames.first { it.id == "1" }
        assertEquals(56.69f, moved.x, 0.1f); assertEquals(113.39f, moved.y, 0.1f); assertEquals(566.9f, moved.w, 0.5f)
        assertTrue(deck.media.keys.any { it.startsWith("Pictures/hramota-") })
        val again = OdpEdit.open(ByteArrayInputStream(saved))
        assertTrue(again.paragraphs.any { it.text == "Новий напис" })
        assertTrue(again.paragraphs.none { it.text.startsWith("Підсумки кварталу") })
        val manifest = ZipInputStream(ByteArrayInputStream(saved)).use { z -> var m = ""; while (true) { val e = z.nextEntry ?: break; if (e.name == "META-INF/manifest.xml") m = String(z.readBytes(), Charsets.UTF_8) }; m }
        assertTrue(manifest, manifest.contains("Pictures/hramota-") && manifest.contains("image/png"))
    }
}
