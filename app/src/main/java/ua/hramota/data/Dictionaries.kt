package ua.hramota.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** One dictionary kept in Hramota: its name, how many words, and where its files are. */
data class Dictionary(val id: String, val name: String, val words: Int, val dir: File)

/** One article found: the dictionary, the word as written there, and its text. */
data class Article(val dictionary: String, val word: String, val text: String)

/**
 * Offline dictionaries in the StarDict format (.ifo, .idx, .dict or .dict.dz, as single files or in
 * a ZIP), chosen by the person and kept in Hramota's own storage. Nothing is looked up anywhere else.
 */
object Dictionaries {
    private const val MAX_WORDS = 1_000_000
    private const val MAX_DICT = 512L * 1024 * 1024
    private const val MAX_IDX = 128L * 1024 * 1024
    private const val MAX_ARTICLE = 256 * 1024

    private fun root(context: Context) = File(context.filesDir, "dictionaries").apply { mkdirs() }

    fun list(context: Context): List<Dictionary> = root(context).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        val ifo = readIfo(File(dir, "dict.ifo")) ?: return@mapNotNull null
        Dictionary(dir.name, ifo["bookname"] ?: dir.name, ifo["wordcount"]?.toIntOrNull() ?: 0, dir)
    }.sortedBy { it.name.lowercase() }

    fun remove(context: Context, id: String) {
        if (!id.matches(Regex("[a-z0-9]{1,40}"))) return
        File(root(context), id).deleteRecursively()
        cache.remove(id)
    }

    /**
     * Adds a dictionary from the files the person chose: the three StarDict parts, or one ZIP holding
     * them. Returns its name. Call off the main thread.
     */
    fun add(context: Context, uris: List<Uri>, nameOf: (Uri) -> String): String {
        val dir = File(root(context), "d" + System.currentTimeMillis().toString(36)).apply { mkdirs() }
        try {
            for (u in uris) {
                val name = nameOf(u).lowercase(Locale.ROOT)
                val input = context.contentResolver.openInputStream(u) ?: continue
                input.use { stream ->
                    if (name.endsWith(".zip")) unzip(stream, dir) else place(name, stream, dir)
                }
            }
            val ifo = readIfo(File(dir, "dict.ifo")) ?: throw IOException("No .ifo")
            if (!File(dir, "dict.idx").exists() || !File(dir, "dict.dict").exists()) throw IOException("Parts missing")
            // The index is read once now, so a broken dictionary is refused at once
            Index.load(dir)
            return ifo["bookname"] ?: "?"
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun unzip(input: InputStream, dir: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > 100) break
                if (!e.isDirectory) place(e.name.substringAfterLast('/').lowercase(Locale.ROOT), zip, dir)
            }
        }
    }

    /** Puts one part under a fixed name (nothing from the file decides where it goes); .dz is unpacked. */
    private fun place(name: String, input: InputStream, dir: File) {
        val (target, limit, gz) = when {
            name.endsWith(".ifo") -> Triple("dict.ifo", 64L * 1024, false)
            name.endsWith(".idx") -> Triple("dict.idx", MAX_IDX, false)
            name.endsWith(".idx.gz") -> Triple("dict.idx", MAX_IDX, true)
            name.endsWith(".dict.dz") -> Triple("dict.dict", MAX_DICT, true)
            name.endsWith(".dict") -> Triple("dict.dict", MAX_DICT, false)
            else -> return
        }
        val source = if (gz) GZIPInputStream(input) else input
        File(dir, target).outputStream().buffered().use { out ->
            val buf = ByteArray(64 * 1024)
            var n = 0L
            while (true) {
                val k = source.read(buf); if (k < 0) break
                n += k
                if (n > limit) throw IOException("Dictionary too large")
                out.write(buf, 0, k)
            }
        }
    }

    private fun readIfo(f: File): Map<String, String>? {
        if (!f.exists() || f.length() > 64 * 1024) return null
        val lines = f.readLines()
        if (lines.firstOrNull()?.trim()?.removePrefix("\uFEFF") != "StarDict's dict ifo file") return null
        return lines.drop(1).mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
    }

    /** A dictionary's words, sorted for looking up, with where each article is. */
    private class Index(val words: Array<String>, val keys: Array<String>, val offsets: LongArray, val sizes: IntArray, val types: String) {
        companion object {
            fun load(dir: File): Index {
                val ifo = readIfo(File(dir, "dict.ifo")) ?: throw IOException("No .ifo")
                val wide = ifo["idxoffsetbits"] == "64"
                val entries = ArrayList<Triple<String, Long, Int>>()
                DataInputStream(File(dir, "dict.idx").inputStream().buffered()).use { d ->
                    val word = java.io.ByteArrayOutputStream()
                    while (entries.size < MAX_WORDS) {
                        word.reset()
                        var b = d.read()
                        if (b < 0) break
                        while (b > 0) { word.write(b); if (word.size() > 1024) throw IOException("Bad index"); b = d.read() }
                        if (b < 0) throw IOException("Bad index")
                        val off = if (wide) d.readLong() else d.readInt().toLong() and 0xFFFFFFFFL
                        val size = d.readInt()
                        if (off < 0 || size < 0) throw IOException("Bad index")
                        entries += Triple(word.toString("UTF-8"), off, size)
                    }
                }
                // Sorted again by our own key (worked out once per word): the file's order is not trusted
                val keys = Array(entries.size) { key(entries[it].first) }
                val order = entries.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]) }
                return Index(
                    Array(order.size) { entries[order[it]].first }, Array(order.size) { keys[order[it]] },
                    LongArray(order.size) { entries[order[it]].second }, IntArray(order.size) { entries[order[it]].third }, ifo["sametypesequence"] ?: "",
                )
            }
        }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Index>()

    /** The form words are compared in: lower case, without stress marks, ґ and г kept apart, ' and ’ alike. */
    fun key(word: String): String = Normalizer.normalize(word.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() || it == '̆' || it == '̈' }
        .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        .replace('’', '\'').replace('ʼ', '\'')

    /**
     * Articles for a word in every dictionary: the word itself, else the nearest form with its ending
     * taken off (Ukrainian words change their endings: «млина» finds «млин»). Call off the main thread.
     */
    fun lookUp(context: Context, word: String, max: Int = 8): List<Article> {
        val w = word.trim().trim { !it.isLetterOrDigit() && it != '\'' && it != '’' && it != '-' }
        if (w.isEmpty() || w.length > 80) return emptyList()
        val out = ArrayList<Article>()
        for (d in list(context)) {
            val index = try {
                cache[d.id] ?: Index.load(d.dir).also {
                    // A few indexes kept in memory; the rest read again when needed
                    if (cache.size >= 2) cache.clear()
                    cache[d.id] = it
                }
            } catch (e: Exception) { continue } catch (e: OutOfMemoryError) { cache.clear(); continue }
            val hits = find(index, key(w))
            for (i in hits.take(3)) {
                val text = article(d.dir, index, i) ?: continue
                out += Article(d.name, index.words[i], text)
                if (out.size >= max) return out
            }
        }
        return out
    }

    private fun lowerBound(keys: Array<String>, k: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] < k) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun find(index: Index, k: String): List<Int> {
        val keys = index.keys
        var i = lowerBound(keys, k)
        val exact = ArrayList<Int>()
        while (i < keys.size && keys[i] == k) { exact += i; i++ }
        if (exact.isNotEmpty()) return exact
        // Endings taken off one by one: the longest stem that begins a word in the dictionary
        for (cut in 1..minOf(4, k.length - 2)) {
            val stem = k.dropLast(cut)
            val at = lowerBound(keys, stem)
            val near = (at until minOf(keys.size, at + 20)).filter { keys[it].startsWith(stem) && keys[it].length <= stem.length + 4 }
            if (near.isNotEmpty()) return near.sortedBy { keys[it].length }
        }
        return emptyList()
    }

    private fun article(dir: File, index: Index, i: Int): String? = try {
        val size = minOf(index.sizes[i], MAX_ARTICLE)
        val bytes = ByteArray(size)
        RandomAccessFile(File(dir, "dict.dict"), "r").use { f ->
            if (index.offsets[i] + size > f.length()) return null
            f.seek(index.offsets[i]); f.readFully(bytes)
        }
        plain(bytes, index.types)
    } catch (e: Exception) { null }

    /** The article as plain text: markup (HTML, XDXF, Pango) turned into lines, never shown as code. */
    private fun plain(bytes: ByteArray, types: String): String {
        val parts = ArrayList<Pair<Char, String>>()
        if (types.isNotEmpty()) {
            var pos = 0
            types.forEachIndexed { k, t ->
                if (pos >= bytes.size) return@forEachIndexed
                val last = k == types.length - 1
                if (t.isLowerCase()) {
                    val end = if (last) bytes.size else (pos until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: bytes.size
                    parts += t to String(bytes, pos, end - pos, Charsets.UTF_8); pos = end + 1
                } else {
                    // Binary parts (sounds, pictures) are left out
                    if (last) pos = bytes.size else if (pos + 4 <= bytes.size) {
                        val n = java.nio.ByteBuffer.wrap(bytes, pos, 4).int; pos += 4 + maxOf(0, n)
                    } else pos = bytes.size
                }
            }
        } else parts += 'm' to String(bytes, Charsets.UTF_8)
        return parts.joinToString("\n") { (t, s) -> if (t == 'm' || t == 't' || t == 'y') s else stripMarkup(s) }.trim()
    }

    private fun stripMarkup(s: String): String = s
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|<k>|</k>|</dtrn>|<blockquote>"), "\n")
        .replace(Regex("<[^>]{0,500}>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\n{3,}"), "\n\n")
}
