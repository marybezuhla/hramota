package ua.hramota.doc.docx

import org.xmlpull.v1.XmlPullParser
import ua.hramota.doc.Inspection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Page size and margins, in twips. */
data class PageSetup(
    val width: Int = 11906, val height: Int = 16838,
    val top: Int = 1440, val bottom: Int = 1440, val left: Int = 1440, val right: Int = 1440,
)

/** A piece of a paragraph. */
sealed interface Inline {
    data class Text(val text: String, val props: RunProps) : Inline
    /** A picture, its size in points; [rel] points at the file inside the package. */
    data class Image(val rel: String, val width: Float, val height: Float) : Inline
    data object Tab : Inline
    data object LineBreak : Inline
    data object PageBreak : Inline
}

sealed interface Block {
    /** A paragraph with fully resolved formatting; [label] is its list number or bullet. */
    data class Para(val props: ParaProps, val mark: RunProps, val inlines: List<Inline>, val label: String?, val labelProps: RunProps?) : Block
    data class Table(val columns: List<Int>, val rows: List<Row>, val borders: Boolean, val indent: Int) : Block
}

data class Row(val cells: List<Cell>, val height: Int?, val exact: Boolean, val cantSplit: Boolean = false)
data class Cell(val span: Int, val mergedAbove: Boolean, val blocks: List<Block>, val shading: Int?)

class DocxDocument(
    val page: PageSetup,
    val blocks: List<Block>,
    /** Pictures by path inside the package, e.g. "media/image1.png". */
    val media: Map<String, ByteArray>,
    val fonts: ThemeFonts,
    /** Relationship id → picture path, e.g. "rId5" → "media/image1.png". */
    val rels: Map<String, String>,
    /** "Don't use HTML paragraph auto spacing": paragraph gaps add up instead of taking the larger. */
    val addSpacing: Boolean = false,
    /** Word version the document was laid out for (15 = Word 2013 and later). */
    val compatibilityMode: Int = 15,
    val inspection: Inspection = Inspection(),
) {
    fun picture(rel: String): ByteArray? = rels[rel]?.let { media[it] }
}

/** The two theme fonts a document's styles may refer to. */
data class ThemeFonts(val minor: String = "Calibri", val major: String = "Calibri Light")

/**
 * Reads a DOCX: the body with resolved styles, numbering and pictures. One pass over the ZIP;
 * every part has a size limit, so a damaged or hostile file cannot exhaust memory.
 */
object DocxReader {
    const val MAX_PART = 64L * 1024 * 1024
    /** Styles, numbering, theme, settings and relationships are small in real documents. */
    const val MAX_SMALL_PART = 8L * 1024 * 1024
    const val MAX_MEDIA_TOTAL = 96L * 1024 * 1024
    /** Paragraphs and table cells read; a longer document shows its first part. */
    const val MAX_BLOCKS = 400_000
    /** Tables inside tables, and wrappers (links, insertions) inside a paragraph. */
    const val MAX_TABLE_DEPTH = 16
    const val MAX_INLINE_DEPTH = 64
    /** Columns of a table, as in Word. */
    const val MAX_COLUMNS = 63

    fun read(input: InputStream): DocxDocument {
        val parts = HashMap<String, ByteArray>()
        val media = HashMap<String, ByteArray>()
        var mediaTotal = 0L
        // For the document inspector: what the package carries besides the text
        var macros = false
        val embedded = ArrayList<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "word/document.xml" -> parts[name] = readLimited(zip, MAX_PART)
                    name == "word/styles.xml" || name == "word/numbering.xml" || name == "word/theme/theme1.xml" ||
                        name == "word/_rels/document.xml.rels" || name == "word/settings.xml" || name == "word/_rels/settings.xml.rels" ||
                        name == "word/comments.xml" || name == "docProps/core.xml" -> parts[name] = readLimited(zip, MAX_SMALL_PART)
                    // Package paths are matched without regard to case: Word itself opens "Word/Embeddings/…"
                    name.contains("vbaProject", ignoreCase = true) || name.contains("vbaData", ignoreCase = true) -> macros = true
                    (name.contains("/embeddings/", ignoreCase = true) || name.contains("/activeX/", ignoreCase = true)) && !name.endsWith("/") ->
                        if (embedded.size < 100) embedded += name.substringAfterLast('/')
                    name.startsWith("word/media/") -> {
                        val bytes = readLimited(zip, MAX_PART)
                        mediaTotal += bytes.size
                        if (mediaTotal <= MAX_MEDIA_TOTAL) media[name.removePrefix("word/")] = bytes
                    }
                }
            }
        }
        val doc = parts["word/document.xml"] ?: throw IOException("word/document.xml not found")
        parts.values.forEach(Xml::checkNoDoctype)
        val styles = Styles.parse(parts["word/styles.xml"]?.let { Xml.tree(ByteArrayInputStream(it)) })
        val numbering = Numbering.parse(parts["word/numbering.xml"]?.let { Xml.tree(ByteArrayInputStream(it)) })
        val theme = parts["word/theme/theme1.xml"]?.let { Xml.tree(ByteArrayInputStream(it)) }
        val fonts = ThemeFonts(
            minor = theme?.find("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() } ?: "Calibri",
            major = theme?.find("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() } ?: "Calibri Light",
        )
        // Relationship id → media path, e.g. rId5 → media/image1.png
        val rels = parts["word/_rels/document.xml.rels"]?.let { Xml.tree(ByteArrayInputStream(it)) }
            ?.all("Relationship")?.mapNotNull { r -> r.attr("Id")?.let { id -> r.attr("Target")?.let { id to it.removePrefix("/word/") } } }?.toMap().orEmpty()

        val body = Body(styles, numbering, rels)
        val blocks = ArrayList<Block>()
        var page = PageSetup()
        val p = Xml.parser(ByteArrayInputStream(doc))
        var inBody = false
        // Streams the body one top-level element at a time: a large document is never one big tree
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when {
                    p.name == "body" -> inBody = true
                    // A table can run to thousands of rows: it is read row by row
                    inBody && p.name == "tbl" -> {
                        body.streamTable(p, blocks)
                        if (body.count > MAX_BLOCKS) break
                    }
                    inBody && (p.name == "p" || p.name == "sectPr" || p.name == "sdt") -> {
                        val node = Xml.subtree(p)
                        when (node.name) {
                            "sectPr" -> page = pageSetup(node)
                            else -> body.blocks(node, blocks, 0)
                        }
                        if (body.count > MAX_BLOCKS) break
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        val settings = parts["word/settings.xml"]?.let { Xml.tree(ByteArrayInputStream(it)) }
        val addSpacing = settings?.path("compat", "doNotUseHTMLParagraphAutoSpacing").isOn() == true
        val compat = settings?.child("compat")?.all("compatSetting")
            ?.firstOrNull { it.attr("name") == "compatibilityMode" }?.attr("val")?.toIntOrNull()
            // No setting means an older Word document
            ?: if (settings != null) 12 else 15
        val inspection = inspect(parts, doc, macros, embedded)
        return DocxDocument(page, blocks, media, fonts, rels, addSpacing, compat, inspection)
    }

    /** What the inspector reports: risky parts (never run or fetched) and things to know before sending. */
    private fun inspect(parts: Map<String, ByteArray>, doc: ByteArray, macros: Boolean, embedded: List<String>): Inspection {
        fun rels(name: String) = parts[name]?.let { Xml.tree(ByteArrayInputStream(it)) }?.all("Relationship").orEmpty()
        val docRels = rels("word/_rels/document.xml.rels")
        val external = (docRels + rels("word/_rels/settings.xml.rels")).filter { it.attr("TargetMode") == "External" }
        val links = external.count { it.attr("Type")?.endsWith("/hyperlink") == true }
        // Objects the relationships name even if they sit outside the usual folders
        val related = docRels.filter { r ->
            r.attr("TargetMode") != "External" && r.attr("Type")?.substringAfterLast('/')?.lowercase() in setOf("oleobject", "package", "control", "activexcontrol")
        }.mapNotNull { it.attr("Target")?.substringAfterLast('/') }
        val core = parts["docProps/core.xml"]?.let { Xml.tree(ByteArrayInputStream(it)) }
        // Byte searches: the body can be tens of megabytes, too big to turn into one string
        fun has(vararg marks: String) = marks.any { indexOf(doc, it.toByteArray()) >= 0 }
        return Inspection(
            macros = macros || docRels.any { it.attr("Type")?.endsWith("/vbaProject", ignoreCase = true) == true },
            embedded = (embedded + related).distinct().take(100),
            // Anything external that is not a link: a remote template, a linked picture or object
            remoteResources = external.size - links,
            dde = hasDde(doc),
            links = links,
            trackedChanges = has("<w:ins ", "<w:del "),
            comments = parts["word/comments.xml"]?.let { c -> count(c, "<w:comment ".toByteArray()) } ?: 0,
            hiddenText = has("<w:vanish/>", "<w:vanish "),
            author = core?.find("creator")?.let { textOf(it) },
            lastModifiedBy = core?.find("lastModifiedBy")?.let { textOf(it) },
        )
    }

    private fun textOf(n: XmlNode): String? = n.text.takeIf { it.isNotBlank() }?.take(200)

    private fun indexOf(data: ByteArray, mark: ByteArray, from: Int = 0): Int {
        if (mark.isEmpty()) return -1
        var i = from
        val last = data.size - mark.size
        outer@ while (i <= last) {
            if (data[i] == mark[0]) {
                for (k in 1 until mark.size) if (data[i + k] != mark[k]) { i++; continue@outer }
                return i
            }
            i++
        }
        return -1
    }

    /**
     * A DDE field, in one pass over the body: "DDE " or "DDEAUTO" (any case, as Word reads field names)
     * right after a tag (">", "> ") or at the start of a simple field's instruction (w:instr=").
     */
    private fun hasDde(data: ByteArray): Boolean {
        fun lower(i: Int): Int = data[i].toInt().let { if (it in 'A'.code..'Z'.code) it + 32 else it }
        fun at(i: Int, mark: String): Boolean = i >= 0 && i + mark.length <= data.size && mark.indices.all { lower(i + it) == mark[it].code }
        var i = 0
        val last = data.size - 3
        while (i <= last) {
            val b = data[i].toInt()
            if ((b == 'd'.code || b == 'D'.code) && lower(i + 1) == 'd'.code && lower(i + 2) == 'e'.code) {
                // Skip one space before the name
                val j = if (i > 0 && data[i - 1] == ' '.code.toByte()) i - 1 else i
                val afterTag = j > 0 && data[j - 1] == '>'.code.toByte() && (at(i + 3, "auto") || at(i + 3, " "))
                val inInstr = at(j - 9, "w:instr=\"")
                if (afterTag || inInstr) return true
            }
            i++
        }
        return false
    }

    private fun count(data: ByteArray, mark: ByteArray): Int {
        var n = 0
        var i = indexOf(data, mark)
        while (i >= 0 && n < 100_000) { n++; i = indexOf(data, mark, i + mark.size) }
        return n
    }

    private fun pageSetup(s: XmlNode): PageSetup {
        val sz = s.child("pgSz")
        val m = s.child("pgMar")
        val d = PageSetup()
        // Page sides from 1 to 22 inches (Word's limits); margins leave at least half an inch for text
        val w = sz?.attr("w")?.toIntOrNull()?.coerceIn(1440, 31680) ?: d.width
        val h = sz?.attr("h")?.toIntOrNull()?.coerceIn(1440, 31680) ?: d.height
        fun margin(v: String?, def: Int, side: Int) = (v?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: def).coerceIn(0, (side - 720) / 2)
        return PageSetup(
            width = w,
            height = h,
            top = margin(m?.attr("top"), d.top, h),
            bottom = margin(m?.attr("bottom"), d.bottom, h),
            left = margin(m?.attr("left") ?: m?.attr("start"), d.left, w),
            right = margin(m?.attr("right") ?: m?.attr("end"), d.right, w),
        )
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Part larger than $limit bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Turns body elements into blocks, resolving styles and numbering. */
    private class Body(val styles: Styles, val numbering: Numbering, val rels: Map<String, String>) {
        /** Paragraphs and cells read so far, against [MAX_BLOCKS]. */
        var count = 0

        fun blocks(node: XmlNode, out: MutableList<Block>, depth: Int) {
            if (count > MAX_BLOCKS || depth > MAX_TABLE_DEPTH) return
            when (node.name) {
                "p" -> { count++; out += para(node) }
                "tbl" -> out += table(node, depth + 1)
                // Content controls wrap ordinary paragraphs and tables
                "sdt" -> node.child("sdtContent")?.children?.forEach { blocks(it, out, depth + 1) }
            }
        }

        /** Table styles of the tables being read, innermost last. */
        private val tableStyles = ArrayList<String?>()

        fun para(p: XmlNode): Block.Para {
            val direct = ParaProps.parse(p.child("pPr"))
            var (styleP, styleR) = styles.paragraph(direct?.styleId)
            // In a table Word applies: document defaults < table style < paragraph style < direct.
            // A paragraph-style value equal to the document default does not override the table style.
            var defaults = styles.defaultPara
            var runDefaults = styles.defaultRun
            if (tableStyles.isNotEmpty()) {
                val (tableP, tableR) = styles.table(tableStyles.last())
                styleP = styleP.withoutDefaults(styles.defaultPara)
                defaults = defaults.with(tableP)
                runDefaults = runDefaults.with(tableR)
            }
            var props = defaults.with(styleP).with(direct)
            val baseRun = runDefaults.with(styleR)
            var label: String? = null
            var labelProps: RunProps? = null
            val numId = props.numId
            if (numId != null && numId != "0") {
                val lvl = props.numLevel ?: 0
                numbering.level(numId, lvl)?.let { level ->
                    // List indents come from the numbering level unless the paragraph sets its own
                    props = defaults.with(styleP).with(level.para).with(direct)
                    label = numbering.next(numId, lvl)
                    labelProps = baseRun.with(level.run)
                }
            }
            val markProps = baseRun.with(RunProps.parse(p.path("pPr", "rPr")))
            val inlines = ArrayList<Inline>()
            collectRuns(p, baseRun, inlines, 0)
            return Block.Para(props, markProps, inlines, label, labelProps)
        }

        private fun collectRuns(parent: XmlNode, base: RunProps, out: MutableList<Inline>, depth: Int) {
            if (depth > MAX_INLINE_DEPTH) return
            for (c in parent.children) when (c.name) {
                "r" -> run(c, base, out)
                // Inserted text in tracked changes is part of the current text; deleted text is not
                "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "bdo", "dir" -> collectRuns(c, base, out, depth + 1)
                "sdt" -> c.child("sdtContent")?.let { collectRuns(it, base, out, depth + 1) }
            }
        }

        private fun run(r: XmlNode, base: RunProps, out: MutableList<Inline>) {
            val direct = RunProps.parse(r.child("rPr"))
            val charStyle = r.path("rPr", "rStyle")?.attr("val")
            val props = base.with(styles.character(charStyle)).with(direct)
            if (props.vanish == true) return
            for (c in r.children) when (c.name) {
                "t" -> if (c.text.isNotEmpty()) out += Inline.Text(c.text, props)
                "tab", "ptab" -> out += Inline.Tab
                "br" -> out += if (c.attr("type") == "page") Inline.PageBreak else Inline.LineBreak
                "cr" -> out += Inline.LineBreak
                "noBreakHyphen" -> out += Inline.Text("‑", props)
                "softHyphen" -> Unit
                "sym" -> out += Inline.Text(Numbering.bullet(c.attr("char")?.toIntOrNull(16)?.toChar()?.toString() ?: ""), props)
                "drawing" -> image(c)?.let { out += it }
                "pict", "object" -> c.find("imagedata")?.attr("id")?.let { id ->
                    rels[id]?.let { out += Inline.Image(id, 0f, 0f) }
                }
            }
        }

        private fun image(d: XmlNode): Inline.Image? {
            val holder = d.child("inline") ?: d.child("anchor") ?: return null
            val ext = holder.child("extent") ?: return null
            val cx = ext.attr("cx")?.toLongOrNull() ?: return null
            val cy = ext.attr("cy")?.toLongOrNull() ?: return null
            val embed = holder.find("blip")?.attr("embed") ?: return null
            // Sizes in EMU (12 700 per point), no larger than the largest page
            return Inline.Image(embed, (cx / 12700f).coerceIn(0f, 1584f), (cy / 12700f).coerceIn(0f, 1584f))
        }

        fun table(t: XmlNode, depth: Int): Block.Table {
            val tblPr = t.child("tblPr")
            val styleId = tblPr?.child("tblStyle")?.attr("val")
            tableStyles += styleId
            try {
                // Rows stop with the block limit too: a table cannot hold millions of rows in memory
                val rows = t.all("tr").asSequence().takeWhile { count <= MAX_BLOCKS }.map { row(it, depth) }.toList()
                return tableOf(tblPr, t.child("tblGrid"), styleId, rows)
            } finally { tableStyles.removeAt(tableStyles.size - 1) }
        }

        /** A top-level table straight from the parser (at its start tag), one row tree at a time. */
        fun streamTable(p: XmlPullParser, out: MutableList<Block>) {
            var tblPr: XmlNode? = null
            var grid: XmlNode? = null
            val rows = ArrayList<Row>()
            tableStyles += null
            try {
                while (true) when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        val node = Xml.subtree(p)
                        when (node.name) {
                            "tblPr" -> { tblPr = node; tableStyles[tableStyles.size - 1] = node.child("tblStyle")?.attr("val") }
                            "tblGrid" -> grid = node
                            "tr" -> if (count <= MAX_BLOCKS) rows += row(node, 1)
                        }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "tbl") break
                    XmlPullParser.END_DOCUMENT -> break
                }
                out += tableOf(tblPr, grid, tableStyles.last(), rows)
            } finally { tableStyles.removeAt(tableStyles.size - 1) }
        }

        private fun row(tr: XmlNode, depth: Int): Row {
            val trh = tr.path("trPr", "trHeight")
            return Row(
                cells = tr.all("tc").take(MAX_COLUMNS).map { tc ->
                    val pr = tc.child("tcPr")
                    val vm = pr?.child("vMerge")
                    val inner = ArrayList<Block>()
                    count++
                    for (c in tc.children) blocks(c, inner, depth)
                    Cell(
                        span = (pr?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1).coerceIn(1, MAX_COLUMNS),
                        mergedAbove = vm != null && vm.attr("val") != "restart",
                        blocks = inner,
                        shading = pr?.child("shd")?.attr("fill")?.let(::hexColor),
                    )
                },
                height = trh?.attr("val")?.toIntOrNull()?.coerceIn(0, ParaProps.MAX_LEN),
                exact = trh?.attr("hRule") == "exact",
                cantSplit = tr.path("trPr", "cantSplit").isOn() == true,
            )
        }

        private fun tableOf(tblPr: XmlNode?, grid: XmlNode?, styleId: String?, rows: List<Row>): Block.Table {
            val direct = tblPr?.child("tblBorders")?.children?.map { it.attr("val") }
            val borders = if (direct != null && direct.isNotEmpty()) direct.any { it != null && it != "nil" && it != "none" } else styles.tableHasBorders(styleId)
            val cols = grid?.all("gridCol")?.take(MAX_COLUMNS)
                ?.map { (it.attr("w")?.toIntOrNull() ?: 0).coerceIn(0, ParaProps.MAX_LEN) }.orEmpty()
            val indent = (tblPr?.child("tblInd")?.attr("w")?.toIntOrNull() ?: 0).coerceIn(-ParaProps.MAX_LEN, ParaProps.MAX_LEN)
            return Block.Table(cols, rows, borders, indent)
        }
    }
}
