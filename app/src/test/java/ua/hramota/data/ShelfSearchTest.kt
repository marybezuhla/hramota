package ua.hramota.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class ShelfSearchTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun placesAreFoundAcrossTheShelf() {
        val pdf = LibraryEntry("a1", "Звіт.pdf", "PDF", null, copy = "/x")
        val book = LibraryEntry("b2", "Кобзар.epub", "EPUB", null, copy = "/y", docTitle = "Кобзар")
        Vault.writeIn(context, ShelfSearch.dir(context), "a1.txt", "Перша сторінка\nДруга сторінка: бюджет на рік\nТретя".toByteArray())
        Vault.writeIn(context, ShelfSearch.dir(context), "b2.txt", "Садок вишневий коло хати\nХрущі над вишнями гудуть".toByteArray())
        val hits = ShelfSearch.search(context, listOf(pdf, book), "Бюджет")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].place); assertTrue(hits[0].pages)
        assertEquals("бюджет", hits[0].snippet.substring(hits[0].match.first, hits[0].match.last + 1))
        // Apostrophes and case are forgiven; titles are searched too
        assertEquals(2, ShelfSearch.search(context, listOf(pdf, book), "вишн").size)
        assertEquals(-1, ShelfSearch.search(context, listOf(pdf, book), "кобзар").first().place)
        ShelfSearch.clean(context, setOf("a1"))
        assertTrue(ShelfSearch.search(context, listOf(book), "вишн").isEmpty())
    }
}
