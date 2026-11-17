package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.doc.docx.edit.Revisions

class RevisionsTest {
    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val doc = "<w:document $w><w:body>" +
        "<w:p><w:r><w:t>Строк оплати </w:t></w:r>" +
        "<w:del w:id=\"1\" w:author=\"Олена\"><w:r><w:delText>10</w:delText></w:r></w:del>" +
        "<w:ins w:id=\"2\" w:author=\"Олена\"><w:r><w:t>5</w:t></w:r></w:ins>" +
        "<w:r><w:rPr><w:b/><w:rPrChange w:id=\"3\" w:author=\"Олена\"><w:rPr><w:i/></w:rPr></w:rPrChange></w:rPr><w:t> днів</w:t></w:r></w:p>" +
        "<w:tbl><w:tr><w:trPr><w:ins w:id=\"4\"/></w:trPr><w:tc><w:p><w:r><w:t>Новий рядок</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
        "</w:body></w:document>"

    private fun text(x: String) = Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(x).joinToString("") { it.groupValues[1] }

    @Test fun acceptAll() {
        val x = Revisions.resolve(doc, accept = true)
        assertEquals("Строк оплати 5 днівНовий рядок", text(x))
        assertFalse(x.contains("w:ins") || x.contains("w:del") || x.contains("rPrChange"))
        assertTrue(x.contains("<w:rPr><w:b/></w:rPr>"))
    }

    @Test fun rejectAll() {
        val x = Revisions.resolve(doc, accept = false)
        assertEquals("Строк оплати 10 днів", text(x))
        assertFalse(x.contains("w:ins") || x.contains("w:delText") || x.contains("rPrChange"))
        // The old formatting is back
        assertTrue(x, x.contains("<w:rPr><w:i/></w:rPr>"))
        assertFalse(x.contains("<w:tr>"))
        assertEquals(4, Revisions.count(doc))
    }

    @Test fun aDeletedParagraphMarkJoinsTheNextParagraph() {
        val d = "<w:document $w><w:body>" +
            "<w:p><w:pPr><w:rPr><w:del w:id=\"1\" w:author=\"О\"/></w:rPr></w:pPr><w:r><w:t>Перший </w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>другий</w:t></w:r></w:p></w:body></w:document>"
        val a = Revisions.resolve(d, accept = true)
        // One paragraph, with the next one's properties
        assertEquals(1, Regex("<w:p>").findAll(a).count())
        assertTrue(a, a.contains("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>Перший </w:t></w:r><w:r><w:t>другий</w:t></w:r></w:p>"))
        // Rejected: both paragraphs stay, without the mark
        val r = Revisions.resolve(d, accept = false)
        assertEquals(2, Regex("<w:p>").findAll(r).count())
        assertFalse(r.contains("w:del"))
    }

    @Test fun rejectingPutsBackTheOldSectionAndTable() {
        val d = "<w:document $w><w:body><w:tbl><w:tblPr><w:tblW w:w=\"5000\"/><w:tblPrChange w:id=\"1\"><w:tblPr><w:tblW w:w=\"2000\"/></w:tblPr></w:tblPrChange></w:tblPr>" +
            "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>" +
            "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId1\"/><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>" +
            "<w:sectPrChange w:id=\"2\"><w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:sectPrChange></w:sectPr></w:body></w:document>"
        val r = Revisions.resolve(d, accept = false)
        assertTrue(r, r.contains("<w:tblPr><w:tblW w:w=\"2000\"/></w:tblPr>"))
        assertTrue(r, r.contains("<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId1\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>"))
        val a = Revisions.resolve(d, accept = true)
        assertTrue(a, a.contains("w:orient=\"landscape\"") && !a.contains("Change"))
    }
}
