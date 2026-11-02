package ua.hramota.doc

import org.junit.Assert.assertEquals
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
}
