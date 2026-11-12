package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.doc.docx.DocxReader
import ua.hramota.readingText
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.slides.PptxReader
import java.io.File

/**
 * Word, Excel and PowerPoint files with a password. "Standard" ones are made by LibreOffice (password
 * «Грамота-2026»); the "Agile" package was made to the specification by a script.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class OfficeCryptoTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()
    private val password = "Грамота-2026"

    @Test fun protectedFilesOpenWithTheirPassword() {
        val docx = bytes("protected/enc.docx")
        assertTrue(OfficeCrypto.isProtected(docx))
        val doc = DocxReader.read(OfficeCrypto.decrypt(docx, password).inputStream())
        assertTrue(doc.readingText().any { it.text.contains("Постачальник") })
        val wb = XlsxReader.read(OfficeCrypto.decrypt(bytes("protected/enc.xlsx"), password).inputStream())
        assertTrue(wb.sheets.isNotEmpty())
        val deck = PptxReader.read(OfficeCrypto.decrypt(bytes("protected/enc.pptx"), password).inputStream())
        assertTrue(deck.slides.isNotEmpty())
    }

    @Test(expected = WrongPasswordException::class)
    fun aWrongPasswordIsSaidSo() { OfficeCrypto.decrypt(bytes("protected/enc.docx"), "грамота-2026") }

    @Test fun agile() {
        val out = OfficeCrypto.decrypt(bytes("protected/agile.info"), bytes("protected/agile.package"), password)
        assertTrue(out.contentEquals(bytes("docs/02-paragraphs.docx")))
    }

    @Test(expected = WrongPasswordException::class)
    fun agileWrongPassword() { OfficeCrypto.decrypt(bytes("protected/agile.info"), bytes("protected/agile.package"), "x") }

    @Test fun ordinaryFilesAreNotProtected() {
        assertFalse(OfficeCrypto.isProtected(bytes("docs/02-paragraphs.docx")))
        assertFalse(OfficeCrypto.mayBeProtected(bytes("docs/02-paragraphs.docx")))
        assertTrue(OfficeCrypto.mayBeProtected(bytes("protected/enc.xlsx")))
        assertEquals(false, OfficeCrypto.isProtected(ByteArray(10)))
    }
}
