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

    /** The sample with a turned shape and a group put on its first slide. */
    private fun withTurnedAndGroup(): ByteArray {
        val out = ByteArrayOutputStream()
        val z = java.util.zip.ZipOutputStream(out)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                var data = zin.readBytes()
                if (e.name == "content.xml") {
                    val c = String(data, Charsets.UTF_8)
                    val at = c.indexOf("<presentation:notes")
                    data = (c.substring(0, at) + "<draw:custom-shape svg:width=\"4cm\" svg:height=\"2cm\" draw:transform=\"rotate (0.785398163397448) translate (5cm 8cm)\"><draw:enhanced-geometry draw:type=\"rectangle\"/></draw:custom-shape>" +
                        "<draw:g><draw:rect svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"2cm\" svg:height=\"1cm\"/><draw:line svg:x1=\"1cm\" svg:y1=\"3cm\" svg:x2=\"4cm\" svg:y2=\"3cm\"/></draw:g>" + c.substring(at)).toByteArray(Charsets.UTF_8)
                }
                val n = ZipEntry(e.name)
                if (e.name == "mimetype") { n.method = ZipEntry.STORED; n.size = data.size.toLong(); n.compressedSize = data.size.toLong(); n.crc = java.util.zip.CRC32().apply { update(data) }.value }
                z.putNextEntry(n); z.write(data); z.closeEntry()
            }
        }
        z.finish()
        return out.toByteArray()
    }

    @Test fun turnedShapesAndGroupsAreMoved() {
        val src = withTurnedAndGroup()
        val edit = OdpEdit.open(ByteArrayInputStream(src))
        val frames = edit.readBack(ByteArrayInputStream(src)).slides[0].frames
        val turned = frames.first { it.id == "2" }
        assertEquals(-45f, turned.rotation, 0.01f)
        // Its box before turning: 4 × 2 cm, its middle where the turn put it
        assertEquals(113.39f, turned.w, 0.1f); assertEquals(56.69f, turned.h, 0.1f)
        val group = frames.first { it.id == "3" }
        assertEquals("group", group.kind)
        assertEquals(28.35f, group.x, 0.1f); assertEquals(85.04f, group.w, 0.1f); assertEquals(56.69f, group.h, 0.1f)
        val emu = { pt: Float -> (pt * 12700).toLong() }
        val placed = mapOf(
            ua.hramota.doc.slides.edit.PptxEdit.ShapeRef(0, "2") to longArrayOf(emu(turned.x + 36f), emu(turned.y), emu(turned.w), emu(turned.h)),
            // The group twice as big, its corner at 2 cm, 2 cm
            ua.hramota.doc.slides.edit.PptxEdit.ShapeRef(0, "3") to longArrayOf(720000, 720000, 2160000, 1440000),
        )
        val out = ByteArrayOutputStream()
        edit.save(edit.paragraphs, (0 until edit.slideCount).toList(), ByteArrayInputStream(src), out, ua.hramota.doc.slides.edit.PptxEdit.Shapes(placed = placed))
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "odp-turned.odp").writeBytes(saved); java.io.File(it, "odp-turned.pages").writeText("${edit.slideCount}") }
        val again = edit.readBack(ByteArrayInputStream(saved)).slides[0].frames
        val t2 = again.first { it.id == "2" }
        assertEquals(turned.x + 36f, t2.x, 0.1f); assertEquals(turned.y, t2.y, 0.1f); assertEquals(-45f, t2.rotation, 0.01f)
        val g2 = again.first { it.id == "3" }
        assertEquals(56.69f, g2.x, 0.1f); assertEquals(56.69f, g2.y, 0.1f); assertEquals(170.08f, g2.w, 0.2f); assertEquals(113.39f, g2.h, 0.2f)
        val c = ZipInputStream(ByteArrayInputStream(saved)).use { z -> var m = ""; while (true) { val e = z.nextEntry ?: break; if (e.name == "content.xml") m = String(z.readBytes(), Charsets.UTF_8) }; m }
        assertTrue(c, c.contains("svg:x2=\"8.000cm\"") && c.contains("svg:y1=\"6.000cm\""))
    }
}
