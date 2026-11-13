package ua.hramota.doc.sheet.edit

import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.XmlScan
import java.io.IOException

/**
 * Changes to a workbook's styles part and a sheet's columns: new cell looks are added at the end of
 * the lists (fonts, fills, number formats, cell formats), so every look already used stays as it was.
 */
internal object Styles {
    /** Number formats Excel knows by number without writing them out. */
    private val BUILT_IN = mapOf(
        "General" to 0, "0" to 1, "0.00" to 2, "#,##0" to 3, "#,##0.00" to 4, "0%" to 9, "0.00%" to 10, "0.00E+00" to 11,
        "mm-dd-yy" to 14, "d-mmm-yy" to 15, "h:mm" to 20, "h:mm:ss" to 21, "@" to 49,
    )

    private fun prefix(e: Elem) = e.name.substringBefore(':', "").let { if (it.isEmpty() || it == e.name) "" else "$it:" }

    /**
     * [styles] with one new cell format for each (style it starts from, change); returns the new
     * text and the index the first new format has (the others follow in order).
     */
    fun add(styles: String, wanted: List<Pair<String, CellLook>>): Pair<String, Int> {
        val root = XmlScan.children(styles, 0, styles.length).firstOrNull { it.local == "styleSheet" } ?: throw IOException("No styles")
        val p = prefix(root)
        val kids = XmlScan.children(styles, root)
        fun list(local: String) = kids.firstOrNull { it.local == local }
        val cellXfs = list("cellXfs") ?: throw IOException("No cell formats")
        val xfs = XmlScan.children(styles, cellXfs).filter { it.local == "xf" }
        val fonts = list("fonts")?.let { f -> XmlScan.children(styles, f).filter { it.local == "font" } } ?: emptyList()
        val fills = list("fills")?.let { f -> XmlScan.children(styles, f).filter { it.local == "fill" } } ?: emptyList()
        val numFmts = list("numFmts")?.let { f -> XmlScan.children(styles, f).filter { it.local == "numFmt" } } ?: emptyList()
        val newFonts = ArrayList<String>(); val newFills = ArrayList<String>(); val newFmts = ArrayList<String>(); val newXfs = ArrayList<String>()
        val fontIds = HashMap<String, Int>(); val fillIds = HashMap<Int, Int>(); val fmtIds = HashMap<String, Int>()
        var nextFmt = maxOf(163, numFmts.maxOfOrNull { it.attrs["numFmtId"]?.toIntOrNull() ?: 0 } ?: 0) + 1
        for ((base, look) in wanted) {
            val xf = xfs.getOrNull(base.toIntOrNull() ?: 0) ?: xfs.firstOrNull() ?: throw IOException("No cell formats")
            val a = HashMap(xf.attrs)
            // Font: the cell's own, with bold and italic as asked
            if (look.bold != null || look.italic != null) {
                val f = fonts.getOrNull(a["fontId"]?.toIntOrNull() ?: 0)
                val inner = if (f == null || f.empty) "" else styles.substring(f.openEnd, f.closeStart)
                var body = inner.replace(Regex("<(?:[A-Za-z_][\\w.-]*:)?[bi](?:\\s+val=\"[^\"]*\")?\\s*/>"), "")
                val add = (if ((look.italic ?: Regex("<(?:\\w+:)?i\\b").containsMatchIn(inner)) == true) "<${p}i/>" else "")
                val bold = if ((look.bold ?: Regex("<(?:\\w+:)?b\\b").containsMatchIn(inner)) == true) "<${p}b/>" else ""
                // In schema order b, i come first
                body = bold + add + body
                val font = "<${p}font>$body</${p}font>"
                a["fontId"] = (fonts.size + fontIds.getOrPut(font) { newFonts += font; newFonts.size - 1 }).toString()
                a["applyFont"] = "1"
            }
            look.fill?.let { argb ->
                val id = fillIds.getOrPut(argb) {
                    newFills += if (argb == 0) "<${p}fill><${p}patternFill patternType=\"none\"/></${p}fill>"
                    else "<${p}fill><${p}patternFill patternType=\"solid\"><${p}fgColor rgb=\"${"%08X".format(argb)}\"/><${p}bgColor indexed=\"64\"/></${p}patternFill></${p}fill>"
                    newFills.size - 1
                }
                a["fillId"] = (fills.size + id).toString()
                a["applyFill"] = "1"
            }
            look.format?.let { code ->
                val id = BUILT_IN[code] ?: numFmts.firstOrNull { it.attrs["formatCode"] == code }?.attrs?.get("numFmtId")?.toIntOrNull()
                    ?: fmtIds.getOrPut(code) { val n = nextFmt++; newFmts += "<${p}numFmt numFmtId=\"$n\" formatCode=\"${XmlScan.escape(code)}\"/>"; n }
                a["numFmtId"] = id.toString()
                a["applyNumberFormat"] = "1"
            }
            // The alignment: the cell's own, with the side changed
            var children = if (xf.empty) "" else styles.substring(xf.openEnd, xf.closeStart)
            look.align?.let { side ->
                val al = Regex("<(?:[A-Za-z_][\\w.-]*:)?alignment\\b[^>]*?/>|<(?:[A-Za-z_][\\w.-]*:)?alignment\\b[^>]*>.*?</(?:[A-Za-z_][\\w.-]*:)?alignment>", RegexOption.DOT_MATCHES_ALL).find(children)
                val tag = if (al == null) "<${p}alignment horizontal=\"$side\"/>"
                    else if (Regex("\\shorizontal=\"[^\"]*\"").containsMatchIn(al.value)) al.value.replace(Regex("\\shorizontal=\"[^\"]*\""), " horizontal=\"$side\"")
                    else al.value.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1 horizontal=\"$side\"")
                children = if (al == null) tag + children else children.replaceRange(al.range, tag)
                a["applyAlignment"] = "1"
            }
            val attrs = a.entries.joinToString("") { " ${it.key}=\"${XmlScan.escape(it.value)}\"" }
            newXfs += if (children.isEmpty()) "<${p}xf$attrs/>" else "<${p}xf$attrs>$children</${p}xf>"
        }
        // Written from the end of the part backwards, so earlier positions stay right
        val edits = ArrayList<Triple<Int, Int, String>>()
        fun append(local: String, items: List<String>, before: Set<String>) {
            if (items.isEmpty()) return
            val e = list(local)
            if (e == null) {
                val at = kids.firstOrNull { it.local in before }?.start ?: root.closeStart
                edits += Triple(at, at, "<${p}$local count=\"${items.size}\">${items.joinToString("")}</${p}$local>")
            } else {
                val item = mapOf("numFmts" to "numFmt", "fonts" to "font", "fills" to "fill", "cellXfs" to "xf").getValue(local)
                val count = XmlScan.children(styles, e).count { it.local == item } + items.size
                val open = styles.substring(e.start, e.openEnd).let { t ->
                    val tt = if (e.selfClosing) t.removeSuffix("/>").trimEnd() + ">" else t
                    if (Regex("\\scount=\"[^\"]*\"").containsMatchIn(tt)) tt.replace(Regex("\\scount=\"[^\"]*\""), " count=\"$count\"") else tt.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1 count=\"$count\"")
                }
                val body = if (e.selfClosing) "" else styles.substring(e.openEnd, e.closeStart)
                edits += Triple(e.start, e.end, open + body + items.joinToString("") + "</${e.name}>")
            }
        }
        append("numFmts", newFmts, setOf("fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst"))
        append("fonts", newFonts, setOf("fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst"))
        append("fills", newFills, setOf("borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "tableStyles", "colors", "extLst"))
        append("cellXfs", newXfs, emptySet())
        val b = StringBuilder(styles)
        // At the same place, the part replaced goes first and the new part is put before it after
        // (an insertion first would be swallowed by the replacement)
        for ((s, e, t) in edits.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })) b.replace(s, e, t)
        return b.toString() to xfs.size
    }

    /** A sheet with columns [widths] (0-based column to width in characters) set, other columns as they were. */
    fun widths(sheet: String, widths: Map<Int, Float>): String {
        val root = XmlScan.children(sheet, 0, sheet.length).first { it.local == "worksheet" }
        val p = prefix(root)
        val kids = XmlScan.children(sheet, root)
        val cols = kids.firstOrNull { it.local == "cols" }
        // The columns as ranges, each with its attributes
        val ranges = ArrayList<Triple<Int, Int, Map<String, String>>>()
        if (cols != null) for (c in XmlScan.children(sheet, cols)) if (c.local == "col") {
            val min = c.attrs["min"]?.toIntOrNull() ?: continue; val max = c.attrs["max"]?.toIntOrNull() ?: continue
            ranges += Triple(min, max, c.attrs)
        }
        for ((col0, w) in widths) {
            val col = col0 + 1
            val i = ranges.indexOfFirst { col in it.first..it.second }
            val wAttr = "%.2f".format(java.util.Locale.ROOT, w)
            if (i < 0) { ranges += Triple(col, col, mapOf("min" to "$col", "max" to "$col", "width" to wAttr, "customWidth" to "1")); continue }
            val (mn, mx, at) = ranges.removeAt(i)
            if (mn < col) ranges += Triple(mn, col - 1, at + mapOf("min" to "$mn", "max" to "${col - 1}"))
            ranges += Triple(col, col, at + mapOf("min" to "$col", "max" to "$col", "width" to wAttr, "customWidth" to "1"))
            if (mx > col) ranges += Triple(col + 1, mx, at + mapOf("min" to "${col + 1}", "max" to "$mx"))
        }
        ranges.sortBy { it.first }
        val xml = "<${p}cols>" + ranges.joinToString("") { (_, _, at) ->
            // min and max first, as Excel writes them
            val ordered = listOf("min", "max").mapNotNull { k -> at[k]?.let { k to it } } + at.filterKeys { it != "min" && it != "max" }.toList()
            "<${p}col" + ordered.joinToString("") { " ${it.first}=\"${XmlScan.escape(it.second)}\"" } + "/>"
        } + "</${p}cols>"
        if (cols != null) return sheet.substring(0, cols.start) + xml + sheet.substring(cols.end)
        // Schema order: cols comes before sheetData
        val sd = kids.first { it.local == "sheetData" }
        return sheet.substring(0, sd.start) + xml + sheet.substring(sd.start)
    }
}
