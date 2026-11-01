package ua.hramota.doc

import ua.hramota.doc.slides.paragraphs

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A paragraph of plain text; [heading] is 0 for body text, 1..6 for headings. */
data class Paragraph(val text: String, val heading: Int = 0)

/** The kinds of file Hramota recognises, by name and type. */
enum class Format(val label: String) {
    PDF("PDF"), DOCX("DOCX"), ODT("ODT"), TXT("TXT"), MARKDOWN("MD"),
    DOC("DOC"), XLSX("XLSX"), XLS("XLS"), ODS("ODS"), CSV("CSV"),
    PPTX("PPTX"), PPT("PPT"), ODP("ODP"), RTF("RTF"), UNKNOWN("");

    /** Formats whose text can be read and compared now. */
    val hasText: Boolean get() = this == DOCX || this == ODT || this == TXT || this == MARKDOWN || this == DOC || this == CSV || this == XLSX || this == XLS || this == PPTX

    companion object {
        fun detect(name: String?, mime: String?): Format {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            byExtension[ext]?.let { return it }
            return when (mime) {
                "application/pdf" -> PDF
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                "application/vnd.oasis.opendocument.text" -> ODT
                "text/markdown" -> MARKDOWN
                "text/plain" -> TXT
                "application/msword" -> DOC
                "application/rtf", "text/rtf" -> RTF
                "text/csv", "text/comma-separated-values", "application/csv", "text/tab-separated-values" -> CSV
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX
                "application/vnd.ms-excel" -> XLS
                "application/vnd.oasis.opendocument.spreadsheet" -> ODS
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX
                "application/vnd.ms-powerpoint" -> PPT
                "application/vnd.oasis.opendocument.presentation" -> ODP
                else -> UNKNOWN
            }
        }

        private val byExtension = mapOf(
            "pdf" to PDF, "docx" to DOCX, "odt" to ODT, "txt" to TXT, "md" to MARKDOWN, "markdown" to MARKDOWN,
            "doc" to DOC, "rtf" to RTF, "xlsx" to XLSX, "xls" to XLS, "ods" to ODS, "csv" to CSV, "tsv" to CSV,
            "pptx" to PPTX, "ppt" to PPT, "odp" to ODP,
        )
    }
}

/**
 * Reads the text of a document as paragraphs. Streams the XML, so large files do not load whole,
 * and stops at fixed limits so a damaged or hostile file cannot exhaust memory.
 */
object TextExtractor {
    /** Largest unpacked XML part read from a DOCX or ODT. */
    const val MAX_XML_BYTES = 64L * 1024 * 1024
    /** Largest plain text file read. */
    const val MAX_TEXT_BYTES = 20L * 1024 * 1024

    /** Longest paragraph shown as one piece; longer ones are split so the screen never lays out megabytes at once. */
    const val MAX_PARAGRAPH = 4000
    /** Most paragraphs kept from one file. */
    const val MAX_PARAGRAPHS = 500_000

    fun read(input: InputStream, format: Format): List<Paragraph> = split(readWhole(input, format))

    /** Splits very long paragraphs (at a space when there is one nearby) and caps the count. */
    fun split(paragraphs: List<Paragraph>): List<Paragraph> {
        if (paragraphs.size <= MAX_PARAGRAPHS && paragraphs.all { it.text.length <= MAX_PARAGRAPH }) return paragraphs
        val out = ArrayList<Paragraph>()
        for (p in paragraphs) {
            if (out.size >= MAX_PARAGRAPHS) break
            var start = 0
            val t = p.text
            if (t.length <= MAX_PARAGRAPH) { out += p; continue }
            while (start < t.length && out.size < MAX_PARAGRAPHS) {
                var end = minOf(t.length, start + MAX_PARAGRAPH)
                if (end < t.length) {
                    val space = t.lastIndexOf(' ', end)
                    if (space > start + MAX_PARAGRAPH / 2) end = space + 1
                }
                out += Paragraph(t.substring(start, end), p.heading)
                start = end
            }
        }
        return out
    }

    private fun readWhole(input: InputStream, format: Format): List<Paragraph> = when (format) {
        Format.DOCX -> fromZip(input, "word/document.xml", ::docx)
        Format.ODT -> fromZip(input, "content.xml", ::odt)
        Format.TXT -> plain(input, markdown = false)
        Format.MARKDOWN -> plain(input, markdown = true)
        Format.DOC -> DocReader.read(input)
        // A table compared row by row
        Format.CSV -> CsvReader.read(input).rows.map { Paragraph(it.joinToString(" | ")) }
        // Sheets compared row by row, each sheet under its name
        Format.XLSX -> sheetText(ua.hramota.doc.sheet.XlsxReader.read(input))
        Format.XLS -> sheetText(ua.hramota.doc.sheet.XlsReader.read(input))
        Format.PPTX -> ua.hramota.doc.slides.PptxReader.read(input).paragraphs { "#$it" }
        else -> throw IOException("No text reader for $format")
    }

    private fun sheetText(w: ua.hramota.doc.sheet.Workbook): List<Paragraph> = w.sheets.flatMap { s ->
        listOf(Paragraph(s.name, 2)) + s.rows.map { r -> Paragraph(r.cols.indices.joinToString(" | ") { s.text(r, it) }) }
    }

    private fun fromZip(input: InputStream, part: String, parse: (InputStream) -> List<Paragraph>): List<Paragraph> {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == part) return parse(Limited(zip, MAX_XML_BYTES))
            }
        }
        throw IOException("$part not found")
    }

    private fun parser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply {
            setInput(input, "UTF-8")
        }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** DOCX body: w:p paragraphs, w:t text, tabs and breaks; headings by paragraph style. */
    fun docx(input: InputStream): List<Paragraph> {
        val p = parser(input)
        val out = ArrayList<Paragraph>()
        val text = StringBuilder()
        var inPara = 0
        var heading = 0
        var inText = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> if (p.namespace == W) when (p.name) {
                    "p" -> { if (inPara == 0) { text.clear(); heading = 0 }; inPara++ }
                    "t" -> inText = true
                    "tab" -> if (inPara > 0) text.append('\t')
                    "br", "cr" -> if (inPara > 0) text.append('\n')
                    "pStyle" -> heading = headingLevel(p.getAttributeValue(W, "val"))
                    "outlineLvl" -> p.getAttributeValue(W, "val")?.toIntOrNull()?.let { if (it in 0..5) heading = it + 1 }
                }
                XmlPullParser.TEXT -> if (inText && inPara > 0) text.append(p.text)
                XmlPullParser.END_TAG -> if (p.namespace == W) when (p.name) {
                    "t" -> inText = false
                    "p" -> { inPara--; if (inPara == 0) out += Paragraph(text.toString(), heading) }
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    private const val TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

    /** ODT body: text:p and text:h, with spaces, tabs and line breaks. */
    fun odt(input: InputStream): List<Paragraph> {
        val p = parser(input)
        val out = ArrayList<Paragraph>()
        val text = StringBuilder()
        var depth = 0
        var heading = 0
        var inBody = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "body") inBody = true
                    if (inBody && p.namespace == TEXT_NS) when (p.name) {
                        "p", "h" -> {
                            if (depth == 0) {
                                text.clear()
                                heading = if (p.name == "h") (p.getAttributeValue(TEXT_NS, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6) else 0
                            }
                            depth++
                        }
                        "s" -> if (depth > 0) repeat((p.getAttributeValue(TEXT_NS, "c")?.toIntOrNull() ?: 1).coerceIn(1, 1000)) { text.append(' ') }
                        "tab" -> if (depth > 0) text.append('\t')
                        "line-break" -> if (depth > 0) text.append('\n')
                        // Notes are not part of the running text
                        "note" -> skip(p)
                    }
                }
                XmlPullParser.TEXT -> if (depth > 0) text.append(p.text)
                XmlPullParser.END_TAG -> if (inBody && p.namespace == TEXT_NS && (p.name == "p" || p.name == "h")) {
                    depth--
                    if (depth == 0) out += Paragraph(text.toString(), heading)
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    private fun skip(p: XmlPullParser) {
        var level = 1
        while (level > 0) when (p.next()) {
            XmlPullParser.START_TAG -> level++
            XmlPullParser.END_TAG -> level--
            XmlPullParser.END_DOCUMENT -> return
        }
    }

    /** "Heading1", "heading 2", "Заголовок3", "Title" → level; anything else → 0. */
    fun headingLevel(style: String?): Int {
        if (style == null) return 0
        val s = style.lowercase().replace(" ", "")
        if (s == "title") return 1
        val m = Regex("^(heading|заголовок)([1-6])$").find(s) ?: return 0
        return m.groupValues[2].toInt()
    }

    /** Plain text: blank-line-free lines become paragraphs; Markdown "#" lines become headings. */
    fun plain(input: InputStream, markdown: Boolean): List<Paragraph> {
        // Text saved by older Windows programs is often in Windows-1251, not UTF-8
        val text = TextDecoding.decode(TextDecoding.readLimited(input, MAX_TEXT_BYTES))
        return text.split(Regex("\r\n|\n|\r")).map { line ->
            if (markdown) {
                val m = Regex("^(#{1,6})\\s+(.*)$").find(line)
                if (m != null) return@map Paragraph(m.groupValues[2], m.groupValues[1].length)
            }
            Paragraph(line)
        }
    }

    /** Stops reading past [limit] bytes with an error instead of filling memory. */
    private class Limited(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        private fun check(n: Int) {
            if (n > 0) { count += n; if (count > limit) throw IOException("File part is larger than $limit bytes") }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) check(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { check(it) }
        // Closing the part must not close the whole ZIP stream around it
        override fun close() = Unit
    }
}
