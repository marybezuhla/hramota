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
        val copy = File(Library.copiesDir(context), "11111111111111111111111111111111.txt").apply { writeText("Вигаданий документ") }
        library.putShelf(ShelfDef("s1", "Робота", "list", 200f, 1))
        library.update("11111111111111111111111111111111") { LibraryEntry("11111111111111111111111111111111", "Нотатки.txt", "TXT", null, copy = copy.path, position = 5, total = 10, bookmarks = setOf(1, 4), openedAt = 100, shelves = setOf("s1"),
            notes = listOf(Note(1, 0, 1, 5, 2, "думка", at = 7))) }
        library.update("22222222222222222222222222222222") { LibraryEntry("22222222222222222222222222222222", "Звіт.pdf", "PDF", "content://x", bookmarks = setOf(3), openedAt = 50) }
        Thread.sleep(200)

        val out = ByteArrayOutputStream()
        Backup.write(context, out, "довгий пароль".toCharArray(), setOf("11111111111111111111111111111111"))
        val bytes = out.toByteArray()
        // Nothing readable inside without the password
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("Вигаданий"))

        // A wrong password changes nothing
        assertTrue(runCatching { Backup.restore(context, ByteArrayInputStream(bytes), "не той".toCharArray()) }.isFailure)

        // Start over, with one document already here holding another bookmark
        library.clearAll()
        library.update("22222222222222222222222222222222") { LibraryEntry("22222222222222222222222222222222", "Звіт.pdf", "PDF", "content://x", bookmarks = setOf(9), openedAt = 60) }
        val result = Backup.restore(context, ByteArrayInputStream(bytes), "довгий пароль".toCharArray())
        assertEquals(1, result.added); assertEquals(1, result.updated); assertEquals(1, result.documents)
        val d1 = library.get("11111111111111111111111111111111")!!
        assertEquals("Вигаданий документ", File(d1.copy!!).readText())
        assertEquals(setOf(1, 4), d1.bookmarks)
        assertEquals("думка", d1.notes.single().text)
        assertEquals(setOf("s1"), d1.shelves)
        assertTrue(library.shelves.value.any { it.id == "s1" && it.name == "Робота" })
        // Joined, never less: both bookmarks, the later place
        assertEquals(setOf(3, 9), library.get("22222222222222222222222222222222")!!.bookmarks)
    }

    @Test fun refusesChangedOrCutFiles() {
        val library = Library.get(context)
        library.update("33333333333333333333333333333333") { LibraryEntry("33333333333333333333333333333333", "Лист.txt", "TXT", null, bookmarks = setOf(2), openedAt = 1) }
        Thread.sleep(200)
        val out = ByteArrayOutputStream()
        Backup.write(context, out, "пароль-ґудзик".toCharArray(), emptySet())
        val bytes = out.toByteArray()
        fun refused(b: ByteArray) = runCatching { Backup.restore(context, ByteArrayInputStream(b), "пароль-ґудзик".toCharArray()) }.exceptionOrNull() is Backup.Refused
        // The salt changed in the header
        assertTrue(refused(bytes.copyOf().also { it[12] = (it[12] + 1).toByte() }))
        // The iteration count lowered
        assertTrue(refused(bytes.copyOf().also { it[7] = 0 }))
        // Cut short
        assertTrue(refused(bytes.copyOfRange(0, bytes.size - 3)))
        // Not a backup at all
        assertTrue(refused("просто текст".toByteArray()))
        // Whole, it restores
        assertTrue(runCatching { Backup.restore(context, ByteArrayInputStream(bytes), "пароль-ґудзик".toCharArray()) }.isSuccess)
    }

    @Test fun neverTakesAnotherDocumentsFile() {
        val library = Library.get(context)
        val mine = File(Library.copiesDir(context), "other.txt").apply { writeText("Чужий документ") }
        library.update("55555555555555555555555555555555") { LibraryEntry("55555555555555555555555555555555", "Інше.txt", "TXT", null, copy = mine.path) }
        val src = File(Library.copiesDir(context), "44444444444444444444444444444444.txt").apply { writeText("Мій документ") }
        library.update("44444444444444444444444444444444") { LibraryEntry("44444444444444444444444444444444", "Мій.txt", "TXT", null, copy = src.path) }
        Thread.sleep(200)
        val out = ByteArrayOutputStream()
        Backup.write(context, out, "довгий пароль".toCharArray(), setOf("44444444444444444444444444444444"))
        library.dropCopy("44444444444444444444444444444444")
        Backup.restore(context, ByteArrayInputStream(out.toByteArray()), "довгий пароль".toCharArray())
        assertEquals("Мій документ", File(library.get("44444444444444444444444444444444")!!.copy!!).readText())
        assertEquals("Чужий документ", mine.readText())
    }

    /** An id from a backup names files: only a fingerprint is accepted, nothing that could climb out. */
    @Test fun refusesIdsThatAreNotFingerprints() {
        val library = Library.get(context)
        for (bad in listOf("../vault", "../../shared_prefs/settings", "a/b", "", "ABCDEF")) assertEquals(null, library.entry(org.json.JSONObject().put("id", bad)))
        assertEquals("0123456789abcdef0123456789abcdef", library.entry(org.json.JSONObject().put("id", "0123456789abcdef0123456789abcdef"))?.id)
    }
}
