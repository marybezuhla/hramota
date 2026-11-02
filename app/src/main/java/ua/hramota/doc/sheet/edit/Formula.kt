package ua.hramota.doc.sheet.edit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** A cell's value while calculating. */
sealed class Value {
    data class Num(val v: Double) : Value()
    data class Text(val v: String) : Value()
    data class Bool(val v: Boolean) : Value()
    data class Err(val code: String) : Value()
    data object Empty : Value()

    companion object {
        val DIV0 = Err("#DIV/0!"); val VALUE = Err("#VALUE!"); val REF = Err("#REF!"); val NAME = Err("#NAME?"); val NA = Err("#N/A"); val NUM = Err("#NUM!")
    }
}

/** A cell address: sheet (null: the formula's own), row and column from 0, and which parts are fixed with $. */
data class Ref(val sheet: String?, val row: Int, val col: Int, val absRow: Boolean = false, val absCol: Boolean = false)

/**
 * Excel formulas, enough for everyday tables: numbers, text, cell and range references (also on other
 * sheets), arithmetic, comparison, "&" and common functions. A formula using anything else is
 * [Parsed.supported] false: its value is left as the file has it, and Excel works it out when opened.
 */
object Formula {
    sealed class Node {
        data class N(val v: Double) : Node()
        data class S(val v: String) : Node()
        data class B(val v: Boolean) : Node()
        data class E(val code: String) : Node()
        /** An argument left out ("IF(A1,,1)"): an empty value, 0 in arithmetic. */
        data object Blank : Node()
        data class Cell(val ref: Ref) : Node()
        data class Range(val from: Ref, val to: Ref) : Node()
        data class Unary(val op: Char, val a: Node) : Node()
        data class Percent(val a: Node) : Node()
        data class Bin(val op: String, val a: Node, val b: Node) : Node()
        data class Call(val name: String, val args: List<Node>) : Node()
    }

    class Parsed(val node: Node?, val supported: Boolean)

    val FUNCTIONS = setOf(
        "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "COUNTBLANK", "PRODUCT", "ROUND", "ROUNDUP", "ROUNDDOWN", "INT", "ABS", "MOD",
        "POWER", "SQRT", "IF", "IFERROR", "AND", "OR", "NOT", "CONCATENATE", "CONCAT", "LEN", "UPPER", "LOWER", "TRIM", "SUMIF", "COUNTIF",
        "AVERAGEIF", "TRUE", "FALSE", "PI", "SIGN", "LEFT", "RIGHT", "MID",
    )

    private const val MAX_LEN = 8192
    private const val MAX_DEPTH = 64

    /** Parses formula text as Excel stores it (without "="). */
    fun parse(text: String): Parsed = try {
        if (text.length > MAX_LEN) Parsed(null, false) else {
            val p = Parser(text)
            val n = p.expr(0)
            p.skip()
            if (p.i != text.length) Parsed(null, false) else Parsed(n, p.supported)
        }
    } catch (e: Exception) { Parsed(null, false) }

    private class Parser(val s: String) {
        var i = 0
        var supported = true

        fun skip() { while (i < s.length && s[i] == ' ') i++ }

        // Precedence, low to high: comparison, &, + -, * /, ^
        private val levels = listOf(listOf("<>", "<=", ">=", "=", "<", ">"), listOf("&"), listOf("+", "-"), listOf("*", "/"), listOf("^"))

        fun expr(level: Int, depth: Int = 0): Node {
            if (depth > MAX_DEPTH) throw IllegalStateException("Too deep")
            if (level == levels.size) return unary(depth)
            var left = expr(level + 1, depth + 1)
            while (true) {
                skip()
                val op = levels[level].firstOrNull { s.startsWith(it, i) } ?: return left
                i += op.length
                val right = expr(level + 1, depth + 1)
                left = Node.Bin(op, left, right)
            }
        }

        private fun unary(depth: Int): Node {
            skip()
            if (i < s.length && (s[i] == '-' || s[i] == '+')) { val op = s[i]; i++; return Node.Unary(op, unary(depth + 1)) }
            var n = primary(depth)
            skip()
            while (i < s.length && s[i] == '%') { i++; n = Node.Percent(n); skip() }
            return n
        }

        private fun primary(depth: Int): Node {
            skip()
            if (i >= s.length) throw IllegalStateException("End")
            val c = s[i]
            when {
                c == '(' -> { i++; val n = expr(0, depth + 1); skip(); expect(')'); return n }
                c == '"' -> {
                    val b = StringBuilder(); i++
                    while (true) {
                        if (i >= s.length) throw IllegalStateException("Open string")
                        if (s[i] == '"') { if (i + 1 < s.length && s[i + 1] == '"') { b.append('"'); i += 2; continue }; i++; break }
                        b.append(s[i]); i++
                    }
                    return Node.S(b.toString())
                }
                c == '#' -> {
                    val m = Regex("#(NULL!|DIV/0!|VALUE!|REF!|NAME\\?|NUM!|N/A)").matchAt(s, i) ?: throw IllegalStateException("Error")
                    i += m.value.length; return Node.E(m.value)
                }
                c.isDigit() || c == '.' -> {
                    val m = Regex("[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?|[0-9]+\\.").matchAt(s, i) ?: throw IllegalStateException("Number")
                    i += m.value.length
                    return Node.N(m.value.toDouble())
                }
                c == '\'' || c.isLetter() || c == '$' || c == '_' -> return nameOrRef(depth)
                else -> throw IllegalStateException("Unexpected $c")
            }
        }

        private fun expect(c: Char) { if (i < s.length && s[i] == c) i++ else throw IllegalStateException("Expected $c") }

        private val cellRe = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})")

        private fun nameOrRef(depth: Int): Node {
            val start = i
            var sheet: String? = null
            if (s[i] == '\'') {
                val b = StringBuilder(); i++
                while (true) {
                    if (i >= s.length) throw IllegalStateException("Open name")
                    if (s[i] == '\'') { if (i + 1 < s.length && s[i + 1] == '\'') { b.append('\''); i += 2; continue }; i++; break }
                    b.append(s[i]); i++
                }
                expect('!'); sheet = b.toString()
            } else {
                val m = Regex("[A-Za-z_\\p{L}][\\w.\\p{L}]*!").matchAt(s, i)
                if (m != null) { sheet = m.value.dropLast(1); i += m.value.length }
            }
            val cell = cellRe.matchAt(s, i)
            if (cell != null && (i + cell.value.length >= s.length || !(s[i + cell.value.length].isLetterOrDigit() || s[i + cell.value.length] == '(' || s[i + cell.value.length] == '_'))) {
                i += cell.value.length
                val a = ref(sheet, cell)
                if (i < s.length && s[i] == ':') {
                    val m2 = cellRe.matchAt(s, i + 1) ?: throw IllegalStateException("Range")
                    i += 1 + m2.value.length
                    return Node.Range(a, ref(sheet, m2))
                }
                return Node.Cell(a)
            }
            if (sheet != null) { supported = false; i = s.length; return Node.E("#REF!") }
            val m = Regex("[A-Za-z_][A-Za-z0-9_.]*").matchAt(s, i) ?: throw IllegalStateException("Name at $start")
            val name = m.value.uppercase()
            i += m.value.length
            skip()
            if (i < s.length && s[i] == '(') {
                i++
                val args = ArrayList<Node>()
                skip()
                if (i < s.length && s[i] == ')') { i++ } else {
                    while (true) {
                        skip()
                        // An empty argument ("IF(A1,,1)") is an empty value
                        if (i < s.length && (s[i] == ',' || s[i] == ')')) args += Node.Blank else args += expr(0, depth + 1)
                        skip()
                        if (i < s.length && s[i] == ',') { i++; continue }
                        expect(')'); break
                    }
                }
                val n = name.removePrefix("_XLFN.")
                if (n !in FUNCTIONS) supported = false
                return Node.Call(n, args)
            }
            return when (name) {
                "TRUE" -> Node.B(true)
                "FALSE" -> Node.B(false)
                // A defined name, a table reference…: not worked out here
                else -> { supported = false; Node.E("#NAME?") }
            }
        }

        private fun ref(sheet: String?, m: MatchResult): Ref {
            val col = colIndex(m.groupValues[2].uppercase())
            val row = m.groupValues[4].toInt() - 1
            if (col !in 0 until 16_384 || row !in 0 until 1_048_576) throw IllegalStateException("Out of sheet")
            return Ref(sheet, row, col, absRow = m.groupValues[3] == "$", absCol = m.groupValues[1] == "$")
        }
    }

    fun colIndex(letters: String): Int { var n = 0; for (ch in letters) n = n * 26 + (ch - 'A' + 1); return n - 1 }

    /**
     * Formula text as people type it (after "="): ";" between arguments and "," in numbers, as in
     * Ukrainian Excel, become Excel's stored form.
     */
    fun normalize(typed: String): String {
        // Written as stored (commas between arguments) and readable so: kept; otherwise commas are decimal marks
        if (';' !in typed && parse(typed.trim()).node != null) return typed.trim()
        val b = StringBuilder()
        var inString = false
        for (c in typed.trim()) {
            if (c == '"') inString = !inString
            b.append(if (inString) c else when (c) { ';' -> ','; ',' -> '.'; else -> c })
        }
        return b.toString()
    }

    /**
     * The formula of a cell that shares the formula of [master] (written at [from]), moved to [to]:
     * references without "$" move by the same rows and columns.
     */
    fun shift(text: String, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): String {
        val dr = toRow - fromRow; val dc = toCol - fromCol
        if (dr == 0 && dc == 0) return text
        val b = StringBuilder()
        var i = 0
        val re = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})")
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                // Strings and quoted sheet names are copied as they are
                val q = c; b.append(c); i++
                while (i < text.length) { b.append(text[i]); if (text[i] == q) { if (i + 1 < text.length && text[i + 1] == q) { b.append(q); i += 2; continue }; i++; break }; i++ }
                continue
            }
            val prevOk = i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '.')
            val m = if (prevOk) re.matchAt(text, i) else null
            val end = m?.let { i + it.value.length }
            if (m != null && (end!! >= text.length || !(text[end].isLetterOrDigit() || text[end] == '(' || text[end] == '_'))) {
                val col = if (m.groupValues[1] == "$") colIndex(m.groupValues[2].uppercase()) else colIndex(m.groupValues[2].uppercase()) + dc
                val row = if (m.groupValues[3] == "$") m.groupValues[4].toInt() - 1 else m.groupValues[4].toInt() - 1 + dr
                if (col < 0 || row < 0 || col >= 16_384 || row >= 1_048_576) b.append("#REF!") else b.append(m.groupValues[1]).append(ua.hramota.doc.sheet.columnName(col)).append(m.groupValues[3]).append(row + 1)
                i = end
                continue
            }
            // A name (function or sheet) is copied whole, so "LOG10" or "Sheet1" is not taken for a reference
            if (c.isLetter() || c == '_') {
                var e = i
                while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_' || text[e] == '.')) e++
                if (e < text.length && (text[e] == '(' || text[e] == '!')) { b.append(text, i, e); i = e; continue }
            }
            b.append(c); i++
        }
        return b.toString()
    }

    /** Rows inserted ([n] > 0) or deleted ([n] < 0) at row [at] (from 0), as Excel moves what refers to them. */
    class RowMove(val at: Int, val n: Int) {
        /** The row's new number; null when it was deleted. */
        fun row(r: Int): Int? = when {
            r < at -> r
            n > 0 -> (r + n).takeIf { it < MAX_ROWS }
            r < at - n -> null
            else -> r + n
        }

        /** A range of rows afterwards: it grows or shrinks with rows inside it; null when all of it was deleted. */
        fun range(top: Int, bottom: Int): Pair<Int, Int>? {
            if (n > 0) return (if (top >= at) top + n else top).coerceAtMost(MAX_ROWS - 1) to (if (bottom >= at) bottom + n else bottom).coerceAtMost(MAX_ROWS - 1)
            val end = at - n
            val t = when { top < at -> top; top >= end -> top + n; else -> at }
            val b = when { bottom < at -> bottom; bottom >= end -> bottom + n; else -> at - 1 }
            return if (t > b) null else t to b
        }
    }

    private const val MAX_ROWS = 1_048_576
    private val CELL_RANGE = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})(?::(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7}))?")
    private val ROW_RANGE = Regex("(\\$?)([0-9]{1,7}):(\\$?)([0-9]{1,7})")
    private val COL_RANGE = Regex("\\$?[A-Za-z]{1,3}:\\$?[A-Za-z]{1,3}")

    /**
     * The formula after rows moved on sheet [target]: references to it (and, when [own] is true, those
     * without a sheet) follow their rows; one wholly deleted becomes #REF!. Null when the formula has
     * references this cannot follow (several sheets at once).
     */
    fun moveRows(text: String, own: Boolean, target: String, move: RowMove): String? {
        val b = StringBuilder(text.length + 8)
        var i = 0
        var sheet: String? = null   // the sheet named just before (for the reference that follows)
        var external = false
        fun boundaryAfter(end: Int) = end >= text.length || !(text[end].isLetterOrDigit() || text[end] == '(' || text[end] == '_' || text[end] == '.')
        while (i < text.length) {
            val c = text[i]
            val named = sheet
            val ext = external
            sheet = null; external = false
            when {
                c == '"' -> {
                    b.append(c); i++
                    while (i < text.length) { b.append(text[i]); if (text[i] == '"') { if (i + 1 < text.length && text[i + 1] == '"') { b.append('"'); i += 2; continue }; i++; break }; i++ }
                    continue
                }
                c == '\'' -> {
                    // A quoted sheet name: 'Мій аркуш'!A1 (or '[1]Аркуш'!A1, in another workbook)
                    val sb = StringBuilder(); var j = i + 1
                    while (j < text.length) { if (text[j] == '\'') { if (j + 1 < text.length && text[j + 1] == '\'') { sb.append('\''); j += 2; continue }; break }; sb.append(text[j]); j++ }
                    b.append(text, i, minOf(j + 1, text.length)); i = j + 1
                    if (i < text.length && text[i] == '!') {
                        val name = sb.toString()
                        if (name.contains(':')) return null
                        b.append('!'); i++
                        sheet = name.substringAfter(']'); external = name.startsWith("[")
                    }
                    continue
                }
                c == '[' -> {
                    // A table's columns (Таблиця1[[#This Row],[Сума]]) or another workbook ([1]Аркуш!A1)
                    var depth = 0; var j = i
                    while (j < text.length) { if (text[j] == '[') depth++ else if (text[j] == ']') { depth--; if (depth == 0) break }; j++ }
                    val inner = text.substring(i + 1, minOf(j, text.length))
                    b.append(text, i, minOf(j + 1, text.length)); i = j + 1
                    if (inner.isNotEmpty() && inner.all { it.isDigit() }) external = true
                    continue
                }
                c == '#' -> {
                    // An error written in the formula (#REF!, #N/A): not a sheet's name
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '/' || text[j] == '_')) j++
                    if (j < text.length && (text[j] == '!' || text[j] == '?')) j++
                    b.append(text, i, j); i = j
                    continue
                }
            }
            val prevOk = named != null || i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '.' || text[i - 1] == '\'' || text[i - 1] == ']')
            val applies = if (named != null) !ext && named.equals(target, ignoreCase = true) else own && !ext
            if (prevOk) {
                val cr = COL_RANGE.matchAt(text, i)
                if (cr != null && boundaryAfter(i + cr.value.length)) { b.append(cr.value); i += cr.value.length; continue }
                val rr = ROW_RANGE.matchAt(text, i)
                if (rr != null && boundaryAfter(i + rr.value.length) && (i == 0 || !text[i - 1].isDigit())) {
                    if (!applies) b.append(rr.value) else {
                        val g = rr.groupValues
                        val top = g[2].toInt() - 1; val bottom = g[4].toInt() - 1
                        val moved = move.range(minOf(top, bottom), maxOf(top, bottom))
                        if (moved == null) b.append("#REF!") else b.append(g[1]).append(moved.first + 1).append(':').append(g[3]).append(moved.second + 1)
                    }
                    i += rr.value.length
                    continue
                }
                val m = CELL_RANGE.matchAt(text, i)
                if (m != null && boundaryAfter(i + m.value.length)) {
                    val g = m.groupValues
                    if (!applies) b.append(m.value)
                    else if (g[6].isEmpty()) {
                        val r = move.row(g[4].toInt() - 1)
                        if (r == null) b.append("#REF!") else b.append(g[1]).append(g[2]).append(g[3]).append(r + 1)
                    } else {
                        val r1 = g[4].toInt() - 1; val r2 = g[8].toInt() - 1
                        val moved = move.range(minOf(r1, r2), maxOf(r1, r2))
                        if (moved == null) b.append("#REF!")
                        else {
                            val (t, bt) = if (r1 <= r2) moved else moved.second to moved.first
                            b.append(g[1]).append(g[2]).append(g[3]).append(t + 1).append(':').append(g[5]).append(g[6]).append(g[7]).append(bt + 1)
                        }
                    }
                    i += m.value.length
                    continue
                }
            }
            // A name: a function, a sheet before "!", or a defined name; copied whole
            if (c.isLetter() || c == '_' || c == '\\') {
                var e = i
                while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_' || text[e] == '.' || text[e] == '\\')) e++
                b.append(text, i, e)
                if (e < text.length && text[e] == '!') { sheet = text.substring(i, e); external = ext; b.append('!'); e++ }
                // Several sheets at once (Аркуш1:Аркуш3!A1)
                else if (e < text.length && text[e] == ':' && Regex("[^\\s!:()]+!").matchAt(text, e + 1) != null && !CELL_RANGE.matchesAt(text, e + 1)) return null
                i = e
                continue
            }
            b.append(c); i++
        }
        return b.toString()
    }

    /** Where a calculation gets cell values (other cells' formulas are worked out on the way). */
    interface Cells {
        fun value(sheet: String?, row: Int, col: Int): Value
    }

    private class Ctx(val cells: Cells, var budget: Int)

    /** Works out [node]. A range where one value is expected gives #VALUE!. */
    fun eval(node: Node, cells: Cells): Value = try { scalar(node, Ctx(cells, 2_000_000)) } catch (e: Budget) { Value.NUM } catch (e: Exception) { Value.VALUE }

    private class Budget : RuntimeException()

    private fun scalar(n: Node, c: Ctx): Value {
        if (--c.budget < 0) throw Budget()
        return when (n) {
            is Node.N -> Value.Num(n.v)
            is Node.S -> Value.Text(n.v)
            is Node.B -> Value.Bool(n.v)
            is Node.E -> Value.Err(n.code)
            is Node.Blank -> Value.Empty
            is Node.Cell -> c.cells.value(n.ref.sheet, n.ref.row, n.ref.col)
            is Node.Range -> Value.VALUE
            is Node.Unary -> when (val v = num(scalar(n.a, c))) { is Value.Num -> if (n.op == '-') Value.Num(-v.v) else v; else -> v }
            is Node.Percent -> when (val v = num(scalar(n.a, c))) { is Value.Num -> Value.Num(v.v / 100); else -> v }
            is Node.Bin -> bin(n.op, scalar(n.a, c), scalar(n.b, c))
            is Node.Call -> call(n.name, n.args, c)
        }
    }

    /** All values of an argument: a range gives each cell, anything else one value. */
    private fun values(n: Node, c: Ctx): Sequence<Pair<Value, Boolean>> = when (n) {
        is Node.Range -> sequence {
            val top = minOf(n.from.row, n.to.row); val bottom = maxOf(n.from.row, n.to.row)
            val left = minOf(n.from.col, n.to.col); val right = maxOf(n.from.col, n.to.col)
            // Whole columns or rows go only as far as cells are kept
            if ((bottom - top + 1).toLong() * (right - left + 1) > 1_000_000) throw Budget()
            for (r in top..bottom) for (k in left..right) {
                if (--c.budget < 0) throw Budget()
                yield(c.cells.value(n.from.sheet, r, k) to true)
            }
        }
        // A cell referred to counts as Excel counts a range: text and empty cells are left out
        is Node.Cell -> sequenceOf(scalar(n, c) to true)
        else -> sequenceOf(scalar(n, c) to false)
    }

    private fun num(v: Value): Value = when (v) {
        is Value.Num, is Value.Err -> v
        is Value.Bool -> Value.Num(if (v.v) 1.0 else 0.0)
        is Value.Empty -> Value.Num(0.0)
        is Value.Text -> parseNumber(v.v)?.let { Value.Num(it) } ?: Value.VALUE
    }

    private fun text(v: Value): String = when (v) {
        is Value.Text -> v.v
        is Value.Num -> show(v.v)
        is Value.Bool -> if (v.v) "TRUE" else "FALSE"
        is Value.Empty -> ""
        is Value.Err -> v.code
    }

    /** A number as Excel's General format writes it into text. */
    fun show(d: Double): String {
        if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
        val s = "%.15g".format(java.util.Locale.ROOT, d)
        // Trailing zeros are dropped from the digits only, never from an exponent
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = (if (e >= 0) s.substring(0, e) else s).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
        return if (e >= 0) mantissa + "E" + s.substring(e + 1).let { x -> val sign = if (x.startsWith("-")) "-" else "+"; sign + x.trimStart('+', '-').trimStart('0').padStart(2, '0') } else mantissa
    }

    private fun truth(v: Value): Value = when (v) {
        is Value.Bool, is Value.Err -> v
        is Value.Num -> Value.Bool(v.v != 0.0)
        is Value.Empty -> Value.Bool(false)
        is Value.Text -> when (v.v.uppercase()) { "TRUE" -> Value.Bool(true); "FALSE" -> Value.Bool(false); else -> Value.VALUE }
    }

    private fun bin(op: String, a: Value, b: Value): Value {
        if (a is Value.Err) return a
        if (b is Value.Err) return b
        if (op == "&") return Value.Text(text(a) + text(b))
        if (op in setOf("=", "<>", "<", ">", "<=", ">=")) {
            val cmp = compare(a, b)
            return Value.Bool(when (op) { "=" -> cmp == 0; "<>" -> cmp != 0; "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; else -> cmp >= 0 })
        }
        val x = num(a); val y = num(b)
        if (x is Value.Err) return x
        if (y is Value.Err) return y
        val p = (x as Value.Num).v; val q = (y as Value.Num).v
        val r = when (op) {
            "+" -> p + q; "-" -> p - q; "*" -> p * q
            "/" -> if (q == 0.0) return Value.DIV0 else p / q
            "^" -> p.pow(q)
            else -> return Value.VALUE
        }
        return if (r.isNaN() || r.isInfinite()) Value.NUM else Value.Num(r)
    }

    /** Excel's order: numbers, then text (ignoring case), then FALSE, TRUE; empty is 0 or "". */
    private fun compare(a: Value, b: Value): Int {
        fun rank(v: Value) = when (v) { is Value.Num -> 0; is Value.Text -> 1; is Value.Bool -> 2; else -> 0 }
        val x = if (a is Value.Empty) (if (b is Value.Text) Value.Text("") else if (b is Value.Bool) Value.Bool(false) else Value.Num(0.0)) else a
        val y = if (b is Value.Empty) (if (x is Value.Text) Value.Text("") else if (x is Value.Bool) Value.Bool(false) else Value.Num(0.0)) else b
        if (rank(x) != rank(y)) return rank(x).compareTo(rank(y))
        return when {
            x is Value.Num && y is Value.Num -> x.v.compareTo(y.v)
            x is Value.Text && y is Value.Text -> x.v.lowercase().compareTo(y.v.lowercase())
            x is Value.Bool && y is Value.Bool -> x.v.compareTo(y.v)
            else -> 0
        }
    }

    /** Numbers in the arguments: from ranges only real numbers count; typed values are converted. */
    private fun numbers(args: List<Node>, c: Ctx): Pair<List<Double>, Value.Err?> {
        val out = ArrayList<Double>()
        for (a in args) for ((v, fromRange) in values(a, c)) {
            if (v is Value.Err) return out to v
            if (fromRange) { if (v is Value.Num) out += v.v }
            else when (val n = num(v)) { is Value.Num -> out += n.v; is Value.Err -> return out to n; else -> {} }
        }
        return out to null
    }

    private fun criterion(crit: Value): (Value) -> Boolean {
        val s = text(crit)
        val m = Regex("^(<=|>=|<>|<|>|=)?(.*)$").find(s)!!
        val op = m.groupValues[1].ifEmpty { "=" }
        val rest = m.groupValues[2]
        val target: Value = parseNumber(rest)?.let { Value.Num(it) } ?: Value.Text(rest)
        return { v ->
            if (target is Value.Text && op == "=" && ('*' in rest || '?' in rest)) {
                val re = Regex(rest.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
                re.matches(text(v))
            } else if (target is Value.Num && v !is Value.Num) op == "<>"
            else {
                val cmp = compare(if (v is Value.Empty && target is Value.Text) Value.Text("") else v, target)
                when (op) { "=" -> cmp == 0 && (v !is Value.Empty || rest.isEmpty()); "<>" -> cmp != 0; "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; else -> cmp >= 0 }
            }
        }
    }

    private fun round(x: Double, digits: Int, mode: Int): Double {
        val f = 10.0.pow(digits)
        val v = x * f
        // Rounding is decided on the decimal value people see, not the binary fraction (2.675 → 2.68)
        val adj = java.math.BigDecimal(show(v)).toDouble()
        val r = when (mode) {
            0 -> java.math.BigDecimal(adj).setScale(0, java.math.RoundingMode.HALF_UP).toDouble()
            // Away from zero, and towards it, on the decimal value
            1 -> java.math.BigDecimal(show(v)).setScale(0, java.math.RoundingMode.UP).toDouble()
            else -> java.math.BigDecimal(show(v)).setScale(0, java.math.RoundingMode.DOWN).toDouble()
        }
        return r / f
    }

    private fun call(name: String, args: List<Node>, c: Ctx): Value {
        fun arg(k: Int): Value = if (k < args.size) scalar(args[k], c) else Value.Empty
        fun n(k: Int): Value = num(arg(k))
        fun need(min: Int, max: Int = min) = args.size in min..max
        return when (name) {
            "SUM" -> numbers(args, c).let { (v, e) -> e ?: Value.Num(v.sum()) }
            "PRODUCT" -> numbers(args, c).let { (v, e) -> e ?: Value.Num(if (v.isEmpty()) 0.0 else v.fold(1.0) { a, b -> a * b }) }
            "AVERAGE" -> numbers(args, c).let { (v, e) -> e ?: if (v.isEmpty()) Value.DIV0 else Value.Num(v.average()) }
            "MIN" -> numbers(args, c).let { (v, e) -> e ?: Value.Num(v.minOrNull() ?: 0.0) }
            "MAX" -> numbers(args, c).let { (v, e) -> e ?: Value.Num(v.maxOrNull() ?: 0.0) }
            "COUNT" -> Value.Num(args.sumOf { a -> values(a, c).count { (v, r) -> v is Value.Num || (!r && num(v) is Value.Num) } }.toDouble())
            "COUNTA" -> Value.Num(args.sumOf { a -> values(a, c).count { (v, _) -> v !is Value.Empty } }.toDouble())
            "COUNTBLANK" -> Value.Num(args.sumOf { a -> values(a, c).count { (v, _) -> v is Value.Empty || (v is Value.Text && v.v.isEmpty()) } }.toDouble())
            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                if (!need(2)) return Value.VALUE
                val x = n(0); val d = n(1)
                if (x !is Value.Num) return x
                if (d !is Value.Num) return d
                Value.Num(round(x.v, d.v.toInt().coerceIn(-15, 15), when (name) { "ROUND" -> 0; "ROUNDUP" -> 1; else -> 2 }))
            }
            "INT" -> when (val x = n(0)) { is Value.Num -> Value.Num(floor(x.v)); else -> x }
            "ABS" -> when (val x = n(0)) { is Value.Num -> Value.Num(abs(x.v)); else -> x }
            "SIGN" -> when (val x = n(0)) { is Value.Num -> Value.Num(kotlin.math.sign(x.v)); else -> x }
            "SQRT" -> when (val x = n(0)) { is Value.Num -> if (x.v < 0) Value.NUM else Value.Num(sqrt(x.v)); else -> x }
            "MOD" -> {
                val x = n(0); val y = n(1)
                if (x !is Value.Num) return x
                if (y !is Value.Num) return y
                if (y.v == 0.0) Value.DIV0 else Value.Num(x.v - y.v * floor(x.v / y.v))
            }
            "POWER" -> bin("^", arg(0), arg(1))
            "PI" -> Value.Num(Math.PI)
            "TRUE" -> Value.Bool(true)
            "FALSE" -> Value.Bool(false)
            "IF" -> {
                if (!need(1, 3)) return Value.VALUE
                when (val t = truth(arg(0))) {
                    is Value.Bool -> if (t.v) (if (args.size > 1) arg(1) else Value.Bool(true)) else (if (args.size > 2) arg(2) else Value.Bool(false))
                    else -> t
                }
            }
            "IFERROR" -> { val v = arg(0); if (v is Value.Err) arg(1) else v }
            "AND", "OR" -> {
                var acc: Boolean? = null
                for (a in args) for ((v, r) in values(a, c)) {
                    if (v is Value.Err) return v
                    if (r && (v is Value.Empty || v is Value.Text)) continue
                    val t = truth(v) as? Value.Bool ?: return Value.VALUE
                    acc = if (name == "AND") (acc ?: true) && t.v else (acc ?: false) || t.v
                }
                acc?.let { Value.Bool(it) } ?: Value.VALUE
            }
            "NOT" -> when (val t = truth(arg(0))) { is Value.Bool -> Value.Bool(!t.v); else -> t }
            "CONCATENATE", "CONCAT" -> {
                val b = StringBuilder()
                for (a in args) for ((v, _) in values(a, c)) { if (v is Value.Err) return v; b.append(text(v)); if (b.length > 32_767) return Value.VALUE }
                Value.Text(b.toString())
            }
            "LEN" -> when (val v = arg(0)) { is Value.Err -> v; else -> Value.Num(text(v).length.toDouble()) }
            "UPPER" -> when (val v = arg(0)) { is Value.Err -> v; else -> Value.Text(text(v).uppercase()) }
            "LOWER" -> when (val v = arg(0)) { is Value.Err -> v; else -> Value.Text(text(v).lowercase()) }
            "TRIM" -> when (val v = arg(0)) { is Value.Err -> v; else -> Value.Text(text(v).trim().replace(Regex(" +"), " ")) }
            "LEFT", "RIGHT" -> {
                val v = arg(0); if (v is Value.Err) return v
                val k = if (args.size > 1) (n(1) as? Value.Num)?.v?.toInt() ?: return Value.VALUE else 1
                if (k < 0) return Value.VALUE
                val t = text(v)
                Value.Text(if (name == "LEFT") t.take(k) else t.takeLast(k))
            }
            "MID" -> {
                val v = arg(0); if (v is Value.Err) return v
                val from = (n(1) as? Value.Num)?.v?.toInt() ?: return Value.VALUE
                val k = (n(2) as? Value.Num)?.v?.toInt() ?: return Value.VALUE
                if (from < 1 || k < 0) return Value.VALUE
                Value.Text(text(v).drop(from - 1).take(k))
            }
            "SUMIF", "COUNTIF", "AVERAGEIF" -> {
                if (args.isEmpty()) return Value.VALUE
                val range = args[0] as? Node.Range ?: return Value.VALUE
                val test = criterion(arg(1))
                val checked = values(range, c).map { it.first }.toList()
                if (name == "COUNTIF") return Value.Num(checked.count(test).toDouble())
                val sumRange = (args.getOrNull(2) as? Node.Range)?.let { s ->
                    // The sum range takes the shape of the checked one, from its own top left
                    val h = maxOf(range.from.row, range.to.row) - minOf(range.from.row, range.to.row)
                    val w = maxOf(range.from.col, range.to.col) - minOf(range.from.col, range.to.col)
                    val top = minOf(s.from.row, s.to.row); val left = minOf(s.from.col, s.to.col)
                    values(Node.Range(s.from.copy(row = top, col = left), s.from.copy(row = top + h, col = left + w)), c).map { it.first }.toList()
                } ?: checked
                val picked = checked.indices.filter { test(checked[it]) }.mapNotNull { (sumRange.getOrNull(it) as? Value.Num)?.v }
                if (name == "SUMIF") Value.Num(picked.sum()) else if (picked.isEmpty()) Value.DIV0 else Value.Num(picked.average())
            }
            else -> Value.NAME
        }
    }

    /**
     * A number as people type it: "1 234,5", "1234.5", "-7", "15%", "1,5e3". Null if it is not one.
     * A comma is the decimal mark (as in Ukraine) unless the text also has a dot.
     */
    fun parseNumber(typed: String): Double? {
        var t = typed.trim().replace(' ', ' ').replace(' ', ' ').replace('−', '-')
        if (t.isEmpty() || t.length > 40) return null
        var percent = false
        if (t.endsWith("%")) { percent = true; t = t.dropLast(1).trim() }
        t = t.replace(" ", "")
        // Both marks: the last one is the decimal mark ("1.234,5" and "1,234.5"); a comma alone is decimal
        t = when {
            '.' in t && ',' in t -> if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            else -> t.replace(',', '.')
        }
        if (!Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?").matches(t)) return null
        val v = t.toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return if (percent) v / 100 else v
    }
}

/** Functions newer than Excel 2007 are stored with this prefix (Excel shows #NAME? without it). */
internal val NEW_FUNCTIONS = setOf("CONCAT")

/** Formula text for the file: newer functions get Excel's prefix. */
internal fun Formula.stored(text: String): String = NEW_FUNCTIONS.fold(text) { t, f -> t.replace(Regex("(?<![\\w.])$f\\(", RegexOption.IGNORE_CASE), "_xlfn.$f(") }

/**
 * Formula text as shown to type over: without Excel's prefixes, and, where the decimal mark is a comma,
 * with ";" between arguments and "," in numbers (strings and sheet names as they are).
 */
internal fun Formula.typed(text: String, decimalComma: Boolean): String {
    val t = text.replace("_xlfn.", "", ignoreCase = true)
    if (!decimalComma) return t
    val b = StringBuilder()
    var quote = 0.toChar()
    for ((i, c) in t.withIndex()) {
        if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); b.append(c); continue }
        when {
            c == '"' || c == '\'' -> { quote = c; b.append(c) }
            c == ',' -> b.append(';')
            c == '.' && i > 0 && t[i - 1].isDigit() && i + 1 < t.length && t[i + 1].isDigit() -> b.append(',')
            else -> b.append(c)
        }
    }
    return b.toString()
}
