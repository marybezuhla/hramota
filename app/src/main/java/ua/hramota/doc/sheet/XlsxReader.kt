package ua.hramota.doc.sheet

import org.xmlpull.v1.XmlPullParser
import ua.hramota.doc.Inspection
import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.XmlNode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Collects one sheet's cells in any order and builds it with rows sorted. */
class SheetBuilder(private val name: String, private val budget: IntArray) {
    /** One row's cells as they come, in plain arrays (no boxed numbers: a sheet may hold millions). */
    private class RowCells {
        var size = 0
        var cols = IntArray(4); var styles = IntArray(4); var numbers = DoubleArray(4); var texts = arrayOfNulls<String>(4)
        fun add(c: Int, s: Int, n: Double, t: String?) {
            if (size == cols.size) { val m = size * 2; cols = cols.copyOf(m); styles = styles.copyOf(m); numbers = numbers.copyOf(m); texts = texts.copyOf(m) }
            cols[size] = c; styles[size] = s; numbers[size] = n; texts[size] = t; size++
        }
    }
    private val rows = HashMap<Int, RowCells>()
    val widths = HashMap<Int, Float>()
    val heights = HashMap<Int, Float>()
    val merges = ArrayList<Merge>()
    var defaultWidth = 48f
    var defaultHeight = 15f
    var frozenRows = 0
    var frozenCols = 0

    /** False once the workbook's cell limit is reached. */
    val open: Boolean get() = budget[0] > 0

    fun number(row: Int, col: Int, style: Int, value: Double) = put(row, col, style, value, null)
    fun text(row: Int, col: Int, style: Int, value: String) = put(row, col, style, 0.0, value.take(SheetLimits.MAX_TEXT))
    /** An empty cell that still shows its fill or borders. */
    fun blank(row: Int, col: Int, style: Int) = put(row, col, style, 0.0, "")

    private fun put(row: Int, col: Int, style: Int, number: Double, text: String?) {
        if (row !in 0 until SheetLimits.MAX_ROWS || col !in 0 until SheetLimits.MAX_COLS || budget[0] <= 0) return
        budget[0]--
        val r = rows.getOrPut(row) { RowCells() }
        r.add(col, style, number, text)
    }

    fun build(styles: List<CellStyle>, date1904: Boolean): Sheet {
        val out = rows.entries.sortedBy { it.key }.map { (index, r) ->
            // Cells usually come in order; sorting is needed only when they do not
            val inOrder = (1 until r.size).all { r.cols[it] > r.cols[it - 1] }
            val order = if (inOrder) (0 until r.size).toList() else (0 until r.size).sortedBy { r.cols[it] }.distinctBy { r.cols[it] }
            SheetRow(
                index,
                IntArray(order.size) { r.cols[order[it]] },
                IntArray(order.size) { r.styles[order[it]] },
                DoubleArray(order.size) { r.numbers[order[it]] },
                Array(order.size) { r.texts[order[it]] },
            )
        }
        return Sheet(name, out, styles, widths, defaultWidth, heights, defaultHeight, merges.take(10_000), frozenRows, frozenCols, date1904)
    }
}

/** Width in points of a column width as XLSX stores it (characters including padding; 7 px each at 100%). */
fun storedWidth(width: Double): Float = (width * 7 * 0.75).toFloat().coerceIn(0f, 1500f)

/**
 * XLSX workbooks: sheets with their cells, shared strings, styles (fonts, fills, borders, alignment,
 * number formats), column widths, row heights, merged cells and frozen panes. Formulas show the
 * value saved in the file; nothing is calculated or run.
 */
object XlsxReader {
    const val MAX_PART = 96L * 1024 * 1024

    fun read(input: InputStream): Workbook {
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        var macros = false
        val embedded = ArrayList<String>()
        var external = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.removePrefix("/")
                val lower = name.lowercase()
                when {
                    lower.endsWith("vbaproject.bin") -> macros = true
                    lower.startsWith("xl/embeddings/") -> embedded += name.substringAfterLast('/')
                    lower.startsWith("xl/externallinks/") && lower.endsWith(".xml") -> external++
                    lower.endsWith(".xml") || lower.endsWith(".rels") -> {
                        val bytes = readLimited(zip, MAX_PART)
                        total += bytes.size
                        if (total > MAX_PART * 3) throw IOException("Workbook too large")
                        parts[name] = bytes
                    }
                }
            }
        }
        parts.values.forEach(Xml::checkNoDoctype)
        fun tree(name: String): XmlNode? = parts[name]?.let { Xml.tree(ByteArrayInputStream(it)) }

        val wb = tree("xl/workbook.xml") ?: throw IOException("Not a workbook")
        val date1904 = wb.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        val rels = tree("xl/_rels/workbook.xml.rels")?.all("Relationship").orEmpty()
            .associate { (it.attr("Id") ?: "") to (it.attr("Target") ?: "") }
        val strings = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val theme = themeColors(tree("xl/theme/theme1.xml"))
        val styles = styles(tree("xl/styles.xml"), theme)

        val budget = intArrayOf(SheetLimits.MAX_CELLS)
        val sheets = ArrayList<Sheet>()
        for (s in wb.child("sheets")?.all("sheet").orEmpty().take(SheetLimits.MAX_SHEETS)) {
            if (s.attr("state") == "hidden" || s.attr("state") == "veryHidden") continue
            val target = rels[s.attr("id")] ?: continue
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            val bytes = parts.remove(path) ?: continue
            val b = SheetBuilder(s.attr("name") ?: "Sheet", budget)
            sheet(bytes, b, strings)
            sheets += b.build(styles, date1904)
        }
        if (sheets.isEmpty()) throw IOException("No sheets")
        return Workbook(sheets, Inspection(macros = macros, embedded = embedded.take(100), remoteResources = external))
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

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val p = Xml.parser(ByteArrayInputStream(bytes))
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false; var inT = false; var phonetic = 0
        while (true) when (p.next()) {
            XmlPullParser.START_TAG -> when (p.name) {
                "si" -> { inSi = true; sb.clear() }
                "t" -> inT = true
                "rPh" -> phonetic++
            }
            XmlPullParser.TEXT -> if (inSi && inT && phonetic == 0 && sb.length < SheetLimits.MAX_TEXT) sb.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "si" -> { inSi = false; out += sb.toString(); if (out.size > SheetLimits.MAX_CELLS) return out }
                "t" -> inT = false
                "rPh" -> phonetic--
            }
            XmlPullParser.END_DOCUMENT -> return out
        }
    }

    /** Theme colours in the order cells refer to them: light 1, dark 1, light 2, dark 2, accents, links. */
    private fun themeColors(theme: XmlNode?): List<Int> {
        val scheme = theme?.find("clrScheme") ?: return emptyList()
        fun color(n: XmlNode?): Int {
            val c = n?.children?.firstOrNull() ?: return 0xFF000000.toInt()
            val hex = c.attr("lastClr") ?: c.attr("val") ?: "000000"
            return (hex.toLongOrNull(16)?.toInt() ?: 0) or 0xFF000000.toInt()
        }
        val names = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        return names.map { color(scheme.child(it)) }
    }

    private fun color(n: XmlNode?, theme: List<Int>): Int? {
        if (n == null || n.attr("auto") == "1") return null
        var c: Int? = n.attr("rgb")?.let { hex -> hex.takeLast(6).toLongOrNull(16)?.let { (it.toInt()) or 0xFF000000.toInt() } }
            ?: n.attr("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
            ?: n.attr("indexed")?.toIntOrNull()?.let { Palette.color(it, null) }
        val tint = n.attr("tint")?.toDoubleOrNull()
        if (c != null && tint != null && tint != 0.0) c = Palette.tint(c, tint)
        return c
    }

    private fun styles(root: XmlNode?, theme: List<Int>): List<CellStyle> {
        if (root == null) return listOf(CellStyle.DEFAULT)
        val formats = root.child("numFmts")?.all("numFmt").orEmpty().associate { (it.attr("numFmtId")?.toIntOrNull() ?: -1) to (it.attr("formatCode") ?: "General") }
        val fonts = root.child("fonts")?.all("font").orEmpty()
        val fills = root.child("fills")?.all("fill").orEmpty()
        val borders = root.child("borders")?.all("border").orEmpty()
        return root.child("cellXfs")?.all("xf").orEmpty().take(65_536).map { xf ->
            val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
            val fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0)?.child("patternFill")
            val border = borders.getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)
            val al = xf.child("alignment")
            val numId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            fun on(n: XmlNode?) = n != null && n.attr("val").let { it == null || it == "1" || it == "true" }
            var edges = 0
            for ((name, bit) in listOf("left" to 1, "top" to 2, "right" to 4, "bottom" to 8)) {
                val style = border?.child(name)?.attr("style")
                if (style != null && style != "none") edges = edges or bit
            }
            CellStyle(
                bold = on(font?.child("b")), italic = on(font?.child("i")),
                underline = font?.child("u")?.let { it.attr("val") != "none" } == true,
                strike = on(font?.child("strike")),
                size = (font?.child("sz")?.attr("val")?.toFloatOrNull() ?: 11f).coerceIn(1f, 409f),
                color = color(font?.child("color"), theme),
                fill = if (fill?.attr("patternType").let { it != null && it != "none" }) color(fill?.child("fgColor"), theme) ?: color(fill?.child("bgColor"), theme) else null,
                align = when (val h = al?.attr("horizontal")) { "center", "centerContinuous", "distributed" -> "center"; "right" -> "right"; "left", "fill", "justify" -> "left"; else -> h?.let { null } },
                vertical = when (al?.attr("vertical")) { "top" -> "top"; "center", "distributed", "justify" -> "center"; else -> null },
                wrap = al?.attr("wrapText").let { it == "1" || it == "true" },
                borders = edges,
                format = formats[numId] ?: NumberFormat.builtIn(numId) ?: "General",
            )
        }.ifEmpty { listOf(CellStyle.DEFAULT) }
    }

    /** A worksheet streamed row by row: a sheet of a million cells is never one tree. */
    private fun sheet(bytes: ByteArray, b: SheetBuilder, strings: List<String>) {
        val p = Xml.parser(ByteArrayInputStream(bytes))
        var row = -1
        var nextCol = 0
        var cellRef: String? = null
        var cellType: String? = null
        var cellStyle = 0
        var value: StringBuilder? = null
        var inline = StringBuilder()
        var inV = false; var inIs = false; var inT = false
        var cellCol = 0
        while (true) when (p.next()) {
            XmlPullParser.START_TAG -> when (p.name) {
                "sheetFormatPr" -> {
                    // Stored widths include the cell padding: a character is 7 px at 100%
                    p.getAttributeValue(null, "defaultColWidth")?.toDoubleOrNull()?.let { b.defaultWidth = storedWidth(it) }
                        ?: p.getAttributeValue(null, "baseColWidth")?.toDoubleOrNull()?.let { b.defaultWidth = ((it * 7 + 8) * 0.75).toFloat().coerceIn(0f, 1500f) }
                    p.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { b.defaultHeight = it.coerceIn(0f, 409f) }
                }
                "col" -> {
                    val min = (p.getAttributeValue(null, "min")?.toIntOrNull() ?: 1) - 1
                    val max = ((p.getAttributeValue(null, "max")?.toIntOrNull() ?: 1) - 1).coerceAtMost(min + 2000)
                    val hidden = p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                    val w = if (hidden) 0f else p.getAttributeValue(null, "width")?.toDoubleOrNull()?.let { storedWidth(it) } ?: b.defaultWidth
                    for (c in min..max) if (c in 0 until SheetLimits.MAX_COLS) b.widths[c] = w.coerceAtLeast(0f)
                }
                "pane" -> if (p.getAttributeValue(null, "state").let { it == "frozen" || it == "frozenSplit" }) {
                    b.frozenCols = p.getAttributeValue(null, "xSplit")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 50) ?: 0
                    b.frozenRows = p.getAttributeValue(null, "ySplit")?.toDoubleOrNull()?.toInt()?.coerceIn(0, 50) ?: 0
                }
                "row" -> {
                    row = (p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1)) ?: (row + 1)
                    nextCol = 0
                    val hidden = p.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                    val ht = p.getAttributeValue(null, "ht")?.toFloatOrNull()
                    if (hidden) b.heights[row] = 0f else if (ht != null && p.getAttributeValue(null, "customHeight").let { it == "1" || it == "true" } || ht != null && ht > b.defaultHeight) b.heights[row] = ht!!.coerceIn(0f, 409f)
                    if (!b.open) return
                }
                "c" -> {
                    cellRef = p.getAttributeValue(null, "r")
                    cellCol = cellRef?.let { columnOf(it) }?.takeIf { it >= 0 } ?: nextCol
                    cellRef?.let { r -> rowOf(r).takeIf { it >= 0 }?.let { row = it } }
                    nextCol = cellCol + 1
                    cellType = p.getAttributeValue(null, "t")
                    cellStyle = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                    value = null; inline.clear()
                }
                "v" -> { inV = true; value = StringBuilder() }
                "is" -> inIs = true
                "t" -> inT = true
                "mergeCell" -> p.getAttributeValue(null, "ref")?.let { ref ->
                    val parts = ref.split(':')
                    if (parts.size == 2) {
                        val m = Merge(rowOf(parts[0]), columnOf(parts[0]), rowOf(parts[1]), columnOf(parts[1]))
                        if (m.top >= 0 && m.left >= 0 && m.bottom >= m.top && m.right >= m.left && b.merges.size < 10_000) b.merges += m
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (inV) value?.let { if (it.length < SheetLimits.MAX_TEXT) it.append(p.text) }
                else if (inIs && inT && inline.length < SheetLimits.MAX_TEXT) inline.append(p.text)
            }
            XmlPullParser.END_TAG -> when (p.name) {
                "v" -> inV = false
                "t" -> inT = false
                "is" -> inIs = false
                "c" -> {
                    if (row >= 0) {
                        val v = value?.toString()
                        when (cellType) {
                            "s" -> v?.trim()?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { b.text(row, cellCol, cellStyle, it) } ?: b.blank(row, cellCol, cellStyle)
                            "inlineStr" -> b.text(row, cellCol, cellStyle, inline.toString())
                            "str" -> b.text(row, cellCol, cellStyle, v ?: "")
                            "b" -> b.text(row, cellCol, cellStyle, if (v?.trim() == "1") "TRUE" else "FALSE")
                            "e" -> b.text(row, cellCol, cellStyle, v ?: "#N/A")
                            else -> v?.trim()?.toDoubleOrNull()?.let { b.number(row, cellCol, cellStyle, it) } ?: b.blank(row, cellCol, cellStyle)
                        }
                    }
                }
            }
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** Excel's indexed colours (the default 64-colour palette, which an XLS file may change). */
object Palette {
    private val default = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** ARGB for index [i]; [custom] holds a file's own colours from index 8. Null for "automatic". */
    fun color(i: Int, custom: IntArray?): Int? = when {
        i in 8 until 64 && custom != null && i - 8 < custom.size -> custom[i - 8] or 0xFF000000.toInt()
        i in 0 until 64 -> default[i] or 0xFF000000.toInt()
        else -> null
    }

    /** A colour made lighter (positive tint) or darker (negative), as Excel applies theme tints. */
    fun tint(c: Int, t: Double): Int {
        fun ch(v: Int) = (if (t > 0) v + (255 - v) * t else v * (1 + t)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }
}
