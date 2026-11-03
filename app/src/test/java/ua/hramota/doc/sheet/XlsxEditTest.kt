package ua.hramota.doc.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.sheet.edit.CellKey
import ua.hramota.doc.sheet.edit.Formula
import ua.hramota.doc.sheet.edit.Value
import ua.hramota.doc.sheet.edit.XlsxEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Editing XLSX: changed cells and the formulas depending on them; everything else as it was. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class XlsxEditTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private fun part(b: ByteArray, name: String): String? = ZipInputStream(ByteArrayInputStream(b)).use { z ->
        while (true) { val e = z.nextEntry ?: return null; if (e.name == name) return String(z.readBytes()) }
        @Suppress("UNREACHABLE_CODE") null
    }
    private fun save(path: String, edit: XlsxEdit, label: String): ByteArray {
        val out = ByteArrayOutputStream()
        edit.save(ByteArrayInputStream(bytes(path)), out)
        // Kept for CI, where LibreOffice opens each edited file
        File("build/edited").apply { mkdirs() }.let { File(it, "$label.xlsx").writeBytes(out.toByteArray()) }
        return out.toByteArray()
    }

    private val cells = object : Formula.Cells {
        override fun value(sheet: String?, row: Int, col: Int) = when (row to col) { 0 to 0 -> Value.Num(2.0); 1 to 0 -> Value.Num(3.0); 2 to 0 -> Value.Text("x"); else -> Value.Empty }
    }
    private fun ev(f: String) = Formula.eval(Formula.parse(f).node!!, cells)

    @Test fun formulas() {
        assertEquals(Value.Num(7.0), ev("1+2*3"))
        assertEquals(Value.Num(4.0), ev("-2^2"))
        assertEquals(Value.Num(5.0), ev("SUM(A1:A3)"))
        assertEquals(Value.Num(2.5), ev("AVERAGE(A1:A2)"))
        assertEquals(Value.Text("2 шт"), ev("A1&\" шт\""))
        assertEquals(Value.Text("так"), ev("IF(A1>1,\"так\",\"ні\")"))
        assertEquals(Value.Num(2.68), ev("ROUND(2.675,2)"))
        assertEquals(Value.Num(3.0), ev("SUMIF(A1:A3,\">2\")"))
        assertEquals(Value.DIV0, ev("A1/0"))
        assertFalse(Formula.parse("VLOOKUP(1,A1:B2,2)").supported)
        assertEquals("B3+\$B\$2+C\$3+SUM(B3:B5)", Formula.shift("A1+\$B\$2+B\$3+SUM(A1:A3)", 0, 0, 2, 1))
        assertEquals("SUM(A1,A2)*1.5", Formula.normalize("SUM(A1;A2)*1,5"))
        assertEquals(1234.5, Formula.parseNumber("1 234,5")!!, 0.0)
        assertEquals(0.15, Formula.parseNumber("15%")!!, 1e-12)
        assertNull(Formula.parseNumber("12 шт"))
        assertEquals(1234.5, Formula.parseNumber("1.234,5")!!, 0.0)
        // A cell referred to counts like a range: empty and text cells are left out
        assertEquals(Value.Num(2.5), ev("AVERAGE(A1,A2,A4)"))
        assertEquals(Value.Num(2.0), ev("SUM(A1,A3)"))
        assertEquals("1E+20", Formula.show(1e20))
        assertEquals(Value.Num(2.0), ev("ROUNDUP(1.0000000001,0)"))
    }

    @Test fun valueChangesFlowIntoFormulasAndTheFileOpens() {
        val path = "sheets/01-budget.xlsx"
        val edit = XlsxEdit.open(ByteArrayInputStream(bytes(path)))
        assertEquals("=SUM(B4:D4)", edit.content(CellKey(0, 3, 4)))
        edit.set(CellKey(0, 3, 1), edit.parse("7000", false)!!)
        edit.set(CellKey(0, 20, 0), edit.parse("Нове", false)!!)
        edit.set(CellKey(0, 20, 1), edit.parse("=SUM(E4:E9)", false)!!)
        val v = edit.recalc()
        assertEquals(Value.Num(11500.5), v[CellKey(0, 3, 4)])
        val saved = save(path, edit, "edited-budget")
        val sheet = XlsxReader.read(ByteArrayInputStream(saved)).sheets[0]
        assertEquals("Нове", sheet.text(sheet.row(20)!!, sheet.row(20)!!.find(0)))
        // Rows not touched are written exactly as they were
        val before = part(bytes(path), "xl/worksheets/sheet1.xml")!!
        val after = part(saved, "xl/worksheets/sheet1.xml")!!
        val row6 = Regex("<row r=\"6\".*?</row>").find(before)!!.value
        assertTrue(after.contains(row6))
        assertTrue(part(saved, "xl/workbook.xml")!!.contains("fullCalcOnLoad=\"1\""))
    }

    @Test fun brokenFormulasAreRefusedAndCommaFormsAccepted() {
        val edit = XlsxEdit.open(ByteArrayInputStream(bytes("sheets/01-budget.xlsx")))
        assertNull(edit.parse("=SUM(A1", false))
        assertNull(edit.parse("==A1", false))
        assertEquals(ua.hramota.doc.sheet.edit.Input.Formula("ROUND(A1,2)*1.5"), edit.parse("=ROUND(A1;2)*1,5", false))
        assertEquals(ua.hramota.doc.sheet.edit.Input.Formula("A1*1.5"), edit.parse("=A1*1,5", false))
        // Shown as typed in Ukrainian Excel
        assertEquals("=SUM(B4:D4)", edit.content(CellKey(0, 3, 4)))
    }

    @Test fun sharedFormulasOtherSheetsDatesAndCalcChain() {
        val path = "docs-edit/shared.xlsx"
        val edit = XlsxEdit.open(ByteArrayInputStream(bytes(path)))
        assertEquals("=B3*Ціни!A2", edit.content(CellKey(0, 2, 2)))
        edit.set(CellKey(1, 0, 0), edit.parse("150", false)!!)
        edit.set(CellKey(0, 2, 2), edit.parse("=B3*Ціни!\$A\$1", false)!!)
        edit.set(CellKey(0, 0, 3), edit.parse("05.11.2026", true)!!)
        val v = edit.recalc()
        assertEquals(Value.Num(1500.0), v[CellKey(0, 1, 2)])
        assertEquals(Value.Num(300.0), v[CellKey(0, 2, 2)])
        assertEquals(Value.Num(1800.0), v[CellKey(0, 4, 2)])
        assertEquals(Value.Num(46331.0), v[CellKey(0, 0, 3)])
        val saved = save(path, edit, "edited-shared")
        assertNull(part(saved, "xl/calcChain.xml"))
        assertFalse(part(saved, "[Content_Types].xml")!!.contains("calcChain"))
        // The shared group is written out cell by cell
        assertTrue(part(saved, "xl/worksheets/sheet1.xml")!!.contains("<x:f>B4*Ціни!A3</x:f>"))
        XlsxReader.read(ByteArrayInputStream(saved))
    }

    @Test fun rowsMovingFollowRows() {
        val m = Formula.RowMove(4, 2)
        assertEquals("SUM(A1:A12)", Formula.moveRows("SUM(A1:A10)", true, "Дані", m))
        assertEquals("\$A\$9+A3", Formula.moveRows("\$A\$7+A3", true, "Дані", m))
        assertEquals("'Дані'!A7+Інший!A5", Formula.moveRows("'Дані'!A5+Інший!A5", false, "Дані", m))
        assertEquals("\"A5\"&LOG10(A7)", Formula.moveRows("\"A5\"&LOG10(A5)", true, "Дані", m))
        assertEquals("[1]Дані!A5", Formula.moveRows("[1]Дані!A5", false, "Дані", m))
        assertEquals("SUM(7:8)+SUM(A:A)", Formula.moveRows("SUM(5:6)+SUM(A:A)", true, "Дані", m))
        assertNull(Formula.moveRows("SUM(Аркуш1:Аркуш3!A1)", true, "Дані", m))
        val d = Formula.RowMove(4, -2)
        assertEquals("#REF!+A5+SUM(A4:A4)+SUM(#REF!)", Formula.moveRows("A5+A7+SUM(A4:A6)+SUM(A5:A6)", true, "Дані", d))
    }

    @Test fun insertedAndDeletedRowsMoveEverythingThatRefersToThem() {
        val edit = XlsxEdit.open(ByteArrayInputStream(bytes("docs-edit/rows.xlsx")))
        assertNull(edit.moveRows(0, 4, 2))
        // The total takes in the moved rows and is worked out again (rows came in inside it)
        assertEquals("=SUM(A1:A12)", edit.content(CellKey(0, 0, 2)))
        assertEquals(Value.Num(550.0), edit.recalc()[CellKey(0, 0, 2)])
        // A formula that only refers to a moved cell keeps its value: not worked out again
        assertNull(edit.recalc()[CellKey(0, 1, 2)])
        assertEquals("=A7", edit.content(CellKey(0, 1, 2)))
        val out = save("docs-edit/rows.xlsx", edit, "rows-inserted")
        val sheet = part(out, "xl/worksheets/sheet1.xml")!!
        assertTrue(sheet.contains("<mergeCell ref=\"D3:E7\"/>"))
        assertTrue(sheet.contains("sqref=\"F2:F10\""))
        assertTrue(sheet.contains("<autoFilter ref=\"A1:B12\"/>"))
        assertTrue(part(out, "xl/worksheets/sheet2.xml")!!.contains("'Дані'!A7+1"))
        assertTrue(part(out, "xl/workbook.xml")!!.contains("'Дані'!\$A\$2:\$A\$11"))
        // Read again as the viewer does: the values are in their new rows
        val back = XlsxReader.read(ByteArrayInputStream(out)).sheets[0]
        assertEquals("50", back.text(back.row(6)!!, 0))
        assertNull(back.row(4))

        val del = XlsxEdit.open(ByteArrayInputStream(bytes("docs-edit/rows.xlsx")))
        assertNull(del.moveRows(0, 4, -2))
        assertEquals(Value.REF, del.recalc()[CellKey(0, 1, 2)])
        val d = save("docs-edit/rows.xlsx", del, "rows-deleted")
        assertTrue(part(d, "xl/worksheets/sheet1.xml")!!.contains("<f>SUM(A1:A8)</f>"))
        // Undone: the file as it was
        val again = XlsxEdit.open(ByteArrayInputStream(bytes("docs-edit/rows.xlsx")))
        val before = again.snapshot()
        again.moveRows(0, 0, 3)
        again.restore(before)
        assertEquals("=A5", again.content(CellKey(0, 1, 2)))
    }

    @Test fun rowsAreNotMovedUnderTables() {
        val edit = XlsxEdit.open(ByteArrayInputStream(bytes("docs-edit/rows-table.xlsx")))
        assertEquals(XlsxEdit.RowRefusal.SHEET, edit.moveRows(0, 2, 1))
    }
}
