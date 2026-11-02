package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.DocxWriter
import ua.hramota.doc.docx.WordBinaryReader
import ua.hramota.doc.sheet.XlsReader
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.sheet.XlsxWriter
import ua.hramota.readingText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Old Word and Excel files kept as DOCX and XLSX: the same text and values come back. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class ConvertTest {
    private fun file(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI())

    @Test fun docBecomesDocxWithTheSameText() {
        val dir = file("docs-doc")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".doc") }.sortedBy { it.name }) {
            val doc = f.inputStream().use { WordBinaryReader.read(it) }
            val out = ByteArrayOutputStream()
            DocxWriter.write(doc, out, "uk-UA")
            File("build/edited").apply { mkdirs() }.let { File(it, "converted-${f.nameWithoutExtension}.docx").writeBytes(out.toByteArray()) }
            val back = DocxReader.read(ByteArrayInputStream(out.toByteArray()))
            // A list label is written as text and a tab: compared with spacing evened out
            fun norm(t: String) = t.replace('\t', ' ').replace(Regex(" +"), " ").trim()
            assertEquals(f.name, doc.readingText().map { norm(it.text) }, back.readingText().map { norm(it.text) })
            assertEquals(f.name, doc.page, back.page)
        }
    }

    @Test fun xlsBecomesXlsxWithTheSameValues() {
        val wb = file("sheets/01-budget.xls").inputStream().use { XlsReader.read(it) }
        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out)
        File("build/edited").apply { mkdirs() }.let { File(it, "converted-budget.xlsx").writeBytes(out.toByteArray()) }
        val back = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(wb.sheets.map { it.name }, back.sheets.map { it.name })
        for ((a, b) in wb.sheets.zip(back.sheets)) {
            assertEquals(a.rows.map { r -> r.index to r.cols.indices.map { a.text(r, it) } }, b.rows.map { r -> r.index to r.cols.indices.map { b.text(r, it) } })
            assertEquals(a.merges, b.merges)
            assertEquals(a.frozenRows, b.frozenRows)
        }
    }

    private fun sheet(name: String, date1904: Boolean = false, hidden: Boolean = false, heights: Map<Int, Float> = emptyMap()) = ua.hramota.doc.sheet.Sheet(
        name, listOf(ua.hramota.doc.sheet.SheetRow(0, intArrayOf(0, 1), intArrayOf(1, 0), doubleArrayOf(45000.0, 0.0), arrayOf(null, "текст"))),
        listOf(ua.hramota.doc.sheet.CellStyle.DEFAULT, ua.hramota.doc.sheet.CellStyle(format = "dd.mm.yyyy")),
        emptyMap(), 48f, heights, 15f, emptyList(), 0, 0, date1904, hidden,
    )

    @Test fun xlsxKeeps1904DatesHiddenSheetsAndValidNames() {
        val long = "Дуже довга назва аркуша для звіту"
        val wb = ua.hramota.doc.sheet.Workbook(listOf(
            sheet("Дані", date1904 = true, heights = mapOf(3 to 0f, 5 to 30f)), sheet("дані", date1904 = true), sheet(long, date1904 = true),
            sheet(long, date1904 = true), sheet("'Звіт'", date1904 = true), sheet("Прихований", date1904 = true, hidden = true),
        ))
        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out)
        File("build/edited").apply { mkdirs() }.let { File(it, "converted-names.xlsx").writeBytes(out.toByteArray()) }
        val back = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        // The hidden sheet stays hidden (the viewer does not show it)
        assertEquals(5, back.sheets.size)
        val names = back.sheets.map { it.name }
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
        assertTrue(names.toString(), names.all { it.length <= 31 && !it.startsWith("'") && !it.endsWith("'") })
        // The same date, not one four years earlier
        for ((a, b) in wb.sheets.zip(back.sheets)) assertEquals(a.text(a.rows[0], 0), b.text(b.rows[0], 0))
        assertEquals(0f, back.sheets[0].height(3))
        assertEquals(30f, back.sheets[0].height(5))
    }

    @Test fun docxMergesFollowTheColumnNotTheCellNumber() {
        fun para(t: String) = ua.hramota.doc.docx.Block.Para(ua.hramota.doc.docx.ParaProps(), ua.hramota.doc.docx.RunProps(), listOf(ua.hramota.doc.docx.Inline.Text(t, ua.hramota.doc.docx.RunProps())), null, null)
        fun cell(t: String, span: Int = 1, above: Boolean = false) = ua.hramota.doc.docx.Cell(span, above, if (above) emptyList() else listOf(para(t)), null)
        // Row 1: a wide cell then C; row 2: A, B, and C merged into the one above; then two equal rows
        val rows = listOf(
            ua.hramota.doc.docx.Row(listOf(cell("AB", 2), cell("C")), null, false),
            ua.hramota.doc.docx.Row(listOf(cell("a"), cell("b"), cell("", above = true)), null, false),
            ua.hramota.doc.docx.Row(listOf(cell("x"), cell("y"), cell("z")), null, false),
            ua.hramota.doc.docx.Row(listOf(cell("x"), cell("y"), cell("z")), null, false),
            ua.hramota.doc.docx.Row(emptyList(), null, false),
        )
        val doc = ua.hramota.doc.docx.DocxDocument(ua.hramota.doc.docx.PageSetup(), listOf(ua.hramota.doc.docx.Block.Table(listOf(2000, 2000, 2000), rows, true, 0), para("кінець")),
            emptyMap(), ua.hramota.doc.docx.ThemeFonts(), emptyMap(), compatibilityMode = 12)
        val out = ByteArrayOutputStream()
        DocxWriter.write(doc, out, "uk-UA")
        File("build/edited").apply { mkdirs() }.let { File(it, "converted-merges.docx").writeBytes(out.toByteArray()) }
        val back = DocxReader.read(ByteArrayInputStream(out.toByteArray()))
        val t = back.blocks.first() as ua.hramota.doc.docx.Block.Table
        assertEquals(4, t.rows.size)
        assertEquals(listOf(false, false), t.rows[0].cells.map { it.mergedAbove })
        assertEquals(listOf(false, false, true), t.rows[1].cells.map { it.mergedAbove })
        assertEquals(listOf(false, false, false), t.rows[3].cells.map { it.mergedAbove })
        // The old layout is kept (an old DOC breaks pages as Word 2007 did)
        assertEquals(12, back.compatibilityMode)
    }

    @Test fun newDocumentsOpenAndLeaveCompatibilityModeOff() {
        File("build/edited").apply { mkdirs() }.let {
            File(it, "new-document.docx").writeBytes(Templates.docx("uk-UA"))
            File(it, "new-workbook.xlsx").writeBytes(Templates.xlsx("Аркуш1"))
        }
        assertEquals(15, DocxReader.read(ByteArrayInputStream(Templates.docx("uk-UA"))).compatibilityMode)
        assertEquals("Аркуш1", XlsxReader.read(ByteArrayInputStream(Templates.xlsx("Аркуш1"))).sheets.single().name)
    }
}
