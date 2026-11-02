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
 * A shelf of one's own: a name, a look ("modern", "wood" or "list") and a colour (a hue in degrees;
 * below 0 the natural colour). Documents are put on it like tags; [ALL] holds every kept document.
 */
data class ShelfDef(val id: String, val name: String, val style: String = "modern", val hue: Float = -1f, val created: Long = 0) {
    companion object { const val ALL = "all" }
}

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
    /** Shelves of one's own the document stands on (it is on the shelf "All" while its copy is kept). */
    val shelves: Set<String> = emptySet(),
) {
    /** On the shelf: kept inside Hramota. */
    val kept: Boolean get() = copy != null
}

/**
 * The document memory: one small JSON file in the app's private storage (no cloud backup), a few
 * hundred bytes per document. Exported only by the person, inside an encrypted backup.
 */
class Library private constructor(private val context: Context) {
    private val state = MutableStateFlow(load())
    val entries: StateFlow<Map<String, LibraryEntry>> = state.asStateFlow()
    private val shelfState = MutableStateFlow(loadShelves())
    /** "All" first, then one's own shelves in the order they were made. */
    val shelves: StateFlow<List<ShelfDef>> = shelfState.asStateFlow()

    @Synchronized fun putShelf(def: ShelfDef) {
        val list = shelfState.value.filter { it.id != def.id } + def
        shelfState.value = list.sortedWith(compareBy({ it.id != ShelfDef.ALL }, { it.created }))
        saveShelves(shelfState.value)
    }

    /** Takes a shelf away; the documents on it stay in Hramota. */
    fun removeShelf(id: String) {
        if (id == ShelfDef.ALL) return
        synchronized(this) { shelfState.value = shelfState.value.filter { it.id != id }; saveShelves(shelfState.value) }
        for (e in state.value.values) if (id in e.shelves) update(e.id) { it?.copy(shelves = it.shelves - id) }
    }

    fun setShelves(id: String, shelves: Set<String>) = update(id) { e -> e?.copy(shelves = shelves.filter { s -> shelfState.value.any { it.id == s } }.toSet()) }

    private fun loadShelves(): List<ShelfDef> {
        val list = if (Vault.locked(context)) emptyList() else try {
            val json = JSONArray(String(Vault.readSmall(context, "shelves.json") ?: throw java.io.FileNotFoundException()))
            (0 until minOf(json.length(), MAX_SHELVES)).mapNotNull { i -> json.optJSONObject(i)?.let(::shelfFrom) }
        } catch (e: Exception) { emptyList() }
        // "All" is always there; its first look follows the old wooden-shelf setting
        return if (list.any { it.id == ShelfDef.ALL }) list.sortedWith(compareBy({ it.id != ShelfDef.ALL }, { it.created }))
        else listOf(ShelfDef(ShelfDef.ALL, "", if (Settings.get(context).state.value.woodShelf) "wood" else "modern")) + list
    }

    private fun saveShelves(list: List<ShelfDef>) {
        val json = JSONArray()
        for (d in list) json.put(shelfJson(d))
        if (Vault.locked(context)) return
        try { Vault.writeSmall(context, "shelves.json", json.toString().toByteArray()) } catch (_: Exception) {}
    }

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

    /** Takes the copy out of Hramota (off the shelf); bookmarks, notes and the place stay remembered. */
    fun dropCopy(id: String) {
        get(id)?.copy?.let { File(it).delete() }
        update(id) { it?.copy(copy = null, shelves = emptySet()) }
    }

    /** Forgets everything about a document, and deletes the copy kept inside Hramota. */
    fun forget(id: String) {
        get(id)?.copy?.let { File(it).delete() }
        File(coversDir(context), "$id.png").delete()
        File(coversDir(context), "$id.png" + Vault.SUFFIX).delete()
        update(id) { null }
    }

    fun clearAll() {
        state.value.keys.toList().forEach(::forget)
        synchronized(this) { shelfState.value = shelfState.value.filter { it.id == ShelfDef.ALL }; saveShelves(shelfState.value) }
    }

    /** Reads the memory again: after the locked storage is opened or locked. */
    @Synchronized fun reload() {
        state.value = load()
        shelfState.value = loadShelves()
    }

    private fun keepAside(name: String) {
        // Unreadable: kept aside rather than overwritten, so nothing is lost for good
        for (f in listOf(File(context.filesDir, name + Vault.SUFFIX), File(context.filesDir, name)))
            if (f.exists()) try { f.copyTo(File(context.filesDir, "$name.broken-${System.currentTimeMillis()}")) } catch (_: Exception) {}
    }

    private fun load(): Map<String, LibraryEntry> {
        // Locked: nothing is shown, and nothing is written over the sealed memory
        if (Vault.locked(context)) return emptyMap()
        val bytes = try { Vault.readSmall(context, "library.json") } catch (e: Exception) { keepAside("library.json"); return emptyMap() } ?: return emptyMap()
        val json = try { JSONArray(String(bytes)) } catch (e: Exception) { keepAside("library.json"); return emptyMap() }
        val out = HashMap<String, LibraryEntry>()
        for (i in 0 until json.length()) {
            // One damaged entry is skipped, not the whole memory
            val e = try { entry(json.getJSONObject(i)) } catch (ex: Exception) { null } ?: continue
            out[e.id] = e
        }
        return out
    }

    internal fun toJson(e: LibraryEntry) = JSONObject().apply {
        put("id", e.id); put("title", e.title); put("format", e.format)
        e.uri?.let { put("uri", it) }; e.copy?.let { put("copy", it) }
        put("position", e.position); put("total", e.total)
        put("bookmarks", JSONArray(e.bookmarks.sorted())); put("shelf", e.onShelf); put("opened", e.openedAt)
        if (e.shelves.isNotEmpty()) put("shelves", JSONArray(e.shelves.toList()))
        if (e.notes.isNotEmpty()) put("notes", JSONArray().apply {
            for (n in e.notes) put(JSONObject().apply { put("b", n.block); put("s", n.start); put("eb", n.endBlock); put("e", n.end); put("c", n.color); if (n.text.isNotEmpty()) put("t", n.text); put("at", n.at) })
        })
    }

    internal fun shelfJson(d: ShelfDef) = JSONObject().apply { put("id", d.id); put("name", d.name); put("style", d.style); put("hue", d.hue.toDouble()); put("created", d.created) }

    internal fun shelfFrom(o: JSONObject): ShelfDef? = ShelfDef(
        o.optString("id").ifEmpty { return null }.take(40), o.optString("name").take(80), o.optString("style").takeIf { it in STYLES } ?: "modern",
        o.optDouble("hue", -1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-1f, 360f) ?: -1f, o.optLong("created"),
    )

    internal fun entry(o: JSONObject): LibraryEntry? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return LibraryEntry(
            id = id, title = o.optString("title"), format = o.optString("format"),
            uri = o.optString("uri").ifEmpty { null }, copy = o.optString("copy").ifEmpty { null },
            position = o.optInt("position").coerceAtLeast(0), total = o.optInt("total").coerceAtLeast(0),
            bookmarks = o.optJSONArray("bookmarks")?.let { a -> (0 until minOf(a.length(), MAX_BOOKMARKS)).map { a.optInt(it) }.filter { it >= 0 }.toSet() } ?: emptySet(),
            onShelf = o.optBoolean("shelf"), openedAt = o.optLong("opened"),
            notes = o.optJSONArray("notes")?.let { a ->
                (0 until minOf(a.length(), MAX_NOTES)).mapNotNull { k ->
                    val n = a.optJSONObject(k) ?: return@mapNotNull null
                    val b = n.optInt("b"); val eb = n.optInt("eb"); val st = n.optInt("s"); val en = n.optInt("e")
                    if (b < 0 || eb < b || st < 0 || en < 0 || (eb == b && en < st)) return@mapNotNull null
                    Note(b, st, eb, en, n.optInt("c").coerceIn(0, 3), n.optString("t").take(MAX_NOTE_TEXT), n.optLong("at"))
                }.distinctBy { it.at }
            } ?: emptyList(),
            shelves = o.optJSONArray("shelves")?.let { a -> (0 until minOf(a.length(), MAX_SHELVES)).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet(),
        )
    }

    // Written on its own thread, the latest state only: reading position is saved often
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pending = java.util.concurrent.atomic.AtomicReference<Map<String, LibraryEntry>?>()

    private fun save(map: Map<String, LibraryEntry>) {
        if (pending.getAndSet(map) == null) writer.execute { pending.getAndSet(null)?.let(::write) }
    }

    private fun write(map: Map<String, LibraryEntry>) {

        val json = JSONArray()
        for (e in map.values) json.put(toJson(e))
        if (Vault.locked(context)) return
        try { Vault.writeSmall(context, "library.json", json.toString().toByteArray()) } catch (_: Exception) {}
    }

    /** Seals every remembered file (locked storage turned on, unlocked): memory, shelves, covers, copies, recents. */
    fun sealAll() = convert(seal = true)

    /** The other way: everything back to plain files. */
    fun unsealAll() = convert(seal = false)

    private fun convert(seal: Boolean) {
        val key = Vault.key() ?: throw java.io.IOException("Locked")
        // Memory and shelves: written again in the new form (the old one is removed by the writer)
        writer.submit {}.get()
        write(state.value)
        saveShelves(shelfState.value)
        for (f in coversDir(context).listFiles().orEmpty()) Shelf.convertFile(key, f, seal)
        for (e in state.value.values) {
            val path = e.copy ?: continue
            val f = File(path)
            if (!f.exists()) continue
            val now = Shelf.convertFile(key, f, seal) ?: continue
            update(e.id) { it?.copy(copy = now.path) }
        }
        writer.submit {}.get()
        Recents.convert(context, seal)
    }

    companion object {
        const val MAX_BOOKMARKS = 1000
        const val MAX_NOTES = 5000
        const val MAX_SHELVES = 200
        val STYLES = setOf("modern", "wood", "list")
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
