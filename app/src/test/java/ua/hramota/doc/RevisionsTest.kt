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
}
