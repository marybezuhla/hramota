package ua.hramota.doc.docx

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Damaged and hostile documents (from the independent audit): each must open or fail cleanly,
 * quickly, without exhausting memory or the stack.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocxHostileTest {
    private val fonts = Fonts(ApplicationProvider.getApplicationContext<android.content.Context>().assets)
    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(body: String, vararg extra: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("word/document.xml", "<?xml version=\"1.0\"?><w:document $w><w:body>$body</w:body></w:document>")
            extra.forEach { (n, t) -> put(n, t) }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /** Reads and lays out within [ms] milliseconds; returns the page count. */
    private fun open(input: ByteArrayInputStream, ms: Long = 20_000): Int {
        val start = System.nanoTime()
        val pages = DocxPages(DocxReader.read(input), fonts)
        if (pages.pageCount > 0) pages.draw(0, 400)
        val took = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $took ms", took < ms)
        return pages.pageCount
    }

    private val para = "<w:p><w:r><w:t>Текст</w:t></w:r></w:p>"

    @Test fun zeroAndNegativePageSize() {
        assertTrue(open(docx(para + "<w:sectPr><w:pgSz w:w=\"0\" w:h=\"-5\"/><w:pgMar w:left=\"999999\" w:right=\"999999\" w:top=\"0\" w:bottom=\"0\"/></w:sectPr>")) >= 1)
    }

    @Test fun hugePageSize() {
        assertTrue(open(docx(para + "<w:sectPr><w:pgSz w:w=\"2000000000\" w:h=\"2000000000\"/></w:sectPr>")) >= 1)
    }

    @Test fun hugeAndNegativeGridSpan() {
        val t = "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2000000000\"/></w:tcPr>$para</w:tc>" +
            "<w:tc><w:tcPr><w:gridSpan w:val=\"-7\"/></w:tcPr>$para</w:tc></w:tr></w:tbl>"
        assertTrue(open(docx(t)) >= 1)
    }

    @Test fun deeplyNestedLinks() {
        val depth = 20_000
        val p = "<w:p>" + "<w:hyperlink>".repeat(depth) + "<w:r><w:t>x</w:t></w:r>" + "</w:hyperlink>".repeat(depth) + "</w:p>"
        assertTrue(open(docx(p)) >= 1)
    }

    @Test fun deeplyNestedTables() {
        val depth = 3_000
        val t = "<w:tbl><w:tr><w:tc>".repeat(depth) + para + "</w:tc></w:tr></w:tbl>".repeat(depth)
        assertTrue(open(docx(t)) >= 1)
    }

    @Test fun hugeListStart() {
        val numbering = "<w:numbering $w><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"2000000000\"/>" +
            "<w:numFmt w:val=\"lowerLetter\"/><w:lvlText w:val=\"%1)\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
        val p = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>пункт</w:t></w:r></w:p>"
        assertTrue(open(docx(p.repeat(50), "word/numbering.xml" to numbering)) >= 1)
    }

    @Test fun badNumbersInFormatting() {
        val p = "<w:p><w:pPr><w:ind w:left=\"2000000000\" w:hanging=\"-2000000000\"/><w:spacing w:line=\"-500\" w:lineRule=\"exact\" w:before=\"99999999\"/>" +
            "<w:tabs><w:tab w:val=\"left\" w:pos=\"2000000000\"/></w:tabs></w:pPr><w:r><w:rPr><w:sz w:val=\"NaN\"/></w:rPr><w:t>текст</w:t></w:r>" +
            "<w:r><w:rPr><w:sz w:val=\"99999999\"/></w:rPr><w:t>ще</w:t></w:r></w:p>"
        assertTrue(open(docx(p)) >= 1)
    }

    @Test fun manyCellsInOneRow() {
        val t = "<w:tbl><w:tr>" + "<w:tc><w:p/></w:tc>".repeat(200_000) + "</w:tr></w:tbl>"
        assertTrue(open(docx(t)) >= 1)
    }

    @Test fun doctypeIsRefused() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE lol [<!ENTITY a \"aaaaaaaaaa\">]><w:document $w><w:body><w:p><w:r><w:t>&a;</w:t></w:r></w:p></w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("word/document.xml")); z.write(evil.toByteArray()); z.closeEntry() }
        assertThrows(IOException::class.java) { DocxReader.read(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test fun emptyAndTableOnlyDocuments() {
        assertTrue(open(docx("")) >= 1)
        assertTrue(open(docx("<w:tbl></w:tbl>")) >= 1)
        assertTrue(open(docx("<w:tbl><w:tr></w:tr></w:tbl>")) >= 1)
    }
}
