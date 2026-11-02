package ua.hramota.doc.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.docx.edit.EditPara
import ua.hramota.doc.docx.edit.Kind
import ua.hramota.doc.docx.edit.ListKind
import ua.hramota.doc.docx.edit.XmlScan
import ua.hramota.readingText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Editing DOCX: only what was changed is written anew, and the result opens again. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class DocxEditTest {
    private fun bytes(name: String) = File(javaClass.classLoader!!.getResource("docs/$name")!!.toURI()).readBytes()

    private fun part(docx: ByteArray, name: String): ByteArray? = ZipInputStream(ByteArrayInputStream(docx)).use { z ->
        while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return z.readBytes() }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun save(name: String, edit: DocxEdit, list: List<EditPara>, label: String = "edited"): ByteArray {
        val out = ByteArrayOutputStream()
        edit.save(list, ByteArrayInputStream(bytes(name)), out)
        // Kept for CI, where LibreOffice opens each edited file to show it is a valid document
        File("build/edited").apply { mkdirs() }.let { File(it, "$label-$name").writeBytes(out.toByteArray()) }
        return out.toByteArray()
    }

    private fun texts(docx: ByteArray) = DocxReader.read(ByteArrayInputStream(docx)).readingText().map { it.text }

    @Test fun unchangedSaveKeepsTheDocumentToTheByte() {
        for (name in listOf("01-fonts.docx", "02-paragraphs.docx", "03-lists.docx", "04-tables.docx", "05-flow.docx", "07-headers.docx")) {
            val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
            val saved = save(name, edit, edit.paragraphs, "same")
            assertTrue(name, part(bytes(name), "word/document.xml")!!.contentEquals(part(saved, "word/document.xml")!!))
            assertEquals(name, texts(bytes(name)), texts(saved))
        }
    }

    @Test fun typedTextAndFormattingAreSaved() {
        val name = "02-paragraphs.docx"
        val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
        val list = edit.paragraphs.toMutableList()
        val i = list.indexOfFirst { it.editable && it.text.length > 20 }
        val p = list[i]
        // "Новий початок. " typed at the start; the first word made bold
        val added = "Новий початок. "
        val text = added + p.text
        val marks = List(added.length) { p.marks.first() } + p.marks
        val bolded = marks.mapIndexed { k, m -> if (k < 5) edit.restyle(m, bold = true) else m }
        list[i] = p.changed(text = text, marks = bolded)
        val saved = save(name, edit, list)
        val after = texts(saved)
        assertTrue(after.any { it.startsWith(added) })
        // The other paragraphs are written exactly as they were
        val xml = String(part(saved, "word/document.xml")!!)
        for ((k, q) in edit.paragraphs.withIndex()) if (k != i) assertTrue(xml.contains(q.original!!))
        assertTrue(xml.contains("<w:b/>"))
        // Opened again, the bold is there
        val again = DocxEdit.open(ByteArrayInputStream(saved))
        val q = again.paragraphs.first { it.text.startsWith(added) }
        assertTrue(again.look(q, q.marks[0]).bold)
        assertFalse(again.look(q, q.marks[6]).bold && !edit.look(p, p.marks[0]).bold)
    }

    @Test fun splitJoinHeadingsAndLists() {
        val name = "05-flow.docx"
        val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
        val list = edit.paragraphs.toMutableList()
        val i = list.indexOfFirst { it.editable && it.text.length > 40 && "sectPr" !in it.pPr }
        val (a, b) = edit.split(list[i], 10)
        list[i] = a; list.add(i + 1, b)
        list[i] = edit.withKind(list[i], Kind.H2)
        list[i + 1] = edit.withList(list[i + 1], ListKind.BULLET, list[i])
        val j = (i + 3 until list.size).first { edit.canJoin(list[it - 1], list[it]) }
        val joined = edit.join(list[j - 1], list[j])
        list[j - 1] = joined; list.removeAt(j)
        val saved = save(name, edit, list)
        val again = DocxEdit.open(ByteArrayInputStream(saved))
        val shown = again.shown(again.paragraphs)
        val h = again.paragraphs.indexOfFirst { it.text == a.text }
        assertEquals(Kind.H2, shown[h].kind)
        assertEquals(ListKind.BULLET, shown[h + 1].list)
        assertNotNull(shown[h + 1].marker)
        assertTrue(texts(saved).contains(joined.text))
        assertNotNull(part(saved, "word/numbering.xml"))
    }

    @Test fun aNewNumberedListCountsFromOne() {
        val name = "02-paragraphs.docx"
        val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
        val list = edit.paragraphs.toMutableList()
        val (x, y) = list.indices.filter { list[it].editable }.let { it[1] to it[4] }
        list[x] = edit.withList(list[x], ListKind.NUMBER, list[x - 1])
        list[x + 1] = edit.withList(list[x + 1], ListKind.NUMBER, list[x])
        list[y] = edit.withList(list[y], ListKind.NUMBER, list[y - 1])
        val shown = edit.shown(list)
        assertEquals(listOf("1.", "2.", "1."), listOf(shown[x].marker, shown[x + 1].marker, shown[y].marker))
        save(name, edit, list, "numbers")
    }

    @Test fun tablesKeepTheirCellsAndCellsDoNotJoin() {
        val name = "04-tables.docx"
        val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
        val shown = edit.shown(edit.paragraphs)
        val cells = edit.paragraphs.indices.filter { shown[it].inTable }
        assertTrue(cells.size > 4)
        // Two cells side by side are not one paragraph
        assertFalse(edit.canJoin(edit.paragraphs[cells[0]], edit.paragraphs[cells[1]]))
        val list = edit.paragraphs.toMutableList()
        val c = cells[2]
        list[c] = list[c].changed(text = list[c].text + " (змінено)", marks = list[c].marks + List(10) { list[c].typing })
        val saved = save(name, edit, list)
        assertTrue(texts(saved).any { it.contains("(змінено)") })
    }

    @Test fun propertiesKeepSchemaOrder() {
        val ppr = "<w:pPr><w:keepNext/><w:jc w:val=\"center\"/></w:pPr>"
        val withStyle = DocxEdit.setChild(ppr, "pStyle", "<w:pStyle w:val=\"H\"/>", listOf("pStyle", "keepNext", "jc"))
        assertEquals("<w:pPr><w:pStyle w:val=\"H\"/><w:keepNext/><w:jc w:val=\"center\"/></w:pPr>", withStyle)
        val between = DocxEdit.setChild(ppr, "numPr", "<w:numPr/>", listOf("pStyle", "keepNext", "numPr", "jc"))
        assertEquals("<w:pPr><w:keepNext/><w:numPr/><w:jc w:val=\"center\"/></w:pPr>", between)
        assertEquals("", DocxEdit.removeChild("<w:pPr><w:pStyle w:val=\"H\"/></w:pPr>", "pStyle", emptyList()))
    }

    @Test fun scannerKeepsQuotedBracketsAndEntities() {
        val s = "<a x=\"1 > 2\"><b>t &amp; &#x41;</b><!-- <c> --><d/></a>"
        val a = XmlScan.children(s, 0, s.length).single()
        val kids = XmlScan.children(s, a)
        assertEquals(listOf("b", "d"), kids.map { it.name })
        assertEquals("t & A", XmlScan.unescape(s.substring(kids[0].openEnd, kids[0].closeStart)))
        assertEquals("1 > 2", a.attrs["x"])
    }
}
