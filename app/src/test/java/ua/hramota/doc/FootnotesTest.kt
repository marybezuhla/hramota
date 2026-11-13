package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.Block
import ua.hramota.doc.docx.Inline
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class FootnotesTest {
    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun withParts(base: ByteArray, parts: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(base)).use { zin ->
                while (true) { val e = zin.nextEntry ?: break; if (e.name in parts) continue; z.putNextEntry(ZipEntry(e.name)); zin.copyTo(z); z.closeEntry() }
            }
            for ((n, t) in parts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test fun notesComeAfterTheTextWithTheirNumbers() {
        val doc = "<w:document $w><w:body><w:p><w:r><w:t>Текст із виноскою</w:t></w:r><w:r><w:footnoteReference w:id=\"7\"/></w:r><w:r><w:t> і ще одною</w:t></w:r><w:r><w:footnoteReference w:id=\"3\"/></w:r></w:p></w:body></w:document>"
        val notes = "<w:footnotes $w><w:footnote w:type=\"separator\" w:id=\"-1\"><w:p/></w:footnote>" +
            "<w:footnote w:id=\"3\"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> </w:t></w:r><w:r><w:t>Друга примітка.</w:t></w:r></w:p></w:footnote>" +
            "<w:footnote w:id=\"7\"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t>Перша примітка.</w:t></w:r></w:p></w:footnote></w:footnotes>"
        val d = DocxReader.read(ByteArrayInputStream(withParts(Templates.docx("uk-UA"), mapOf("word/document.xml" to doc, "word/footnotes.xml" to notes))))
        val paras = d.blocks.filterIsInstance<Block.Para>().map { p -> p.inlines.filterIsInstance<Inline.Text>().joinToString("") { it.text } }
        assertEquals("Текст із виноскою1 і ще одною2", paras[0])
        assertTrue(paras.toString(), paras.contains("1 Перша примітка."))
        assertTrue(paras.toString(), paras.contains("2 Друга примітка."))
        assertTrue(paras.indexOf("1 Перша примітка.") < paras.indexOf("2 Друга примітка."))
    }
}
