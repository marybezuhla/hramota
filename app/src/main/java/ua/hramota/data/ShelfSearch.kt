package ua.hramota.data

import android.content.Context
import ua.hramota.doc.Format
import ua.hramota.doc.Search
import ua.hramota.doc.TextExtractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Search through every document on the shelf. Each document's text is taken out once (in the
 * background, the first time search is used) and kept beside its copy, sealed like it; a document
 * is known by its content, so an edited one is a new one and is read again.
 */
object ShelfSearch {
    /** One place found: the document, which page (PDF) or paragraph, and the words around it. */
    data class Hit(val entry: LibraryEntry, val place: Int, val pages: Boolean, val snippet: String, val match: IntRange)

    fun dir(context: Context) = File(context.filesDir, "search-index").apply { mkdirs() }

    /** Page or paragraph texts are kept one per line in the index; line breaks inside them as this. */
    private const val INNER = ' '
    private const val MAX_PDF_PAGES = 3000
    private const val MAX_CHARS = 20_000_000

    fun indexed(context: Context, id: String) = File(dir(context), "$id.txt").exists() || File(dir(context), "$id.txt" + Vault.SUFFIX).exists()

    /** The kept documents whose text is not taken out yet. */
    fun missing(context: Context, entries: List<LibraryEntry>) = entries.filter { it.kept && !indexed(context, it.id) && searchable(it.format) }

    private fun searchable(label: String) = Format.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { it == Format.PDF || it.hasText } == true

    /**
     * Takes out the text of one document and keeps it. PDFs are read by the isolated PDF engine,
     * page by page. Call off the main thread; false if it could not be read (tried again next time).
     */
    suspend fun index(context: Context, e: LibraryEntry): Boolean = indexing.withLock {
        // Taken out already (by a search box opened meanwhile)
        if (indexed(context, e.id)) return@withLock true
        indexOne(context, e)
    }

    /** One document read at a time, whichever search box asks: two never take out the same text at once. */
    private val indexing = Mutex()

    private suspend fun indexOne(context: Context, e: LibraryEntry): Boolean {
        val path = e.copy ?: return false
        val format = Format.entries.firstOrNull { it.label.equals(e.format, ignoreCase = true) } ?: return false
        val uri = Shelf.copyUri(context, path, e.title) ?: return false
        try {
            val parts: List<String> = if (format == Format.PDF) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
                val pages = ua.hramota.pdf.PdfiumPages.open(context, pfd)
                // A page that could not be read leaves the document unindexed: it is tried again next time
                try { (0 until minOf(pages.pageCount, MAX_PDF_PAGES)).map { pages.text(it) ?: return false } } finally { pages.close() }
            } else {
                context.contentResolver.openInputStream(uri)?.use { TextExtractor.read(it, format) }?.map { it.text } ?: return false
            }
            var total = 0
            val text = buildString {
                for (p in parts) {
                    if (total > MAX_CHARS) break
                    val line = p.replace('\n', INNER).replace('\r', ' ')
                    append(line).append('\n'); total += line.length
                }
            }
            Vault.writeIn(context, dir(context), e.id + ".txt", text.toByteArray())
            return true
        } catch (x: Exception) {
            return false
        } finally {
            Shelf.closeOpened(context, uri)
        }
    }

    /** Turning the lock on or off: every index file is sealed or opened as the documents are. */
    fun convert(context: Context, key: javax.crypto.SecretKey, seal: Boolean) = synchronized(this) {
        for (f in dir(context).listFiles().orEmpty()) if (f.isFile && !f.name.endsWith(".converting")) Shelf.convertFile(key, f, seal)
    }

    /** Index files of documents no longer kept: deleted. */
    fun clean(context: Context, kept: Set<String>) {
        // Locked, or the shelf not read yet: nothing is judged gone
        if (Vault.locked(context) || kept.isEmpty()) return
        for (f in dir(context).listFiles().orEmpty()) {
            val id = f.name.removeSuffix(Vault.SUFFIX).removeSuffix(".txt")
            if (id !in kept) f.delete()
        }
    }

    /** Places [query] is found, at most [perDoc] in each document and [max] in all. */
    fun search(context: Context, entries: List<LibraryEntry>, query: String, perDoc: Int = 5, max: Int = 300): List<Hit> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Hit>()
        for (e in entries) {
            if (out.size >= max) break
            // The title first: a document found by its name
            val title = e.coverTitle ?: e.docTitle ?: e.title
            Search.find(title, query).firstOrNull()?.let { r -> out += Hit(e, -1, false, title, r) }
            val bytes = try { Vault.readIn(dir(context), e.id + ".txt") } catch (x: Exception) {
                // A damaged index file: deleted, the text is taken out again next time
                if (!Vault.locked(context)) { File(dir(context), e.id + ".txt").delete(); File(dir(context), e.id + ".txt" + Vault.SUFFIX).delete() }
                null
            } ?: continue
            val pdf = e.format.equals("PDF", ignoreCase = true)
            var n = 0
            for ((i, line) in String(bytes).lineSequence().withIndex()) {
                if (n >= perDoc || out.size >= max) break
                val hits = Search.find(line, query)
                for (r in hits) {
                    if (n >= perDoc) break
                    val from = (r.first - 50).coerceAtLeast(0); val to = (r.last + 51).coerceAtMost(line.length)
                    val prefix = if (from > 0) "…" else ""
                    val snip = prefix + line.substring(from, to).replace(INNER, ' ') + if (to < line.length) "…" else ""
                    out += Hit(e, i, pdf, snip, (r.first - from + prefix.length)..(r.last - from + prefix.length))
                    n++
                }
            }
        }
        return out
    }
}
