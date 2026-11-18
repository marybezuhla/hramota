package ua.hramota.doc.odf

import ua.hramota.doc.docx.Xml
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.XmlScan
import ua.hramota.doc.sheet.Workbook
import ua.hramota.doc.sheet.edit.CellKey
import ua.hramota.doc.sheet.edit.CellLook
import ua.hramota.doc.sheet.edit.Formula
import ua.hramota.doc.sheet.edit.Input
import ua.hramota.doc.sheet.edit.SheetEdit
import ua.hramota.doc.sheet.edit.SheetSnapshot
import ua.hramota.doc.sheet.edit.Value
import ua.hramota.doc.sheet.edit.XlsxEdit
import ua.hramota.doc.sheet.edit.typed
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An OpenDocument spreadsheet (ODS) opened for editing what cells hold, in the file itself: values,
 * text and formulas, with the formulas that depend on them worked out again. Formulas are shown and
 * typed as in Excel ("=SUM(A1:A3)") and written as OpenDocument has them ("of:=SUM([.A1:.A3])").
 * Only the rows with changed cells are written anew (repeated rows and cells split around them);
 * everything else in the file stays as it was. Looks, rows and columns, notes are not changed here.
 */
class OdsEdit private constructor(
    private val digest: ByteArray,
    private val ns: Prefixes,
    first: Doc,
) : SheetEdit {
    /**
     * The content as it now stands: the file's, or with rows or columns inserted or deleted (then
     * with every formula worked out and written again when saved).
     */
    private class Doc(
        val xml: String,
        val sheets: List<OSheet>,
        /** The content's own automatic styles, by name, as written (new ones are made from them). */
        val autoStyles: Map<String, Elem>,
        /** Where the content's automatic styles are (new ones go at their end); null when it has none. */
        val autoElem: Elem?,
        val bodyStart: Int,
        /** Every style name the document has (new names are not taken from them). */
        val taken: Set<String>,
        /** Rows or columns were moved: every formula is worked out and written again. */
        val moved: Boolean = false,
    )

    @Volatile private var doc: Doc = first
    private val xml: String get() = doc.xml
    private val sheets: List<OSheet> get() = doc.sheets
    private val autoStyles: Map<String, Elem> get() = doc.autoStyles
    private val autoElem: Elem? get() = doc.autoElem
    private val bodyStart: Int get() = doc.bodyStart
    private val taken: Set<String> get() = doc.taken

    /** The prefixes the file gives the OpenDocument namespaces ([number], [dc] null when it has none). */
    class Prefixes(val office: String, val table: String, val text: String, val style: String = "style", val fo: String = "fo", val number: String? = null, val dc: String? = null)

    /** A cell of the file: its type and value as written, its shown text, its formula (and as Excel writes it, if it can be). */
    private class OCell(val type: String?, val value: String?, val text: String, val formula: String?, val excel: String?, val currency: String?,
                        /** The cell's style's name; its text formatted inside (spans, links). */
                        val style: String? = null, val rich: Boolean = false)

    private class OSheet(val name: String, val table: Elem, val cells: HashMap<Long, OCell>, val locked: HashSet<Long>, val lockedAreas: List<IntArray>,
                         /** Columns' default cell styles: (first, last, style). */
                         val columns: List<Triple<Int, Int, String?>> = emptyList(),
                         /** Cells with a note in the file. */
                         val annotated: Set<Long> = emptySet()) {
        fun cell(row: Int, col: Int) = cells[key(row, col)]
        fun columnStyle(col: Int): String? = columns.firstOrNull { col in it.first..it.second }?.third
    }

    override val sheetNames = first.sheets.map { SheetEdit.SheetName(it.name, true) }
    override val extension = "ods"

    private val inputs = HashMap<CellKey, Input>()
    private val parsedInputs = HashMap<CellKey, Formula.Parsed>()

    override val changes: Map<CellKey, Input> @Synchronized get() = HashMap(inputs)

    @Synchronized
    override fun set(cell: CellKey, input: Input?) {
        var i = input
        // A date cell keeps being a date when a date is typed into it
        val raw = sheets.getOrNull(cell.sheet)?.cell(cell.row, cell.col)
        if (raw?.type == "date" && i is Input.Text) dateSerial(i.v)?.let { i = Input.Number(it) }
        if (i == null) inputs.remove(cell) else inputs[cell] = i
        parsedInputs.remove(cell)
        (i as? Input.Formula)?.let { parsedInputs[cell] = Formula.parse(it.text) }
    }

    override fun locked(cell: CellKey): Boolean {
        val sh = sheets.getOrNull(cell.sheet) ?: return true
        if (key(cell.row, cell.col) in sh.locked) return true
        if (sh.lockedAreas.any { b -> cell.row in b[0]..b[2] && cell.col in b[1]..b[3] }) return true
        // A formula Hramota cannot write as Excel does is not changed here
        val raw = sh.cell(cell.row, cell.col) ?: return false
        return raw.formula != null && raw.excel == null
    }

    @Synchronized
    override fun content(cell: CellKey, decimalComma: Boolean): String {
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
        raw.excel?.let { return "=" + Formula.typed(it, decimalComma) }
        if (raw.formula != null) return raw.text
        return when (raw.type) {
            "float", "percentage", "currency" -> raw.value?.toDoubleOrNull()?.let(::num) ?: raw.text
            "boolean" -> if (raw.value == "true") "TRUE" else "FALSE"
            else -> raw.text
        }
    }

    /**
     * What the cell holds, exactly (for moving it whole, as sorting does): null for a formula, an
     * error, a date or time, text formatted inside, or a cell with a note, which are not moved.
     */
    @Synchronized
    override fun stored(cell: CellKey): Input? {
        inputs[cell]?.let { return if (it is Input.Formula) null else it }
        val sh = sheets.getOrNull(cell.sheet) ?: return null
        if (key(cell.row, cell.col) in sh.annotated) return null
        val raw = sh.cell(cell.row, cell.col) ?: return Input.Clear
        if (raw.formula != null || raw.rich || raw.type == "date" || raw.type == "time") return null
        return when (val v = rawValue(raw)) {
            is Value.Num -> Input.Number(v.v)
            is Value.Text -> Input.Text(v.v)
            is Value.Bool -> Input.Bool(v.v)
            is Value.Err -> null
            Value.Empty -> Input.Clear
        }
    }

    /** Cell styles' names by the numbers [style] gives (0: none, the column's own). */
    private val styleNumbers = arrayListOf<String?>(null)

    /** The cell's style, as a number this editor knows it by (a look with it puts that style back). */
    @Synchronized
    override fun style(cell: CellKey): Int? {
        val name = sheets.getOrNull(cell.sheet)?.cell(cell.row, cell.col)?.style ?: return 0
        val at = styleNumbers.indexOf(name)
        return if (at >= 0) at else { styleNumbers += name; styleNumbers.size - 1 }
    }

    override fun parse(typed: String, dateCell: Boolean, date1904: Boolean): Input? {
        val t = typed.replace("\r", "")
        if (t.isEmpty()) return Input.Clear
        if (t.startsWith("'")) return Input.Text(t.substring(1))
        if (t.startsWith("=") && t.length > 1) {
            val f = Formula.normalize(t.substring(1))
            // A formula that cannot be read, names a sheet the workbook does not have, or cannot be written for OpenDocument, is refused
            // (and one Hramota cannot work out: its value would be wrong until LibreOffice works it out again)
            val parsed = Formula.parse(f)
            if (parsed.node == null || !parsed.supported || toOdf(f) == null) return null
            for (m in SHEET_REF.findAll(f)) {
                val name = m.groupValues[1].let { if (it.startsWith("'")) it.substring(1, it.length - 1).replace("''", "'") else it }
                if (sheets.none { it.name.equals(name, ignoreCase = true) }) return null
            }
            return Input.Formula(f)
        }
        Formula.parseNumber(t)?.let { return Input.Number(it) }
        when (t.trim().uppercase()) { "TRUE", "ІСТИНА" -> return Input.Bool(true); "FALSE", "ХИБНІСТЬ" -> return Input.Bool(false) }
        if (dateCell) dateSerial(t)?.let { return Input.Number(it) }
        return Input.Text(t.take(32_767))
    }

    // --- Working out ---

    private fun rawValue(c: OCell): Value = when (c.type) {
        "float", "percentage", "currency" -> c.value?.toDoubleOrNull()?.let { Value.Num(it) } ?: Value.Text(c.text)
        "date" -> c.value?.let(::fromIsoDate)?.let { Value.Num(it) } ?: Value.Text(c.text)
        "time" -> c.value?.let(::fromIsoTime)?.let { Value.Num(it) } ?: Value.Text(c.text)
        "boolean" -> Value.Bool(c.value == "true")
        "string" -> Value.Text(c.value ?: c.text)
        else -> if (c.text.startsWith("#") && c.formula != null) Value.Err(c.text) else if (c.text.isEmpty()) Value.Empty else Value.Text(c.text)
    }

    private val parsedFile = HashMap<Long, Formula.Parsed>()

    override fun recalc(): Map<CellKey, Value> {
        val snapshot = synchronized(this) { HashMap(inputs) to HashMap(parsedInputs) }
        // Long chains of formulas are followed by recursion: on a thread with room for it
        var result: Map<CellKey, Value>? = null
        var failure: Throwable? = null
        val t = Thread(null, { try { result = recalcHere(snapshot.first, snapshot.second) } catch (e: Throwable) { failure = e } }, "recalc", 256L * 1024 * 1024)
        t.start(); t.join()
        failure?.let { if (it is StackOverflowError) throw IOException("Formulas too deep") else throw it }
        return result!!
    }

    private fun parsedOf(s: Int, k: Long, c: OCell): Formula.Parsed? {
        val e = c.excel ?: return null
        return synchronized(parsedFile) { parsedFile.getOrPut(s.toLong() shl 40 or k) { Formula.parse(e) } }
    }

    private fun recalcHere(inputs: Map<CellKey, Input>, parsedInputs: Map<CellKey, Formula.Parsed>): Map<CellKey, Value> {
        val memo = HashMap<CellKey, Value>()
        val busy = HashSet<CellKey>()
        val cells = object : Formula.Cells {
            var own = 0
            override fun value(sheet: String?, row: Int, col: Int): Value {
                val s = if (sheet == null) own else sheets.indexOfFirst { it.name.equals(sheet, ignoreCase = true) }
                if (s < 0) return Value.REF
                return valueOf(CellKey(s, row, col))
            }
            fun valueOf(k: CellKey): Value {
                memo[k]?.let { return it }
                val input = inputs[k]
                val raw = if (input == null) sheets.getOrNull(k.sheet)?.cell(k.row, k.col) else null
                val parsed = parsedInputs[k] ?: raw?.let { parsedOf(k.sheet, key(k.row, k.col), it) }
                val v: Value = when {
                    parsed != null && parsed.supported && parsed.node != null -> {
                        // A circle of formulas: 0, not a hang
                        if (!busy.add(k)) return Value.Num(0.0)
                        val saved = own; own = k.sheet
                        try { Formula.eval(parsed.node, this) }
                        catch (e: Formula.TooBig) { raw?.let { rawValue(it) } ?: Value.NAME }
                        finally { own = saved; busy.remove(k) }
                    }
                    input == null -> raw?.let { rawValue(it) } ?: Value.Empty
                    else -> when (input) {
                        Input.Clear -> Value.Empty
                        is Input.Number -> Value.Num(input.v)
                        is Input.Text -> Value.Text(input.v)
                        is Input.Bool -> Value.Bool(input.v)
                        is Input.Formula -> Value.NAME
                    }
                }
                memo[k] = v
                return v
            }
        }
        val out = HashMap<CellKey, Value>()
        for (k in inputs.keys) out[k] = cells.valueOf(k)
        // Every formula of the file worked out again (only while something was changed)
        if (inputs.isNotEmpty() || doc.moved) for ((s, sh) in sheets.withIndex()) for ((k, c) in sh.cells) {
            if (c.excel == null) continue
            val ck = CellKey(s, (k / COLS).toInt(), (k % COLS).toInt())
            if (ck !in inputs) out[ck] = cells.valueOf(ck)
        }
        return out
    }

    // --- Looks, widths, notes ---

    private val looks = HashMap<CellKey, CellLook>()
    private val widths = HashMap<Pair<Int, Int>, Float>()
    private val notes = HashMap<CellKey, String>()

    override val noteChanges: Map<CellKey, String> @Synchronized get() = HashMap(notes)
    @Synchronized override fun setNote(cell: CellKey, text: String?) { if (text == null) notes.remove(cell) else notes[cell] = text.take(5000) }
    override fun canNote(sheet: Int) = ns.dc != null && sheet in sheets.indices
    override val lookChanges: Map<CellKey, CellLook> @Synchronized get() = HashMap(looks)
    override val widthChanges: Map<Pair<Int, Int>, Float> @Synchronized get() = HashMap(widths)
    @Synchronized override fun setLook(cell: CellKey, look: CellLook?): Boolean {
        // A number format needs the file's number styles
        if (look?.format != null && look.format !in DATA_STYLES) return false
        if (look?.format != null && ns.number == null) return false
        if (look == null || look.isEmpty) looks.remove(cell) else looks[cell] = look
        return true
    }
    override fun look(cell: CellKey): CellLook? = synchronized(this) { looks[cell] }
    @Synchronized override fun setWidth(sheet: Int, col: Int, chars: Float?) { if (chars == null) widths.remove(sheet to col) else widths[sheet to col] = chars.coerceIn(0.5f, 255f) }
    override val size: Long get() = xml.length.toLong()

    private class Snap(val doc: Doc, val inputs: Map<CellKey, Input>, val looks: Map<CellKey, CellLook>, val widths: Map<Pair<Int, Int>, Float>, val notes: Map<CellKey, String>) : SheetSnapshot
    @Synchronized override fun snapshot(): SheetSnapshot = Snap(doc, HashMap(inputs), HashMap(looks), HashMap(widths), HashMap(notes))
    @Synchronized override fun restore(s: SheetSnapshot) {
        s as Snap
        if (s.doc !== doc) { doc = s.doc; synchronized(parsedFile) { parsedFile.clear() } }
        inputs.clear(); parsedInputs.clear(); for ((k, v) in s.inputs) set(k, v)
        looks.clear(); looks.putAll(s.looks); widths.clear(); widths.putAll(s.widths); notes.clear(); notes.putAll(s.notes)
    }

    /** Styles made for one save: cell styles by base and look, number styles by format, column styles by width. */
    private inner class Made {
        val defs = StringBuilder()
        private val names = HashMap<String, String>()
        private val used = HashSet<String>()
        private fun fresh(prefix: String): String { var n = 1; while ("$prefix$n" in taken || "$prefix$n" in used) n++; return "$prefix$n".also { used += it } }

        /** The number style for an Excel format code (one of [DATA_STYLES]); null for "General". */
        fun dataStyle(code: String): String? {
            val def = DATA_STYLES[code] ?: return null
            return names.getOrPut("fmt:$code") {
                val name = fresh("HrNf")
                val n = ns.number!!
                defs.append(def.replace("{n}", n).replace("{s}", ns.style).replace("{name}", name))
                name
            }
        }

        /** A cell style: [base] (a style's name, or null for the default) with [look] put on it. */
        fun cellStyle(base: String?, look: CellLook): String = names.getOrPut("cell:$base:$look") {
            val name = fresh("HrCe")
            val st = ns.style; val fo = ns.fo
            val data = look.format?.let { if (it == "General") "" else dataStyle(it) }
            // The changes, by properties element
            val text = LinkedHashMap<String, String>(); val cell = LinkedHashMap<String, String>(); val para = LinkedHashMap<String, String>()
            look.bold?.let { b -> val v = if (b) "bold" else "normal"; text["$fo:font-weight"] = v; text["$st:font-weight-asian"] = v; text["$st:font-weight-complex"] = v }
            look.italic?.let { i -> val v = if (i) "italic" else "normal"; text["$fo:font-style"] = v; text["$st:font-style-asian"] = v; text["$st:font-style-complex"] = v }
            look.fill?.let { f -> cell["$fo:background-color"] = if (f == 0) "transparent" else "#%06x".format(f and 0xFFFFFF) }
            look.align?.let { a ->
                if (a == "general") cell["$st:text-align-source"] = "value-type"
                else { cell["$st:text-align-source"] = "fix"; cell["$st:repeat-content"] = "false"; para["$fo:text-align"] = when (a) { "center" -> "center"; "right" -> "end"; else -> "start" } }
            }
            val auto = base?.let { autoStyles[it] }
            if (auto != null) {
                // An automatic style: copied, with the changes put in
                var x = xml.substring(auto.start, auto.end)
                x = setAttr(x, "$st:name", name)
                if (data != null) x = if (data.isEmpty()) removeAttr(x, "$st:data-style-name") else setAttr(x, "$st:data-style-name", data)
                x = withProps(x, "$st:table-cell-properties", cell)
                x = withProps(x, "$st:paragraph-properties", para)
                x = withProps(x, "$st:text-properties", text)
                defs.append(x)
            } else {
                defs.append("<$st:style $st:name=\"$name\" $st:family=\"table-cell\" $st:parent-style-name=\"${XmlScan.escape(base ?: "Default")}\"")
                if (!data.isNullOrEmpty()) defs.append(" $st:data-style-name=\"$data\"")
                defs.append('>')
                fun el(local: String, m: Map<String, String>) { if (m.isNotEmpty()) { defs.append("<$st:$local"); for ((k, v) in m) defs.append(' ').append(k).append("=\"").append(XmlScan.escape(v)).append('"'); defs.append("/>") } }
                el("table-cell-properties", cell); el("paragraph-properties", para); el("text-properties", text)
                defs.append("</$st:style>")
            }
            name
        }

        /** A column style of [base] with the width [chars] (characters, as Excel counts them). */
        fun columnStyle(base: String?, chars: Float): String = names.getOrPut("col:$base:$chars") {
            val name = fresh("HrCo")
            val st = ns.style
            val cm = "%.3fcm".format(java.util.Locale.ROOT, chars * 7 * 0.75 / 28.3465)
            val auto = base?.let { autoStyles[it] }
            if (auto != null) {
                defs.append(withProps(setAttr(xml.substring(auto.start, auto.end), "$st:name", name), "$st:table-column-properties", mapOf("$st:column-width" to cm, "$st:use-optimal-column-width" to "false")))
            } else defs.append("<$st:style $st:name=\"$name\" $st:family=\"table-column\"><$st:table-column-properties ${ns.fo}:break-before=\"auto\" $st:column-width=\"$cm\"/></$st:style>")
            name
        }
    }

    /** [x] (an element's XML) with attribute [name] set on its start tag. */
    private fun setAttr(x: String, name: String, value: String): String {
        val end = x.indexOf('>').let { if (x[it - 1] == '/') it - 1 else it }
        val tag = x.substring(0, end)
        val re = Regex("\\s${Regex.escape(name)}=(?:\"[^\"]*\"|'[^']*')")
        val v = " $name=\"${XmlScan.escape(value)}\""
        return (if (re.containsMatchIn(tag)) tag.replace(re) { v } else tag + v) + x.substring(end)
    }

    private fun removeAttr(x: String, name: String): String {
        val end = x.indexOf('>')
        return x.substring(0, end).replace(Regex("\\s${Regex.escape(name)}=(?:\"[^\"]*\"|'[^']*')"), "") + x.substring(end)
    }

    /** A style's XML with the attributes [props] set on its child [element] (added, in schema order, if it has none). */
    private fun withProps(x: String, element: String, props: Map<String, String>): String {
        if (props.isEmpty()) return x
        val root = XmlScan.children(x, 0, x.length).first()
        val kids = XmlScan.children(x, root)
        kids.firstOrNull { it.name == element }?.let { k ->
            var tag = x.substring(k.start, k.end)
            for ((n, v) in props) tag = setAttr(tag, n, v)
            return x.substring(0, k.start) + tag + x.substring(k.end)
        }
        val order = listOf("table-column-properties", "table-cell-properties", "paragraph-properties", "text-properties")
        val rank = order.indexOf(element.substringAfter(':'))
        val before = kids.firstOrNull { order.indexOf(it.local) > rank }
        val add = "<$element" + props.entries.joinToString("") { " ${it.key}=\"${XmlScan.escape(it.value)}\"" } + "/>"
        if (root.selfClosing) return x.substring(0, root.end - 2).trimEnd() + ">" + add + "</${root.name}>"
        val at = before?.start ?: root.closeStart
        return x.substring(0, at) + add + x.substring(at)
    }
    // --- Rows and columns ---

    override val canRows get() = true
    override val canSort get() = true

    private class Refused(val why: XlsxEdit.RowRefusal) : Exception()

    /**
     * Inserts [n] empty rows before row [at] of [sheet] ([n] > 0), or deletes [-n] rows from [at]
     * ([n] < 0), as LibreOffice does: formulas on every sheet follow. Null when done, otherwise why
     * not (nothing is changed then). Call off the main thread.
     */
    @Synchronized override fun moveRows(sheet: Int, at: Int, n: Int): XlsxEdit.RowRefusal? = move(sheet, at, n, false)

    /** As [moveRows], for columns. */
    @Synchronized override fun moveColumns(sheet: Int, at: Int, n: Int): XlsxEdit.RowRefusal? = move(sheet, at, n, true)

    private fun move(sheet: Int, at: Int, n: Int, cols: Boolean): XlsxEdit.RowRefusal? {
        if (n == 0) return null
        if (at < 0) return XlsxEdit.RowRefusal.SHEET
        val d = doc
        val sh = d.sheets.getOrNull(sheet) ?: return XlsxEdit.RowRefusal.SHEET
        val tb = ns.table
        if (sh.lockedAreas.isNotEmpty() || sh.table.attrs["$tb:protected"] == "true") return XlsxEdit.RowRefusal.LOCKED
        val x = d.xml
        // In the sheet: drawings tied to cells, conditional formats, its own names, print ranges, checks of what is typed
        if (BLOCKERS_IN_TABLE.containsMatchIn(x.substring(sh.table.start, sh.table.end))) return XlsxEdit.RowRefusal.SHEET
        // Elsewhere: ranges of the sheet named, filtered, checked or summed by their address (not followed here)
        val name = sh.name
        for (part in listOf(x.substring(d.bodyStart, sh.table.start), x.substring(sh.table.end))) for (m in ADDRESSES.findAll(part)) {
            val v = XmlScan.unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
            if (v.contains(name) || v.contains("'" + name.replace("'", "''") + "'")) return XlsxEdit.RowRefusal.WORKBOOK
        }
        val move = Formula.RowMove(at, n, cols)
        return try {
            // What was typed here follows its row (worked out first: nothing changes if it cannot)
            fun moved(k: CellKey): CellKey? = if (k.sheet != sheet) k else if (cols) move.row(k.col)?.let { k.copy(col = it) } else move.row(k.row)?.let { k.copy(row = it) }
            val newInputs = HashMap<CellKey, Input>()
            for ((k, i) in inputs) {
                val key = moved(k) ?: continue
                newInputs[key] = if (i is Input.Formula) Input.Formula(Formula.moveRows(i.text, k.sheet == sheet, name, move) ?: throw Refused(XlsxEdit.RowRefusal.FORMULAS)) else i
            }
            val newLooks = HashMap<CellKey, CellLook>(); for ((k, v) in looks) moved(k)?.let { newLooks[it] = v }
            val newNotes = HashMap<CellKey, String>(); for ((k, v) in notes) moved(k)?.let { newNotes[it] = v }
            val newWidths = HashMap<Pair<Int, Int>, Float>()
            for ((k, v) in widths) { val key = if (!cols || k.first != sheet) k else move.row(k.second)?.let { k.first to it } ?: continue; newWidths[key] = v }

            // The sheet's rows or columns (cells deleted with them go, formulas and all), then the formulas of every sheet
            val table = tablesOf(x).getOrNull(sheet) ?: throw Refused(XlsxEdit.RowRefusal.SHEET)
            val newTable = if (cols) movedColumns(x, table, at, n) else movedRows(x, table, at, n)
            val step = x.substring(0, table.start) + newTable + x.substring(table.end)
            val next = parse(movedFormulas(step, sheet, name, move), ns, moved = true)

            doc = next
            synchronized(parsedFile) { parsedFile.clear() }
            inputs.clear(); parsedInputs.clear(); for ((k, i) in newInputs) set(k, i)
            looks.clear(); looks.putAll(newLooks)
            notes.clear(); notes.putAll(newNotes)
            widths.clear(); widths.putAll(newWidths)
            null
        } catch (e: Refused) {
            e.why
        }
    }

    /** The content with every formula that refers to the moved rows following them. */
    private fun movedFormulas(x: String, sheet: Int, name: String, move: Formula.RowMove): String {
        val tables = tablesOf(x)
        val re = Regex("(\\s${Regex.escape(ns.table)}:formula=)(?:\"([^\"]*)\"|'([^']*)')")
        val b = StringBuilder(x.length + 1024)
        var pos = 0
        for (m in re.findAll(x)) {
            val at = m.range.first
            val inTable = tables.indexOfFirst { at in it.start until it.end }
            if (inTable < 0) continue
            val raw = XmlScan.unescape(m.groupValues[2].ifEmpty { m.groupValues[3] })
            val own = inTable == sheet
            val excel = fromOdf(raw)
            if (excel == null) { if (own || raw.contains(name)) throw Refused(XlsxEdit.RowRefusal.FORMULAS); continue }
            val moved = Formula.moveRows(excel, own, name, move) ?: throw Refused(XlsxEdit.RowRefusal.FORMULAS)
            if (moved == excel) continue
            val odf = toOdf(moved) ?: throw Refused(XlsxEdit.RowRefusal.FORMULAS)
            b.append(x, pos, at).append(m.groupValues[1]).append('"').append(XmlScan.escape(odf)).append('"')
            pos = m.range.last + 1
        }
        return b.append(x, pos, x.length).toString()
    }

    /** The spreadsheet's tables in [x], in order. */
    private fun tablesOf(x: String): List<Elem> {
        val root = XmlScan.children(x, 0, x.length).firstOrNull { it.local == "document-content" } ?: return emptyList()
        val body = XmlScan.children(x, root).firstOrNull { it.name == "${ns.office}:body" } ?: return emptyList()
        val sp = XmlScan.children(x, body).firstOrNull { it.name == "${ns.office}:spreadsheet" } ?: return emptyList()
        return XmlScan.children(x, sp).filter { it.name == "${ns.table}:table" }
    }

    /** [e] written again with its repeat count [k] (as it is when that is its own). */
    private fun again(x: String, e: Elem, attr: String, k: Int): String =
        if (k == repeat(e, attr)) x.substring(e.start, e.end) else repeated(x.substring(e.start, e.openEnd), attr, k) + x.substring(e.openEnd, e.end)

    /** Nothing in it but looks: no value, text, formula, note or drawing. */
    private fun blank(x: String, e: Elem) = e.empty || !FILLED.containsMatchIn(x.substring(e.start, e.end))

    /**
     * Things in a row (cells), or a table's rows or columns, one after another, each repeated
     * [attr] times: [n] new ones ([fresh]) put before number [at], or [-n] taken out from it. [trim],
     * a blank one repeated many times, is shortened by as many as are put in, so the whole stays
     * within the sheet (LibreOffice fills its sheets to their last row and column).
     */
    private inner class Line(val x: String, val attr: String, val at: Int, val n: Int, val trim: Elem?, val fresh: (Elem) -> String, val check: (Elem, Int) -> Unit) {
        var count = 0
        var done = n < 0
        fun item(e: Elem, b: StringBuilder) {
            val orig = repeat(e, attr)
            check(e, count)
            // The one shortened: as many fewer as are put in, wherever in it they go
            val rn = if (n > 0 && e.start == trim?.start) orig - n else orig
            if (n > 0) {
                if (!done && at >= count && at < count + orig) {
                    val before = minOf(at - count, rn)
                    if (before > 0) b.append(again(x, e, attr, before))
                    b.append(fresh(e)); done = true
                    if (rn - before > 0) b.append(again(x, e, attr, rn - before))
                } else b.append(again(x, e, attr, rn))
            } else {
                val end = at - n
                val kept = if (at >= count + rn || end <= count) rn else (at - count).coerceAtLeast(0) + (count + rn - end).coerceAtLeast(0)
                if (kept > 0) b.append(again(x, e, attr, kept))
            }
            count += orig
        }
    }

    /** The elements named [item] inside [from, to), in order, at any depth of [groups] (rows inside groups too). */
    private fun itemsOf(x: String, from: Int, to: Int, item: String, groups: Set<String>, out: MutableList<Elem> = ArrayList()): List<Elem> {
        for (c in XmlScan.children(x, from, to)) when (c.name) {
            item -> out += c
            in groups -> if (!c.empty) itemsOf(x, c.openEnd, c.closeStart, item, groups, out)
        }
        return out
    }

    /**
     * When [n] more would not fit within [max]: the blank one, repeated more than [n] times, after
     * which there is nothing but blank ones (shortened to make room); refused if there is none.
     */
    private fun trimOf(x: String, items: List<Elem>, attr: String, n: Int, max: Int): Elem? {
        if (n <= 0) return null
        val total = items.sumOf { repeat(it, attr).toLong() }
        if (total + n <= max) return null
        for (e in items.asReversed()) {
            if (!blank(x, e)) break
            if (repeat(e, attr) > n) return e
        }
        throw Refused(XlsxEdit.RowRefusal.FULL)
    }

    private fun movedRows(x: String, t: Elem, at: Int, n: Int): String {
        val tb = ns.table
        val groups = setOf("$tb:table-header-rows", "$tb:table-rows", "$tb:table-row-group")
        val end = at - minOf(n, 0)
        val line = Line(x, "$tb:number-rows-repeated", at, n, trimOf(x, itemsOf(x, t.openEnd, t.closeStart, "$tb:table-row", groups), "$tb:number-rows-repeated", n, MAX_ROWS),
            fresh = { r -> "<$tb:table-row" + (r.attrs["$tb:style-name"]?.let { " $tb:style-name=\"${XmlScan.escape(it)}\"" } ?: "") + (if (n > 1) " $tb:number-rows-repeated=\"$n\"" else "") + "><$tb:table-cell/></$tb:table-row>" },
            check = { r, row ->
                // Merged cells across the rows' edge would be cut: refused
                if (!r.empty && x.indexOf("rows-spanned", r.start).let { it in r.start until r.end }) for (c in XmlScan.children(x, r)) {
                    val span = c.attrs["$tb:number-rows-spanned"]?.toIntOrNull() ?: 1
                    if (span <= 1) continue
                    val top = row; val bottom = row + span - 1
                    val cut = if (n > 0) top < at && bottom >= at else top < end && bottom >= at && !(top >= at && bottom < end)
                    if (cut) throw Refused(XlsxEdit.RowRefusal.LOCKED)
                }
            })
        val b = StringBuilder()
        fun rows(from: Int, to: Int) {
            var pos = from
            for (r in XmlScan.children(x, from, to)) {
                b.append(x, pos, r.start); pos = r.end
                when (r.name) {
                    "$tb:table-row" -> line.item(r, b)
                    in groups -> if (r.empty) b.append(x, r.start, r.end) else { b.append(x, r.start, r.openEnd); rows(r.openEnd, r.closeStart); b.append(x, r.closeStart, r.end) }
                    else -> b.append(x, r.start, r.end)
                }
            }
            b.append(x, pos, to)
        }
        b.append(x, t.start, t.openEnd)
        rows(t.openEnd, t.closeStart)
        b.append(x, t.closeStart, t.end)
        return b.toString()
    }

    private fun movedColumns(x: String, t: Elem, at: Int, n: Int): String {
        val tb = ns.table
        val rowGroups = setOf("$tb:table-header-rows", "$tb:table-rows", "$tb:table-row-group")
        val colGroups = setOf("$tb:table-columns", "$tb:table-header-columns", "$tb:table-column-group")
        val end = at - minOf(n, 0)
        val b = StringBuilder()
        val columns = Line(x, "$tb:number-columns-repeated", at, n, trimOf(x, itemsOf(x, t.openEnd, t.closeStart, "$tb:table-column", colGroups), "$tb:number-columns-repeated", n, MAX_COLS),
            fresh = { c -> again(x, c, "$tb:number-columns-repeated", n) }, check = { _, _ -> })
        fun row(r: Elem) {
            if (r.empty) { b.append(x, r.start, r.end); return }
            val cells = XmlScan.children(x, r).filter { it.name == "$tb:table-cell" || it.name == "$tb:covered-table-cell" }
            val line = Line(x, "$tb:number-columns-repeated", at, n, trimOf(x, cells, "$tb:number-columns-repeated", n, MAX_COLS),
                fresh = { "<$tb:table-cell" + (if (n > 1) " $tb:number-columns-repeated=\"$n\"" else "") + "/>" },
                check = { c, col ->
                    val span = c.attrs["$tb:number-columns-spanned"]?.toIntOrNull() ?: 1
                    if (span > 1) {
                        val left = col; val right = col + span - 1
                        val cut = if (n > 0) left < at && right >= at else left < end && right >= at && !(left >= at && right < end)
                        if (cut) throw Refused(XlsxEdit.RowRefusal.LOCKED)
                    }
                })
            b.append(x, r.start, r.openEnd)
            var pos = r.openEnd
            for (c in XmlScan.children(x, r)) {
                b.append(x, pos, c.start); pos = c.end
                if (c.name == "$tb:table-cell" || c.name == "$tb:covered-table-cell") line.item(c, b) else b.append(x, c.start, c.end)
            }
            b.append(x, pos, r.end)
        }
        fun walk(from: Int, to: Int) {
            var pos = from
            for (c in XmlScan.children(x, from, to)) {
                b.append(x, pos, c.start); pos = c.end
                when (c.name) {
                    "$tb:table-column" -> columns.item(c, b)
                    "$tb:table-row" -> row(c)
                    in colGroups, in rowGroups -> if (c.empty) b.append(x, c.start, c.end) else { b.append(x, c.start, c.openEnd); walk(c.openEnd, c.closeStart); b.append(x, c.closeStart, c.end) }
                    else -> b.append(x, c.start, c.end)
                }
            }
            b.append(x, pos, to)
        }
        b.append(x, t.start, t.openEnd)
        walk(t.openEnd, t.closeStart)
        b.append(x, t.closeStart, t.end)
        return b.toString()
    }

    // --- Saving ---

    /** A note's XML (by Hramota, with no date). */
    private fun noteXml(text: String): String {
        val o = ns.office; val t = ns.text
        return "<$o:annotation><${ns.dc}:creator>$AUTHOR</${ns.dc}:creator>" + text.split('\n').joinToString("") { "<$t:p>${XmlScan.escape(it)}</$t:p>" } + "</$o:annotation>"
    }

    /** A cell kept as it is but for its style or note ([style] null: as it was; [note] null: as it was). */
    private fun patched(orig: Elem?, style: String?, note: String?): String {
        val tb = ns.table
        if (orig == null) return "<$tb:table-cell" + (style?.let { " $tb:style-name=\"${XmlScan.escape(it)}\"" } ?: "") + if (note == null) "/>" else ">${noteXml(note)}</$tb:table-cell>"
        var tag = repeated(xml.substring(orig.start, orig.openEnd), "$tb:number-columns-repeated", 1)
        if (style != null) tag = setAttr(tag, "$tb:style-name", style)
        if (note == null) return tag + xml.substring(orig.openEnd, orig.end)
        val kids = if (orig.empty) emptyList() else XmlScan.children(xml, orig).filter { it.local != "annotation" }
        val open = if (orig.selfClosing) tag.removeSuffix("/>").trimEnd() + ">" else tag
        return open + noteXml(note) + kids.joinToString("") { xml.substring(it.start, it.end) } + "</${orig.name}>"
    }

    /** One cell written anew, from the file's cell there (if any). */
    private fun cellXml(orig: Elem?, input: Input?, raw: OCell?, value: Value, style: String? = null, note: String? = null): String {
        val o = ns.office; val tb = ns.table; val t = ns.text
        val formula = (input as? Input.Formula)?.let { toOdf(it.text) } ?: if (input == null) raw?.formula else null
        val attrs = LinkedHashMap<String, String>()
        orig?.attrs?.forEach { (k, v) -> if (k !in VALUE_ATTRS.map { it.replace("office:", "$o:").replace("table:", "$tb:") } && !k.startsWith("calcext:")) attrs[k] = v }
        attrs.remove("$tb:number-columns-repeated")
        style?.let { attrs["$tb:style-name"] = it }
        if (formula != null) attrs["$tb:formula"] = formula
        var text: String? = null
        val number: Double? = (value as? Value.Num)?.v
        val wasType = raw?.type
        when {
            number != null -> {
                val type = if (input == null || input is Input.Number || input is Input.Formula) (wasType?.takeIf { it in setOf("percentage", "currency", "date") } ?: "float") else "float"
                attrs["$o:value-type"] = type
                if (type == "date") attrs["$o:date-value"] = toIsoDate(number) else attrs["$o:value"] = plain(number)
                if (type == "currency") raw?.currency?.let { attrs["$o:currency"] = it }
                text = if (type == "date") toIsoDate(number).replace('T', ' ') else Formula.show(number).replace('.', ',')
            }
            value is Value.Text -> { attrs["$o:value-type"] = "string"; if (formula != null) attrs["$o:string-value"] = value.v; text = value.v }
            value is Value.Bool -> { attrs["$o:value-type"] = "boolean"; attrs["$o:boolean-value"] = value.v.toString(); text = if (value.v) "TRUE" else "FALSE" }
            value is Value.Err -> text = value.code
        }
        val b = StringBuilder("<$tb:table-cell")
        for ((k, v) in attrs) b.append(' ').append(k).append("=\"").append(XmlScan.escape(v)).append('"')
        // What else the cell holds stays (a note, a picture); its text is written anew
        val kept = (note?.let { noteXml(it) } ?: "") + (orig?.takeIf { !it.empty }?.let { e -> XmlScan.children(xml, e).filter { it.local != "p" && it.local != "h" && it.local != "list" && (note == null || it.local != "annotation") }.joinToString("") { xml.substring(it.start, it.end) } } ?: "")
        if (text == null && kept.isEmpty()) return b.append("/>").toString()
        b.append('>').append(kept)
        text?.split('\n')?.forEach { line -> b.append("<$t:p>").append(XmlScan.escape(line)).append("</$t:p>") }
        return b.append("</$tb:table-cell>").toString()
    }

    /** [tag] (a start tag) with its repeat count set to [n] (none when 1). */
    private fun repeated(tag: String, attr: String, n: Int): String {
        val re = Regex("\\s${Regex.escape(attr)}=(?:\"[^\"]*\"|'[^']*')")
        val bare = tag.replace(re, "")
        return if (n == 1) bare else bare.replaceFirst(Regex("^(<[^\\s>/]+)"), "$1 $attr=\"$n\"")
    }

    private fun repeat(e: Elem, attr: String) = (e.attrs[attr]?.toIntOrNull() ?: 1).coerceAtLeast(1)

    /** An element written with another repeat count (its content as it was). */
    private fun withRepeat(e: Elem, attr: String, n: Int): String = repeated(xml.substring(e.start, e.openEnd), attr, n) + xml.substring(e.openEnd, e.end)

    /** The table with [writes] (row to column to the new cell, made from the cell there) put in. */
    private fun rewriteTable(t: Elem, writes: TreeMap<Int, TreeMap<Int, (Elem?) -> String>>, colStyles: TreeMap<Int, (String?) -> String> = TreeMap()): String {
        val tb = ns.table
        val b = StringBuilder()
        var row = 0
        var column = 0
        fun columns(from: Int, to: Int) {
            var pos = from
            for (c in XmlScan.children(xml, from, to)) {
                b.append(xml, pos, c.start); pos = c.end
                when (c.name) {
                    "$tb:table-column" -> {
                        val n = repeat(c, "$tb:number-columns-repeated")
                        val hit = colStyles.subMap(column, column + n)
                        if (hit.isEmpty()) { b.append(xml, c.start, c.end); column += n; continue }
                        var cur = column
                        for ((at, make) in hit) {
                            if (at > cur) b.append(withRepeat(c, "$tb:number-columns-repeated", at - cur))
                            val tag = repeated(xml.substring(c.start, c.openEnd), "$tb:number-columns-repeated", 1)
                            b.append(setAttr(tag + xml.substring(c.openEnd, c.end), "$tb:style-name", make(c.attrs["$tb:style-name"])))
                            cur = at + 1
                        }
                        if (cur < column + n) b.append(withRepeat(c, "$tb:number-columns-repeated", column + n - cur))
                        column += n
                    }
                    "$tb:table-columns", "$tb:table-header-columns", "$tb:table-column-group" -> {
                        if (c.empty) { b.append(xml, c.start, c.end); continue }
                        b.append(xml, c.start, c.openEnd); columns(c.openEnd, c.closeStart); b.append(xml, c.closeStart, c.end)
                    }
                    else -> b.append(xml, c.start, c.end)
                }
            }
            b.append(xml, pos, to)
        }
        fun rewriteRow(r: Elem, cols: TreeMap<Int, (Elem?) -> String>) {
            b.append(repeated(xml.substring(r.start, r.openEnd).let { if (r.selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }, "$tb:number-rows-repeated", 1))
            var col = 0
            var pos = r.openEnd
            if (!r.empty) for (c in XmlScan.children(xml, r)) {
                b.append(xml, pos, c.start); pos = c.end
                if (c.name != "$tb:table-cell" && c.name != "$tb:covered-table-cell") { b.append(xml, c.start, c.end); continue }
                val n = repeat(c, "$tb:number-columns-repeated")
                val hit = cols.subMap(col, col + n)
                if (hit.isEmpty()) { b.append(xml, c.start, c.end); col += n; continue }
                var cur = col
                for ((at, make) in hit) {
                    if (at > cur) b.append(withRepeat(c, "$tb:number-columns-repeated", at - cur))
                    b.append(make(c))
                    cur = at + 1
                }
                if (cur < col + n) b.append(withRepeat(c, "$tb:number-columns-repeated", col + n - cur))
                col += n
            }
            if (!r.empty) b.append(xml, pos, r.closeStart)
            // Cells past the row's last: empty ones up to them
            for ((at, make) in cols.tailMap(col)) {
                if (at > col) b.append(if (at - col == 1) "<$tb:table-cell/>" else "<$tb:table-cell $tb:number-columns-repeated=\"${at - col}\"/>")
                b.append(make(null)); col = at + 1
            }
            b.append("</$tb:table-row>")
        }
        fun rows(from: Int, to: Int) {
            var pos = from
            for (r in XmlScan.children(xml, from, to)) {
                b.append(xml, pos, r.start); pos = r.end
                when (r.name) {
                    "$tb:table-row" -> {
                        val n = repeat(r, "$tb:number-rows-repeated")
                        val hit = writes.subMap(row, row + n)
                        if (hit.isEmpty()) { b.append(xml, r.start, r.end); row += n; continue }
                        var cur = row
                        for ((at, cols) in hit) {
                            if (at > cur) b.append(withRepeat(r, "$tb:number-rows-repeated", at - cur))
                            rewriteRow(r, cols)
                            cur = at + 1
                        }
                        if (cur < row + n) b.append(withRepeat(r, "$tb:number-rows-repeated", row + n - cur))
                        row += n
                    }
                    "$tb:table-header-rows", "$tb:table-rows", "$tb:table-row-group" -> {
                        if (r.empty) { b.append(xml, r.start, r.end); continue }
                        b.append(xml, r.start, r.openEnd); rows(r.openEnd, r.closeStart); b.append(xml, r.closeStart, r.end)
                    }
                    else -> b.append(xml, r.start, r.end)
                }
            }
            b.append(xml, pos, to)
        }
        b.append(xml, t.start, t.openEnd)
        // Columns first (their widths), then the rows: the table's children in two passes over one stretch
        val firstRow = XmlScan.children(xml, t).firstOrNull { it.name == "$tb:table-row" || it.name == "$tb:table-rows" || it.name == "$tb:table-header-rows" || it.name == "$tb:table-row-group" }?.start ?: t.closeStart
        columns(t.openEnd, firstRow)
        // Columns past the table's last
        for ((at, make) in colStyles.tailMap(column)) {
            if (at > column) b.append(if (at - column == 1) "<$tb:table-column/>" else "<$tb:table-column $tb:number-columns-repeated=\"${at - column}\"/>")
            b.append("<$tb:table-column $tb:style-name=\"${make(null)}\"/>"); column = at + 1
        }
        rows(firstRow, t.closeStart)
        // Rows past the table's last: empty ones up to them
        for ((at, cols) in writes.tailMap(row)) {
            if (at > row) b.append("<$tb:table-row${if (at - row > 1) " $tb:number-rows-repeated=\"${at - row}\"" else ""}><$tb:table-cell/></$tb:table-row>")
            b.append("<$tb:table-row>")
            var col = 0
            for ((c, make) in cols) {
                if (c > col) b.append(if (c - col == 1) "<$tb:table-cell/>" else "<$tb:table-cell $tb:number-columns-repeated=\"${c - col}\"/>")
                b.append(make(null)); col = c + 1
            }
            b.append("</$tb:table-row>")
            row = at + 1
        }
        return b.append(xml, t.closeStart, t.end).toString()
    }

    /** content.xml with the changed cells, and the formulas whose values changed, written anew. */
    fun contentXml(): String {
        val values = recalc()
        val inputs: Map<CellKey, Input> = synchronized(this) { HashMap(this.inputs) }
        val looks: Map<CellKey, CellLook> = synchronized(this) { HashMap(this.looks) }
        val notes: Map<CellKey, String> = synchronized(this) { HashMap(this.notes) }
        val widths: Map<Pair<Int, Int>, Float> = synchronized(this) { HashMap(this.widths) }
        val made = Made()
        val out = StringBuilder(xml.length + 4096)
        var pos = bodyStart
        for ((s, sh) in sheets.withIndex()) {
            val writes = TreeMap<Int, TreeMap<Int, (Elem?) -> String>>()
            /** The style a cell gets: its own (or its column's) with the look put on it. */
            fun styleOf(k: CellKey, e: Elem?): String? = looks[k]?.let { look ->
                // A look that names a style (a cell moved by sorting) starts from that one
                val base = if (look.style != null) synchronized(this) { styleNumbers.getOrNull(look.style.toIntOrNull() ?: 0) } ?: sh.columnStyle(k.col)
                    else e?.attrs?.get("${ns.table}:style-name") ?: sh.columnStyle(k.col)
                val rest = look.copy(style = null)
                if (rest.isEmpty) base ?: "Default" else made.cellStyle(base, rest)
            }
            for ((k, v) in values) {
                if (k.sheet != s) continue
                val input = inputs[k]
                val raw = sh.cell(k.row, k.col)
                // A formula of the file is written again only when its value changed
                if (input == null) { if (raw == null || raw.excel == null || sameValue(rawValue(raw), v)) continue }
                writes.getOrPut(k.row) { TreeMap() }[k.col] = { e -> cellXml(e, input, raw, v, styleOf(k, e), notes[k]) }
            }
            // Cells whose look or note alone changed: kept as they are but for that
            for (k in looks.keys + notes.keys) {
                if (k.sheet != s || writes[k.row]?.containsKey(k.col) == true) continue
                writes.getOrPut(k.row) { TreeMap() }[k.col] = { e -> patched(e, styleOf(k, e), notes[k]) }
            }
            val colStyles = TreeMap<Int, (String?) -> String>()
            for ((sc, w) in widths) if (sc.first == s) colStyles[sc.second] = { base -> made.columnStyle(base, w) }
            // Formulas Hramota cannot work out may depend on what changed: written without their old values, so LibreOffice works them out
            if (inputs.isNotEmpty() || doc.moved) for ((k, c) in sh.cells) {
                if (c.formula == null) continue
                val supported = c.excel?.let { parsedOf(s, k, c) }?.supported == true
                if (supported) continue
                val row = (k / COLS).toInt(); val col = (k % COLS).toInt()
                if (CellKey(s, row, col) in inputs) continue
                writes.getOrPut(row) { TreeMap() }[col] = { e -> cellXml(e, null, c, Value.Empty) }
            }
            if (writes.isEmpty() && colStyles.isEmpty()) continue
            out.append(xml, pos, sh.table.start)
            out.append(rewriteTable(sh.table, writes, colStyles))
            pos = sh.table.end
        }
        out.append(xml, pos, xml.length)
        // The styles made, at the end of the content's own automatic styles
        val defs = made.defs.toString()
        val head = StringBuilder()
        val a = autoElem
        when {
            defs.isEmpty() -> head.append(xml, 0, bodyStart)
            a == null -> head.append(xml, 0, bodyStart).append("<${ns.office}:automatic-styles>").append(defs).append("</${ns.office}:automatic-styles>")
            a.selfClosing -> head.append(xml, 0, a.start).append("<${a.name}>").append(defs).append("</${a.name}>").append(xml, a.end, bodyStart)
            else -> head.append(xml, 0, a.closeStart).append(defs).append(xml, a.closeStart, bodyStart)
        }
        return head.append(out).toString()
    }

    private fun sameValue(a: Value, b: Value): Boolean = when {
        a is Value.Num && b is Value.Num -> a.v == b.v || Math.abs(a.v - b.v) <= 1e-9 * maxOf(1.0, Math.abs(a.v))
        else -> a == b
    }

    override fun save(source: InputStream, out: OutputStream) {
        val content = contentXml().toByteArray(Charsets.UTF_8)
        val written = HashSet<String>()
        var total = 0L
        ZipInputStream(source).use { zip ->
            val z = ZipOutputStream(out)
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                val name = e.name
                if (!written.add(name)) throw IOException("Duplicate entry")
                when (name) {
                    "mimetype" -> {
                        val b = readAll(zip, 1024)
                        z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().apply { update(b) }.value })
                        z.write(b)
                    }
                    CONTENT -> {
                        val was = readAll(zip, MAX_CONTENT)
                        if (!MessageDigest.getInstance("SHA-256").digest(was).contentEquals(digest)) throw IOException("The workbook changed meanwhile")
                        z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                        z.write(content)
                    }
                    else -> {
                        z.putNextEntry(ZipEntry(name).apply { e.time.takeIf { it > 0 }?.let { time = it } })
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf); if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL) throw IOException("Package too large")
                            z.write(buf, 0, n)
                        }
                    }
                }
                z.closeEntry()
            }
            if (CONTENT !in written) throw IOException("No content")
            z.finish(); z.flush()
        }
    }

    override fun readBack(input: InputStream): Workbook = OdsReader.read(input)

    companion object {
        const val CONTENT = "content.xml"
        private const val COLS = 20_000L
        private fun key(row: Int, col: Int): Long = row * COLS + col
        private const val MAX_CONTENT = 48L * 1024 * 1024
        private const val MAX_TOTAL = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val MAX_CELLS = 400_000
        private const val MAX_ROWS = 1_048_576
        private const val MAX_COLS = 16_384
        private val VALUE_ATTRS = setOf("office:value-type", "office:value", "office:date-value", "office:time-value", "office:boolean-value", "office:string-value", "office:currency", "table:formula")
        /** In a sheet: what is tied to its cells or ranges other than through them (rows are not moved then). */
        private val BLOCKERS_IN_TABLE = Regex("<(?:[\\w.-]+:)?(?:conditional-formats|named-expressions|shapes|scenario|table-source|dde-source)\\b|\\s[\\w.-]+:(?:end-cell-address|print-ranges|content-validation-name)=")
        /** Addresses of cells and ranges outside the sheets' cells (named ranges, filters, checks, pivot tables). */
        private val ADDRESSES = Regex("\\s[\\w.-]+:(?:cell-range-address|base-cell-address|target-range-address|range-address|cell-address|source-cell-range-addresses|target-cell-address|expression|condition)=(?:\"([^\"]*)\"|'([^']*)')")
        /** What makes a cell, row or column more than its looks. */
        private val FILLED = Regex("value-type=|formula=|<(?:[\\w.-]+:)?(?:p|h|annotation|frame|custom-shape)[\\s/>]")
        private val SHEET_REF = Regex("('(?:[^']|'')+'|[\\p{L}_][\\p{L}\\p{N}_.]*)!")
        private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        private const val NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
        private const val DC = "http://purl.org/dc/elements/1.1/"
        /** Who notes added here are by: the app, never the person's name. */
        const val AUTHOR = "Hramota"

        /** OpenDocument number styles for the formats the editor offers ({n}: number prefix, {s}: style prefix, {name}: the style's name). */
        private val DATA_STYLES: Map<String, String> = mapOf(
            "General" to "",
            "0" to "<{n}:number-style {s}:name=\"{name}\"><{n}:number {n}:decimal-places=\"0\" {n}:min-decimal-places=\"0\" {n}:min-integer-digits=\"1\"/></{n}:number-style>",
            "#,##0.00" to "<{n}:number-style {s}:name=\"{name}\"><{n}:number {n}:decimal-places=\"2\" {n}:min-decimal-places=\"2\" {n}:min-integer-digits=\"1\" {n}:grouping=\"true\"/></{n}:number-style>",
            "0%" to "<{n}:percentage-style {s}:name=\"{name}\"><{n}:number {n}:decimal-places=\"0\" {n}:min-decimal-places=\"0\" {n}:min-integer-digits=\"1\"/><{n}:text>%</{n}:text></{n}:percentage-style>",
            "#,##0.00\\ \"₴\"" to "<{n}:currency-style {s}:name=\"{name}\"><{n}:number {n}:decimal-places=\"2\" {n}:min-decimal-places=\"2\" {n}:min-integer-digits=\"1\" {n}:grouping=\"true\"/><{n}:text> </{n}:text><{n}:currency-symbol {n}:language=\"uk\" {n}:country=\"UA\">₴</{n}:currency-symbol></{n}:currency-style>",
            "dd.mm.yyyy" to "<{n}:date-style {s}:name=\"{name}\"><{n}:day {n}:style=\"long\"/><{n}:text>.</{n}:text><{n}:month {n}:style=\"long\"/><{n}:text>.</{n}:text><{n}:year {n}:style=\"long\"/></{n}:date-style>",
            "@" to "<{n}:text-style {s}:name=\"{name}\"><{n}:text-content/></{n}:text-style>",
        )

        private val BASE = LocalDate.of(1899, 12, 30)

        /** "02.11.2026" (or 2.11.2026, 02/11/2026) as a spreadsheet's day number. */
        internal fun dateSerial(t: String): Double? {
            val m = Regex("\\s*(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\s*").matchEntire(t) ?: return null
            return try { ChronoUnit.DAYS.between(BASE, LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())).toDouble() } catch (e: Exception) { null }
        }

        internal fun fromIsoDate(v: String): Double? = try {
            if ('T' in v) {
                val dt = LocalDateTime.parse(v.substringBefore('.').substringBefore('Z'))
                ChronoUnit.DAYS.between(BASE, dt.toLocalDate()) + dt.toLocalTime().toSecondOfDay() / 86400.0
            } else ChronoUnit.DAYS.between(BASE, LocalDate.parse(v)).toDouble()
        } catch (e: Exception) { null }

        internal fun fromIsoTime(v: String): Double? {
            val m = Regex("-?PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matchEntire(v.trim()) ?: return null
            val h = m.groupValues[1].toDoubleOrNull() ?: 0.0; val mi = m.groupValues[2].toDoubleOrNull() ?: 0.0; val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
            return (h * 3600 + mi * 60 + s) / 86400.0
        }

        internal fun toIsoDate(serial: Double): String {
            val day = Math.floor(serial).toLong()
            val d = BASE.plusDays(day)
            val secs = Math.round((serial - day) * 86400).toInt().coerceIn(0, 86399)
            return if (secs == 0) d.toString() else "%sT%02d:%02d:%02d".format(java.util.Locale.ROOT, d, secs / 3600, secs / 60 % 60, secs % 60)
        }

        private fun plain(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

        // --- Formulas: OpenDocument's way and Excel's ---

        /** A sheet's name as Excel writes it before "!". */
        private fun excelSheet(name: String): String =
            if (Regex("[\\p{L}_][\\p{L}\\p{N}_.]*").matches(name) && !Regex("[A-Za-z]{1,3}\\d+").matches(name)) "$name!" else "'" + name.replace("'", "''") + "'!"

        private fun odfSheet(name: String): String =
            if (Regex("[\\p{L}_][\\p{L}\\p{N}_]*").matches(name)) name else "'" + name.replace("'", "''") + "'"

        /** One end of an OpenDocument reference ("$Аркуш1.$A$1", ".B2"): its sheet (null: this one) and cell. */
        private fun refPart(p: String): Pair<String?, String>? {
            // The last dot not inside quotes parts the sheet from the cell
            var inQuote = false; var dot = -1
            for ((i, c) in p.withIndex()) { if (c == '\'') inQuote = !inQuote else if (c == '.' && !inQuote) dot = i }
            if (dot < 0) return null
            var sheet = p.substring(0, dot).removePrefix("$")
            val cell = p.substring(dot + 1)
            if (!Regex("\\$?[A-Za-z]{1,3}\\$?\\d+").matches(cell)) return null
            if (sheet.startsWith("'")) { if (!sheet.endsWith("'") || sheet.length < 2) return null; sheet = sheet.substring(1, sheet.length - 1).replace("''", "'") }
            return (sheet.ifEmpty { null }) to cell.uppercase()
        }

        /** An OpenDocument formula ("of:=SUM([.A1:.A3])") as Excel writes it ("SUM(A1:A3)"); null if it cannot be. */
        internal fun fromOdf(f: String): String? {
            var t = f.trim()
            val prefix = Regex("^([a-zA-Z]+):=").find(t)
            if (prefix != null) {
                if (prefix.groupValues[1] == "msoxl") return t.substring(prefix.value.length)
                t = t.substring(prefix.value.length)
            } else t = t.removePrefix("=")
            val b = StringBuilder()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when (c) {
                    '"' -> {
                        var j = i + 1
                        while (j < t.length) { if (t[j] == '"') { if (j + 1 < t.length && t[j + 1] == '"') j += 2 else break } else j++ }
                        if (j >= t.length) return null
                        b.append(t, i, j + 1); i = j + 1
                    }
                    '[' -> {
                        var j = i + 1; var q = false
                        while (j < t.length && (t[j] != ']' || q)) { if (t[j] == '\'') q = !q; j++ }
                        if (j >= t.length) return null
                        val inner = t.substring(i + 1, j)
                        if (inner.contains("#REF!")) b.append("#REF!") else {
                            // The two ends of a range (a colon not inside quotes)
                            var colon = -1; q = false
                            for ((k, ch) in inner.withIndex()) { if (ch == '\'') q = !q else if (ch == ':' && !q) { colon = k; break } }
                            val a = refPart(if (colon < 0) inner else inner.substring(0, colon)) ?: return null
                            val z = if (colon < 0) null else (refPart(inner.substring(colon + 1)) ?: return null)
                            // A range across sheets is not something Excel's way writes the same
                            if (z != null && z.first != null && !z.first.equals(a.first, ignoreCase = true)) return null
                            a.first?.let { b.append(excelSheet(it)) }
                            b.append(a.second)
                            z?.let { b.append(':').append(it.second) }
                        }
                        i = j + 1
                    }
                    ';' -> { b.append(','); i++ }
                    else -> {
                        // TRUE() and FALSE() are Excel's TRUE and FALSE
                        val m = Regex("(TRUE|FALSE)\\(\\)", RegexOption.IGNORE_CASE).matchAt(t, i)
                        if (m != null && (i == 0 || !t[i - 1].isLetterOrDigit())) { b.append(m.groupValues[1].uppercase()); i += m.value.length } else { b.append(c); i++ }
                    }
                }
            }
            return b.toString()
        }

        private val EXCEL_REF = Regex("(?:('(?:[^']|'')+'|[\\p{L}_][\\p{L}\\p{N}_.]*)!)?(\\$?[A-Za-z]{1,3}\\$?\\d+)(?::(\\$?[A-Za-z]{1,3}\\$?\\d+))?")

        /** An Excel formula ("SUM(A1:A3)") as OpenDocument writes it ("of:=SUM([.A1:.A3])"); null if it cannot be. */
        internal fun toOdf(f: String): String? {
            val b = StringBuilder("of:=")
            var i = 0
            while (i < f.length) {
                val c = f[i]
                if (c == '"') {
                    var j = i + 1
                    while (j < f.length) { if (f[j] == '"') { if (j + 1 < f.length && f[j + 1] == '"') j += 2 else break } else j++ }
                    if (j >= f.length) return null
                    b.append(f, i, j + 1); i = j + 1; continue
                }
                if (c == ',') { b.append(';'); i++; continue }
                val prevOk = i == 0 || !(f[i - 1].isLetterOrDigit() || f[i - 1] == '_' || f[i - 1] == '.' || f[i - 1] == '$')
                if (prevOk && (c.isLetter() || c == '$' || c == '\'' || c == '_')) {
                    val m = EXCEL_REF.matchAt(f, i)
                    val end = m?.let { i + it.value.length }
                    if (m != null && (end!! >= f.length || !(f[end].isLetterOrDigit() || f[end] == '(' || f[end] == '_' || f[end] == '!'))) {
                        val sheet = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { if (it.startsWith("'")) it.substring(1, it.length - 1).replace("''", "'") else it }
                        b.append('[').append(if (sheet != null) "$" + odfSheet(sheet) + "." else ".").append(m.groupValues[2].uppercase())
                        if (m.groupValues[3].isNotEmpty()) b.append(":.").append(m.groupValues[3].uppercase())
                        b.append(']')
                        i = end; continue
                    }
                    // A name (a function, TRUE, FALSE): copied whole
                    var e = i
                    while (e < f.length && (f[e].isLetterOrDigit() || f[e] == '_' || f[e] == '.')) e++
                    if (e == i) { b.append(c); i++; continue }
                    val word = f.substring(i, e)
                    // Whole columns or rows (A:A, 1:3) and other sheets' names alone are not written here
                    if (e < f.length && (f[e] == ':' || f[e] == '!')) return null
                    if ((word.equals("TRUE", true) || word.equals("FALSE", true)) && (e >= f.length || f[e] != '(')) b.append(word.uppercase()).append("()") else b.append(word)
                    i = e; continue
                }
                if (c == '\'') return null
                b.append(c); i++
            }
            return b.toString()
        }

        /** Opens an ODS for editing. Call off the main thread. */
        fun open(input: InputStream): OdsEdit {
            var content: ByteArray? = null; var manifest: ByteArray? = null; var mime: String? = null
            val names = HashSet<String>()
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("Too many entries")
                    if (!names.add(e.name)) throw IOException("Duplicate entry")
                    when (e.name) {
                        CONTENT -> content = readAll(zip, MAX_CONTENT)
                        "META-INF/manifest.xml" -> manifest = readAll(zip, 8L * 1024 * 1024)
                        "mimetype" -> mime = String(readAll(zip, 1024), Charsets.US_ASCII).trim()
                    }
                }
            }
            if (mime != null && mime != "application/vnd.oasis.opendocument.spreadsheet") throw IOException("Not a spreadsheet")
            if (manifest?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) throw IOException("Encrypted")
            val bytes = content ?: throw IOException("No content")
            Xml.checkNoDoctype(bytes)
            val xml = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "document-content" } ?: throw IOException("No content")
            fun prefix(uri: String) = root.attrs.entries.firstOrNull { it.value == uri && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:") ?: throw IOException("Unusual namespaces")
            fun prefixOrNull(uri: String) = root.attrs.entries.firstOrNull { it.value == uri && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:")
            val ns = Prefixes(prefix(OFFICE), prefix(TABLE), prefix(TEXT), prefixOrNull(STYLE) ?: "style", prefixOrNull(FO) ?: "fo", prefixOrNull(NUMBER), prefixOrNull(DC))
            return OdsEdit(MessageDigest.getInstance("SHA-256").digest(bytes), ns, parse(xml, ns))
        }

        /** The content's sheets and cells, read for editing. */
        private fun parse(xml: String, ns: Prefixes, moved: Boolean = false): Doc {
            val root = XmlScan.children(xml, 0, xml.length).firstOrNull { it.local == "document-content" } ?: throw IOException("No content")
            val o = ns.office; val tb = ns.table; val t = ns.text
            val top = XmlScan.children(xml, root)
            val body = top.firstOrNull { it.name == "$o:body" } ?: throw IOException("No body")
            val autoElem = top.firstOrNull { it.name == "$o:automatic-styles" }
            if (autoElem != null && autoElem.start > body.start) throw IOException("Unusual order")
            val autoStyles = HashMap<String, Elem>()
            val taken = HashSet<String>()
            if (autoElem != null) for (st in XmlScan.children(xml, autoElem)) st.attrs.entries.firstOrNull { it.key.endsWith(":name") }?.value?.let { n -> taken += n; if (st.local == "style") autoStyles[n] = st }
            val sheetEl = XmlScan.children(xml, body).firstOrNull { it.name == "$o:spreadsheet" } ?: throw IOException("No spreadsheet")
            var total = 0
            val sheets = ArrayList<OSheet>()
            for (table in XmlScan.children(xml, sheetEl).filter { it.name == "$tb:table" }) {
                val name = table.attrs["$tb:name"] ?: "Sheet${sheets.size + 1}"
                val cells = HashMap<Long, OCell>()
                val locked = HashSet<Long>()
                val areas = ArrayList<IntArray>()
                val annotated = HashSet<Long>()
                var row = 0
                val columns = ArrayList<Triple<Int, Int, String?>>()
                var col0 = 0
                fun cols(e: Elem) {
                    for (c in XmlScan.children(xml, e)) when (c.name) {
                        "$tb:table-column" -> { val n = (c.attrs["$tb:number-columns-repeated"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_COLS); columns += Triple(col0, col0 + n - 1, c.attrs["$tb:default-cell-style-name"]); col0 += n }
                        "$tb:table-columns", "$tb:table-header-columns", "$tb:table-column-group" -> cols(c)
                    }
                }
                cols(table)
                var rich = false
                fun textOf(c: Elem): String = XmlScan.children(xml, c).filter { it.name == "$t:p" || it.name == "$t:h" }.joinToString("\n") { p ->
                    val sb = StringBuilder()
                    fun walk(e: Elem) {
                        var pos = e.openEnd
                        for (k in XmlScan.children(xml, e)) {
                            sb.append(XmlScan.unescape(xml.substring(pos, k.start))); pos = k.end
                            when (k.local) { "s" -> sb.append(" ".repeat((k.attrs["$t:c"]?.toIntOrNull() ?: 1).coerceIn(1, 100))); "tab" -> sb.append('\t'); "line-break" -> sb.append('\n'); "annotation" -> {}; else -> { rich = true; if (!k.empty) walk(k) } }
                        }
                        sb.append(XmlScan.unescape(xml.substring(pos, e.closeStart)))
                    }
                    if (!p.empty) walk(p)
                    sb.toString()
                }
                fun rows(e: Elem, depth: Int) {
                    if (depth > 20) return
                    for (r in XmlScan.children(xml, e)) when (r.name) {
                        "$tb:table-row" -> {
                            val rn = (r.attrs["$tb:number-rows-repeated"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_ROWS)
                            if (row >= MAX_ROWS) return
                            var col = 0
                            if (!r.empty) for (c in XmlScan.children(xml, r)) {
                                if (c.name != "$tb:table-cell" && c.name != "$tb:covered-table-cell") continue
                                val cn = (c.attrs["$tb:number-columns-repeated"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_COLS)
                                val covered = c.name == "$tb:covered-table-cell"
                                val type = c.attrs["$o:value-type"]
                                val formula = c.attrs["$tb:formula"]
                                rich = false
                                val text = if (c.empty) "" else textOf(c)
                                val noted = !c.empty && XmlScan.children(xml, c).any { it.local == "annotation" }
                                val has = type != null || formula != null || text.isNotEmpty()
                                // Array formulas: the whole area stays as it is
                                c.attrs["$tb:number-matrix-columns-spanned"]?.toIntOrNull()?.let { mc ->
                                    val mr = c.attrs["$tb:number-matrix-rows-spanned"]?.toIntOrNull() ?: 1
                                    areas += intArrayOf(row, col, row + mr - 1, col + mc - 1)
                                }
                                if (noted && !has && !covered) annotated += key(row, col)
                                if (covered || has) {
                                    // Repeats of a cell that holds something: each one (within what the reader shows)
                                    val rows = if (rn > 1000) 1000 else rn
                                    val cols = if (cn > 1024) 1024 else cn
                                    if (has || covered) {
                                        val oc = if (has) OCell(type, c.attrs["$o:value"] ?: c.attrs["$o:date-value"] ?: c.attrs["$o:time-value"] ?: c.attrs["$o:boolean-value"] ?: c.attrs["$o:string-value"],
                                            text, formula, formula?.let(::fromOdf)?.takeIf { Formula.parse(it).node != null }, c.attrs["$o:currency"], c.attrs["$tb:style-name"], rich) else null
                                        for (dr in 0 until rows) for (dc in 0 until cols) {
                                            val k = key(row + dr, col + dc)
                                            if (covered) locked += k
                                            if (noted) annotated += k
                                            if (oc != null) { cells[k] = oc; if (++total > MAX_CELLS) throw IOException("Too many cells to edit") }
                                        }
                                    }
                                }
                                col += cn
                            }
                            row += rn
                        }
                        "$tb:table-header-rows", "$tb:table-rows", "$tb:table-row-group" -> rows(r, depth + 1)
                    }
                }
                rows(table, 0)
                sheets += OSheet(name, table, cells, locked, areas, columns, annotated)
            }
            if (sheets.isEmpty()) throw IOException("No sheets")
            // Names the document's other parts may use too (styles.xml is not read here): new ones get a prefix of their own
            return Doc(xml, sheets, autoStyles, autoElem, body.start, taken, moved)
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
