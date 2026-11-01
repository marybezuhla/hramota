package ua.hramota.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** A marked passage, from a place in one block to a place in another, with its colour and an optional note. */
data class Note(val block: Int, val start: Int, val endBlock: Int, val end: Int, val color: Int, val text: String = "", val at: Long = 0)

/**
 * What Hramota remembers about a document, by its content (not its name or place): the same file
 * downloaded again a year later opens with its bookmarks and where reading stopped.
 */
data class LibraryEntry(
    /** Fingerprint of the content. */
    val id: String,
    val title: String,
    /** "PDF", "DOCX", "EPUB"… */
    val format: String,
    /** Where the file was last opened from (may no longer be reachable). */
    val uri: String?,
    /** A copy kept inside Hramota, if the person chose so (always for books). */
    val copy: String? = null,
    /** Page (or position in the text) where reading stopped, and how many there are. */
    val position: Int = 0,
    val total: Int = 0,
    val bookmarks: Set<Int> = emptySet(),
    val onShelf: Boolean = false,
    val openedAt: Long = 0,
    val notes: List<Note> = emptyList(),
)

/**
 * The document memory: one small JSON file in the app's private storage (no cloud backup), a few
 * hundred bytes per document. Exported only by the person, inside an encrypted backup.
 */
class Library private constructor(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val state = MutableStateFlow(load())
    val entries: StateFlow<Map<String, LibraryEntry>> = state.asStateFlow()

    fun get(id: String): LibraryEntry? = state.value[id]

    @Synchronized fun update(id: String, change: (LibraryEntry?) -> LibraryEntry?) {
        val map = state.value.toMutableMap()
        val next = change(map[id])
        if (next == null) map.remove(id) else map[id] = next
        state.value = map
        save(map)
    }

    fun toggleBookmark(id: String, page: Int) = update(id) { e ->
        e ?: return@update null
        val set = e.bookmarks.toSortedSet()
        if (!set.add(page)) set.remove(page)
        e.copy(bookmarks = set.take(MAX_BOOKMARKS).toSet())
    }

    /** Adds a note, or replaces the one made at the same moment ([Note.at] is its identity). */
    fun putNote(id: String, note: Note) = update(id) { e ->
        e ?: return@update null
        e.copy(notes = (e.notes.filter { it.at != note.at } + note).sortedWith(compareBy({ it.block }, { it.start })).take(MAX_NOTES))
    }

    fun removeNote(id: String, at: Long) = update(id) { it?.copy(notes = it.notes.filter { n -> n.at != at }) }

    fun setPosition(id: String, position: Int, total: Int) = update(id) { it?.copy(position = position.coerceAtLeast(0), total = total.coerceAtLeast(0)) }

    /** Forgets everything about a document, and deletes the copy kept inside Hramota. */
    fun forget(id: String) {
        get(id)?.copy?.let { File(it).delete() }
        File(coversDir(context), "$id.png").delete()
        update(id) { null }
    }

    fun clearAll() { state.value.keys.toList().forEach(::forget) }

    private fun load(): Map<String, LibraryEntry> = try {
        val json = JSONArray(String(file.readFully()))
        (0 until json.length()).associate { i ->
            val o = json.getJSONObject(i)
            val e = LibraryEntry(
                id = o.getString("id"), title = o.optString("title"), format = o.optString("format"),
                uri = o.optString("uri").ifEmpty { null }, copy = o.optString("copy").ifEmpty { null },
                position = o.optInt("position"), total = o.optInt("total"),
                bookmarks = o.optJSONArray("bookmarks")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                onShelf = o.optBoolean("shelf"), openedAt = o.optLong("opened"),
                notes = o.optJSONArray("notes")?.let { a ->
                    (0 until minOf(a.length(), MAX_NOTES)).mapNotNull { k ->
                        val n = a.optJSONObject(k) ?: return@mapNotNull null
                        Note(n.optInt("b"), n.optInt("s"), n.optInt("eb"), n.optInt("e"), n.optInt("c"), n.optString("t").take(MAX_NOTE_TEXT), n.optLong("at"))
                    }
                } ?: emptyList(),
            )
            e.id to e
        }
    } catch (e: Exception) { emptyMap() }

    private fun save(map: Map<String, LibraryEntry>) {
        val json = JSONArray()
        for (e in map.values) json.put(JSONObject().apply {
            put("id", e.id); put("title", e.title); put("format", e.format)
            e.uri?.let { put("uri", it) }; e.copy?.let { put("copy", it) }
            put("position", e.position); put("total", e.total)
            put("bookmarks", JSONArray(e.bookmarks.sorted())); put("shelf", e.onShelf); put("opened", e.openedAt)
            if (e.notes.isNotEmpty()) put("notes", JSONArray().apply {
                for (n in e.notes) put(JSONObject().apply { put("b", n.block); put("s", n.start); put("eb", n.endBlock); put("e", n.end); put("c", n.color); if (n.text.isNotEmpty()) put("t", n.text); put("at", n.at) })
            })
        })
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out) }
    }

    companion object {
        const val MAX_BOOKMARKS = 1000
        const val MAX_NOTES = 5000
        const val MAX_NOTE_TEXT = 10_000
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library = instance ?: synchronized(this) { instance ?: Library(context.applicationContext).also { instance = it } }

        fun coversDir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
        fun copiesDir(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }

        /**
         * A fingerprint of the content: its size and the first and last megabyte, hashed together.
         * Fast for any size of file, and the same for the same file wherever it comes from.
         */
        fun fingerprint(context: Context, uri: Uri): String? = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val digest = MessageDigest.getInstance("SHA-256")
                val size = pfd.statSize
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    digest.update(size.toString().toByteArray())
                    val buf = java.nio.ByteBuffer.allocate(CHUNK)
                    fun readAt(pos: Long) {
                        buf.clear()
                        var p = pos
                        while (buf.hasRemaining()) { val n = channel.read(buf, p); if (n <= 0) break; p += n }
                        buf.flip(); digest.update(buf)
                    }
                    if (size > 0) {
                        readAt(0)
                        if (size > CHUNK) readAt(maxOf(CHUNK.toLong(), size - CHUNK))
                    } else {
                        // A stream with no known size: the first two megabytes
                        val bytes = ByteArray(CHUNK * 2)
                        var n = 0
                        while (n < bytes.size) { val k = input.read(bytes, n, bytes.size - n); if (k < 0) break; n += k }
                        digest.update(bytes, 0, n)
                    }
                }
                digest.digest().take(16).joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) { null }

        private const val CHUNK = 1024 * 1024
    }
}
