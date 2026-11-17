package ua.hramota.doc.odf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.Block
import ua.hramota.doc.docx.Inline
import ua.hramota.doc.docx.edit.Kind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** OpenDocument text edited in the file itself: text, marks, headings, list items; the rest kept to the byte. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class OdtEditTest {
    private val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
        "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
    private val linked = "<text:p text:style-name=\"Standard\">Див. <text:a xlink:href=\"https://example.org\">сайт</text:a></text:p>"
    private val field = "<text:p>Сьогодні <text:date text:date-value=\"2026-11-18\">18.11.2026</text:date>, нотатка<text:note text:id=\"n1\" text:note-class=\"footnote\"><text:note-citation>1</text:note-citation><text:note-body><text:p>Виноска</text:p></text:note-body></text:note>.</text:p>"
    private val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\">" +
        "<office:automatic-styles><style:style style:name=\"T1\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style></office:automatic-styles>" +
        "<office:body><office:text>" +
        "<text:p text:style-name=\"Standard\">Привіт,  <text:s/>світе <text:span text:style-name=\"T1\">жирно</text:span></text:p>" +
        "<text:list text:style-name=\"L1\"><text:list-item><text:p>Перший пункт</text:p></text:list-item><text:list-item><text:p>Другий</text:p></text:list-item></text:list>" +
        "<table:table><table:table-column/><table:table-row><table:table-cell><text:p>У клітинці</text:p></table:table-cell></table:table-row></table:table>" +
        linked + field +
        "</office:text></office:body></office:document-content>"
    private val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $ns><office:styles>" +
        "<style:style style:name=\"Standard\" style:family=\"paragraph\"/><style:style style:name=\"Heading_20_2\" style:family=\"paragraph\"><style:text-properties fo:font-weight=\"bold\"/></style:style>" +
        "<text:list-style style:name=\"L1\"><text:list-level-style-number text:level=\"1\" style:num-format=\"1\" style:num-suffix=\".\"/></text:list-style>" +
        "</office:styles></office:document-styles>"

    private fun odt(content: String = this.content): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mime = "application/vnd.oasis.opendocument.text".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong(); crc = CRC32().apply { update(mime) }.value })
            z.write(mime); z.closeEntry()
            for ((n, t) in listOf("content.xml" to content, "styles.xml" to styles, "META-INF/manifest.xml" to "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"/>")) {
                z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun part(zip: ByteArray, name: String): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return String(z.readBytes()) } }
    }

    @Test fun paragraphsAreRead() {
        val edit = OdtEdit.open(ByteArrayInputStream(odt()))
        assertEquals(listOf("Привіт,  світе жирно", "Перший пункт", "Другий", "У клітинці", "Див. сайт", "Сьогодні 18.11.2026, нотатка1."), edit.paragraphs.map { it.text })
        assertEquals(listOf(true, true, true, true, true), edit.paragraphs.map { it.editable })
        val p = edit.paragraphs[0]
        assertTrue(edit.look(p, p.marks.last()).bold)
        assertFalse(edit.look(p, p.marks.first()).bold)
        val shown = edit.shown(edit.paragraphs)
        assertEquals(listOf(null, "1.", "2.", null, null, null), shown.map { it.marker })
        assertTrue(shown[3].inTable)
    }

    @Test fun editsAreWrittenIntoTheFile() {
        val bytes = odt()
        val edit = OdtEdit.open(ByteArrayInputStream(bytes))
        val list = edit.paragraphs.toMutableList()
        // The first paragraph: a word changed, the first word made italic
        val p = list[0]
        val text = p.text.replace("світе", "світ")
        val marks = p.marks.toMutableList().also { it.removeAt(text.indexOf("світ") + 4) }.mapIndexed { i, m -> if (i < 6) edit.restyle(m, italic = true) else m }
        list[0] = edit.retyped(p, text, marks, 0, p.text.length)
        // A list item split in two; the second item a heading
        val (a, b) = edit.split(list[1], 7)
        list[1] = a; list.add(2, b)
        list[2] = edit.withKind(list[2], Kind.H2)
        val out = ByteArrayOutputStream()
        edit.save(list, ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        // The file is still an OpenDocument: its type first, not packed
        ZipInputStream(ByteArrayInputStream(saved)).use { z -> val e = z.nextEntry!!; assertEquals("mimetype", e.name); assertEquals(ZipEntry.STORED, e.method) }
        val c = part(saved, "content.xml")!!
        // What was not changed is as it was, to the byte
        assertTrue(c.contains(linked))
        assertTrue(c.contains("<text:p>У клітинці</text:p>"))
        assertTrue(c, c.contains("fo:font-style=\"italic\""))
        assertTrue(c, c.contains("</text:list-item><text:list-item><text:h text:style-name=\"Heading_20_2\" text:outline-level=\"2\">пункт</text:h>"))
        // Read again: the same text, with the change
        val again = OdtEdit.open(ByteArrayInputStream(saved))
        assertEquals(listOf("Привіт,  світ жирно", "Перший ", "пункт", "Другий", "У клітинці", "Див. сайт", "Сьогодні 18.11.2026, нотатка1."), again.paragraphs.map { it.text })
        val q = again.paragraphs[0]
        assertTrue(again.look(q, q.marks[0]).italic)
        assertFalse(again.look(q, q.marks[8]).italic)
        assertTrue(again.look(q, q.marks.last()).bold)
        assertEquals(Kind.H2, again.kind(again.paragraphs[2]))
        assertEquals(listOf(null, "1.", "2.", "3.", null, null, null), again.shown(again.paragraphs).map { it.marker })
        // And as the viewer shows it
        val doc = edit.readBack(ByteArrayInputStream(saved))
        val first = doc.blocks.filterIsInstance<Block.Para>().first().inlines.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertEquals("Привіт,  світ жирно", first)
    }

    @Test fun spacesAreKept() {
        val edit = OdtEdit.open(ByteArrayInputStream(odt()))
        val p = edit.paragraphs[0]
        val list = edit.paragraphs.toMutableList()
        val text = "  два   пробіли"
        list[0] = edit.retyped(p, text, List(text.length) { p.typing }, 0, p.text.length)
        val out = ByteArrayOutputStream()
        edit.save(list, ByteArrayInputStream(odt()), out)
        val again = OdtEdit.open(ByteArrayInputStream(out.toByteArray()))
        assertEquals(text, again.paragraphs[0].text)
    }

    @Test fun aChangedFileIsNotWrittenOver() {
        val edit = OdtEdit.open(ByteArrayInputStream(odt()))
        val other = odt(content.replace("Другий", "Інший"))
        val r = runCatching { edit.save(edit.paragraphs, ByteArrayInputStream(other), ByteArrayOutputStream()) }
        assertTrue(r.isFailure)
    }


    @Test fun aLibreOfficeDocument() {
        val bytes = javaClass.classLoader!!.getResource("odf/03-lists.odt")!!.readBytes()
        val edit = OdtEdit.open(ByteArrayInputStream(bytes))
        val list = edit.paragraphs.toMutableList()
        val i = list.indexOfFirst { it.text == "Третій пункт" }
        assertTrue(i > 0)
        val p = list[i]
        val text = "Третій пункт, змінений"
        list[i] = edit.retyped(p, text, p.marks + List(text.length - p.text.length) { p.marks.last() }.map { edit.restyle(it, bold = true) }, p.text.length, p.text.length)
        val (a, b) = edit.split(list[i], 6)
        list[i] = a; list.add(i + 1, b)
        list[0] = edit.withKind(list[0], Kind.H2)
        val out = ByteArrayOutputStream()
        edit.save(list, ByteArrayInputStream(bytes), out)
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "odt-lists.odt").writeBytes(out.toByteArray()) }
        val again = OdtEdit.open(ByteArrayInputStream(out.toByteArray()))
        assertTrue(again.paragraphs.any { it.text == "Третій" })
        val q = again.paragraphs.first { it.text == " пункт, змінений" }
        assertTrue(again.look(q, q.marks.last()).bold)
        assertEquals(Kind.H2, again.kind(again.paragraphs[0]))
        assertEquals(edit.paragraphs.size + 1, again.paragraphs.size)
    }

    private fun saved(edit: OdtEdit, list: List<ua.hramota.doc.docx.edit.EditPara>, bytes: ByteArray = odt()): ByteArray =
        ByteArrayOutputStream().also { edit.save(list, ByteArrayInputStream(bytes), it) }.toByteArray()

    @Test fun linksFieldsAndNotesStayWhole() {
        val edit = OdtEdit.open(ByteArrayInputStream(odt()))
        val list = edit.paragraphs.toMutableList()
        val l = list[4]
        list[4] = edit.retyped(l, "Дивіться " + l.text.removePrefix("Див. "), List(9) { l.marks[0] } + l.marks.drop(5), 0, 5)
        val f = list[5]
        // Typed right after the field: plain text, the field written once
        val at = f.text.indexOf(",")
        list[5] = edit.retyped(f, f.text.substring(0, at) + " (середа)" + f.text.substring(at), f.marks.subList(0, at) + List(9) { f.marks[at - 1] } + f.marks.subList(at, f.marks.size), at, at)
        val c = part(saved(edit, list), "content.xml")!!
        assertTrue(c, c.contains("<text:p text:style-name=\"Standard\">Дивіться <text:a xlink:href=\"https://example.org\">сайт</text:a></text:p>"))
        assertTrue(c, c.contains("Сьогодні <text:date text:date-value=\"2026-11-18\">18.11.2026</text:date> (середа), нотатка<text:note text:id=\"n1\""))
        assertEquals(1, Regex("<text:date ").findAll(c).count())
    }

    @Test fun listsAreTurnedOnAndOff() {
        val bytes = odt()
        val edit = OdtEdit.open(ByteArrayInputStream(bytes))
        val list = edit.paragraphs.toMutableList()
        list[0] = edit.withList(list[0], ua.hramota.doc.docx.edit.ListKind.BULLET, null)
        list[2] = edit.withList(list[2], null, list[1])
        assertEquals(listOf("•", "1.", null), edit.shown(list).take(3).map { it.marker })
        val out = saved(edit, list, bytes)
        val again = OdtEdit.open(ByteArrayInputStream(out))
        assertEquals(listOf("•", "1.", null, null, null, null), again.shown(again.paragraphs).map { it.marker })
        assertTrue(part(out, "content.xml")!!.contains("<text:list-style style:name=\"HrB\">"))
    }

    @Test fun commentsAreAddedAndTakenAway() {
        val bytes = odt()
        val edit = OdtEdit.open(ByteArrayInputStream(bytes))
        assertTrue(edit.canComment)
        val list = edit.paragraphs.toMutableList()
        list[0] = edit.comment(list[0], 0, 6, "Марія", "Перевірити")
        val out = saved(edit, list, bytes)
        val again = OdtEdit.open(ByteArrayInputStream(out))
        val c = again.comments().single()
        assertEquals("Марія", c.author); assertEquals("Перевірити", c.text)
        assertEquals("Привіт", again.commentPlace(again.paragraphs, c.id)!!.second)
        val gone = again.uncomment(again.paragraphs, c.id)!!
        val out2 = ByteArrayOutputStream().also { again.save(gone, ByteArrayInputStream(out), it) }.toByteArray()
        assertTrue(OdtEdit.open(ByteArrayInputStream(out2)).comments().isEmpty())
    }
}
