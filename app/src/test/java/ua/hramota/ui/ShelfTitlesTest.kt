package ua.hramota.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.ViewerActivity
import ua.hramota.data.Library
import ua.hramota.data.LibraryEntry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShelfTitlesTest {
    private val id = "5".repeat(32)

    @Test fun theBooksOwnTitleIsShown() {
        val e = LibraryEntry(id, "Sanderson_Z-imli-narodzheni_1_Z-imli-narodzheni.844650.fb2", "FB2", null, docTitle = "Земляні народжені")
        assertEquals("Земляні народжені", shownTitle(e))
        // A title chosen for the cover still comes first
        assertEquals("Моя книга", shownTitle(e.copy(coverTitle = "Моя книга")))
    }

    @Test fun aFileNameIsMadeReadable() {
        assertEquals("Sanderson Z-imli-narodzheni 1 Z-imli-narodzheni", readableName("Sanderson_Z-imli-narodzheni_1_Z-imli-narodzheni.844650.fb2"))
        assertEquals("Звіт 2024", readableName("Звіт 2024.docx"))
        assertEquals("v1.2", readableName("v1.2.pdf"))
        assertEquals("Budget.2025", readableName("Budget.2025.xlsx"))
    }

    @Test fun programTitlesAreNotTaken() {
        assertTrue(ViewerActivity.generatedTitle("Microsoft Word - звіт.doc"))
        assertTrue(ViewerActivity.generatedTitle("Document1"))
        assertTrue(ViewerActivity.generatedTitle("Презентація"))
        assertTrue(ViewerActivity.generatedTitle("zvit.docx"))
        assertFalse(ViewerActivity.generatedTitle("Річний звіт громади"))
    }

    @Test fun theTitleIsKept() {
        val library = Library.get(ApplicationProvider.getApplicationContext())
        val e = LibraryEntry(id, "f.fb2", "FB2", null, docTitle = "Земляні народжені")
        assertEquals("Земляні народжені", library.entry(library.toJson(e))?.docTitle)
    }
}
