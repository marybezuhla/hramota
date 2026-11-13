package ua.hramota.notebook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ua.hramota.data.Library
import ua.hramota.data.Vault
import ua.hramota.ink.InkJson
import ua.hramota.ink.InkTool
import ua.hramota.ink.Stroke
import java.util.UUID

/** A notebook: a name and the colour of its cover (ARGB). */
data class Book(val id: String, val title: String, val color: Int, val created: Long = 0)

/** A marked piece of a paragraph: "b" bold, "i" italic, "u" underlined, "s" struck through, "hl" highlighted. */
data class Span(val start: Int, val end: Int, val kind: String)

/** What a note is made of: paragraphs, handwriting and pictures, one after another. */
sealed interface NBlock {
    val id: Long

    /**
     * A paragraph. [style]: "body", "h1", "h2", "h3", "bullet", "number", "check" (a to-do item,
     * [checked] when done) or "quote".
     */
    data class Text(override val id: Long, val text: String, val style: String = "body", val checked: Boolean = false, val spans: List<Span> = emptyList()) : NBlock

    /** Handwriting on a piece of paper [height] dp high; [recognized]: its text as read (for search). */
    data class Ink(override val id: Long, val strokes: List<Stroke>, val height: Float = 320f, val recognized: String = "", val readFor: Int = -1) : NBlock

    /** A picture, its file in the notebook's pictures folder. */
    data class Picture(override val id: Long, val file: String, val w: Int, val h: Int, val alt: String = "") : NBlock
}

/** A note's description, in the notebook's index: everything the lists and the search need. */
data class NoteMeta(
    val id: String,
    /** The notebook it is in, or null (in none). */
    val book: String? = null,
    val title: String = "",
    val created: Long = 0,
    val updated: Long = 0,
    val pinned: Boolean = false,
    val starred: Boolean = false,
    /** Opened only after the phone's fingerprint, face or screen lock. */
    val locked: Boolean = false,
    /** When it was put in the bin (gone for good 30 days later); 0 if not. */
    val deleted: Long = 0,
    /** "plain", "lines", "grid" or "dots". */
    val paper: String = "plain",
    /** The first lines (not for locked notes). */
    val preview: String = "",
    /** All its text, the handwriting as read too: what the search looks in. */
    val text: String = "",
    val hasInk: Boolean = false,
)

/** A whole note: its description and its content. */
data class Note(val meta: NoteMeta, val blocks: List<NBlock>)

/**
 * The notebook ("Записник"): notes of one's own, kept only on this phone, in Hramota's own format
 * (an index and one JSON file per note; pictures beside them). With locked storage on, everything
 * is sealed like the shelf. Exported as Word, PDF, Markdown or a picture.
 */
object Notebook {
    private const val INDEX = "index.json"
    const val BIN_DAYS = 30L
    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val notes = MutableStateFlow<List<NoteMeta>>(emptyList())
    @Volatile private var loaded = false
    /** Under lock with the storage closed: nothing is read or written. */
    @Volatile var unreadable = false
        private set

    fun books(context: Context): StateFlow<List<Book>> { ensure(context); return books.asStateFlow() }
    fun notes(context: Context): StateFlow<List<NoteMeta>> { ensure(context); return notes.asStateFlow() }

    @Synchronized private fun ensure(context: Context) { if (!loaded) load(context) }

    @Synchronized fun reload(context: Context) { load(context) }

    @Synchronized private fun load(context: Context) {
        val dir = Library.notebookDir(context)
        val bytes = try { Vault.readIn(dir, INDEX) } catch (e: Exception) { null }
        unreadable = bytes == null && java.io.File(dir, INDEX + Vault.SUFFIX).exists()
        if (bytes == null) { books.value = emptyList(); notes.value = emptyList(); loaded = !unreadable; return }
        try {
            val o = JSONObject(String(bytes))
            val b = o.optJSONArray("books") ?: JSONArray()
            books.value = (0 until b.length()).map { i -> b.getJSONObject(i).let { Book(it.getString("id"), it.optString("title"), it.optInt("color", COLORS[0]), it.optLong("created")) } }
            val n = o.optJSONArray("notes") ?: JSONArray()
            val now = System.currentTimeMillis()
            val all = (0 until n.length()).map { i -> meta(n.getJSONObject(i)) }
            // The bin keeps a note 30 days
            val (gone, kept) = all.partition { it.deleted > 0 && now - it.deleted > BIN_DAYS * 24 * 3600 * 1000 }
            notes.value = kept
            loaded = true
            if (gone.isNotEmpty()) { gone.forEach { removeFiles(context, it.id) }; save(context) }
        } catch (e: Exception) {
            books.value = emptyList(); notes.value = emptyList()
            loaded = true
        }
    }

    private fun meta(o: JSONObject) = NoteMeta(
        id = o.getString("id"), book = o.optString("book").ifEmpty { null }, title = o.optString("title"),
        created = o.optLong("created"), updated = o.optLong("updated"), pinned = o.optBoolean("pinned"), starred = o.optBoolean("starred"),
        locked = o.optBoolean("locked"), deleted = o.optLong("deleted"), paper = o.optString("paper", "plain"),
        preview = o.optString("preview"), text = o.optString("text"), hasInk = o.optBoolean("ink"),
    )

    private fun metaJson(m: NoteMeta) = JSONObject().apply {
        put("id", m.id); m.book?.let { put("book", it) }; put("title", m.title); put("created", m.created); put("updated", m.updated)
        if (m.pinned) put("pinned", true); if (m.starred) put("starred", true); if (m.locked) put("locked", true)
        if (m.deleted > 0) put("deleted", m.deleted); put("paper", m.paper)
        // A locked note's text stays in its own file only (not in the list, not in the search)
        if (!m.locked) { put("preview", m.preview); put("text", m.text) }
        if (m.hasInk) put("ink", true)
    }

    @Synchronized private fun save(context: Context) {
        if (unreadable) throw java.io.IOException("Locked")
        val o = JSONObject()
        o.put("version", 1)
        o.put("books", JSONArray().apply { books.value.forEach { b -> put(JSONObject().put("id", b.id).put("title", b.title).put("color", b.color).put("created", b.created)) } })
        o.put("notes", JSONArray().apply { notes.value.forEach { put(metaJson(it)) } })
        Vault.writeIn(context, Library.notebookDir(context), INDEX, o.toString().toByteArray())
    }

    // ---------------------------------------------------------------- notebooks

    @Synchronized fun putBook(context: Context, b: Book) {
        ensure(context)
        books.value = books.value.filter { it.id != b.id } + b
        save(context)
    }

    /** Takes a notebook away; its notes stay (in no notebook). */
    @Synchronized fun removeBook(context: Context, id: String) {
        ensure(context)
        books.value = books.value.filter { it.id != id }
        notes.value = notes.value.map { if (it.book == id) it.copy(book = null) else it }
        save(context)
    }

    fun newBook(title: String, color: Int) = Book(UUID.randomUUID().toString(), title, color, System.currentTimeMillis())

    // ---------------------------------------------------------------- notes

    @Synchronized fun update(context: Context, id: String, change: (NoteMeta) -> NoteMeta) {
        ensure(context)
        notes.value = notes.value.map { if (it.id == id) change(it) else it }
        save(context)
    }

    fun create(context: Context, book: String?, paper: String = "plain", blocks: List<NBlock> = listOf(NBlock.Text(1, "")), title: String = ""): NoteMeta {
        val now = System.currentTimeMillis()
        val m = NoteMeta(UUID.randomUUID().toString(), book, title, now, now, paper = paper)
        write(context, Note(m, blocks))
        return notes.value.first { it.id == m.id }
    }

    fun read(context: Context, id: String): Note? {
        ensure(context)
        val meta = notes.value.firstOrNull { it.id == id } ?: return null
        val bytes = try { Vault.readIn(Library.notebookDir(context), "$id.json") } catch (e: Exception) { null } ?: return Note(meta, listOf(NBlock.Text(1, "")))
        return try { Note(meta, blocks(JSONObject(String(bytes)).getJSONArray("blocks"))) } catch (e: Exception) { null }
    }

    /** Writes a note: its file, then its description in the index (the title, preview and search text from its content). */
    @Synchronized fun write(context: Context, note: Note) {
        ensure(context)
        val o = JSONObject().put("version", 1).put("blocks", JSONArray().apply { note.blocks.forEach { put(blockJson(it)) } })
        Vault.writeIn(context, Library.notebookDir(context), note.meta.id + ".json", o.toString().toByteArray())
        val text = plainText(note.blocks)
        val m = note.meta.copy(
            preview = text.lineSequence().filter { it.isNotBlank() }.drop(if (note.meta.title.isBlank()) 1 else 0).take(3).joinToString("\n").take(240),
            text = (note.meta.title + "\n" + text).take(MAX_SEARCH_TEXT),
            hasInk = note.blocks.any { it is NBlock.Ink && it.strokes.isNotEmpty() },
        )
        notes.value = notes.value.filter { it.id != m.id } + m
        save(context)
    }

    /** Into the bin (or out of it with [back]). */
    fun toBin(context: Context, id: String, back: Boolean = false) = update(context, id) { it.copy(deleted = if (back) 0 else System.currentTimeMillis(), pinned = if (back) it.pinned else false) }

    /** Gone for good: its file and pictures too. */
    @Synchronized fun deleteForGood(context: Context, id: String) {
        ensure(context)
        removeFiles(context, id)
        notes.value = notes.value.filter { it.id != id }
        save(context)
    }

    @Synchronized fun emptyBin(context: Context) {
        ensure(context)
        val (gone, kept) = notes.value.partition { it.deleted > 0 }
        gone.forEach { removeFiles(context, it.id) }
        notes.value = kept
        save(context)
    }

    private fun removeFiles(context: Context, id: String) {
        Vault.deleteIn(Library.notebookDir(context), "$id.json")
        val media = Library.notebookMediaDir(context)
        media.listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- pictures

    /** Keeps a picture for a note; its file name. */
    fun putPicture(context: Context, noteId: String, bytes: ByteArray, png: Boolean): String {
        val name = "$noteId-" + UUID.randomUUID().toString().take(12) + if (png) ".png" else ".jpg"
        Vault.writeIn(context, Library.notebookMediaDir(context), name, bytes)
        return name
    }

    fun picture(context: Context, name: String): ByteArray? = try { Vault.readIn(Library.notebookMediaDir(context), name) } catch (e: Exception) { null }

    /** Pictures no block of the note uses any more (taken out, undone): deleted when the note is closed. */
    fun dropUnusedPictures(context: Context, note: Note) {
        val used = note.blocks.filterIsInstance<NBlock.Picture>().map { it.file }.toSet()
        Library.notebookMediaDir(context).listFiles().orEmpty()
            .filter { it.name.startsWith(note.meta.id + "-") && it.name.removeSuffix(Vault.SUFFIX) !in used }
            .forEach { it.delete() }
    }

    // ---------------------------------------------------------------- JSON

    private const val MAX_SEARCH_TEXT = 200_000

    fun plainText(blocks: List<NBlock>): String = blocks.mapNotNull {
        when (it) {
            is NBlock.Text -> it.text
            is NBlock.Ink -> it.recognized.ifBlank { null }
            is NBlock.Picture -> it.alt.ifBlank { null }
        }
    }.joinToString("\n")

    fun blockJson(b: NBlock): JSONObject = when (b) {
        is NBlock.Text -> JSONObject().put("t", "text").put("id", b.id).put("text", b.text).put("style", b.style).apply {
            if (b.checked) put("checked", true)
            if (b.spans.isNotEmpty()) put("spans", JSONArray().apply { b.spans.forEach { s -> put(JSONArray().put(s.start).put(s.end).put(s.kind)) } })
        }
        is NBlock.Ink -> JSONObject().put("t", "ink").put("id", b.id).put("h", b.height.toDouble()).put("recognized", b.recognized).put("readFor", b.readFor)
            .put("strokes", JSONArray().apply {
                b.strokes.forEach { s ->
                    put(JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("tool", s.tool.name).put("stylus", s.stylus)
                        .put("p", InkJson.strokes(listOf(s)).getJSONArray(0)))
                }
            })
        is NBlock.Picture -> JSONObject().put("t", "pic").put("id", b.id).put("file", b.file).put("w", b.w).put("h", b.h).put("alt", b.alt)
    }

    fun blocks(a: JSONArray): List<NBlock> = (0 until a.length()).mapNotNull { i ->
        val o = a.getJSONObject(i)
        when (o.optString("t")) {
            "text" -> NBlock.Text(o.getLong("id"), o.optString("text"), o.optString("style", "body"), o.optBoolean("checked"),
                o.optJSONArray("spans")?.let { s -> (0 until s.length()).map { j -> s.getJSONArray(j).let { Span(it.getInt(0), it.getInt(1), it.getString(2)) } } } ?: emptyList())
            "ink" -> {
                val s = o.optJSONArray("strokes") ?: JSONArray()
                NBlock.Ink(o.getLong("id"), (0 until s.length()).map { j ->
                    val so = s.getJSONObject(j)
                    val pts = InkJson.readStrokes(JSONArray().put(so.getJSONArray("p")))[0].points
                    Stroke(pts, so.optInt("c", ua.hramota.ink.INK), so.optDouble("w", 2.2).toFloat(),
                        runCatching { InkTool.valueOf(so.optString("tool", "PEN")) }.getOrDefault(InkTool.PEN), so.optBoolean("stylus"))
                }, o.optDouble("h", 320.0).toFloat(), o.optString("recognized"), o.optInt("readFor", -1))
            }
            "pic" -> NBlock.Picture(o.getLong("id"), o.getString("file"), o.optInt("w"), o.optInt("h"), o.optString("alt"))
            else -> null
        }
    }

    /** Cover colours to choose from (and the first for a new notebook). */
    val COLORS = intArrayOf(0xFF2F5597.toInt(), 0xFF2E7D4F.toInt(), 0xFFB4472E.toInt(), 0xFF7A4E9C.toInt(), 0xFFB0651F.toInt(), 0xFF00838F.toInt(), 0xFFC2185B.toInt(), 0xFF5D4037.toInt())
}
