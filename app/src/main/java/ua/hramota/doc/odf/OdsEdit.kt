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
    private val xml: String,
    private val digest: ByteArray,
    private val ns: Prefixes,
    private val sheets: List<OSheet>,
) : SheetEdit {
    class Prefixes(val office: String, val table: String, val text: String)

    /** A cell of the file: its type and value as written, its shown text, its formula (and as Excel writes it, if it can be). */
    private class OCell(val type: String?, val value: String?, val text: String, val formula: String?, val excel: String?, val currency: String?)

    private class OSheet(val name: String, val table: Elem, val cells: HashMap<Long, OCell>, val locked: HashSet<Long>, val lockedAreas: List<IntArray>) {
        fun cell(row: Int, col: Int) = cells[key(row, col)]
    }

    override val sheetNames = sheets.map { SheetEdit.SheetName(it.name, true) }
    override val full = false
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

    override fun stored(cell: CellKey): Input? = null
    override fun style(cell: CellKey): Int? = null

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
        if (inputs.isNotEmpty()) for ((s, sh) in sheets.withIndex()) for ((k, c) in sh.cells) {
            if (c.excel == null) continue
            val ck = CellKey(s, (k / COLS).toInt(), (k % COLS).toInt())
            if (ck !in inputs) out[ck] = cells.valueOf(ck)
        }
        return out
    }

    // --- Not changed here ---

    override val noteChanges: Map<CellKey, String> get() = emptyMap()
    override fun setNote(cell: CellKey, text: String?) {}
    override fun canNote(sheet: Int) = false
    override val lookChanges: Map<CellKey, CellLook> get() = emptyMap()
    override val widthChanges: Map<Pair<Int, Int>, Float> get() = emptyMap()
    override fun setLook(cell: CellKey, look: CellLook?) = false
    override fun look(cell: CellKey): CellLook? = null
    override fun setWidth(sheet: Int, col: Int, chars: Float?) {}
    override val size: Long get() = xml.length.toLong()

    private class Snap(val inputs: Map<CellKey, Input>) : SheetSnapshot
    @Synchronized override fun snapshot(): SheetSnapshot = Snap(HashMap(inputs))
    @Synchronized override fun restore(s: SheetSnapshot) { inputs.clear(); parsedInputs.clear(); for ((k, v) in (s as Snap).inputs) set(k, v) }
    override fun moveRows(sheet: Int, at: Int, n: Int) = XlsxEdit.RowRefusal.WORKBOOK
    override fun moveColumns(sheet: Int, at: Int, n: Int) = XlsxEdit.RowRefusal.WORKBOOK

    // --- Saving ---

    /** One cell written anew, from the file's cell there (if any). */
    private fun cellXml(orig: Elem?, input: Input?, raw: OCell?, value: Value): String {
        val o = ns.office; val tb = ns.table; val t = ns.text
        val formula = (input as? Input.Formula)?.let { toOdf(it.text) } ?: if (input == null) raw?.formula else null
        val attrs = LinkedHashMap<String, String>()
        orig?.attrs?.forEach { (k, v) -> if (k !in VALUE_ATTRS.map { it.replace("office:", "$o:").replace("table:", "$tb:") } && !k.startsWith("calcext:")) attrs[k] = v }
        attrs.remove("$tb:number-columns-repeated")
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
        val kept = orig?.takeIf { !it.empty }?.let { e -> XmlScan.children(xml, e).filter { it.local != "p" && it.local != "h" && it.local != "list" }.joinToString("") { xml.substring(it.start, it.end) } } ?: ""
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
    private fun rewriteTable(t: Elem, writes: TreeMap<Int, TreeMap<Int, (Elem?) -> String>>): String {
        val tb = ns.table
        val b = StringBuilder()
        var row = 0
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
        rows(t.openEnd, t.closeStart)
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
        val inputs = synchronized(this) { HashMap(inputs) }
        val out = StringBuilder(xml.length + 4096)
        var pos = 0
        for ((s, sh) in sheets.withIndex()) {
            val writes = TreeMap<Int, TreeMap<Int, (Elem?) -> String>>()
            for ((k, v) in values) {
                if (k.sheet != s) continue
                val input = inputs[k]
                val raw = sh.cell(k.row, k.col)
                // A formula of the file is written again only when its value changed
                if (input == null) { if (raw == null || raw.excel == null || sameValue(rawValue(raw), v)) continue }
                writes.getOrPut(k.row) { TreeMap() }[k.col] = { e -> cellXml(e, input, raw, v) }
            }
            // Formulas Hramota cannot work out may depend on what changed: written without their old values, so LibreOffice works them out
            if (inputs.isNotEmpty()) for ((k, c) in sh.cells) {
                if (c.formula == null) continue
                val supported = c.excel?.let { parsedOf(s, k, c) }?.supported == true
                if (supported) continue
                val row = (k / COLS).toInt(); val col = (k % COLS).toInt()
                if (CellKey(s, row, col) in inputs) continue
                writes.getOrPut(row) { TreeMap() }[col] = { e -> cellXml(e, null, c, Value.Empty) }
            }
            if (writes.isEmpty()) continue
            out.append(xml, pos, sh.table.start)
            out.append(rewriteTable(sh.table, writes))
            pos = sh.table.end
        }
        out.append(xml, pos, xml.length)
        return out.toString()
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
        private val SHEET_REF = Regex("('(?:[^']|'')+'|[\\p{L}_][\\p{L}\\p{N}_.]*)!")
        private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"

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
            val ns = Prefixes(prefix(OFFICE), prefix(TABLE), prefix(TEXT))
            val o = ns.office; val tb = ns.table; val t = ns.text
            val body = XmlScan.children(xml, root).firstOrNull { it.name == "$o:body" } ?: throw IOException("No body")
            val sheetEl = XmlScan.children(xml, body).firstOrNull { it.name == "$o:spreadsheet" } ?: throw IOException("No spreadsheet")
            var total = 0
            val sheets = ArrayList<OSheet>()
            for (table in XmlScan.children(xml, sheetEl).filter { it.name == "$tb:table" }) {
                val name = table.attrs["$tb:name"] ?: "Sheet${sheets.size + 1}"
                val cells = HashMap<Long, OCell>()
                val locked = HashSet<Long>()
                val areas = ArrayList<IntArray>()
                var row = 0
                fun textOf(c: Elem): String = XmlScan.children(xml, c).filter { it.name == "$t:p" || it.name == "$t:h" }.joinToString("\n") { p ->
                    val sb = StringBuilder()
                    fun walk(e: Elem) {
                        var pos = e.openEnd
                        for (k in XmlScan.children(xml, e)) {
                            sb.append(XmlScan.unescape(xml.substring(pos, k.start))); pos = k.end
                            when (k.local) { "s" -> sb.append(" ".repeat((k.attrs["$t:c"]?.toIntOrNull() ?: 1).coerceIn(1, 100))); "tab" -> sb.append('\t'); "line-break" -> sb.append('\n'); "annotation" -> {}; else -> if (!k.empty) walk(k) }
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
                                val text = if (c.empty) "" else textOf(c)
                                val has = type != null || formula != null || text.isNotEmpty()
                                // Array formulas: the whole area stays as it is
                                c.attrs["$tb:number-matrix-columns-spanned"]?.toIntOrNull()?.let { mc ->
                                    val mr = c.attrs["$tb:number-matrix-rows-spanned"]?.toIntOrNull() ?: 1
                                    areas += intArrayOf(row, col, row + mr - 1, col + mc - 1)
                                }
                                if (covered || has) {
                                    // Repeats of a cell that holds something: each one (within what the reader shows)
                                    val rows = if (rn > 1000) 1000 else rn
                                    val cols = if (cn > 1024) 1024 else cn
                                    if (has || covered) {
                                        val oc = if (has) OCell(type, c.attrs["$o:value"] ?: c.attrs["$o:date-value"] ?: c.attrs["$o:time-value"] ?: c.attrs["$o:boolean-value"] ?: c.attrs["$o:string-value"],
                                            text, formula, formula?.let(::fromOdf)?.takeIf { Formula.parse(it).node != null }, c.attrs["$o:currency"]) else null
                                        for (dr in 0 until rows) for (dc in 0 until cols) {
                                            val k = key(row + dr, col + dc)
                                            if (covered) locked += k
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
                sheets += OSheet(name, table, cells, locked, areas)
            }
            if (sheets.isEmpty()) throw IOException("No sheets")
            return OdsEdit(xml, MessageDigest.getInstance("SHA-256").digest(bytes), ns, sheets)
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
