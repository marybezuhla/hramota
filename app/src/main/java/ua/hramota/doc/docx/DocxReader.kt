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
    const val MAX_MEDIA_TOTAL = 96L * 1024 * 1024

    fun read(input: InputStream): DocxDocument {
        val parts = HashMap<String, ByteArray>()
        val media = HashMap<String, ByteArray>()
        var mediaTotal = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "word/document.xml" || name == "word/styles.xml" || name == "word/numbering.xml" ||
                        name == "word/theme/theme1.xml" || name == "word/_rels/document.xml.rels" || name == "word/settings.xml" -> parts[name] = readLimited(zip, MAX_PART)
                    name.startsWith("word/media/") -> {
                        val bytes = readLimited(zip, MAX_PART)
                        mediaTotal += bytes.size
                        if (mediaTotal <= MAX_MEDIA_TOTAL) media[name.removePrefix("word/")] = bytes
                    }
                }
            }
        }
        val doc = parts["word/document.xml"] ?: throw IOException("word/document.xml not found")
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
                            else -> body.blocks(node, blocks)
                        }
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
        return PageSetup(
            width = sz?.attr("w")?.toIntOrNull() ?: d.width,
            height = sz?.attr("h")?.toIntOrNull() ?: d.height,
            top = m?.attr("top")?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: d.top,
            bottom = m?.attr("bottom")?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: d.bottom,
            left = m?.attr("left")?.toIntOrNull() ?: m?.attr("start")?.toIntOrNull() ?: d.left,
            right = m?.attr("right")?.toIntOrNull() ?: m?.attr("end")?.toIntOrNull() ?: d.right,
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
        fun blocks(node: XmlNode, out: MutableList<Block>) {
            when (node.name) {
                "p" -> out += para(node)
                "tbl" -> out += table(node)
                // Content controls wrap ordinary paragraphs and tables
                "sdt" -> node.child("sdtContent")?.children?.forEach { blocks(it, out) }
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
            collectRuns(p, baseRun, inlines)
            return Block.Para(props, markProps, inlines, label, labelProps)
        }

        private fun collectRuns(parent: XmlNode, base: RunProps, out: MutableList<Inline>) {
            for (c in parent.children) when (c.name) {
                "r" -> run(c, base, out)
                // Inserted text in tracked changes is part of the current text; deleted text is not
                "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "bdo", "dir" -> collectRuns(c, base, out)
                "sdt" -> c.child("sdtContent")?.let { collectRuns(it, base, out) }
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
            return Inline.Image(embed, cx / 12700f, cy / 12700f)
        }

        fun table(t: XmlNode): Block.Table {
            val tblPr = t.child("tblPr")
            val styleId = tblPr?.child("tblStyle")?.attr("val")
            tableStyles += styleId
            try { return tableBody(t, tblPr, styleId) } finally { tableStyles.removeAt(tableStyles.size - 1) }
        }

        private fun tableBody(t: XmlNode, tblPr: XmlNode?, styleId: String?): Block.Table {
            val direct = tblPr?.child("tblBorders")?.children?.map { it.attr("val") }
            val borders = if (direct != null && direct.isNotEmpty()) direct.any { it != null && it != "nil" && it != "none" } else styles.tableHasBorders(styleId)
            val cols = t.child("tblGrid")?.all("gridCol")?.map { it.attr("w")?.toIntOrNull() ?: 0 }.orEmpty()
            val indent = tblPr?.child("tblInd")?.attr("w")?.toIntOrNull() ?: 0
            val rows = t.all("tr").map { tr ->
                val trh = tr.path("trPr", "trHeight")
                Row(
                    cells = tr.all("tc").map { tc ->
                        val pr = tc.child("tcPr")
                        val vm = pr?.child("vMerge")
                        val inner = ArrayList<Block>()
                        for (c in tc.children) blocks(c, inner)
                        Cell(
                            span = pr?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1,
                            mergedAbove = vm != null && vm.attr("val") != "restart",
                            blocks = inner,
                            shading = pr?.child("shd")?.attr("fill")?.let(::hexColor),
                        )
                    },
                    height = trh?.attr("val")?.toIntOrNull(),
                    exact = trh?.attr("hRule") == "exact",
                )
            }
            return Block.Table(cols, rows, borders, indent)
        }
    }
}
