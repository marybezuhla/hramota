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

    private fun sheet(sh: Sheet, styleOf: (Int) -> Int): String {
        val b = StringBuilder(HEAD + "<worksheet xmlns=\"$S\" xmlns:r=\"$R\">")
        if (sh.frozenRows > 0 || sh.frozenCols > 0) {
            val top = columnName(sh.frozenCols) + (sh.frozenRows + 1)
            b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane")
            if (sh.frozenCols > 0) b.append(" xSplit=\"${sh.frozenCols}\"")
            if (sh.frozenRows > 0) b.append(" ySplit=\"${sh.frozenRows}\"")
            val pane = when { sh.frozenRows > 0 && sh.frozenCols > 0 -> "bottomRight"; sh.frozenRows > 0 -> "bottomLeft"; else -> "topRight" }
            b.append(" topLeftCell=\"$top\" activePane=\"$pane\" state=\"frozen\"/></sheetView></sheetViews>")
        }
        b.append("<sheetFormatPr defaultRowHeight=\"${sh.defaultHeight}\" defaultColWidth=\"${"%.2f".format(java.util.Locale.ROOT, sh.defaultWidth / 5.25f)}\"/>")
        val cols = (0 until minOf(sh.colCount, 16_384)).filter { sh.width(it) != sh.defaultWidth }
        if (cols.isNotEmpty()) {
            b.append("<cols>")
            for (c in cols) b.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${"%.2f".format(java.util.Locale.ROOT, sh.width(c) / 5.25f)}\"${if (sh.width(c) == 0f) " hidden=\"1\"" else ""} customWidth=\"1\"/>")
            b.append("</cols>")
        }
        b.append("<sheetData>")
        for (row in sh.rows) {
            val h = sh.height(row.index)
            b.append("<row r=\"${row.index + 1}\"").append(if (h != sh.defaultHeight) " ht=\"$h\" customHeight=\"1\"" else "").append(">")
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
        b.append("</sheetData>")
        if (sh.merges.isNotEmpty()) {
            b.append("<mergeCells count=\"${sh.merges.size}\">")
            for (m in sh.merges) b.append("<mergeCell ref=\"${columnName(m.left)}${m.top + 1}:${columnName(m.right)}${m.bottom + 1}\"/>")
            b.append("</mergeCells>")
        }
        b.append("</worksheet>")
        return b.toString()
    }

    /** Writes [wb] as an XLSX package to [out]. */
    fun write(wb: Workbook, out: OutputStream) {
        // One list of looks for all sheets (each sheet keeps its own numbering when read)
        val looks = ArrayList<CellStyle>()
        val index = HashMap<CellStyle, Int>()
        looks += CellStyle.DEFAULT; index[CellStyle.DEFAULT] = 0
        val sheets = wb.sheets.map { sh ->
            sheet(sh) { i -> val st = sh.styles.getOrNull(i) ?: CellStyle.DEFAULT; index.getOrPut(st) { looks += st; looks.size - 1 } }
        }
        val names = HashSet<String>()
        val sheetNames = wb.sheets.mapIndexed { i, sh ->
            // Excel's rules for sheet names: 31 characters, none of []:*?/\, each different
            var n = sh.name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31).ifBlank { "Sheet${i + 1}" }
            var k = 2
            while (!names.add(n.lowercase())) n = n.take(27) + " (${k++})"
            n
        }
        val workbook = HEAD + "<workbook xmlns=\"$S\" xmlns:r=\"$R\"><sheets>" +
            sheetNames.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>"
        val rels = HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$R/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rIdStyles\" Type=\"$R/styles\" Target=\"styles.xml\"/></Relationships>"
        val types = HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>"
        val stylesXml = styles(looks)
        ZipOutputStream(out).let { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", types)
            put("_rels/.rels", HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", rels)
            put("xl/styles.xml", stylesXml)
            for ((i, s) in sheets.withIndex()) put("xl/worksheets/sheet${i + 1}.xml", s)
            z.finish(); z.flush()
        }
    }
}
