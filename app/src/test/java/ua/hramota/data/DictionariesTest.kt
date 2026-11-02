package ua.hramota.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** A made-up StarDict dictionary: added from its files, words found as written and with endings changed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DictionariesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun make(dir: File, entries: List<Pair<String, String>>, gz: Boolean): List<File> {
        val dict = ByteArrayOutputStream(); val idx = ByteArrayOutputStream(); val d = DataOutputStream(idx)
        for ((w, text) in entries.sortedBy { it.first }) {
            val bytes = text.toByteArray()
            d.write(w.toByteArray()); d.write(0); d.writeInt(dict.size()); d.writeInt(bytes.size)
            dict.write(bytes)
        }
        val ifo = File(dir, "test.ifo").apply { writeText("StarDict's dict ifo file\nversion=2.4.2\nbookname=Вигаданий словник\nwordcount=${entries.size}\nidxfilesize=${idx.size()}\nsametypesequence=h\n") }
        val idxFile = File(dir, "test.idx").apply { writeBytes(idx.toByteArray()) }
        val dictFile = if (gz) File(dir, "test.dict.dz").apply { outputStream().use { o -> GZIPOutputStream(o).use { it.write(dict.toByteArray()) } } }
        else File(dir, "test.dict").apply { writeBytes(dict.toByteArray()) }
        return listOf(ifo, idxFile, dictFile)
    }

    @Test fun addsAndLooksUp() {
        val dir = File(context.cacheDir, "dictsrc").apply { deleteRecursively(); mkdirs() }
        val files = make(dir, listOf("млин" to "<b>млин</b><br>споруда, де мелють зерно", "вода" to "рідина", "Ґанок" to "прибудова біля входу"), gz = true)
        val name = Dictionaries.add(context, files.map { Uri.fromFile(it) }) { it.lastPathSegment!! }
        assertEquals("Вигаданий словник", name)
        assertEquals(1, Dictionaries.list(context).size)
        val exact = Dictionaries.lookUp(context, "Вода,")
        assertEquals("рідина", exact.single().text)
        // An ending changed: «млина» finds «млин»; markup becomes plain lines
        val inflected = Dictionaries.lookUp(context, "млина")
        assertEquals("млин", inflected.first().word)
        assertEquals("млин\nспоруда, де мелють зерно", inflected.first().text)
        // ґ stays ґ, case does not matter
        assertEquals("Ґанок", Dictionaries.lookUp(context, "ґанок").single().word)
        assertTrue(Dictionaries.lookUp(context, "кіт").isEmpty())
        Dictionaries.list(context).forEach { Dictionaries.remove(context, it.id) }
        assertTrue(Dictionaries.list(context).isEmpty())
    }

    @Test fun refusesWhatIsNotADictionary() {
        val f = File(context.cacheDir, "x.ifo").apply { writeText("not a dictionary") }
        assertTrue(runCatching { Dictionaries.add(context, listOf(Uri.fromFile(f))) { it.lastPathSegment!! } }.isFailure)
        assertTrue(Dictionaries.list(context).isEmpty())
    }
}
