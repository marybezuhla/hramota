package ua.hramota.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.ViewerActivity
import ua.hramota.data.Library
import ua.hramota.data.LibraryEntry
import ua.hramota.doc.Paragraph

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CoversTest {
    @Test fun titlesComeFromTheDocument() {
        val paragraphs = listOf(Paragraph("12"), Paragraph("Протокол зустрічі", heading = 1), Paragraph("Присутні: усі"))
        val t = coverTitles("a1b2c3_scan.docx", propertiesTitle = "Звіт за квартал", paragraphs = paragraphs)
        assertEquals(listOf("Звіт за квартал", "Протокол зустрічі", "a1b2c3 scan"), t)
        // A book's own title first; nothing twice
        assertEquals(listOf("Кобзар", "Кобзар 1840"), coverTitles("Кобзар 1840.epub", bookTitle = "Кобзар", paragraphs = listOf(Paragraph("Кобзар"))))
        // Direction marks that could disguise a title are gone
        assertEquals("Лист", coverTitles("x.txt", propertiesTitle = "‮Лист").first())
    }

    @Test fun theSameTitleGetsTheSamePattern() {
        assertEquals(patternOf("Звіт"), patternOf("Звіт"))
        val all = listOf("Звіт", "Протокол", "Кошторис", "Лист", "Казки", "План", "Договір", "Нотатки").map { patternOf(it) }
        assertTrue(all.map { it.motif }.toSet().size > 1)
        assertTrue(all.all { it.count in 5..7 })
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Звіт 1 2", ViewerActivity.fileNameOf("Звіт: 1/2"))
        assertEquals("document", ViewerActivity.fileNameOf("..."))
        assertTrue(ViewerActivity.fileNameOf("я".repeat(300)).length <= 100)
    }

    @Test fun coverChoiceIsRemembered() {
        val library = Library.get(ApplicationProvider.getApplicationContext())
        val id = "44444444444444444444444444444444"
        library.update(id) { LibraryEntry(id, "Звіт.pdf", "PDF", null, coverTitle = "Річний звіт", cover = "drawn") }
        val back = library.entry(library.toJson(library.get(id)!!))!!
        assertEquals("Річний звіт", back.coverTitle)
        assertEquals("drawn", back.cover)
        // Something unknown from a backup is ignored
        val odd = library.toJson(library.get(id)!!).put("cover", "../x").put("coverTitle", "  ")
        val e = library.entry(odd)!!
        assertEquals("", e.cover)
        assertNull(e.coverTitle)
    }

    @Test fun picturesAsUsual() {
        assertTrue(usesPicture(LibraryEntry("x", "Книга.epub", "EPUB", null)))
        assertTrue(usesPicture(LibraryEntry("x", "Слайди.pptx", "PPTX", null)))
        assertFalse(usesPicture(LibraryEntry("x", "Звіт.pdf", "PDF", null)))
        assertTrue(usesPicture(LibraryEntry("x", "Звіт.pdf", "PDF", null, cover = "picture")))
        assertFalse(usesPicture(LibraryEntry("x", "Книга.epub", "EPUB", null, cover = "drawn")))
        assertEquals("Річний звіт", shownTitle(LibraryEntry("x", "Звіт.pdf", "PDF", null, coverTitle = "Річний звіт")))
        assertEquals("Звіт", shownTitle(LibraryEntry("x", "Звіт.pdf", "PDF", null)))
    }
}
