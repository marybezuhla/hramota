package ua.hramota.doc.sheet

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a workbook as XLSX from what Hramota read (an old Excel XLS, say): values, the look of
 * cells (fonts, fills, borders, alignment, number formats), column widths, row heights, merged
 * cells and frozen panes. Formulas are not carried over: their results are.
 */
object XlsxWriter {
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for ((k, c) in s.withIndex()) when {
            c == '&' -> b.append("&amp;"); c == '<' -> b.append("&lt;"); c == '>' -> b.append("&gt;"); c == '"' -> b.append("&quot;")
            c < ' ' && c != '\t' && c != '\n' -> {}
            c == '￾' || c == '￿' -> {}
            c.isHighSurrogate() -> if (k + 1 < s.length && s[k + 1].isLowSurrogate()) b.append(c)
            c.isLowSurrogate() -> if (k > 0 && s[k - 1].isHighSurrogate()) b.append(c)
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun argb(c: Int) = "%08X".format(c)

    /** The styles part, with one cell format per distinct [CellStyle] (index = position in [looks]). */
    private fun styles(looks: List<CellStyle>): String {
        val fonts = LinkedHashMap<String, Int>(); val fills = LinkedHashMap<String, Int>(); val borders = LinkedHashMap<String, Int>(); val formats = LinkedHashMap<String, Int>()
        fills["<fill><patternFill patternType=\"none\"/></fill>"] = 0; fills["<fill><patternFill patternType=\"gray125\"/></fill>"] = 1
        val xfs = looks.map { st ->
            val font = "<font>" + (if (st.bold) "<b/>" else "") + (if (st.italic) "<i/>" else "") + (if (st.strike) "<strike/>" else "") + (if (st.underline) "<u/>" else "") +
                "<sz val=\"${st.size}\"/>" + (st.color?.let { "<color rgb=\"${argb(it)}\"/>" } ?: "") + "<name val=\"Calibri\"/><family val=\"2\"/></font>"
            val fontId = fonts.getOrPut(font) { fonts.size }
            val fillId = st.fill?.let { c -> fills.getOrPut("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${argb(c)}\"/><bgColor indexed=\"64\"/></patternFill></fill>") { fills.size } } ?: 0
            val border = "<border>" + listOf("left" to 1, "right" to 4, "top" to 2, "bottom" to 8).joinToString("") { (n, bit) -> if (st.borders and bit != 0) "<$n style=\"thin\"><color auto=\"1\"/></$n>" else "<$n/>" } + "<diagonal/></border>"
            val borderId = borders.getOrPut(border) { borders.size }
            val numId = when (st.format) { "General" -> 0; else -> formats.getOrPut(st.format) { 164 + formats.size } }
            val align = if (st.align != null || st.vertical != null || st.wrap) "<alignment" + (st.align?.let { " horizontal=\"$it\"" } ?: "") + (st.vertical?.let { " vertical=\"$it\"" } ?: "") + (if (st.wrap) " wrapText=\"1\"" else "") + "/>" else ""
            "<xf numFmtId=\"$numId\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\"" +
                (if (numId != 0) " applyNumberFormat=\"1\"" else "") + (if (align.isNotEmpty()) " applyAlignment=\"1\">$align</xf>" else "/>")
        }
        val b = StringBuilder(HEAD + "<styleSheet xmlns=\"$S\">")
        if (formats.isNotEmpty()) b.append("<numFmts count=\"${formats.size}\">").append(formats.entries.joinToString("") { (code, id) -> "<numFmt numFmtId=\"$id\" formatCode=\"${esc(code)}\"/>" }).append("</numFmts>")
        if (fonts.isEmpty()) fonts["<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"] = 0
        if (borders.isEmpty()) borders["<border><left/><right/><top/><bottom/><diagonal/></border>"] = 0
        b.append("<fonts count=\"${fonts.size}\">").append(fonts.keys.joinToString("")).append("</fonts>")
        b.append("<fills count=\"${fills.size}\">").append(fills.keys.joinToString("")).append("</fills>")
        b.append("<borders count=\"${borders.size}\">").append(borders.keys.joinToString("")).append("</borders>")
        b.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        val all = xfs.ifEmpty { listOf("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>") }
        b.append("<cellXfs count=\"${all.size}\">").append(all.joinToString("")).append("</cellXfs>")
        b.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        return b.toString()
    }

    private fun num(f: Float) = "%.2f".format(java.util.Locale.ROOT, f)

    /** Writes one sheet straight into the package ([b] goes to the open entry), so big sheets never sit whole in memory. */
    private fun sheet(sh: Sheet, b: Appendable, styleOf: (Int) -> Int) {
        b.append(HEAD + "<worksheet xmlns=\"$S\" xmlns:r=\"$R\">")
        if (sh.frozenRows > 0 || sh.frozenCols > 0) {
            val top = columnName(sh.frozenCols) + (sh.frozenRows + 1)
            b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane")
            if (sh.frozenCols > 0) b.append(" xSplit=\"${sh.frozenCols}\"")
            if (sh.frozenRows > 0) b.append(" ySplit=\"${sh.frozenRows}\"")
            val pane = when { sh.frozenRows > 0 && sh.frozenCols > 0 -> "bottomRight"; sh.frozenRows > 0 -> "bottomLeft"; else -> "topRight" }
            b.append(" topLeftCell=\"$top\" activePane=\"$pane\" state=\"frozen\"/></sheetView></sheetViews>")
        }
        b.append("<sheetFormatPr defaultRowHeight=\"${num(sh.defaultHeight)}\" defaultColWidth=\"${num(sh.defaultWidth / 5.25f)}\"/>")
        val cols = sh.sizedCols.filter { it in 0 until SheetLimits.MAX_COLS && sh.width(it) != sh.defaultWidth }.sorted()
        if (cols.isNotEmpty()) {
            b.append("<cols>")
            for (c in cols) b.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${num(sh.width(c) / 5.25f)}\"${if (sh.width(c) == 0f) " hidden=\"1\"" else ""} customWidth=\"1\"/>")
            b.append("</cols>")
        }
        b.append("<sheetData>")
        // Rows with cells, and empty rows that keep a height of their own (or are hidden), in order
        val sized = sh.sizedRows.filter { it in 0 until SheetLimits.MAX_ROWS && sh.height(it) != sh.defaultHeight && sh.row(it) == null }.sorted()
        var e = 0
        fun rowStart(index: Int) {
            val h = sh.height(index)
            b.append("<row r=\"${index + 1}\"")
            if (h == 0f) b.append(" hidden=\"1\"") else if (h != sh.defaultHeight) b.append(" ht=\"${num(h)}\" customHeight=\"1\"")
        }
        fun emptyRowsBefore(limit: Int) { while (e < sized.size && sized[e] < limit) { rowStart(sized[e++]); b.append("/>") } }
        for (row in sh.rows) {
            emptyRowsBefore(row.index)
            rowStart(row.index); b.append(">")
            for (k in row.cols.indices) {
                val ref = columnName(row.cols[k]) + (row.index + 1)
                val s = styleOf(row.styles[k]).let { if (it != 0) " s=\"$it\"" else "" }
                val t = row.texts[k]
                when {
                    t == null -> { val v = row.numbers[k]; if (v.isFinite()) b.append("<c r=\"$ref\"$s><v>${if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()}</v></c>") }
                    t.isEmpty() -> b.append("<c r=\"$ref\"$s/>")
                    else -> b.append("<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(t.take(SheetLimits.MAX_TEXT))}</t></is></c>")
                }
            }
            b.append("</row>")
        }
        emptyRowsBefore(Int.MAX_VALUE)
        b.append("</sheetData>")
        // Excel refuses a merge of one cell, merges that overlap, and ones outside the sheet
        val taken = ArrayList<Merge>()
        for (m in sh.merges) {
            if (m.top < 0 || m.left < 0 || m.bottom >= SheetLimits.MAX_ROWS || m.right >= SheetLimits.MAX_COLS || m.bottom < m.top || m.right < m.left) continue
            if (m.top == m.bottom && m.left == m.right) continue
            if (taken.any { o -> m.left <= o.right && o.left <= m.right && m.top <= o.bottom && o.top <= m.bottom }) continue
            taken += m
        }
        if (taken.isNotEmpty()) {
            b.append("<mergeCells count=\"${taken.size}\">")
            for (m in taken) b.append("<mergeCell ref=\"${columnName(m.left)}${m.top + 1}:${columnName(m.right)}${m.bottom + 1}\"/>")
            b.append("</mergeCells>")
        }
        b.append("</worksheet>")
    }

    /** Names Excel accepts: at most 31 characters, none of []:*?/\, no apostrophe at either end, not "History", each different. */
    internal fun sheetNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.mapIndexed { i, name ->
            val clean = esc(name).let { unescape(it) }
                .replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().trim('\'').trim().take(31).trimEnd('\'')
                .let { if (it.isBlank() || it.equals("History", ignoreCase = true)) "Sheet${i + 1}" else it }
            var n = clean
            var k = 2
            while (!used.add(n.lowercase(java.util.Locale.ROOT))) {
                val suffix = " (${k++})"
                n = clean.take(31 - suffix.length).trimEnd('\'') + suffix
            }
            n
        }
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")

    /** Writes [wb] as an XLSX package to [out]. */
    fun write(wb: Workbook, out: OutputStream) {
        // One list of looks for all sheets (each sheet keeps its own numbering when read); filled while the sheets are written
        val looks = ArrayList<CellStyle>()
        val index = HashMap<CellStyle, Int>()
        looks += CellStyle.DEFAULT; index[CellStyle.DEFAULT] = 0
        val sheetNames = sheetNames(wb.sheets.map { it.name })
        // The first visible sheet is the one shown when the file opens
        val first = wb.sheets.indexOfFirst { !it.hidden }.coerceAtLeast(0)
        val date1904 = wb.sheets.any { it.date1904 }
        val workbook = HEAD + "<workbook xmlns=\"$S\" xmlns:r=\"$R\">" + (if (date1904) "<workbookPr date1904=\"1\"/>" else "") +
            (if (first > 0) "<bookViews><workbookView activeTab=\"$first\" firstSheet=\"$first\"/></bookViews>" else "") + "<sheets>" +
            sheetNames.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\"${if (wb.sheets[i].hidden) " state=\"hidden\"" else ""} r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>"
        val rels = HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            wb.sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$R/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rIdStyles\" Type=\"$R/styles\" Target=\"styles.xml\"/></Relationships>"
        val types = HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            wb.sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>"
        val z = ZipOutputStream(out)
        val w = java.io.OutputStreamWriter(z, Charsets.UTF_8).buffered(1 shl 16)
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); w.write(text); w.flush(); z.closeEntry() }
        put("[Content_Types].xml", types)
        put("_rels/.rels", HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        put("xl/workbook.xml", workbook)
        put("xl/_rels/workbook.xml.rels", rels)
        for ((i, sh) in wb.sheets.withIndex()) {
            z.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
            sheet(sh, w) { k -> val st = sh.styles.getOrNull(k) ?: CellStyle.DEFAULT; index.getOrPut(st) { looks += st; looks.size - 1 } }
            w.flush(); z.closeEntry()
        }
        // The styles last: only now are all the looks known (the order of parts in a package does not matter)
        put("xl/styles.xml", styles(looks))
        z.finish(); z.flush()
    }
}
