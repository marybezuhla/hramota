package ua.hramota.doc.book

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

private fun parser(bytes: ByteArray, encoding: String? = null): XmlPullParser =
    XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply {
        setInput(ByteArrayInputStream(stripDoctype(bytes)), encoding)
        for ((k, v) in HTML_ENTITIES) try { defineEntityReplacementText(k, v) } catch (_: Exception) {}
    }

private fun readLimited(input: InputStream, limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var n = 0L
    while (true) {
        val k = input.read(buf)
        if (k < 0) break
        n += k
        if (n > limit) throw IOException("Part too large")
        out.write(buf, 0, k)
    }
    return out.toByteArray()
}

/** Every entry of a ZIP (small ones), by path; nothing in it is run. */
private fun unzip(input: InputStream, perEntry: Long, total: Long): Map<String, ByteArray> {
    val out = HashMap<String, ByteArray>()
    var sum = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            val bytes = readLimited(zip, perEntry)
            sum += bytes.size
            if (sum > total) throw IOException("Book too large")
            out[e.name.removePrefix("/")] = bytes
        }
    }
    return out
}

/** EPUB 2 and 3: the reading order of the package, chapter XHTML turned into blocks, pictures and the cover. */
object EpubReader {
    fun read(input: InputStream, withImages: Boolean = true): Book {
        val files = unzip(input, 64L * 1024 * 1024, 320L * 1024 * 1024)
        val container = files["META-INF/container.xml"] ?: throw IOException("Not an EPUB")
        var opfPath: String? = null
        parser(container).let { p -> while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") { opfPath = p.getAttributeValue(null, "full-path"); break } }
        val opf = opfPath?.let { files[it] } ?: throw IOException("Package missing")
        val base = opfPath!!.substringBeforeLast('/', "")
        fun resolve(from: String, href: String): String {
            val clean = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
            val parts = (if (from.isEmpty()) emptyList() else from.split('/')) + clean.split('/')
            val out = ArrayList<String>()
            for (s in parts) when (s) { ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); ".", "" -> Unit; else -> out += s }
            return out.joinToString("/")
        }
        var title = ""; var author = ""; var language = ""; var coverId: String? = null
        val manifest = HashMap<String, Pair<String, String>>()  // id → (path, media type)
        val properties = HashMap<String, String>()
        val spine = ArrayList<String>()
        val p = parser(opf)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> if (title.isEmpty()) title = p.nextText().trim()
                "creator" -> if (author.isEmpty()) author = p.nextText().trim()
                "language" -> if (language.isEmpty()) language = p.nextText().trim()
                "meta" -> if (p.getAttributeValue(null, "name") == "cover") coverId = p.getAttributeValue(null, "content")
                "item" -> {
                    val id = p.getAttributeValue(null, "id") ?: continue
                    manifest[id] = resolve(base, p.getAttributeValue(null, "href") ?: "") to (p.getAttributeValue(null, "media-type") ?: "")
                    p.getAttributeValue(null, "properties")?.let { properties[id] = it }
                }
                "itemref" -> if (p.getAttributeValue(null, "linear") != "no") p.getAttributeValue(null, "idref")?.let { spine += it }
            }
        }
        val cover = (coverId ?: properties.entries.firstOrNull { it.value.contains("cover-image") }?.key)?.let { manifest[it]?.first }
        val b = BlockBuilder()
        val images = HashMap<String, ByteArray>()
        var imageBytes = 0L
        fun image(path: String) {
            if (!withImages) return
            val bytes = files[path] ?: return
            if (path !in images && imageBytes + bytes.size <= Book.MAX_IMAGES) { images[path] = bytes; imageBytes += bytes.size }
            if (path in images) b.image(path)
        }
        for (id in spine.take(5000)) {
            if (b.full) break
            val (path, type) = manifest[id] ?: continue
            if (!type.contains("html") && !path.endsWith("html") && !path.endsWith(".htm")) continue
            val bytes = files[path] ?: continue
            try { xhtml(bytes, b) { src -> image(resolve(path.substringBeforeLast('/', ""), src)) } } catch (e: Exception) { b.end(BookBlock.Kind.PARAGRAPH) }
        }
        if (b.blocks.isEmpty()) throw IOException("Empty book")
        val coverPath = cover?.takeIf { withImages && files.containsKey(it) }?.also { c -> files[c]?.let { if (c !in images) images[c] = it } }
        return Book(title, author, b.blocks, images, coverPath, language)
    }

    /** One chapter: block elements end blocks, inline ones change the style, pictures become their own blocks. */
    private fun xhtml(bytes: ByteArray, b: BlockBuilder, image: (String) -> Unit) {
        val p = parser(bytes)
        var skip = 0
        val kinds = ArrayList<BookBlock.Kind>()
        fun current() = kinds.lastOrNull() ?: BookBlock.Kind.PARAGRAPH
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase()
                    if (skip > 0 || n == "head" || n == "script" || n == "style") { skip++; continue }
                    when (n) {
                        "h1" -> { b.end(current()); kinds += BookBlock.Kind.HEADING1 }
                        "h2" -> { b.end(current()); kinds += BookBlock.Kind.HEADING2 }
                        "h3", "h4", "h5", "h6" -> { b.end(current()); kinds += BookBlock.Kind.HEADING3 }
                        "blockquote" -> { b.end(current()); kinds += BookBlock.Kind.QUOTE }
                        "p", "div", "li", "section", "article", "dd", "dt", "pre", "figcaption", "tr" -> b.end(current())
                        "br" -> b.lineBreak()
                        "hr" -> { b.end(current()); b.end(BookBlock.Kind.BREAK) }
                        "b", "strong" -> { b.styleChanging(); b.bold++; b.styleChanged() }
                        "i", "em", "cite", "dfn" -> { b.styleChanging(); b.italic++; b.styleChanged() }
                        "sup" -> { b.styleChanging(); b.raised++; b.styleChanged() }
                        "img" -> p.getAttributeValue(null, "src")?.let { b.end(current()); image(it) }
                        "image" -> (p.getAttributeValue("http://www.w3.org/1999/xlink", "href") ?: p.getAttributeValue(null, "href"))?.let { b.end(current()); image(it) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = p.name.lowercase()
                    if (skip > 0) { skip--; continue }
                    when (n) {
                        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote" -> { b.end(current()); if (kinds.isNotEmpty()) kinds.removeAt(kinds.size - 1) }
                        "p", "div", "li", "section", "article", "dd", "dt", "pre", "figcaption", "tr", "body" -> b.end(current())
                        "b", "strong" -> { b.styleChanging(); b.bold--; b.styleChanged() }
                        "i", "em", "cite", "dfn" -> { b.styleChanging(); b.italic--; b.styleChanged() }
                        "sup" -> { b.styleChanging(); b.raised--; b.styleChanged() }
                        "td", "th" -> b.text(" ")
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> if (skip == 0) b.text(p.text ?: "")
                XmlPullParser.END_DOCUMENT -> { b.end(current()); return }
            }
            if (b.full) return
        }
    }
}

/** FictionBook (FB2, also inside a ZIP): sections and titles, poems, epigraphs, emphasis, pictures. */
object Fb2Reader {
    private const val XLINK = "http://www.w3.org/1999/xlink"

    fun read(input: InputStream, withImages: Boolean = true): Book {
        val raw = readLimited(input, 256L * 1024 * 1024)
        // A .fb2.zip holds the book as its only entry
        val bytes = if (raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) {
            unzip(ByteArrayInputStream(raw), 256L * 1024 * 1024, 256L * 1024 * 1024).entries.firstOrNull { it.key.lowercase().endsWith(".fb2") }?.value ?: throw IOException("No book in the archive")
        } else raw
        val p = parser(bytes)
        val b = BlockBuilder()
        val images = HashMap<String, ByteArray>()
        var imageBytes = 0L
        var title = ""; var first = ""; var last = ""; var language = ""
        var coverId: String? = null
        val path = ArrayList<String>()
        var sectionDepth = 0
        var inTitle = 0
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    path += n
                    val inDescription = "description" in path
                    when {
                        inDescription && n == "book-title" && title.isEmpty() -> { title = p.nextText().trim(); path.removeAt(path.size - 1) }
                        inDescription && n == "lang" && language.isEmpty() && "title-info" in path -> { language = p.nextText().trim(); path.removeAt(path.size - 1) }
                        inDescription && n == "first-name" && first.isEmpty() && "author" in path && "title-info" in path -> { first = p.nextText().trim(); path.removeAt(path.size - 1) }
                        inDescription && n == "last-name" && last.isEmpty() && "author" in path && "title-info" in path -> { last = p.nextText().trim(); path.removeAt(path.size - 1) }
                        inDescription && n == "image" && "coverpage" in path -> coverId = p.getAttributeValue(XLINK, "href")?.removePrefix("#")
                        n == "binary" -> {
                            val id = p.getAttributeValue(null, "id")
                            val data = p.nextText()
                            path.removeAt(path.size - 1)
                            if (withImages && id != null && imageBytes < Book.MAX_IMAGES) try {
                                val decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                images[id] = decoded; imageBytes += decoded.size
                            } catch (_: IllegalArgumentException) {}
                        }
                        inDescription -> Unit
                        n == "section" -> { b.end(BookBlock.Kind.PARAGRAPH); sectionDepth++ }
                        n == "title" -> { b.end(BookBlock.Kind.PARAGRAPH); inTitle++ }
                        n == "subtitle" -> b.end(BookBlock.Kind.PARAGRAPH)
                        n == "p" || n == "v" || n == "text-author" -> b.end(BookBlock.Kind.PARAGRAPH)
                        n == "empty-line" -> b.end(BookBlock.Kind.BREAK)
                        n == "strong" -> { b.styleChanging(); b.bold++; b.styleChanged() }
                        n == "emphasis" -> { b.styleChanging(); b.italic++; b.styleChanged() }
                        n == "sup" -> { b.styleChanging(); b.raised++; b.styleChanged() }
                        n == "image" -> p.getAttributeValue(XLINK, "href")?.removePrefix("#")?.let { b.end(BookBlock.Kind.PARAGRAPH); b.image(it) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                    if ("description" in path || n == "description") continue
                    val kind = when {
                        inTitle > 0 -> when (sectionDepth) { 0, 1 -> BookBlock.Kind.HEADING1; 2 -> BookBlock.Kind.HEADING2; else -> BookBlock.Kind.HEADING3 }
                        "poem" in path -> BookBlock.Kind.POEM
                        "epigraph" in path || "cite" in path -> BookBlock.Kind.QUOTE
                        else -> BookBlock.Kind.PARAGRAPH
                    }
                    when (n) {
                        "p", "v", "text-author" -> b.end(kind)
                        "subtitle" -> b.end(BookBlock.Kind.SUBTITLE)
                        "title" -> { b.end(kind); inTitle-- }
                        "section" -> { b.end(BookBlock.Kind.PARAGRAPH); sectionDepth-- }
                        "strong" -> { b.styleChanging(); b.bold--; b.styleChanged() }
                        "emphasis" -> { b.styleChanging(); b.italic--; b.styleChanged() }
                        "sup" -> { b.styleChanging(); b.raised--; b.styleChanged() }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> if ("description" !in path && "binary" !in path && "body" in path) b.text(p.text ?: "")
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        // Pictures referred to but missing are dropped
        val blocks = b.blocks.filter { it !is BookBlock.Image || it.key in images }.ifEmpty { b.blocks.filter { it !is BookBlock.Image } }
        if (blocks.isEmpty()) throw IOException("Empty book")
        return Book(title, listOf(first, last).filter { it.isNotEmpty() }.joinToString(" "), blocks, images, coverId?.takeIf { it in images }, language)
    }
}
