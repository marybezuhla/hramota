package ua.hramota.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** A backup brings back the memory and kept documents, joins with what is there, and refuses a wrong password. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun roundTripAndMerge() {
        val library = Library.get(context)
        val copy = File(Library.copiesDir(context), "doc1.txt").apply { writeText("Вигаданий документ") }
        library.putShelf(ShelfDef("s1", "Робота", "list", 200f, 1))
        library.update("doc1") { LibraryEntry("doc1", "Нотатки.txt", "TXT", null, copy = copy.path, position = 5, total = 10, bookmarks = setOf(1, 4), openedAt = 100, shelves = setOf("s1"),
            notes = listOf(Note(1, 0, 1, 5, 2, "думка", at = 7))) }
        library.update("doc2") { LibraryEntry("doc2", "Звіт.pdf", "PDF", "content://x", bookmarks = setOf(3), openedAt = 50) }
        Thread.sleep(200)

        val out = ByteArrayOutputStream()
        Backup.write(context, out, "довгий пароль".toCharArray(), setOf("doc1"))
        val bytes = out.toByteArray()
        // Nothing readable inside without the password
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("Вигаданий"))

        // A wrong password changes nothing
        assertTrue(runCatching { Backup.restore(context, ByteArrayInputStream(bytes), "не той".toCharArray()) }.isFailure)

        // Start over, with one document already here holding another bookmark
        library.clearAll()
        library.update("doc2") { LibraryEntry("doc2", "Звіт.pdf", "PDF", "content://x", bookmarks = setOf(9), openedAt = 60) }
        val result = Backup.restore(context, ByteArrayInputStream(bytes), "довгий пароль".toCharArray())
        assertEquals(1, result.added); assertEquals(1, result.updated); assertEquals(1, result.documents)
        val d1 = library.get("doc1")!!
        assertEquals("Вигаданий документ", File(d1.copy!!).readText())
        assertEquals(setOf(1, 4), d1.bookmarks)
        assertEquals("думка", d1.notes.single().text)
        assertEquals(setOf("s1"), d1.shelves)
        assertTrue(library.shelves.value.any { it.id == "s1" && it.name == "Робота" })
        // Joined, never less: both bookmarks, the later place
        assertEquals(setOf(3, 9), library.get("doc2")!!.bookmarks)
    }
}
