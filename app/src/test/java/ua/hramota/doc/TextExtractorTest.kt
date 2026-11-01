package ua.hramota.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TextExtractorTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test fun docxParagraphsHeadingsAndTrackedDeletions() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Договір</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Строк </w:t></w:r><w:r><w:t>оплати</w:t></w:r><w:r><w:tab/><w:t>5 днів</w:t></w:r>
<w:del><w:r><w:delText>10 днів</w:delText></w:r></w:del></w:p>
<w:p/>
</w:body></w:document>"""
        val p = TextExtractor.read(zip("[Content_Types].xml" to "<x/>", "word/document.xml" to xml), Format.DOCX)
        assertEquals(listOf(Paragraph("Договір", 1), Paragraph("Строк оплати\t5 днів"), Paragraph("")), p)
    }

    @Test fun odtParagraphsSpacesAndHeadings() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
<office:body><office:text>
<text:h text:outline-level="2">Розділ</text:h>
<text:p>Два<text:s text:c="2"/>пробіли<text:line-break/>новий рядок<text:note><text:note-body><text:p>виноска</text:p></text:note-body></text:note></text:p>
</office:text></office:body></office:document-content>"""
        val p = TextExtractor.read(zip("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to xml), Format.ODT)
        assertEquals(listOf(Paragraph("Розділ", 2), Paragraph("Два  пробіли\nновий рядок")), p)
    }

    @Test fun markdownHeadings() {
        val p = TextExtractor.read(ByteArrayInputStream("﻿# Назва\nтекст\r\n## Підрозділ".toByteArray()), Format.MARKDOWN)
        assertEquals(listOf(Paragraph("Назва", 1), Paragraph("текст"), Paragraph("Підрозділ", 2)), p)
    }

    @Test fun missingPartFails() {
        assertThrows(IOException::class.java) { TextExtractor.read(zip("other.xml" to "<a/>"), Format.DOCX) }
    }

    @Test fun headingStyleNames() {
        assertEquals(2, TextExtractor.headingLevel("Heading 2"))
        assertEquals(3, TextExtractor.headingLevel("Заголовок3"))
        assertEquals(1, TextExtractor.headingLevel("Title"))
        assertEquals(0, TextExtractor.headingLevel("Normal"))
    }

    @Test fun formatDetection() {
        assertEquals(Format.DOCX, Format.detect("Договір_v2.DOCX", null))
        assertEquals(Format.PDF, Format.detect("scan", "application/pdf"))
        assertEquals(Format.ODT, Format.detect(null, "application/vnd.oasis.opendocument.text"))
        assertEquals(Format.UNKNOWN, Format.detect("archive.zip", "application/zip"))
    }
}
