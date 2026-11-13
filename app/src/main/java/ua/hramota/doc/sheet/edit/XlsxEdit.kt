package ua.hramota.doc.sheet.edit

import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.XmlScan
import ua.hramota.doc.sheet.columnName
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What the person put into a cell. */
sealed class Input {
    data object Clear : Input()
    data class Number(val v: Double) : Input()
    data class Text(val v: String) : Input()
    data class Bool(val v: Boolean) : Input()
    /** Formula as Excel stores it, without "=". */
    data class Formula(val text: String) : Input()
}

/**
 * A change to how a cell looks; null fields stay as the cell has them. [fill] is ARGB, 0 for no
 * fill; [format] a number format code ("0.00", "0%", "dd.mm.yyyy"); [align] "left", "center",
 * "right" or "general"; [style] puts another cell's whole look first (a cell moved by sorting).
 */
data class CellLook(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fill: Int? = null,
    val format: String? = null,
    val align: String? = null,
    val style: String? = null,
) {
    fun then(o: CellLook) = CellLook(o.bold ?: bold, o.italic ?: italic, o.fill ?: fill, o.format ?: format, o.align ?: align, o.style ?: style)
    val isEmpty get() = this == CellLook()
}

/** A cell in a workbook: sheet number (in the workbook's order), row and column from 0. */
data class CellKey(val sheet: Int, val row: Int, val col: Int)

/**
 * An XLSX opened for editing cells. Only the rows with changed cells are written anew (and the
 * workbook's note to recalculate); every other row, sheet and part stays as the file had it.
 */
class XlsxEdit private constructor(
    first: Layout,
    private val strings: List<String>,
    private val relsXml: String?,
    private val typesXml: String?,
    private val digests: Map<String, ByteArray>,
    private val calcChain: String?,
    /** The workbook's styles part (cell looks), if it has one. */
    private val stylesPath: String?,
    private val stylesXml: String?,
    /** Parts that may point at rows (charts, pivot tables, slicers): while there are any, rows are not inserted or deleted. */
    private val rowBlockers: Boolean,
) {
    /**
     * The workbook's parts as they stand after rows were inserted or deleted (the file's own until
     * then): sheets, workbook.xml, parts written anew, and formulas to work out again.
     */
    class Layout internal constructor(
        val sheets: List<SheetPart>,
        internal val workbookXml: String,
        internal val overrides: Map<String, String>,
        internal val forced: Set<CellKey>,
        internal val moved: Boolean,
    ) {
        @Volatile internal var calc: Calc? = null
    }

    /** What undo needs to go back to: the parts and the typed cells. */
    class Snapshot internal constructor(internal val layout: Layout, internal val inputs: Map<CellKey, Input>, internal val looks: Map<CellKey, CellLook> = emptyMap(), internal val widths: Map<Pair<Int, Int>, Float> = emptyMap())

    @Volatile private var layout: Layout = first
    val sheets: List<SheetPart> get() = layout.sheets

    /** A cell as the file has it: where it is, its style, type, value and formula. */
    class RawCell(val elem: Elem, val s: String?, val t: String?, val v: String?, val inline: String?, val f: String?, val fElem: Elem?, val fKind: String?, val si: String?)

    class SheetPart(
        val name: String,
        val path: String,
        val visible: Boolean,
        internal val xml: String,
        /** The prefix of the spreadsheet namespace with its colon, or "" when it is the default one. */
        internal val p: String,
        internal val sheetData: Elem,
        internal val rows: TreeMap<Int, Elem>,
        internal val cells: HashMap<Long, RawCell>,
        /** Areas not changed here: array formulas, data tables, table headers ([top, left, bottom, right]). */
        internal val lockedAreas: MutableList<IntArray> = ArrayList(),
        /** Shared formula groups whose formula cannot be moved cell by cell here (whole rows or columns). */
        internal val lockedShared: Set<String> = emptySet(),
        /** Looks a whole row or column gives its cells (a new cell takes them, as in Excel). */
        internal val rowStyles: Map<Int, String> = emptyMap(),
        internal val colStyles: List<Triple<Int, Int, String>> = emptyList(),
    ) {
        internal fun cell(row: Int, col: Int) = cells[key(row, col)]
        /** The look a cell has: its own, else its row's, else its column's. */
        internal fun styleOf(row: Int, col: Int): String? = cell(row, col)?.s ?: rowStyles[row] ?: colStyles.firstOrNull { col in it.first..it.second }?.third
    }

    private val inputs = HashMap<CellKey, Input>()
    private val parsedInputs = HashMap<CellKey, Formula.Parsed>()

    /** What the person changed, cell by cell (a copy). */
    val changes: Map<CellKey, Input> @Synchronized get() = HashMap(inputs)

    /** Puts [input] into a cell (null: back to what the file has). */
    @Synchronized
    fun set(cell: CellKey, input: Input?) {
        if (input == null) inputs.remove(cell) else inputs[cell] = input
        parsedInputs.remove(cell)
        if (input is Input.Formula) parsedInputs[cell] = Formula.parse(input.text)
    }

    private val looks = HashMap<CellKey, CellLook>()
    /** Column widths set here, in characters (as Excel keeps them): (sheet, column) to width. */
    private val widths = HashMap<Pair<Int, Int>, Float>()

    /** Looks changed here, cell by cell (a copy). */
    val lookChanges: Map<CellKey, CellLook> @Synchronized get() = HashMap(looks)
    val widthChanges: Map<Pair<Int, Int>, Float> @Synchronized get() = HashMap(widths)

    /** Sets how a cell looks (null: as the file has it). False when the workbook has no styles to add to. */
    @Synchronized
    fun setLook(cell: CellKey, look: CellLook?): Boolean {
        if (stylesXml == null) return false
        if (look == null || look.isEmpty) looks.remove(cell) else looks[cell] = look
        return true
    }

    fun look(cell: CellKey): CellLook? = synchronized(this) { looks[cell] }

    /** Sets a column's width in characters (null: as the file has it). */
    @Synchronized
    fun setWidth(sheet: Int, col: Int, chars: Float?) { if (chars == null) widths.remove(sheet to col) else widths[sheet to col] = chars.coerceIn(0.5f, 255f) }

    /** True when the cell holds what is not changed here: part of an array formula or a data table. */
    fun locked(cell: CellKey): Boolean {
        val sh = sheets.getOrNull(cell.sheet) ?: return true
        if (sh.lockedAreas.any { b -> cell.row in b[0]..b[2] && cell.col in b[1]..b[3] }) return true
        val raw = sh.cell(cell.row, cell.col) ?: return false
        return raw.fKind == "array" || raw.fKind == "dataTable" || (raw.fKind == "shared" && raw.si in sh.lockedShared)
    }

    /** What the cell holds, as the person would type it: "=SUM(A1:A3)", "12,5", "текст". */
    @Synchronized
    fun content(cell: CellKey, decimalComma: Boolean = true): String {
        fun num(d: Double) = Formula.show(d).let { if (decimalComma) it.replace('.', ',') else it }
        inputs[cell]?.let { i ->
            return when (i) {
                Input.Clear -> ""
                is Input.Number -> num(i.v)
                is Input.Text -> if (Formula.parseNumber(i.v) != null || i.v.startsWith("=")) "'" + i.v else i.v
                is Input.Bool -> if (i.v) "TRUE" else "FALSE"
                is Input.Formula -> "=" + Formula.typed(i.text, decimalComma)
            }
        }
        val raw = sheets.getOrNull(cell.sheet)?.cell(cell.row, cell.col) ?: return ""
        if (raw.f != null) return "=" + Formula.typed(raw.f, decimalComma)
        return when (val v = rawValue(raw)) {
            is Value.Num -> num(v.v)
            is Value.Text -> v.v
            is Value.Bool -> if (v.v) "TRUE" else "FALSE"
            is Value.Err -> v.code
            Value.Empty -> ""
        }
    }

    /**
     * What the cell holds, exactly as stored (for moving it whole, as sorting does): null when it
     * holds a formula or an error, which are not moved.
     */
    @Synchronized
    fun stored(cell: CellKey): Input? {
        inputs[cell]?.let { return if (it is Input.Formula) null else it }
        val raw = sheets.getOrNull(cell.sheet)?.cell(cell.row, cell.col) ?: return Input.Clear
        if (raw.f != null) return null
        return when (val v = rawValue(raw)) {
            is Value.Num -> Input.Number(v.v)
            is Value.Text -> Input.Text(v.v)
            is Value.Bool -> Input.Bool(v.v)
            is Value.Err -> null
            Value.Empty -> Input.Clear
        }
    }

    /** The cell's style number (cellXfs), as the file has it. */
    fun style(cell: CellKey): Int? = sheets.getOrNull(cell.sheet)?.styleOf(cell.row, cell.col)?.toIntOrNull()

    private fun rawValue(raw: RawCell): Value = when (raw.t) {
        "s" -> raw.v?.trim()?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { Value.Text(it) } ?: Value.Empty
        "str" -> Value.Text(raw.v ?: "")
        "inlineStr" -> Value.Text(raw.inline ?: raw.v ?: "")
        "b" -> Value.Bool(raw.v?.trim() == "1")
        "e" -> Value.Err(raw.v ?: "#N/A")
        "d" -> Value.Text(raw.v ?: "")
        else -> raw.v?.trim()?.toDoubleOrNull()?.let { Value.Num(it) } ?: if (raw.v == null && raw.inline == null) Value.Empty else Value.Text(raw.v ?: raw.inline ?: "")
    }

    // --- Calculating ---

    private class FormulaCell(val key: CellKey, val parsed: Formula.Parsed, val refs: List<Pair<Int, IntArray>>)

    /** The formulas of a layout that can be worked out here, and which of them refer to a cell or a range. */
    internal class Calc(
        val formulas: List<Any>,
        val single: Map<CellKey, List<Any>>,
        val ranges: List<Triple<Int, IntArray, Any>>,
    )

    private fun calcOf(l: Layout): Calc {
        l.calc?.let { return it }
        synchronized(l) {
            l.calc?.let { return it }
            val out = ArrayList<FormulaCell>()
            for ((si, sh) in l.sheets.withIndex()) for ((k, raw) in sh.cells) {
                if (raw.f == null || raw.fKind == "array" || raw.fKind == "dataTable") continue
                val parsed = Formula.parse(raw.f)
                if (parsed.node == null) continue
                out += FormulaCell(CellKey(si, (k ushr 14).toInt(), (k and 0x3FFF).toInt()), parsed, refsOf(l.sheets, parsed.node, si))
            }
            val single = HashMap<CellKey, MutableList<Any>>()
            val ranges = ArrayList<Triple<Int, IntArray, Any>>()
            for (f in out) for ((s, b) in f.refs) {
                if (b[0] == b[2] && b[1] == b[3]) single.getOrPut(CellKey(s, b[0], b[1])) { ArrayList() } += f
                else ranges += Triple(s, b, f)
            }
            return Calc(out, single, ranges).also { l.calc = it }
        }
    }

    private fun sheetIndex(sheets: List<SheetPart>, name: String?, own: Int): Int = if (name == null) own else sheets.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    /** What a formula refers to: sheet and [top, left, bottom, right] of each cell or range. */
    private fun refsOf(sheets: List<SheetPart>, node: Formula.Node, own: Int): List<Pair<Int, IntArray>> {
        val out = ArrayList<Pair<Int, IntArray>>()
        fun walk(n: Formula.Node) {
            when (n) {
                is Formula.Node.Cell -> out += sheetIndex(sheets, n.ref.sheet, own) to intArrayOf(n.ref.row, n.ref.col, n.ref.row, n.ref.col)
                is Formula.Node.Range -> out += sheetIndex(sheets, n.from.sheet, own) to intArrayOf(minOf(n.from.row, n.to.row), minOf(n.from.col, n.to.col), maxOf(n.from.row, n.to.row), maxOf(n.from.col, n.to.col))
                is Formula.Node.Unary -> walk(n.a)
                is Formula.Node.Percent -> walk(n.a)
                is Formula.Node.Bin -> { walk(n.a); walk(n.b) }
                is Formula.Node.Call -> {
                    n.args.forEach(::walk)
                    // A short sum range ("SUMIF(A1:A10;">0";B1)") covers as much as the range checked
                    val cond = n.args.getOrNull(0) as? Formula.Node.Range
                    val sum = n.args.getOrNull(2)
                    if ((n.name == "SUMIF" || n.name == "AVERAGEIF") && cond != null && sum != null) {
                        val from = when (sum) { is Formula.Node.Cell -> sum.ref; is Formula.Node.Range -> sum.from.let { r -> r.copy(row = minOf(sum.from.row, sum.to.row), col = minOf(sum.from.col, sum.to.col)) }; else -> null }
                        if (from != null) {
                            val h = Math.abs(cond.to.row - cond.from.row); val w = Math.abs(cond.to.col - cond.from.col)
                            out += sheetIndex(sheets, from.sheet, own) to intArrayOf(from.row, from.col, from.row + h, from.col + w)
                        }
                    }
                }
                else -> {}
            }
        }
        walk(node)
        return out
    }

    /**
     * Works out the changed cells and every formula that depends on them, directly or through other
     * formulas. Values of the rest stay as the file has them. Call off the main thread.
     */
    fun recalc(): Map<CellKey, Value> {
        // What was typed, taken at once: the screen can go on changing cells while this works
        val snapshot: Triple<Map<CellKey, Input>, Map<CellKey, Formula.Parsed>, Layout> = synchronized(this) { Triple(HashMap(inputs), HashMap(parsedInputs), layout) }
        return recalcOf(snapshot)
    }

    private fun recalcOf(snapshot: Triple<Map<CellKey, Input>, Map<CellKey, Formula.Parsed>, Layout>): Map<CellKey, Value> {
        // A long chain of formulas (each row adding to the one above) is followed by recursion:
        // done on a thread with room for it, so it never overflows the caller's stack
        var result: Map<CellKey, Value>? = null
        var failure: Throwable? = null
        val t = Thread(null, { try { result = recalcHere(snapshot.third, snapshot.first, snapshot.second) } catch (e: Throwable) { failure = e } }, "recalc", 256L * 1024 * 1024)
        t.start(); t.join()
        failure?.let { if (it is StackOverflowError) throw IOException("Formulas too deep") else throw it }
        return result!!
    }

    private fun recalcHere(l: Layout, inputs: Map<CellKey, Input>, parsedInputs: Map<CellKey, Formula.Parsed>): Map<CellKey, Value> {
        val sheets = l.sheets
        val calc = calcOf(l)
        @Suppress("UNCHECKED_CAST") val single = calc.single as Map<CellKey, List<FormulaCell>>
        @Suppress("UNCHECKED_CAST") val ranges = calc.ranges as List<Triple<Int, IntArray, FormulaCell>>
        @Suppress("UNCHECKED_CAST") val fileFormulas = calc.formulas as List<FormulaCell>
        // From each changed cell (and each formula whose references moved with rows) to the formulas
        // that refer to it, and on from those (each formula once)
        val seeds = inputs.keys + l.forced
        val dirty = HashSet<CellKey>(seeds)
        val queue = ArrayDeque<CellKey>(seeds)
        val reached = HashSet<FormulaCell>()
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            fun reach(f: FormulaCell) { if (f.key !in inputs && reached.add(f) && dirty.add(f.key)) queue.addLast(f.key) }
            single[k]?.forEach(::reach)
            for ((s, b, f) in ranges) if (s == k.sheet && k.row in b[0]..b[2] && k.col in b[1]..b[3]) reach(f)
        }
        val formulas = HashMap<CellKey, Formula.Parsed>()
        for (f in fileFormulas) if (f.key in dirty && f.key !in inputs) formulas[f.key] = f.parsed
        formulas.putAll(parsedInputs)
        val memo = HashMap<CellKey, Value>()
        val busy = HashSet<CellKey>()
        val cells = object : Formula.Cells {
            var own = 0
            override fun value(sheet: String?, row: Int, col: Int): Value {
                val s = sheetIndex(sheets, sheet, own)
                if (s < 0) return Value.REF
                return valueOf(CellKey(s, row, col))
            }
            fun valueOf(k: CellKey): Value {
                memo[k]?.let { return it }
                val parsed = formulas[k]
                val v: Value = when {
                    parsed != null && parsed.supported && parsed.node != null -> {
                        // A cell that refers back to itself (through others) is a circle: 0, as Excel shows it, not a hang
                        if (!busy.add(k)) return Value.Num(0.0)
                        val saved = own; own = k.sheet
                        try { Formula.eval(parsed.node, this) }
                        // Too much to work out here: the file's value stays (Excel works it out on opening)
                        catch (e: Formula.TooBig) { sheets.getOrNull(k.sheet)?.cell(k.row, k.col)?.takeIf { k !in inputs }?.let { rawValue(it) } ?: Value.NAME }
                        finally { own = saved; busy.remove(k) }
                    }
                    else -> when (val i = inputs[k]) {
                        // A formula whose rows moved and that is not worked out here: its old value no longer holds (Excel works it out on opening)
                        null -> sheets.getOrNull(k.sheet)?.cell(k.row, k.col)?.let { raw ->
                            if (k in l.forced && raw.f?.contains("#REF!") == true) Value.REF else rawValue(raw)
                        } ?: Value.Empty
                        Input.Clear -> Value.Empty
                        is Input.Number -> Value.Num(i.v)
                        is Input.Text -> Value.Text(i.v)
                        is Input.Bool -> Value.Bool(i.v)
                        // A formula not worked out here: Excel does it on opening
                        is Input.Formula -> Value.NAME
                    }
                }
                memo[k] = v
                return v
            }
        }
        val out = HashMap<CellKey, Value>()
        for (k in dirty) out[k] = cells.valueOf(k)
        return out
    }

    /**
     * What the person typed, as Excel would take it: "=…" a formula, "'…" always text, numbers
     * (with a decimal comma), TRUE/FALSE, and a date "02.11.2026" as a number when the cell shows dates.
     */
    fun parse(typed: String, dateCell: Boolean, date1904: Boolean = false): Input? {
        val t = typed.replace("\r", "")
        if (t.isEmpty()) return Input.Clear
        if (t.startsWith("'")) return Input.Text(t.substring(1))
        // A formula that cannot be read is refused (Excel would call the file damaged)
        // (one naming a sheet the workbook does not have, too)
        if (t.startsWith("=") && t.length > 1) return Formula.normalize(t.substring(1)).takeIf { f ->
            val node = Formula.parse(f).node ?: return@takeIf false
            refsOf(sheets, node, 0).none { it.first < 0 }
        }?.let { Input.Formula(it) }
        Formula.parseNumber(t)?.let { return Input.Number(it) }
        when (t.trim().uppercase()) { "TRUE", "ІСТИНА" -> return Input.Bool(true); "FALSE", "ХИБНІСТЬ" -> return Input.Bool(false) }
        if (dateCell) Regex("\\s*(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\s*").matchEntire(t)?.let { m ->
            try {
                val d = java.time.LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                val base = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
                return Input.Number(java.time.temporal.ChronoUnit.DAYS.between(base, d).toDouble())
            } catch (_: Exception) {}
        }
        return Input.Text(t.take(32_767))
    }

    // --- Rows ---

    /** Why rows could not be inserted or deleted. */
    enum class RowRefusal { WORKBOOK, SHEET, LOCKED, FORMULAS, FULL }

    private class Refused(val why: RowRefusal) : Exception()

    /** About how much text the workbook's sheets hold (undo of row changes keeps copies of it). */
    val size: Long get() = layout.sheets.sumOf { it.xml.length.toLong() }

    @Synchronized fun snapshot(): Snapshot = Snapshot(layout, HashMap(inputs), HashMap(looks), HashMap(widths))

    @Synchronized fun restore(s: Snapshot) {
        layout = s.layout
        inputs.clear(); inputs.putAll(s.inputs)
        looks.clear(); looks.putAll(s.looks)
        widths.clear(); widths.putAll(s.widths)
        parsedInputs.clear()
        for ((k, i) in inputs) if (i is Input.Formula) parsedInputs[k] = Formula.parse(i.text)
    }

    /**
     * Inserts [n] empty rows before row [at] of [sheet] ([n] > 0), or deletes [-n] rows from [at]
     * ([n] < 0). Every reference to the sheet's rows follows, as in Excel: formulas on all sheets,
     * defined names, merged cells, conditional formats, data checks, links, the filter. Null when done,
     * otherwise why not (nothing is changed then). Call off the main thread.
     */
    @Synchronized
    fun moveRows(sheet: Int, at: Int, n: Int): RowRefusal? = move(sheet, at, n, false)

    /** As [moveRows], for columns: [n] columns inserted before column [at], or [-n] deleted from it. */
    @Synchronized
    fun moveColumns(sheet: Int, at: Int, n: Int): RowRefusal? = move(sheet, at, n, true)

    private fun move(sheet: Int, at: Int, n: Int, cols: Boolean): RowRefusal? {
        if (n == 0) return null
        if (rowBlockers) return RowRefusal.WORKBOOK
        val l = layout
        val target = l.sheets.getOrNull(sheet) ?: return RowRefusal.SHEET
        val x = target.xml
        val root = XmlScan.children(x, 0, x.length).first { it.local == "worksheet" }
        val top = XmlScan.children(x, root)
        // Pictures, charts, notes and tables are anchored to rows in parts not moved here
        if (top.any { it.local in SHEET_BLOCKERS }) return RowRefusal.SHEET
        top.firstOrNull { it.local == "extLst" }?.let { e -> if (x.substring(e.start, e.end).let { it.contains("sqref") || it.contains(":f>") || it.contains(":f ") }) return RowRefusal.SHEET }
        if (target.lockedAreas.isNotEmpty() || target.lockedShared.isNotEmpty() || target.cells.values.any { it.fKind == "array" || it.fKind == "dataTable" }) return RowRefusal.LOCKED
        if (n > 0) {
            val last = if (cols) maxOf(target.cells.keys.maxOfOrNull { (it and 0x3FFF).toInt() } ?: -1, inputs.keys.filter { it.sheet == sheet }.maxOfOrNull { it.col } ?: -1)
                else maxOf(target.rows.lastEntry()?.key ?: -1, inputs.keys.filter { it.sheet == sheet }.maxOfOrNull { it.row } ?: -1)
            if (last + n >= (if (cols) 16_384 else 1_048_576)) return RowRefusal.FULL
        }
        val move = Formula.RowMove(at, n, cols)
        // A cell's new place on the target sheet (null: deleted)
        fun moved(k: CellKey): CellKey? = if (k.sheet != sheet) k else if (cols) move.row(k.col)?.let { k.copy(col = it) } else move.row(k.row)?.let { k.copy(row = it) }
        val name = target.name
        try {
            val forced = HashSet<CellKey>()
            for (k in l.forced) moved(k)?.let { forced += it }
            val newSheets = ArrayList<SheetPart>(l.sheets.size)
            val overrides = HashMap(l.overrides)
            for ((si, sh) in l.sheets.withIndex()) {
                val changed = HashSet<Long>()
                val xml = rewriteSheet(sh, si == sheet, name, move, changed)
                if (xml == null) { newSheets += sh; continue }
                val part = sheet(sh.name, sh.path, sh.visible, xml, MAX_CELLS)
                part.lockedAreas.addAll(sh.lockedAreas.filter { si != sheet })
                newSheets += part
                overrides[sh.path] = xml
                for (k in changed) forced += CellKey(si, (k ushr 14).toInt(), (k and 0x3FFF).toInt())
            }
            val wb = moveNames(l.workbookXml, name, move)
            // What was typed moves with its row; formulas typed follow too
            val newInputs = HashMap<CellKey, Input>()
            for ((k, i) in inputs) {
                val key = moved(k) ?: continue
                newInputs[key] = if (i is Input.Formula) Input.Formula(Formula.moveRows(i.text, k.sheet == sheet, name, move) ?: throw Refused(RowRefusal.FORMULAS)) else i
            }
            val newLooks = HashMap<CellKey, CellLook>()
            for ((k, v) in looks) { val key = moved(k) ?: continue; newLooks[key] = v }
            looks.clear(); looks.putAll(newLooks)
            // Widths set here follow their columns
            if (cols) {
                val newWidths = HashMap<Pair<Int, Int>, Float>()
                for ((k, v) in widths) { val key = if (k.first != sheet) k else move.row(k.second)?.let { k.first to it } ?: continue; newWidths[key] = v }
                widths.clear(); widths.putAll(newWidths)
            }
            layout = Layout(newSheets, wb, overrides, forced, true)
            // The old parts stay only for undo: their worked-out lists are made again if needed
            l.calc = null
            inputs.clear(); inputs.putAll(newInputs)
            parsedInputs.clear()
            for ((k, i) in inputs) if (i is Input.Formula) parsedInputs[k] = Formula.parse(i.text)
            return null
        } catch (e: Refused) {
            return e.why
        }
    }

    private fun moved(text: String, own: Boolean, target: String, move: Formula.RowMove): String =
        Formula.moveRows(text, own, target, move) ?: throw Refused(RowRefusal.FORMULAS)

    /** "B2:D5 F1" with rows moved; areas wholly deleted left out (null when none is left). */
    private fun movedAreas(list: String, move: Formula.RowMove): String? {
        val out = ArrayList<String>()
        for (a in list.trim().split(Regex("\\s+"))) {
            if (a.isEmpty()) continue
            val m = Regex("(\\$?[A-Za-z]{1,3})(\\$?)([0-9]{1,7})(?::(\\$?[A-Za-z]{1,3})(\\$?)([0-9]{1,7}))?").matchEntire(a) ?: run { out += a; null } ?: continue
            val g = m.groupValues
            if (move.cols) {
                fun letters(t: String) = t.removePrefix("$").uppercase()
                fun dollar(t: String) = if (t.startsWith("$")) "$" else ""
                val c1 = Formula.colIndex(letters(g[1]))
                if (g[4].isEmpty()) { move.row(c1)?.let { out += dollar(g[1]) + columnName(it) + g[2] + g[3] }; continue }
                val c2 = Formula.colIndex(letters(g[4]))
                val (l, rt) = move.range(minOf(c1, c2), maxOf(c1, c2)) ?: continue
                out += dollar(g[1]) + columnName(l) + g[2] + g[3] + ":" + dollar(g[4]) + columnName(rt) + g[5] + g[6]
                continue
            }
            val r1 = g[3].toInt() - 1
            if (g[4].isEmpty()) { move.row(r1)?.let { out += g[1] + g[2] + (it + 1) }; continue }
            val r2 = g[6].toInt() - 1
            val (t, b) = move.range(minOf(r1, r2), maxOf(r1, r2)) ?: continue
            out += g[1] + g[2] + (t + 1) + ":" + g[4] + g[5] + (b + 1)
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun setAttr(tag: String, attr: String, value: String): String {
        val re = Regex("(\\s$attr=)(?:\"[^\"]*\"|'[^']*')")
        return if (re.containsMatchIn(tag)) tag.replace(re) { it.groupValues[1] + "\"" + XmlScan.escape(value) + "\"" }
            else tag.replaceFirst(Regex("^(<[^\\s>/]+)"), "$1 $attr=\"${XmlScan.escape(value)}\"")
    }

    /**
     * A sheet with rows moved: the target sheet's rows, cells and areas, and on every sheet the
     * formulas that refer to the target. Null when nothing in it changes. [changed] gets the
     * cells (in their new places) whose formula changed.
     */
    private fun rewriteSheet(sh: SheetPart, isTarget: Boolean, target: String, move: Formula.RowMove, changed: MutableSet<Long>): String? {
        val x = sh.xml
        val p = sh.p
        // A shared formula written out of its group is a plain one; any other keeps its own tag (an array formula stays one)
        fun newF(raw: RawCell, text: String): String {
            val fe = raw.fElem!!
            return if (raw.fKind == "shared" || fe.selfClosing || fe.empty) "<${p}f>${XmlScan.escape(text)}</${p}f>"
                else x.substring(fe.start, fe.openEnd) + XmlScan.escape(text) + x.substring(fe.closeStart, fe.end)
        }
        val root = XmlScan.children(x, 0, x.length).first { it.local == "worksheet" }
        val out = StringBuilder(x.length + 1024)
        out.append(x, 0, root.openEnd)
        var pos = root.openEnd
        var any = false
        for (e in XmlScan.children(x, root)) {
            val replaced: String? = when {
                e.local == "sheetData" -> {
                    val b = StringBuilder()
                    var edited = false
                    // Shared formulas: a group is written cell by cell when any of its cells changes (always on the target sheet)
                    val newText = HashMap<Long, String>()
                    val textChanged = HashSet<Long>()
                    val unshare = HashSet<String>()
                    for ((k, c) in sh.cells) {
                        val f = c.f ?: continue
                        val hit = BooleanArray(1)
                        val t = Formula.moveRows(f, isTarget, target, move, hit) ?: throw Refused(RowRefusal.FORMULAS)
                        // Worked out again only when what it refers to lost rows (moved references give the same value)
                        if (hit[0]) textChanged += k
                        if (t != f) {
                            // A shared formula with whole rows is not moved cell by cell here
                            if (c.fKind == "shared" && c.si in sh.lockedShared) throw Refused(RowRefusal.LOCKED)
                        }
                        if (t != f || isTarget) { newText[k] = t; if (c.fKind == "shared" && c.si != null) unshare += c.si }
                    }
                    for ((k, c) in sh.cells) if (c.fKind == "shared" && c.si in unshare && k !in newText) newText[k] = c.f ?: continue
                    if (!isTarget && newText.isEmpty()) null else {
                        b.append(x, e.start, if (e.selfClosing) e.end else e.openEnd)
                        if (!e.selfClosing) {
                            var at = e.openEnd
                            for ((r, rowElem) in sh.rows) {
                                val nr = if (isTarget && !move.cols) move.row(r) else r
                                b.append(x, at, rowElem.start); at = rowElem.end
                                if (nr == null) { edited = true; continue }
                                val cells = XmlScan.children(x, rowElem)
                                if (!isTarget && cells.none { c -> c.local == "c" && newText.containsKey(key(r, c.attrs["r"]?.let { ua.hramota.doc.sheet.columnOf(it) } ?: -1)) }) {
                                    // A row with no change: as the file has it (cells without an address are rare and written below)
                                    if (cells.all { it.local != "c" || it.attrs["r"] != null }) { b.append(x, rowElem.start, rowElem.end); continue }
                                }
                                edited = true
                                var tag = x.substring(rowElem.start, rowElem.openEnd)
                                if (isTarget) tag = setAttr(tag, "r", (nr + 1).toString())
                                // Which columns the row spans: a hint Excel makes again
                                if (isTarget && move.cols) tag = tag.replace(Regex("\\sspans=(?:\"[^\"]*\"|'[^']*')"), "")
                                b.append(tag)
                                if (rowElem.selfClosing) continue
                                var cpos = rowElem.openEnd
                                var nextCol = 0
                                for (c in cells) {
                                    b.append(x, cpos, c.start); cpos = c.end
                                    if (c.local != "c") { b.append(x, c.start, c.end); continue }
                                    val col = c.attrs["r"]?.let { ua.hramota.doc.sheet.columnOf(it) }?.takeIf { it >= 0 } ?: nextCol
                                    nextCol = col + 1
                                    val nc = if (isTarget && move.cols) move.row(col) else col
                                    // A cell of a deleted column goes
                                    if (nc == null) { edited = true; continue }
                                    val raw = sh.cell(r, col)
                                    val ctag = setAttr(x.substring(c.start, c.openEnd), "r", columnName(nc) + (nr + 1))
                                    val text = newText[key(r, col)]
                                    if (raw?.fElem != null && text != null) {
                                        // Worked out again only where the formula itself changed (the rest keep their values)
                                        if (key(r, col) in textChanged) changed += key(nr, nc)
                                        b.append(ctag).append(x, c.openEnd, raw.fElem.start).append(newF(raw, text)).append(x, raw.fElem.end, c.end)
                                    } else b.append(ctag).append(x, c.openEnd, c.end)
                                }
                                b.append(x, cpos, rowElem.end)
                            }
                            b.append(x, at, e.end)
                        }
                        if (edited) b.toString() else null
                    }
                }
                // Where the cells end: worked out again by Excel
                isTarget && e.local == "dimension" -> ""
                else -> rewriteElem(x, e, isTarget, target, move, "worksheet", null).let { if (it == x.substring(e.start, e.end)) null else it ?: "" }
            }
            if (replaced != null) { any = true; out.append(x, pos, e.start).append(replaced); pos = e.end }
        }
        if (!any) return null
        out.append(x, pos, x.length)
        return out.toString()
    }

    /** An element outside the cells with rows moved; null when it is to go (nothing of it is left). */
    private fun rewriteElem(x: String, e: Elem, isTarget: Boolean, target: String, move: Formula.RowMove, parent: String, rebase: IntArray?): String? {
        var tag = x.substring(e.start, e.openEnd)
        var base = rebase
        // A link within the workbook ("'Дані'!A9") follows the rows too
        if (e.local == "hyperlink") e.attrs["location"]?.let { loc -> tag = setAttr(tag, "location", Formula.moveRows(loc, false, target, move) ?: loc) }
        if (isTarget) {
            when (e.local) {
                "mergeCell", "hyperlink", "autoFilter", "sortState", "sortCondition" -> {
                    val ref = e.attrs["ref"]
                    if (ref != null) {
                        val m = movedAreas(ref, move) ?: return null
                        // A merge of one cell is no merge (Excel would call the file damaged)
                        if (e.local == "mergeCell" && area(m).let { it == null || (it[0] == it[2] && it[1] == it[3]) }) return null
                        tag = setAttr(tag, "ref", m)
                    }
                }
                "conditionalFormatting", "dataValidation", "protectedRange", "ignoredError" -> {
                    val ref = e.attrs["sqref"]
                    if (ref != null) {
                        tag = setAttr(tag, "sqref", movedAreas(ref, move) ?: return null)
                        // Formulas here are written for the area's first cell: when its row goes, they are
                        // written for the first row left first (as Excel does), then moved
                        val top = ref.trim().split(Regex("\\s+")).firstOrNull()?.let(::area)?.get(if (move.cols) 1 else 0)
                        if (top != null && move.n < 0 && move.row(top) == null) base = intArrayOf(top, move.at - move.n)
                    }
                }
                // Which cell was chosen: Excel chooses again
                "selection" -> return null
                "pane" -> e.attrs["topLeftCell"]?.let { t -> tag = setAttr(tag, "topLeftCell", movedAreas(t, move) ?: if (move.cols) columnName(move.at) + (Regex("[0-9]+").find(t)?.value ?: "1") else (Regex("[A-Za-z]+").find(t)?.value ?: "A") + (move.at + 1)) }
                "brk" -> if (parent == (if (move.cols) "colBreaks" else "rowBreaks")) {
                    val id = e.attrs["id"]?.toIntOrNull()
                    if (id != null && id > 0) tag = setAttr(tag, "id", ((move.row(id - 1) ?: return null) + 1).toString())
                }
                // Columns' widths and looks follow their columns
                "col" -> if (parent == "cols" && move.cols) {
                    val mn = e.attrs["min"]?.toIntOrNull(); val mx = e.attrs["max"]?.toIntOrNull()
                    if (mn != null && mx != null && mn >= 1 && mx >= mn) {
                        val (a, b) = move.range(mn - 1, mx - 1) ?: return null
                        tag = setAttr(setAttr(tag, "min", (a + 1).toString()), "max", (b + 1).toString())
                    }
                }
            }
        }
        if (e.selfClosing || e.empty) return tag + x.substring(e.openEnd, e.end)
        val kids = XmlScan.children(x, e)
        // A formula's text (in x14 extensions the text is in an "xm:f" inside)
        if (kids.isEmpty() && (e.local == "formula" || e.local == "formula1" || e.local == "formula2" || e.local == "f")) {
            var text = XmlScan.unescape(x.substring(e.openEnd, e.closeStart))
            if (base != null) text = if (move.cols) Formula.shift(text, 0, base[0], 0, base[1]) else Formula.shift(text, base[0], 0, base[1], 0)
            return tag + XmlScan.escape(moved(text, isTarget, target, move)) + x.substring(e.closeStart, e.end)
        }
        if (kids.isEmpty()) return tag + x.substring(e.openEnd, e.end)
        val b = StringBuilder(tag)
        var pos = e.openEnd
        var kept = 0
        for (k in kids) {
            b.append(x, pos, k.start); pos = k.end
            val r = rewriteElem(x, k, isTarget, target, move, e.local, base)
            if (r != null) { b.append(r); kept++ }
        }
        b.append(x, pos, e.end)
        // A list left empty goes (an empty one is not allowed); the count follows what is left
        if (kept == 0 && e.local in LISTS) return null
        var res = b.toString()
        if (kept != kids.size && e.local in LISTS) {
            val head = res.substring(0, tag.length)
            var h = head
            if (e.attrs.containsKey("count")) h = setAttr(h, "count", kept.toString())
            if (e.attrs.containsKey("manualBreakCount")) h = setAttr(h, "manualBreakCount", kept.toString())
            res = h + res.substring(tag.length)
        }
        return res
    }

    /** workbook.xml with its defined names (print areas, named ranges) following the rows. */
    private fun moveNames(wb: String, target: String, move: Formula.RowMove): String {
        val root = XmlScan.children(wb, 0, wb.length).first { it.local == "workbook" }
        val names = XmlScan.children(wb, root).firstOrNull { it.local == "definedNames" } ?: return wb
        val b = StringBuilder()
        var pos = 0
        for (d in XmlScan.children(wb, names)) {
            if (d.local != "definedName" || d.empty) continue
            val text = XmlScan.unescape(wb.substring(d.openEnd, d.closeStart))
            val t = moved(text, false, target, move)
            if (t != text) { b.append(wb, pos, d.openEnd).append(XmlScan.escape(t)); pos = d.closeStart }
        }
        if (pos == 0) return wb
        return b.append(wb, pos, wb.length).toString()
    }

    // --- Saving ---

    /** Writes the edited workbook: the package is read again from [source] and copied entry by entry. Off the main thread. */
    fun save(source: InputStream, out: OutputStream) {
        // Taken at once, and worked on without holding the workbook: the screen does not wait for the save
        val snapshot: Triple<Map<CellKey, Input>, Map<CellKey, Formula.Parsed>, Layout> = synchronized(this) { Triple(HashMap(inputs), HashMap(parsedInputs), layout) }
        val lookNow: Map<CellKey, CellLook> = synchronized(this) { HashMap(looks) }
        val widthNow: Map<Pair<Int, Int>, Float> = synchronized(this) { HashMap(widths) }
        val inputs = snapshot.first
        val values = recalcOf(snapshot)
        val l = snapshot.third
        val sheets = l.sheets
        val parts = HashMap<String, String>(l.overrides)
        var formulasChanged = l.moved
        // New looks: each distinct (look the cell had, change) becomes one new entry of the styles
        val styleOf = HashMap<CellKey, String>()
        if (lookNow.isNotEmpty() && stylesXml != null && stylesPath != null) {
            val added = LinkedHashMap<Pair<String, CellLook>, Int>()
            for ((k, look) in lookNow) {
                val sh = sheets.getOrNull(k.sheet) ?: continue
                val base = look.style ?: sh.styleOf(k.row, k.col) ?: "0"
                val rest = look.copy(style = null)
                if (rest.isEmpty) { styleOf[k] = base; continue }
                styleOf[k] = "\u0000" + added.getOrPut(base to rest) { added.size }
            }
            if (added.isNotEmpty()) {
                val (xml, first) = Styles.add(stylesXml, added.keys.toList())
                parts[stylesPath] = xml
                for ((k, v) in styleOf) if (v.startsWith("\u0000")) styleOf[k] = (first + v.substring(1).toInt()).toString()
            }
        }
        for ((si, sh) in sheets.withIndex()) {
            val mine = (values.keys.filter { it.sheet == si } + styleOf.keys.filter { it.sheet == si }).distinct()
            val ownWidths = widthNow.filterKeys { it.first == si }.mapKeys { it.key.second }
            if (mine.isEmpty() && ownWidths.isEmpty()) continue
            if (mine.any { k -> inputs[k] is Input.Formula || sh.cell(k.row, k.col)?.f != null && k in inputs }) formulasChanged = true
            var xml = if (mine.isEmpty()) (parts[sh.path] ?: sh.xml) else writeSheet(si, sh, mine, values, inputs, styleOf)
            if (ownWidths.isNotEmpty()) xml = Styles.widths(xml, ownWidths)
            parts[sh.path] = xml
        }
        // Nothing changed (all undone): the file is written as it was
        if (parts.isEmpty() && !l.moved) { copy(source, out, emptyMap(), emptySet()); return }
        // Excel works every formula out again when it opens the file (formulas not done here included)
        parts["xl/workbook.xml"] = fullCalcOnLoad(l.workbookXml)
        // Excel's list of formula cells would no longer match: it is left out, and Excel makes it again
        val dropped = HashSet<String>()
        if (calcChain != null && formulasChanged) {
            dropped += calcChain
            relsXml?.let { r -> parts["xl/_rels/workbook.xml.rels"] = removeElems(r, "Relationship") { it["Type"]?.endsWith("/calcChain") == true } }
            typesXml?.let { t -> parts["[Content_Types].xml"] = removeElems(t, "Override") { it["PartName"]?.removePrefix("/") == calcChain } }
        }
        copy(source, out, parts, dropped)
    }

    private fun writeSheet(si: Int, sh: SheetPart, keys: List<CellKey>, values: Map<CellKey, Value>, inputs: Map<CellKey, Input>, styleOf: Map<CellKey, String> = emptyMap()): String {
        val p = sh.p
        val byRow = keys.groupBy { it.row }
        // A shared formula touched anywhere is written out cell by cell in its whole group
        val unshare = keys.mapNotNull { k -> sh.cell(k.row, k.col)?.takeIf { it.fKind == "shared" && k in inputs }?.si }.toSet()
        val groupRows = if (unshare.isEmpty()) emptyMap() else sh.cells.entries.filter { it.value.fKind == "shared" && it.value.si in unshare }
            .groupBy({ (it.key ushr 14).toInt() }, { (it.key and 0x3FFF).toInt() })
        val rowsToWrite = TreeMap<Int, MutableSet<Int>>()
        for ((r, ks) in byRow) rowsToWrite.getOrPut(r) { HashSet() }.addAll(ks.map { it.col })
        for ((r, cs) in groupRows) rowsToWrite.getOrPut(r) { HashSet() }.addAll(cs)
        val x = sh.xml
        var grew = false
        val rowsXml = TreeMap<Int, String>()
        for ((r, cols) in rowsToWrite) {
            val rowElem = sh.rows[r]
            val cells = TreeMap<Int, String>()
            // The row's cells as they are
            val existing = if (rowElem != null) XmlScan.children(x, rowElem).filter { it.local == "c" } else emptyList()
            val existingCols = HashMap<Int, Elem>()
            var nextCol = 0
            for (e in existing) {
                // A cell without its address is the one after the cell before it (as when the file was read);
                // written anew, every cell of the row gets its address, so new cells cannot shift the rest
                val col = e.attrs["r"]?.let { ua.hramota.doc.sheet.columnOf(it) }?.takeIf { it >= 0 } ?: nextCol
                nextCol = col + 1
                existingCols[col] = e
                val raw = x.substring(e.start, e.end)
                cells[col] = if (e.attrs["r"] != null) raw else raw.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1 r=\"${columnName(col)}${r + 1}\"")
            }
            for (c in cols) {
                val k = CellKey(si, r, c)
                val raw = sh.cell(r, c)
                if (raw == null && existingCols[c] == null) grew = true
                val xml = cellXml(x, p, r, c, raw, inputs[k], values[k], raw?.si in unshare, if (raw == null) sh.styleOf(r, c) else null, styleOf[k])
                if (xml == null) cells.remove(c) else cells[c] = xml
            }
            val start = if (rowElem != null) x.substring(rowElem.start, rowElem.openEnd).let { if (rowElem.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
                .replace(Regex("\\sspans=\"[^\"]*\""), "") else "<${p}row r=\"${r + 1}\">"
            val tail = if (rowElem != null && !rowElem.empty) XmlScan.children(x, rowElem).filter { it.local != "c" }.joinToString("") { x.substring(it.start, it.end) } else ""
            rowsXml[r] = start + cells.values.joinToString("") + tail + "</${p}row>"
            if (rowElem == null) grew = true
        }
        // sheetData: unchanged rows as they were, changed and new ones in row order
        val sd = sh.sheetData
        val b = StringBuilder(x.length + 4096)
        val sdStart = if (sd.selfClosing) x.substring(sd.start, sd.end).removeSuffix("/>").trimEnd() + ">" else x.substring(sd.start, sd.openEnd)
        var dataStart = x.substring(0, sd.start)
        if (grew) dataStart = removeFirst(dataStart, "dimension")
        b.append(dataStart).append(sdStart)
        if (sd.selfClosing) {
            rowsXml.values.forEach { b.append(it) }
        } else {
            var pos = sd.openEnd
            val pendingNew = TreeMap(rowsXml.filterKeys { sh.rows[it] == null })
            for ((r, e) in sh.rows) {
                // New rows before this one
                while (pendingNew.isNotEmpty() && pendingNew.firstKey() < r) b.append(pendingNew.pollFirstEntry()!!.value)
                val changed = rowsXml[r]
                if (changed != null) { b.append(x, pos, e.start); b.append(changed); pos = e.end }
            }
            b.append(x, pos, sd.closeStart)
            pendingNew.values.forEach { b.append(it) }
        }
        b.append("</${sd.name}>").append(x, sd.end, x.length)
        return b.toString()
    }

    /** A cell written anew; null when it is to go (cleared, with no style to keep). */
    private fun cellXml(x: String, p: String, row: Int, col: Int, raw: RawCell?, input: Input?, value: Value?, unshared: Boolean, inherited: String? = null, restyled: String? = null): String? {
        val ref = columnName(col) + (row + 1)
        val s = (restyled ?: raw?.s ?: inherited)?.takeIf { it != "0" || raw?.s != null || restyled != null }?.let { " s=\"${XmlScan.escape(it)}\"" } ?: ""
        // Only the look changed: the cell as it is, with its new style
        if (restyled != null && input == null && value == null && !unshared) {
            if (raw == null) return "<${p}c r=\"$ref\"$s/>"
            val tag = x.substring(raw.elem.start, raw.elem.openEnd)
            val newTag = (if (Regex("\\ss=\"[^\"]*\"").containsMatchIn(tag)) tag.replace(Regex("\\ss=\"[^\"]*\""), s) else tag.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1$s"))
                .let { if (raw.elem.attrs["r"] != null) it else it.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1 r=\"$ref\"") }
            return newTag + x.substring(raw.elem.openEnd, raw.elem.end)
        }
        fun v(text: String) = "<${p}v>${XmlScan.escape(text)}</${p}v>"
        fun stored(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        fun withValue(f: String, value: Value?): String = when (value) {
            is Value.Num -> "<${p}c r=\"$ref\"$s>$f${v(stored(value.v))}</${p}c>"
            is Value.Text -> "<${p}c r=\"$ref\"$s t=\"str\">$f${v(value.v)}</${p}c>"
            is Value.Bool -> "<${p}c r=\"$ref\"$s t=\"b\">$f${v(if (value.v) "1" else "0")}</${p}c>"
            is Value.Err -> if (value == Value.NAME) "<${p}c r=\"$ref\"$s>$f</${p}c>" else "<${p}c r=\"$ref\"$s t=\"e\">$f${v(value.code)}</${p}c>"
            Value.Empty, null -> "<${p}c r=\"$ref\"$s>$f</${p}c>"
        }
        return when (input) {
            Input.Clear -> if (s.isEmpty() || raw == null) null else "<${p}c r=\"$ref\"$s/>"
            is Input.Number -> "<${p}c r=\"$ref\"$s>${v(stored(input.v))}</${p}c>"
            is Input.Text -> "<${p}c r=\"$ref\"$s t=\"inlineStr\"><${p}is><${p}t xml:space=\"preserve\">${XmlScan.escape(input.v)}</${p}t></${p}is></${p}c>"
            is Input.Bool -> "<${p}c r=\"$ref\"$s t=\"b\">${v(if (input.v) "1" else "0")}</${p}c>"
            is Input.Formula -> withValue("<${p}f>${XmlScan.escape(Formula.stored(input.text))}</${p}f>", value)
            null -> {
                // A formula the file has, worked out again (or written out of its shared group)
                raw ?: return null
                val keep = x.substring(raw.elem.start, raw.elem.end).let {
                    if (raw.elem.attrs["r"] != null) it else it.replaceFirst(Regex("^<([^\\s>/]+)"), "<$1 r=\"$ref\"")
                }
                val f = if (unshared || raw.fElem == null) "<${p}f>${XmlScan.escape(raw.f ?: return keep)}</${p}f>" else x.substring(raw.fElem.start, raw.fElem.end)
                if (value == null) return if (unshared) withValue(f, rawValue(raw)) else if (restyled != null) withValue(f, rawValue(raw)) else keep
                withValue(f, value)
            }
        }
    }

    private fun fullCalcOnLoad(wb: String): String {
        val root = XmlScan.children(wb, 0, wb.length).first { it.local == "workbook" }
        val p = root.name.substringBefore(':', "").let { if (it.isEmpty() || it == root.name) "" else "$it:" }
        val kids = XmlScan.children(wb, root)
        val calc = kids.firstOrNull { it.local == "calcPr" }
        if (calc != null) {
            val tag = wb.substring(calc.start, calc.openEnd)
            val newTag = if (Regex("\\sfullCalcOnLoad=\"[^\"]*\"").containsMatchIn(tag)) tag.replace(Regex("\\sfullCalcOnLoad=\"[^\"]*\""), " fullCalcOnLoad=\"1\"")
                else tag.replaceFirst(Regex("\\s*(/?>)$"), " fullCalcOnLoad=\"1\"$1")
            return wb.substring(0, calc.start) + newTag + wb.substring(calc.openEnd)
        }
        // Schema order: calcPr comes before these
        val after = setOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst")
        val at = kids.firstOrNull { it.local in after }?.start ?: root.closeStart
        return wb.substring(0, at) + "<${p}calcPr fullCalcOnLoad=\"1\"/>" + wb.substring(at)
    }

    /** [x] (the worksheet up to its cells) without its first [local] element, written as one tag. */
    private fun removeFirst(x: String, local: String): String {
        val m = Regex("<(?:[A-Za-z_][\\w.-]*:)?$local\\b[^>]*/>").find(x) ?: return x
        return x.removeRange(m.range)
    }

    private fun removeElems(x: String, local: String, test: (Map<String, String>) -> Boolean): String {
        val root = XmlScan.children(x, 0, x.length).firstOrNull() ?: return x
        val drop = XmlScan.children(x, root).filter { it.local == local && test(it.attrs) }
        if (drop.isEmpty()) return x
        val b = StringBuilder(); var pos = 0
        for (d in drop) { b.append(x, pos, d.start); pos = d.end }
        return b.append(x, pos, x.length).toString()
    }

    private fun copy(source: InputStream, out: OutputStream, parts: Map<String, String>, dropped: Set<String>) {
        val written = HashSet<String>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            val z = ZipOutputStream(out)
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                // Parts are known by their name without a leading "/" (as when the file was opened)
                val name = e.name.removePrefix("/")
                if (name in dropped || !written.add(name)) continue
                z.putNextEntry(ZipEntry(e.name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                val replaced = parts[name]
                if (replaced != null) {
                    // A part changed here must still be the one opened
                    val was = readAll(zip, MAX_PART)
                    val opened = digests[name]
                    if (opened != null && !MessageDigest.getInstance("SHA-256").digest(was).contentEquals(opened)) throw IOException("$name changed meanwhile")
                    z.write(replaced.toByteArray(Charsets.UTF_8))
                } else {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw IOException("Package too large")
                        z.write(buf, 0, n)
                    }
                }
                z.closeEntry()
            }
            for (name in parts.keys) if (name !in written) throw IOException("$name missing")
            z.finish(); z.flush()
        }
    }

    companion object {
        const val SML = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        /** Largest sheet part opened for editing (its text is held in memory). */
        const val MAX_PART = 48L * 1024 * 1024
        /** Most cells across the workbook for editing (each takes some hundreds of bytes while open). */
        const val MAX_CELLS = 400_000
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000

        /** Parts that can point at a sheet's rows from outside it, and are not moved here. */
        /** A sheet with these is not changed in its rows: what they hold is anchored to rows in other parts. */
        private val SHEET_BLOCKERS = setOf("drawing", "legacyDrawing", "tableParts", "oleObjects", "controls", "customSheetViews", "scenarios")
        private val LISTS = setOf("mergeCells", "conditionalFormatting", "dataValidations", "hyperlinks", "protectedRanges", "ignoredErrors", "rowBreaks", "colBreaks", "sortState", "cols")
        private val ROW_BLOCKERS = listOf("xl/charts/", "xl/chartsheets/", "xl/pivotcache/", "xl/pivottables/", "xl/slicers/", "xl/slicercaches/", "xl/timelines/", "xl/timelinecaches/", "xl/querytables/", "xl/model/", "xl/richdata/")

        internal fun key(row: Int, col: Int): Long = (row.toLong() shl 14) or col.toLong()

        /** Opens an XLSX for editing. Call off the main thread. */
        fun open(input: InputStream): XlsxEdit {
            val raw = HashMap<String, ByteArray>()
            var blockers = false
            ZipInputStream(input).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    val name = e.name.removePrefix("/")
                    val lower = name.lowercase()
                    if (ROW_BLOCKERS.any { lower.startsWith(it) }) blockers = true
                    if (lower.endsWith(".xml") && (lower.startsWith("xl/worksheets/") || lower.startsWith("xl/tables/") || lower == "xl/workbook.xml" || lower == "xl/sharedstrings.xml") ||
                        lower.startsWith("xl/worksheets/_rels/") && lower.endsWith(".rels") ||
                        lower == "xl/_rels/workbook.xml.rels" || lower == "[content_types].xml" || lower.startsWith("xl/") && lower.endsWith("styles.xml") && lower.count { it == '/' } == 1) {
                        val b = readAll(zip, MAX_PART)
                        total += b.size
                        if (total > MAX_TOTAL / 4) throw IOException("Too large to edit")
                        raw[name] = b
                    }
                }
            }
            val digests = raw.mapValues { MessageDigest.getInstance("SHA-256").digest(it.value) }
            fun text(name: String) = raw[name]?.let { Xml.checkNoDoctype(it); String(it, Charsets.UTF_8).removePrefix("\uFEFF") }
            val wb = text("xl/workbook.xml") ?: throw IOException("Not a workbook")
            val rels = text("xl/_rels/workbook.xml.rels")
            val types = text("[Content_Types].xml")
            val relTargets = HashMap<String, Pair<String, String>>()
            rels?.let { r ->
                val root = XmlScan.children(r, 0, r.length).firstOrNull()
                if (root != null) for (e in XmlScan.children(r, root)) if (e.local == "Relationship") {
                    val target = e.attrs["Target"] ?: continue
                    val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                    relTargets[e.attrs["Id"] ?: continue] = path to (e.attrs["Type"] ?: "")
                }
            }
            val calcChain = relTargets.values.firstOrNull { it.second.endsWith("/calcChain") }?.first
            val strings = text(raw.keys.firstOrNull { it.equals("xl/sharedStrings.xml", ignoreCase = true) } ?: "")?.let(::sharedStrings) ?: emptyList()
            val wbRoot = XmlScan.children(wb, 0, wb.length).first { it.local == "workbook" }
            val sheetsElem = XmlScan.children(wb, wbRoot).firstOrNull { it.local == "sheets" } ?: throw IOException("No sheets")
            val parts = ArrayList<SheetPart>()
            var cellsLeft = MAX_CELLS
            for (s in XmlScan.children(wb, sheetsElem)) {
                if (s.local != "sheet") continue
                val rid = s.attrs.entries.firstOrNull { it.key.endsWith(":id") }?.value ?: continue
                val (path, type) = relTargets[rid] ?: continue
                if (!type.endsWith("/worksheet")) continue
                val xml = text(path) ?: continue
                val part = sheet(s.attrs["name"] ?: "", path, s.attrs["state"].let { it == null || it == "visible" }, xml, cellsLeft)
                cellsLeft -= part.cells.size
                // Header rows of the sheet's tables: their names are kept in the table parts too
                val relsPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
                text(relsPath)?.let { r ->
                    val rr = XmlScan.children(r, 0, r.length).firstOrNull() ?: return@let
                    for (e in XmlScan.children(r, rr)) {
                        if (e.local != "Relationship" || e.attrs["Type"]?.endsWith("/table") != true) continue
                        val target = e.attrs["Target"] ?: continue
                        val tablePath = if (target.startsWith("/")) target.removePrefix("/") else java.io.File(path.substringBeforeLast('/'), target).normalize().path.replace('\\', '/')
                        val t = text(tablePath) ?: continue
                        val troot = XmlScan.children(t, 0, t.length).firstOrNull { it.local == "table" } ?: continue
                        val area = troot.attrs["ref"]?.let(::area) ?: continue
                        if (troot.attrs["headerRowCount"] != "0") part.lockedAreas += intArrayOf(area[0], area[1], area[0], area[3])
                    }
                }
                parts += part
            }
            if (parts.isEmpty()) throw IOException("No sheets")
            val stylesPath = relTargets.values.firstOrNull { it.second.endsWith("/styles") }?.first
            return XlsxEdit(Layout(parts, wb, emptyMap(), emptySet(), false), strings, rels, types, digests, calcChain, stylesPath, stylesPath?.let(::text), blockers)
        }

        private fun sheet(name: String, path: String, visible: Boolean, xml: String, budget: Int): SheetPart {
            val root = XmlScan.children(xml, 0, xml.length).first { it.local == "worksheet" }
            val p = root.attrs.entries.firstOrNull { it.value == SML && (it.key == "xmlns" || it.key.startsWith("xmlns:")) }?.key
                ?.let { if (it == "xmlns") "" else it.removePrefix("xmlns:") + ":" } ?: throw IOException("Unusual namespace")
            val sd = XmlScan.children(xml, root).firstOrNull { it.local == "sheetData" } ?: throw IOException("No sheet data")
            val rows = TreeMap<Int, Elem>()
            val cells = HashMap<Long, RawCell>()
            val masters = HashMap<String, Triple<String, Int, Int>>()
            val sharedChildren = ArrayList<Triple<Long, RawCell, String>>()
            var nextRow = 0
            for (r in XmlScan.children(xml, sd)) {
                if (r.local != "row") continue
                val ri = (r.attrs["r"]?.toIntOrNull()?.minus(1)) ?: nextRow
                if (ri !in 0 until 1_048_576) throw IOException("Bad row")
                nextRow = ri + 1
                rows[ri] = r
                var nextCol = 0
                for (c in XmlScan.children(xml, r)) {
                    if (c.local != "c") continue
                    val ci = c.attrs["r"]?.let { ua.hramota.doc.sheet.columnOf(it) }?.takeIf { it >= 0 } ?: nextCol
                    if (ci !in 0 until 16_384) throw IOException("Bad column")
                    nextCol = ci + 1
                    var v: String? = null; var inline: String? = null; var f: String? = null; var fElem: Elem? = null
                    for (k in XmlScan.children(xml, c)) when (k.local) {
                        "v" -> v = if (k.empty) "" else XmlScan.unescape(xml.substring(k.openEnd, k.closeStart))
                        "f" -> { fElem = k; f = if (k.empty) "" else XmlScan.unescape(xml.substring(k.openEnd, k.closeStart)) }
                        "is" -> inline = buildString { fun walk(e: Elem) { for (t in XmlScan.children(xml, e)) when (t.local) { "t" -> if (!t.empty) append(XmlScan.unescape(xml.substring(t.openEnd, t.closeStart))); "rPh" -> {}; else -> walk(t) } }; walk(k) }
                    }
                    val fe = fElem
                    val kind = fe?.attrs?.get("t") ?: if (fe != null) "normal" else null
                    val si = fe?.attrs?.get("si")
                    val cell = RawCell(c, c.attrs["s"], c.attrs["t"], v, inline, f?.takeIf { it.isNotEmpty() }, fe, kind, si)
                    cells[key(ri, ci)] = cell
                    if (kind == "shared" && si != null) {
                        if (!f.isNullOrEmpty()) masters[si] = Triple(f!!, ri, ci) else sharedChildren += Triple(key(ri, ci), cell, si)
                    }
                    if (cells.size > budget) throw IOException("Too many cells to edit")
                }
            }
            // A cell sharing a formula gets its own text: the group's first one moved to it
            for ((k, cell, si) in sharedChildren) {
                val (text, mr, mc) = masters[si] ?: continue
                val row = (k ushr 14).toInt(); val col = (k and 0x3FFF).toInt()
                cells[k] = RawCell(cell.elem, cell.s, cell.t, cell.v, cell.inline, Formula.shift(text, mr, mc, row, col), cell.fElem, cell.fKind, si)
            }
            val locked = ArrayList<IntArray>()
            for (c in cells.values) if (c.fKind == "array" || c.fKind == "dataTable") c.fElem?.attrs?.get("ref")?.let(::area)?.let { locked += it }
            val wholeRefs = Regex("(?<![A-Za-z0-9_])\\$?[A-Za-z]{1,3}:\\$?[A-Za-z]{1,3}(?![A-Za-z0-9_(])|(?<![A-Za-z0-9_.])\\$?[0-9]{1,7}:\\$?[0-9]{1,7}(?![0-9])")
            // (text in quotes, "8:30" say, is not a reference)
            val lockedShared = masters.filterValues { wholeRefs.containsMatchIn(it.first.replace(Regex("\"[^\"]*\""), "\"\"")) }.keys
            val rowStyles = HashMap<Int, String>()
            for ((ri, r) in rows) if (r.attrs["customFormat"].let { it == "1" || it == "true" }) r.attrs["s"]?.let { rowStyles[ri] = it }
            val colStyles = ArrayList<Triple<Int, Int, String>>()
            XmlScan.children(xml, root).firstOrNull { it.local == "cols" }?.let { cols ->
                for (c in XmlScan.children(xml, cols)) {
                    if (c.local != "col") continue
                    val st = c.attrs["style"] ?: continue
                    val min = c.attrs["min"]?.toIntOrNull() ?: continue; val max = c.attrs["max"]?.toIntOrNull() ?: continue
                    colStyles += Triple(min - 1, max - 1, st)
                }
            }
            return SheetPart(name, path, visible, xml, p, sd, rows, cells, locked, lockedShared, rowStyles, colStyles)
        }

        /** "B2:D5" (or "B2") as [top, left, bottom, right]; null if unreadable. */
        private fun area(ref: String): IntArray? {
            val parts = ref.split(':')
            fun cell(t: String): Pair<Int, Int>? {
                val m = Regex("\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})").matchEntire(t.trim()) ?: return null
                return (m.groupValues[2].toInt() - 1) to Formula.colIndex(m.groupValues[1].uppercase())
            }
            val a = cell(parts[0]) ?: return null
            val b = if (parts.size > 1) cell(parts[1]) ?: return null else a
            return intArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
        }

        private fun sharedStrings(x: String): List<String> {
            val root = XmlScan.children(x, 0, x.length).firstOrNull { it.local == "sst" } ?: return emptyList()
            return XmlScan.children(x, root).filter { it.local == "si" }.map { si ->
                buildString { fun walk(e: Elem) { for (t in XmlScan.children(x, e)) when (t.local) { "t" -> if (!t.empty) append(XmlScan.unescape(x.substring(t.openEnd, t.closeStart))); "rPh" -> {}; else -> walk(t) } }; walk(si) }
            }
        }

        private fun readAll(input: InputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > limit) throw IOException("Part too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
