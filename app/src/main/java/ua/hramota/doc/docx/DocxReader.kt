package ua.hramota.doc.docx

import org.xmlpull.v1.XmlPullParser
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

data class Row(val cells: List<Cell>, val height: Int?, val exact: Boolean)
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
    const val MAX_BLOCKS = 100_000
    /** Tables inside tables, and wrappers (links, insertions) inside a paragraph. */
    const val MAX_TABLE_DEPTH = 16
    const val MAX_INLINE_DEPTH = 64
    /** Columns of a table, as in Word. */
    const val MAX_COLUMNS = 63

    fun read(input: InputStream): DocxDocument {
        val parts = HashMap<String, ByteArray>()
        val media = HashMap<String, ByteArray>()
        var mediaTotal = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "word/document.xml" -> parts[name] = readLimited(zip, MAX_PART)
                    name == "word/styles.xml" || name == "word/numbering.xml" || name == "word/theme/theme1.xml" ||
                        name == "word/_rels/document.xml.rels" || name == "word/settings.xml" -> parts[name] = readLimited(zip, MAX_SMALL_PART)
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
                    inBody && (p.name == "p" || p.name == "tbl" || p.name == "sectPr" || p.name == "sdt") -> {
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
        return DocxDocument(page, blocks, media, fonts, rels, addSpacing, compat)
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
            try { return tableBody(t, tblPr, styleId, depth) } finally { tableStyles.removeAt(tableStyles.size - 1) }
        }

        private fun tableBody(t: XmlNode, tblPr: XmlNode?, styleId: String?, depth: Int): Block.Table {
            val direct = tblPr?.child("tblBorders")?.children?.map { it.attr("val") }
            val borders = if (direct != null && direct.isNotEmpty()) direct.any { it != null && it != "nil" && it != "none" } else styles.tableHasBorders(styleId)
            val cols = t.child("tblGrid")?.all("gridCol")?.take(MAX_COLUMNS)
                ?.map { (it.attr("w")?.toIntOrNull() ?: 0).coerceIn(0, ParaProps.MAX_LEN) }.orEmpty()
            val indent = (tblPr?.child("tblInd")?.attr("w")?.toIntOrNull() ?: 0).coerceIn(-ParaProps.MAX_LEN, ParaProps.MAX_LEN)
            // Rows stop with the block limit too: a table cannot hold millions of rows in memory
            val rows = t.all("tr").asSequence().takeWhile { count <= MAX_BLOCKS }.map { tr ->
                val trh = tr.path("trPr", "trHeight")
                Row(
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
                )
            }.toList()
            return Block.Table(cols, rows, borders, indent)
        }
    }
}
