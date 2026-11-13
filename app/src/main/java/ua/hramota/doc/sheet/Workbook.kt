package ua.hramota.doc.sheet

import ua.hramota.doc.Inspection
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** How a cell looks. Colours are ARGB; null means "as usual" (no fill, black text). */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points. */
    val size: Float = 11f,
    val color: Int? = null,
    val fill: Int? = null,
    /** "left", "center", "right" or null (numbers right, text left). */
    val align: String? = null,
    /** "top", "center" or null (bottom). */
    val vertical: String? = null,
    val wrap: Boolean = false,
    /** Edges with a line: 1 left, 2 top, 4 right, 8 bottom. */
    val borders: Int = 0,
    /** The number format code, e.g. "#,##0.00" or "dd.mm.yyyy". */
    val format: String = "General",
) {
    companion object { val DEFAULT = CellStyle() }
}

/**
 * One row's cells, sparse: column numbers in order, each with a style number and either a text
 * ([texts] not null) or a number (formatted when shown, so a large sheet stays small in memory).
 */
class SheetRow(val index: Int, val cols: IntArray, val styles: IntArray, val numbers: DoubleArray, val texts: Array<String?>) {
    fun find(col: Int): Int = cols.binarySearch(col).let { if (it >= 0) it else -1 }
}

/** A merged area, inclusive. */
data class Merge(val top: Int, val left: Int, val bottom: Int, val right: Int)

/**
 * A worksheet. Sizes are in points: column widths and row heights as Excel shows them at 100%.
 * Only rows with content are stored.
 */
class Sheet(
    val name: String,
    val rows: List<SheetRow>,
    val styles: List<CellStyle>,
    private val widths: Map<Int, Float>,
    val defaultWidth: Float,
    private val heights: Map<Int, Float>,
    val defaultHeight: Float,
    val merges: List<Merge>,
    val frozenRows: Int,
    val frozenCols: Int,
    val date1904: Boolean,
    /** Hidden in the workbook (read only when converting, so it is not lost). */
    val hidden: Boolean = false,
) {
    val rowCount: Int = (rows.lastOrNull()?.index ?: -1) + 1
    val colCount: Int = (rows.maxOfOrNull { r -> r.cols.lastOrNull() ?: -1 } ?: -1).let { c -> maxOf(c, merges.maxOfOrNull { it.right } ?: -1) } + 1

    fun width(col: Int): Float = widths[col] ?: defaultWidth
    /** Columns and rows with a size of their own (also beyond the last cell). */
    val sizedCols: Set<Int> get() = widths.keys
    val sizedRows: Set<Int> get() = heights.keys
    fun height(row: Int): Float = heights[row] ?: defaultHeight

    /** The stored row with this index, or null. */
    fun row(index: Int): SheetRow? {
        var lo = 0; var hi = rows.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = rows[mid]
            when { r.index < index -> lo = mid + 1; r.index > index -> hi = mid - 1; else -> return r }
        }
        return null
    }

    fun style(row: SheetRow, k: Int): CellStyle = styles.getOrNull(row.styles[k]) ?: CellStyle.DEFAULT

    /** What cell [k] of [row] shows. */
    fun text(row: SheetRow, k: Int): String = row.texts[k]
        ?: try { NumberFormat.format(row.numbers[k], style(row, k).format, date1904) } catch (e: Exception) { "#NUM!" }

    /** True when cell [k] holds a number (shown on the right unless the cell says otherwise). */
    fun isNumber(row: SheetRow, k: Int): Boolean = row.texts[k] == null

    /** The sheet after rows were inserted ([n] > 0) or deleted ([n] < 0) at [at]: cells, heights and merges move with them. */
    fun withRowsMoved(at: Int, n: Int): Sheet {
        val m = ua.hramota.doc.sheet.edit.Formula.RowMove(at, n)
        val moved = rows.mapNotNull { r -> m.row(r.index)?.let { SheetRow(it, r.cols, r.styles, r.numbers, r.texts) } }
        val h = HashMap<Int, Float>()
        for ((k, v) in heights) m.row(k)?.let { h[it] = v }
        val merged = merges.mapNotNull { g -> m.range(g.top, g.bottom)?.let { (t, b) -> Merge(t, g.left, b, g.right) }?.takeIf { it.top != it.bottom || it.left != it.right } }
        return Sheet(name, moved, styles, widths, defaultWidth, h, defaultHeight, merged, frozenRows, frozenCols, date1904, hidden)
    }

    /** The sheet with [n] columns inserted before column [at] ([n] > 0) or [-n] deleted from it. */
    fun withColumnsMoved(at: Int, n: Int): Sheet {
        val m = ua.hramota.doc.sheet.edit.Formula.RowMove(at, n, cols = true)
        val moved = rows.map { r ->
            val keep = r.cols.indices.filter { m.row(r.cols[it]) != null }
            SheetRow(r.index, IntArray(keep.size) { m.row(r.cols[keep[it]])!! }, IntArray(keep.size) { r.styles[keep[it]] },
                DoubleArray(keep.size) { r.numbers[keep[it]] }, Array(keep.size) { r.texts[keep[it]] })
        }
        val w = HashMap<Int, Float>()
        for ((k, v) in widths) m.row(k)?.let { w[it] = v }
        val merged = merges.mapNotNull { g -> m.range(g.left, g.right)?.let { (l, r) -> Merge(g.top, l, g.bottom, r) }?.takeIf { it.top != it.bottom || it.left != it.right } }
        return Sheet(name, moved, styles, w, defaultWidth, heights, defaultHeight, merged, frozenRows, frozenCols, date1904, hidden)
    }

    /**
     * The sheet as the editor shows it: other rows and looks, column widths changed (points), and
     * rows a filter leaves out shown with no height.
     */
    fun shown(newRows: List<SheetRow> = rows, newStyles: List<CellStyle> = styles, newWidths: Map<Int, Float> = emptyMap(), hiddenRows: Set<Int> = emptySet()): Sheet {
        val h = if (hiddenRows.isEmpty()) heights else HashMap(heights).also { m -> for (r in hiddenRows) m[r] = 0f }
        return Sheet(name, newRows, newStyles, if (newWidths.isEmpty()) widths else widths + newWidths, defaultWidth, h, defaultHeight, merges, frozenRows, frozenCols, date1904, hidden)
    }

    /** The same sheet with other rows (after editing): sizes, styles and merges stay. */
    fun withRows(newRows: List<SheetRow>) = Sheet(name, newRows, styles, widths, defaultWidth, heights, defaultHeight, merges, frozenRows, frozenCols, date1904, hidden)
}

/** A workbook: its sheets, and what the inspector found in the file. */
/** [complete] is false when a limit or damage left part of the file unread. */
class Workbook(val sheets: List<Sheet>, val inspection: Inspection = Inspection(), val complete: Boolean = true,
    /** Charts drawn as pictures (PNG), by the index of the sheet they stand on. */
    val charts: Map<Int, List<ByteArray>> = emptyMap(),
    /** Comments on cells, by sheet index and (row, column): "Author: words". */
    val comments: Map<Int, Map<Pair<Int, Int>, String>> = emptyMap())

/** Limits so a damaged or hostile file cannot exhaust memory. */
object SheetLimits {
    const val MAX_CELLS = 3_000_000
    const val MAX_ROWS = 1_048_576
    const val MAX_COLS = 16_384
    const val MAX_SHEETS = 256
    const val MAX_TEXT = 32_767
}

/** Excel number formats, enough for what documents use: numbers, percentages, currency, dates and times. */
object NumberFormat {
    /** Longest format code used (Excel's own limit). */
    const val MAX_CODE = 255
    private val CONDITION = Regex("\\[(?:Red|Black|Blue|Green|White|Yellow|Magenta|Cyan|Color ?\\d+|[<>=][^]]*)]", RegexOption.IGNORE_CASE)

    /** Excel's built-in formats by number. */
    fun builtIn(id: Int): String? = when (id) {
        0 -> "General"; 1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"
        9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"; 12 -> "# ?/?"; 13 -> "# ??/??"
        14 -> "dd.mm.yyyy"; 15 -> "d-mmm-yy"; 16 -> "d-mmm"; 17 -> "mmm-yy"
        18 -> "h:mm AM/PM"; 19 -> "h:mm:ss AM/PM"; 20 -> "h:mm"; 21 -> "h:mm:ss"; 22 -> "dd.mm.yyyy h:mm"
        37 -> "#,##0 ;(#,##0)"; 38 -> "#,##0 ;[Red](#,##0)"; 39 -> "#,##0.00;(#,##0.00)"; 40 -> "#,##0.00;[Red](#,##0.00)"
        45 -> "mm:ss"; 46 -> "[h]:mm:ss"; 47 -> "mm:ss.0"; 48 -> "##0.0E+0"; 49 -> "@"
        else -> null
    }

    private val cache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 256
    }

    @Synchronized private fun sections(code: String): List<String> = cache.getOrPut(code) { split(code.take(MAX_CODE)) }

    /** Sections separated by ";" outside quotes and brackets. */
    private fun split(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false; var bracket = false; var escape = false
        for (c in code) {
            when {
                escape -> { sb.append(c); escape = false; continue }
                c == '\\' && !quoted -> { sb.append(c); escape = true; continue }
                c == '"' -> quoted = !quoted
                c == '[' && !quoted -> bracket = true
                c == ']' && !quoted -> bracket = false
                c == ';' && !quoted && !bracket -> { out += sb.toString(); sb.clear(); continue }
            }
            sb.append(c)
        }
        out += sb.toString()
        return out.take(4)
    }

    fun format(value: Double, code: String, date1904: Boolean = false, locale: Locale = Locale.getDefault()): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        val secs = sections(code)
        var section = secs[0]
        var v = value
        if (secs.size >= 2 && value < 0) { section = secs[1]; v = -value }
        else if (secs.size >= 3 && value == 0.0) section = secs[2]
        // A condition like [<1000] is not evaluated: the first section is used
        val clean = section.replace(CONDITION, "")
        val signed = v * (if (value < 0 && secs.size >= 2) -1 else 1)
        if (clean.isBlank() || clean.equals("General", ignoreCase = true) || clean.trim() == "@") return general(signed, locale)
        // "General" with words around it: the words, and the number as General
        if (clean.contains("General", ignoreCase = true)) return literal(clean.replace(Regex("General", RegexOption.IGNORE_CASE), "\u0001")).replace("\u0001", general(signed, locale))
        if (isDate(clean)) return date(v, clean, date1904, locale) ?: general(v, locale)
        // Fractions are shown as ordinary numbers
        if (clean.contains('/') && clean.contains('?')) return general(signed, locale)
        return number(v, clean, locale, negativeSign = value < 0 && secs.size < 2)
    }

    /** The literal text of a format piece: quotes removed, escapes and padding resolved. */
    private fun literal(code: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }; sb.append(code, i + 1, end); i = end + 1; continue }
                c == '\\' && i + 1 < code.length -> { sb.append(code[i + 1]); i += 2; continue }
                c == '_' -> { sb.append(' '); i += 2; continue }
                c == '*' -> { i += 2; continue }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** "General": integers as they are, other numbers with up to ten significant digits. */
    fun general(v: Double, locale: Locale = Locale.getDefault()): String {
        val sym = DecimalFormatSymbols.getInstance(locale)
        if (v == floor(v) && abs(v) < 1e11) return v.toLong().toString().replace('-', '-')
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) return String.format(Locale.ROOT, "%.5E", v).replace(Regex("0+E"), "E").replace(".E", "E").replace('.', sym.decimalSeparator)
        // Excel shows a General number in at most 11 characters: "0.333333333"
        val digits = (10 - (if (a >= 1) floor(kotlin.math.log10(a)).toInt() + 1 else 1)).coerceIn(0, 10)
        // Rounded the way people see the number (2.675 → 2.68), not the exact binary value
        var s = java.math.BigDecimal(v).round(java.math.MathContext(15)).setScale(digits, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        if (s.contains('.')) s = s.replace('.', sym.decimalSeparator)
        return s
    }

    fun isDate(code: String): Boolean {
        var quoted = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> quoted = !quoted
                c == '\\' -> i++
                c == '[' && !quoted -> {
                    val end = code.indexOf(']', i)
                    val inner = if (end > i) code.substring(i + 1, end) else ""
                    if (inner.matches(Regex("[hHmMsS]+"))) return true
                    i = if (end > i) end else i
                }
                !quoted && c.lowercaseChar() in "ydhs" -> return true
                !quoted && c.lowercaseChar() == 'm' -> return true
            }
            i++
        }
        return false
    }

    private fun date(serial: Double, code: String, date1904: Boolean, locale: Locale): String? {
        if (serial < 0 || serial > 2_958_465) return null
        val days = floor(serial).toLong()
        var seconds = ((serial - days) * 86400).roundToLong()
        var d = days
        if (seconds >= 86400) { seconds -= 86400; d++ }
        val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 31)
        // Excel counts 29 February 1900, which did not exist
        val dateValue = if (!date1904 && d >= 60) base.plusDays(d - 1) else base.plusDays(d)
        val dt = LocalDateTime.of(dateValue, java.time.LocalTime.ofSecondOfDay(seconds.coerceIn(0, 86399)))
        val ampm = code.contains("AM/PM", ignoreCase = true) || code.contains("A/P", ignoreCase = true)
        // With a day, month names take the form used in dates ("8 січня"); alone, the plain name ("січень")
        val withDay = code.contains('d', ignoreCase = true)
        val out = StringBuilder()
        var i = 0
        var lastWasHour = false
        val lower = code.lowercase()
        while (i < code.length) {
            val c = code[i]
            val lc = c.lowercaseChar()
            fun run(): Int { var n = 0; while (i + n < code.length && code[i + n].lowercaseChar() == lc) n++; return n }
            when {
                c == '"' -> { val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }; out.append(code, i + 1, end); i = end + 1; continue }
                c == '\\' && i + 1 < code.length -> { out.append(code[i + 1]); i += 2; continue }
                c == '_' -> { out.append(' '); i += 2; continue }
                c == '*' -> { i += 2; continue }
                c == '[' -> {
                    val end = code.indexOf(']', i).let { if (it < 0) code.length else it }
                    val inner = code.substring(i + 1, end).lowercase()
                    val totalHours = d * 24 + seconds / 3600
                    when {
                        inner.startsWith("h") -> out.append(totalHours.toString().padStart(inner.length.coerceAtMost(10), '0'))
                        inner.startsWith("m") -> out.append((d * 1440 + seconds / 60).toString().padStart(inner.length.coerceAtMost(10), '0'))
                        inner.startsWith("s") -> out.append((d * 86400 + seconds).toString().padStart(inner.length.coerceAtMost(10), '0'))
                        inner.startsWith("$") -> out.append(inner.drop(1).substringBefore('-'))
                    }
                    i = end + 1; continue
                }
                lower.startsWith("am/pm", i) -> { out.append(if (dt.hour < 12) "AM" else "PM"); i += 5; continue }
                lower.startsWith("a/p", i) -> { out.append(if (dt.hour < 12) "A" else "P"); i += 3; continue }
                lc == 'y' -> { val n = run(); out.append(if (n <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString()); i += n; lastWasHour = false; continue }
                lc == 'd' -> {
                    val n = run()
                    out.append(when (n) { 1 -> dt.dayOfMonth.toString(); 2 -> dt.dayOfMonth.toString().padStart(2, '0'); 3 -> dt.dayOfWeek.getDisplayName(TextStyle.SHORT, locale); else -> dt.dayOfWeek.getDisplayName(TextStyle.FULL, locale) })
                    i += n; continue
                }
                lc == 'h' -> { val n = run(); val h = if (ampm) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour; out.append(if (n >= 2) h.toString().padStart(2, '0') else h.toString()); i += n; lastWasHour = true; continue }
                lc == 's' -> {
                    val n = run(); out.append(if (n >= 2) dt.second.toString().padStart(2, '0') else dt.second.toString()); i += n
                    // Tenths of a second: ".0"
                    if (i + 1 < code.length && code[i] == '.' && code[i + 1] == '0') { var z = 0; while (i + 1 + z < code.length && code[i + 1 + z] == '0') z++; out.append('.').append("0".repeat(z)); i += 1 + z }
                    continue
                }
                lc == 'm' -> {
                    val n = run()
                    // "m" right after hours or right before seconds means minutes
                    val rest = lower.substring(i + n).trimStart(' ', ':', '.')
                    val minutes = n <= 2 && (lastWasHour || rest.startsWith("s"))
                    if (minutes) out.append(if (n == 2) dt.minute.toString().padStart(2, '0') else dt.minute.toString())
                    else out.append(when (n) {
                        1 -> dt.monthValue.toString(); 2 -> dt.monthValue.toString().padStart(2, '0')
                        3 -> dt.month.getDisplayName(if (withDay) TextStyle.SHORT else TextStyle.SHORT_STANDALONE, locale)
                        5 -> dt.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                        else -> dt.month.getDisplayName(if (withDay) TextStyle.FULL else TextStyle.FULL_STANDALONE, locale)
                    })
                    i += n; lastWasHour = false; continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun number(v: Double, code: String, locale: Locale, negativeSign: Boolean): String {
        val sym = DecimalFormatSymbols.getInstance(locale)
        // Literal text before and after the digits; the digit pattern in the middle
        val prefix = StringBuilder(); val suffix = StringBuilder(); val pattern = StringBuilder()
        var i = 0
        var inDigits = false; var digitsDone = false
        while (i < code.length) {
            val c = code[i]
            val target = if (digitsDone) suffix else if (inDigits) pattern else prefix
            when {
                c == '"' -> { val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }; (if (inDigits) suffix.also { digitsDone = true } else target).append(code, i + 1, end); if (inDigits) inDigits = false; i = end + 1; continue }
                c == '\\' && i + 1 < code.length -> { (if (inDigits) suffix.also { digitsDone = true; inDigits = false } else target).append(code[i + 1]); i += 2; continue }
                c == '_' -> { (if (inDigits) suffix.also { digitsDone = true; inDigits = false } else target).append(' '); i += 2; continue }
                c == '*' -> { i += 2; continue }
                c == '[' -> {
                    val end = code.indexOf(']', i).let { if (it < 0) code.length else it }
                    val inner = code.substring(i + 1, end)
                    if (inner.startsWith("$")) (if (inDigits) suffix.also { digitsDone = true; inDigits = false } else target).append(inner.drop(1).substringBefore('-'))
                    i = end + 1; continue
                }
                !digitsDone && (c in "0#?,." || (inDigits && (c == 'E' || c == 'e' || ((c == '+' || c == '-') && pattern.lastOrNull()?.let { it == 'E' || it == 'e' } == true)))) -> { inDigits = true; pattern.append(c) }
                c == '%' -> { (if (inDigits || digitsDone) suffix.also { digitsDone = true; inDigits = false } else prefix).append('%') }
                else -> { if (inDigits) { inDigits = false; digitsDone = true }; (if (digitsDone) suffix else prefix).append(c) }
            }
            i++
        }
        var x = v
        val percents = (prefix.count { it == '%' } + suffix.count { it == '%' }).coerceAtMost(4)
        repeat(percents) { x *= 100 }
        if (!x.isFinite()) return "#NUM!"
        val p = pattern.toString()
        val body = if (p.contains('E', ignoreCase = true)) scientific(x, p, sym) else plain(x, p, sym)
        val sign = if (negativeSign && body.any { it.isDigit() && it != '0' }) "-" else ""
        return sign + prefix + body + suffix
    }

    private fun plain(x: Double, whole: String, sym: DecimalFormatSymbols): String {
        // Commas at the very end divide by a thousand each (also after decimals: "#,##0.0,,")
        val trailing = whole.takeLastWhile { it == ',' }.length
        val p = whole.dropLast(trailing)
        val dot = p.indexOf('.')
        val intPart = if (dot >= 0) p.substring(0, dot) else p
        val fracPart = if (dot >= 0) p.substring(dot + 1).filter { it in "0#?" } else ""
        var scaled = x
        repeat(trailing.coerceAtMost(4)) { scaled /= 1000 }
        val grouping = intPart.contains(',')
        val minInt = intPart.count { it == '0' }.coerceAtMost(40)
        val bd = java.math.BigDecimal(scaled).round(java.math.MathContext(15)).setScale(fracPart.length.coerceAtMost(30), java.math.RoundingMode.HALF_UP)
        val plainStr = bd.abs().toPlainString()
        var intDigits = plainStr.substringBefore('.')
        var frac = if (fracPart.isNotEmpty()) plainStr.substringAfter('.', "") else ""
        // Optional decimals (#) drop trailing zeros; required ones (0) keep them
        val required = fracPart.count { it == '0' }
        while (frac.length > required && frac.endsWith('0')) frac = frac.dropLast(1)
        if (intDigits == "0" && minInt == 0) intDigits = ""
        intDigits = intDigits.padStart(minInt, '0')
        if (grouping && intDigits.length > 3) {
            val sb = StringBuilder()
            for ((k, ch) in intDigits.withIndex()) { if (k > 0 && (intDigits.length - k) % 3 == 0) sb.append(sym.groupingSeparator); sb.append(ch) }
            intDigits = sb.toString()
        }
        // A pattern with no required digit ("#", "??") shows nothing for zero, as Excel does
        return if (frac.isNotEmpty()) intDigits + sym.decimalSeparator + frac else if (dot >= 0 && fracPart.isEmpty() && p.endsWith('.')) intDigits + sym.decimalSeparator else intDigits
    }

    private fun scientific(x: Double, p: String, sym: DecimalFormatSymbols): String {
        val mant = p.substringBefore('E').substringBefore('e')
        val decimals = mant.substringAfter('.', "").count { it in "0#?" }.coerceAtMost(30)
        if (!x.isFinite()) return "#NUM!"
        val s = String.format(Locale.ROOT, "%.${decimals}E", abs(x))
        val (m, e) = s.split('E')
        val exp = e.toInt()
        val expStr = (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(2, '0')
        return m.replace('.', sym.decimalSeparator) + "E" + expStr
    }
}

/** Column letters as Excel shows them: 0 → A, 26 → AA. */
fun columnName(col: Int): String {
    var n = col + 1
    val sb = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
    return sb.reverse().toString()
}

/** Column number from a reference like "B12" (or just "B"); -1 if none. */
fun columnOf(ref: String): Int {
    var col = 0
    var any = false
    for (c in ref) {
        if (c in 'A'..'Z') { col = col * 26 + (c - 'A' + 1); any = true } else if (c in 'a'..'z') { col = col * 26 + (c - 'a' + 1); any = true } else break
        if (col > SheetLimits.MAX_COLS) return -1
    }
    return if (any) col - 1 else -1
}

/** Row number from a reference like "B12"; -1 if none. */
fun rowOf(ref: String): Int {
    val digits = ref.dropWhile { it.isLetter() }
    return digits.toIntOrNull()?.let { it - 1 }?.takeIf { it in 0 until SheetLimits.MAX_ROWS } ?: -1
}
