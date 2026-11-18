package ua.hramota.doc.odf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.sheet.edit.CellKey
import ua.hramota.doc.sheet.edit.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** OpenDocument spreadsheets edited in the file itself: values and formulas, repeated rows and cells split around a change. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class OdsEditTest {
    private val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
        "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
    private val second = "<table:table table:name=\"Інший аркуш\"><table:table-column/><table:table-row><table:table-cell table:formula=\"of:=[\$Дані.B1]+1\" office:value-type=\"float\" office:value=\"11\"><text:p>11</text:p></table:table-cell></table:table-row></table:table>"
    private val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns><office:body><office:spreadsheet>" +
        "<table:table table:name=\"Дані\"><table:table-column table:number-columns-repeated=\"3\"/>" +
        "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Назва</text:p></table:table-cell>" +
        "<table:table-cell office:value-type=\"float\" office:value=\"10\"><text:p>10</text:p></table:table-cell>" +
        "<table:table-cell table:formula=\"of:=[.B1]*2\" office:value-type=\"float\" office:value=\"20\"><text:p>20</text:p></table:table-cell></table:table-row>" +
        "<table:table-row table:number-rows-repeated=\"3\"><table:table-cell table:number-columns-repeated=\"3\"/></table:table-row>" +
        "<table:table-row><table:table-cell table:formula=\"of:=SUM([.B1:.B4])\" office:value-type=\"float\" office:value=\"10\"><text:p>10</text:p></table:table-cell></table:table-row>" +
        "</table:table>" + second + "</office:spreadsheet></office:body></office:document-content>"

    private fun ods(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mime = "application/vnd.oasis.opendocument.spreadsheet".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong(); crc = CRC32().apply { update(mime) }.value })
            z.write(mime); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/manifest.xml")); z.write("<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"/>".toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun part(zip: ByteArray, name: String): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return String(z.readBytes()) } }
    }

    @Test fun formulasBetweenTheTwoWays() {
        assertEquals("SUM(A1:B2)", OdsEdit.fromOdf("of:=SUM([.A1:.B2])"))
        assertEquals("IF(A1>0,\"а;б\",TRUE)", OdsEdit.fromOdf("of:=IF([.A1]>0;\"а;б\";TRUE())"))
        assertEquals("'Інший аркуш'!\$C\$3", OdsEdit.fromOdf("of:=[\$'Інший аркуш'.\$C\$3]"))
        assertEquals("Дані!B1+1", OdsEdit.fromOdf("of:=[\$Дані.B1]+1"))
        assertNull(OdsEdit.fromOdf("of:=SUM([\$A.A1:\$B.A2])"))
        assertEquals("of:=SUM([.A1:.B2])+[\$'Інший аркуш'.C3]*2", OdsEdit.toOdf("SUM(A1:B2)+'Інший аркуш'!C3*2"))
        assertEquals("of:=IF([.A1]>0;\"так, звісно\";TRUE())", OdsEdit.toOdf("IF(A1>0,\"так, звісно\",TRUE)"))
        assertEquals("of:=LOG10([.\$A\$1])", OdsEdit.toOdf("LOG10(\$A\$1)"))
        assertNull(OdsEdit.toOdf("SUM(A:A)"))
    }

    @Test fun cellsAreRead() {
        val edit = OdsEdit.open(ByteArrayInputStream(ods()))
        assertEquals(listOf("Дані", "Інший аркуш"), edit.sheetNames.map { it.name })
        assertEquals("Назва", edit.content(CellKey(0, 0, 0)))
        assertEquals("10", edit.content(CellKey(0, 0, 1)))
        assertEquals("=B1*2", edit.content(CellKey(0, 0, 2)))
        assertEquals("=SUM(B1:B4)", edit.content(CellKey(0, 4, 0)))
        assertEquals("", edit.content(CellKey(0, 2, 1)))
    }

    @Test fun valuesAndFormulasAreWrittenIntoTheFile() {
        val bytes = ods()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        // Into a row repeated three times, in a cell repeated three times
        edit.set(CellKey(0, 2, 1), edit.parse("5", false)!!)
        edit.set(CellKey(0, 0, 1), edit.parse("15", false)!!)
        // A new formula, past the row's cells
        edit.set(CellKey(0, 6, 4), edit.parse("=B1+B3", false)!!)
        val values = edit.recalc()
        assertEquals(Value.Num(30.0), values[CellKey(0, 0, 2)])
        assertEquals(Value.Num(20.0), values[CellKey(0, 4, 0)])
        assertEquals(Value.Num(16.0), values[CellKey(1, 0, 0)])
        assertEquals(Value.Num(20.0), values[CellKey(0, 6, 4)])
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        ZipInputStream(ByteArrayInputStream(saved)).use { z -> val e = z.nextEntry!!; assertEquals("mimetype", e.name); assertEquals(ZipEntry.STORED, e.method) }
        val c = part(saved, "content.xml")!!
        // The repeated rows split around the changed one
        assertTrue(c, c.contains("<table:table-row><table:table-cell table:number-columns-repeated=\"3\"/></table:table-row><table:table-row><table:table-cell/>" +
            "<table:table-cell office:value-type=\"float\" office:value=\"5\"><text:p>5</text:p></table:table-cell><table:table-cell/></table:table-row>" +
            "<table:table-row><table:table-cell table:number-columns-repeated=\"3\"/></table:table-row>"))
        // Formulas kept as they were, with their new values
        assertTrue(c, c.contains("table:formula=\"of:=[.B1]*2\" office:value-type=\"float\" office:value=\"30\""))
        assertTrue(c, c.contains("table:formula=\"of:=[\$Дані.B1]+1\" office:value-type=\"float\" office:value=\"16\""))
        assertTrue(c, c.contains("table:formula=\"of:=[.B1]+[.B3]\" office:value-type=\"float\" office:value=\"20\""))
        // Read again
        val again = OdsEdit.open(ByteArrayInputStream(saved))
        assertEquals("5", again.content(CellKey(0, 2, 1)))
        assertEquals("15", again.content(CellKey(0, 0, 1)))
        assertEquals("=B1+B3", again.content(CellKey(0, 6, 4)))
        assertEquals("Назва", again.content(CellKey(0, 0, 0)))
        val wb = edit.readBack(ByteArrayInputStream(saved))
        assertEquals(2, wb.sheets.size)
    }

    @Test fun aChangedFileIsNotWrittenOver() {
        val edit = OdsEdit.open(ByteArrayInputStream(ods()))
        edit.set(CellKey(0, 0, 1), edit.parse("1", false)!!)
        val other = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("content.xml")); z.write(content.replace("Назва", "Інше").toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertTrue(runCatching { edit.save(ByteArrayInputStream(other), ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun aLibreOfficeWorkbook() {
        val bytes = javaClass.classLoader!!.getResource("odf/01-budget.ods")!!.readBytes()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        assertEquals("=SUM(B4:D4)", edit.content(CellKey(0, 3, 4)))
        assertEquals("6200", edit.content(CellKey(0, 3, 1)))
        edit.set(CellKey(0, 3, 1), edit.parse("7200", false)!!)
        edit.set(CellKey(0, 11, 0), edit.parse("Нотатка", false)!!)
        assertEquals(Value.Num(11700.5), edit.recalc()[CellKey(0, 3, 4)])
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        // Kept for CI, where LibreOffice opens it and its sums are checked
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "ods-budget.ods").writeBytes(out.toByteArray()); java.io.File(it, "ods-budget.expect").writeText("4,5,11700.5\n4,2,7200\n") }
        val again = OdsEdit.open(ByteArrayInputStream(out.toByteArray()))
        assertEquals("7200", again.content(CellKey(0, 3, 1)))
        assertEquals("Нотатка", again.content(CellKey(0, 11, 0)))
        assertEquals("Сімейний бюджет 2026", again.content(CellKey(0, 0, 0)))
        assertTrue(part(out.toByteArray(), "content.xml")!!.contains("table:formula=\"of:=SUM([.B4:.D4])\" office:value-type=\"float\" office:value=\"11700.5\""))
    }

    @Test fun looksWidthsAndNotes() {
        val bytes = javaClass.classLoader!!.getResource("odf/01-budget.ods")!!.readBytes()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        assertTrue(edit.canLook && edit.canWidth && edit.canNote(0))
        assertTrue(edit.setLook(CellKey(0, 3, 1), ua.hramota.doc.sheet.edit.CellLook(bold = true, fill = 0xFFEB3B, format = "0", align = "center")))
        edit.setNote(CellKey(0, 4, 0), "Перевірити суму")
        edit.setWidth(0, 0, 30f)
        edit.set(CellKey(0, 5, 1), edit.parse("7000", false)!!)
        edit.setLook(CellKey(0, 5, 1), ua.hramota.doc.sheet.edit.CellLook(italic = true))
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "ods-looks.ods").writeBytes(saved); java.io.File(it, "ods-looks.expect").writeText("4,2,6200\n6,2,7000\n6,5,11100\n") }
        val c = part(saved, "content.xml")!!
        assertTrue(c, Regex("<style:style style:name=\"HrCe1\"[^>]*>").containsMatchIn(c))
        assertTrue(c, c.contains("fo:font-weight=\"bold\"") && c.contains("fo:background-color=\"#ffeb3b\""))
        assertTrue(c, c.contains("<number:number-style style:name=\"HrNf1\">"))
        assertTrue(c, Regex("<style:style style:name=\"HrCo1\"[^>]*><style:table-column-properties[^>]*style:column-width=\"").containsMatchIn(c))
        assertTrue(c, c.contains("<dc:creator>Hramota</dc:creator><text:p>Перевірити суму</text:p>"))
        val wb = OdsReader.read(ByteArrayInputStream(saved))
        assertEquals("Hramota: Перевірити суму", wb.comments[0]!![4 to 0])
        // Values as they were (the look alone changed), the one typed in place
        val again = OdsEdit.open(ByteArrayInputStream(saved))
        assertEquals("6200", again.content(CellKey(0, 3, 1)))
        assertEquals("7000", again.content(CellKey(0, 5, 1)))
        assertEquals("Квітень", again.content(CellKey(0, 6, 0)))
    }

    @Test fun rowsInsertedAndDeleted() {
        val bytes = javaClass.classLoader!!.getResource("odf/01-budget.ods")!!.readBytes()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        assertTrue(edit.canRows && edit.canSort)
        val before = edit.snapshot()
        // A row before March: March and the rows after it move down, their sums with them
        assertNull(edit.moveRows(0, 5, 1))
        assertEquals("Березень", edit.content(CellKey(0, 6, 0)))
        assertEquals("=SUM(B7:D7)", edit.content(CellKey(0, 6, 4)))
        assertEquals("", edit.content(CellKey(0, 5, 0)))
        edit.set(CellKey(0, 5, 0), edit.parse("Додатково", false)!!)
        edit.set(CellKey(0, 5, 1), edit.parse("100", false)!!)
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        val saved = out.toByteArray()
        java.io.File("build/edited").apply { mkdirs() }.let { java.io.File(it, "ods-rows.ods").writeBytes(saved); java.io.File(it, "ods-rows.expect").writeText("6,2,100\n7,1,Березень\n7,5,10500\n10,5,15700\n") }
        val c = part(saved, "content.xml")!!
        assertTrue(c, c.contains("of:=SUM([.B7:.D7])"))
        // As many rows as before: the empty ones at the end one fewer
        val rows = Regex("<table:table table:name=\"Бюджет\".*?</table:table>", RegexOption.DOT_MATCHES_ALL).find(c)!!.value
        val count = Regex("<table:table-row\\b([^>]*)>").findAll(rows).sumOf { m -> Regex("number-rows-repeated=\"(\\d+)\"").find(m.groupValues[1])?.groupValues?.get(1)?.toInt() ?: 1 }
        assertEquals(1_048_576, count)
        val wb = OdsReader.read(ByteArrayInputStream(saved))
        assertEquals("Додатково", wb.sheets[0].row(5)!!.let { r -> wb.sheets[0].text(r, r.find(0)) })
        // Undone: as the file was
        edit.restore(before)
        assertEquals("Березень", edit.content(CellKey(0, 5, 0)))
        assertEquals("=SUM(B6:D6)", edit.content(CellKey(0, 5, 4)))
        // February deleted: March moves up
        assertNull(edit.moveRows(0, 4, -1))
        assertEquals("Березень", edit.content(CellKey(0, 4, 0)))
        assertEquals("=SUM(B5:D5)", edit.content(CellKey(0, 4, 4)))
        // The title is one cell across five columns: a column put inside it is refused
        assertEquals(ua.hramota.doc.sheet.edit.XlsxEdit.RowRefusal.LOCKED, edit.moveColumns(0, 2, 1))
    }

    @Test fun columnsInsertedAndDeleted() {
        val bytes = ods()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        assertNull(edit.moveColumns(0, 1, 1))
        assertEquals("=C1*2", edit.content(CellKey(0, 0, 3)))
        assertEquals("=SUM(C1:C4)", edit.content(CellKey(0, 4, 0)))
        assertEquals("=Дані!C1+1", edit.content(CellKey(1, 0, 0)))
        assertEquals("", edit.content(CellKey(0, 0, 1)))
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        val c = part(out.toByteArray(), "content.xml")!!
        assertTrue(c, c.contains("of:=[.C1]*2") && c.contains("of:=SUM([.C1:.C4])") && c.contains("[\$Дані.C1]"))
        // Four columns now
        val first = c.substring(0, c.indexOf("</table:table>"))
        assertEquals(4, Regex("<table:table-column\\b([^>]*)/>").findAll(first).sumOf { m -> Regex("number-columns-repeated=\"(\\d+)\"").find(m.groupValues[1])?.groupValues?.get(1)?.toInt() ?: 1 })
        assertNull(edit.moveColumns(0, 1, -1))
        assertEquals("=B1*2", edit.content(CellKey(0, 0, 2)))
        assertEquals("=Дані!B1+1", edit.content(CellKey(1, 0, 0)))
    }

    @Test fun sortableCells() {
        val bytes = javaClass.classLoader!!.getResource("odf/01-budget.ods")!!.readBytes()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        assertEquals(ua.hramota.doc.sheet.edit.Input.Number(6200.0), edit.stored(CellKey(0, 3, 1)))
        assertEquals(ua.hramota.doc.sheet.edit.Input.Text("Січень"), edit.stored(CellKey(0, 3, 0)))
        assertNull(edit.stored(CellKey(0, 3, 4)))
        assertEquals(ua.hramota.doc.sheet.edit.Input.Clear, edit.stored(CellKey(0, 30, 3)))
        // A style moved with a cell is written with its own name
        val s = edit.style(CellKey(0, 3, 4))!!
        edit.set(CellKey(0, 3, 1), ua.hramota.doc.sheet.edit.Input.Number(1.0))
        assertTrue(edit.setLook(CellKey(0, 3, 1), ua.hramota.doc.sheet.edit.CellLook(style = s.toString())))
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        assertTrue(OdsReader.read(ByteArrayInputStream(out.toByteArray())).sheets[0].let { sh -> sh.row(3)!!.let { r -> sh.style(r, r.find(1)).bold } })
    }

    @Test fun rowsInsertedAtTheSheetsEnd() {
        val bytes = javaClass.classLoader!!.getResource("odf/01-budget.ods")!!.readBytes()
        val edit = OdsEdit.open(ByteArrayInputStream(bytes))
        // Far down, among the empty rows LibreOffice fills its sheet with: the sheet stays as long
        assertNull(edit.moveRows(0, 1_048_570, 3))
        edit.set(CellKey(0, 1_048_570, 0), edit.parse("Унизу", false)!!)
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes), out)
        val c = part(out.toByteArray(), "content.xml")!!
        val rows = Regex("<table:table table:name=\"Бюджет\".*?</table:table>", RegexOption.DOT_MATCHES_ALL).find(c)!!.value
        val count = Regex("<table:table-row\\b([^>]*)>").findAll(rows).sumOf { m -> Regex("number-rows-repeated=\"(\\d+)\"").find(m.groupValues[1])?.groupValues?.get(1)?.toInt() ?: 1 }
        assertEquals(1_048_576, count)
        assertEquals("Унизу", OdsEdit.open(ByteArrayInputStream(out.toByteArray())).content(CellKey(0, 1_048_570, 0)))
    }
}
