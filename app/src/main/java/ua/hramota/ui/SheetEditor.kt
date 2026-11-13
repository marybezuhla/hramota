package ua.hramota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.hramota.EditSaving
import ua.hramota.R
import ua.hramota.doc.sheet.NumberFormat
import ua.hramota.doc.sheet.Sheet
import ua.hramota.doc.sheet.SheetRow
import ua.hramota.doc.sheet.Workbook
import ua.hramota.doc.sheet.edit.CellKey
import ua.hramota.doc.sheet.edit.CellLook
import ua.hramota.doc.sheet.edit.Input
import ua.hramota.doc.sheet.edit.Value
import ua.hramota.doc.sheet.edit.XlsxEdit
import java.io.InputStream
import java.io.OutputStream

/**
 * A workbook being edited: what is shown (the file's workbook with the changed cells and the
 * formulas depending on them worked out again), and what was done, to undo and redo.
 */
class SheetEditorState(val edit: XlsxEdit, private var original: Workbook) {
    var workbook by mutableStateOf(original)
        private set
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Bumped on each change; the screen works the values out again for it. */
    var version by mutableIntStateOf(0)
        private set
    /** Rows being inserted or deleted (the cells wait meanwhile). */
    var busy by mutableStateOf(false)
        private set
    /** What to tell the person (rows could not be changed), once. */
    var notice by mutableStateOf<Int?>(null)

    // Shown sheets (the file's visible ones) in the editor's numbering of all sheets
    private val shownSheets: List<Int> = original.sheets.map { s -> edit.sheets.indexOfFirst { it.visible && it.name == s.name } }

    private sealed class Step
    private class CellStep(val key: CellKey, val before: Input?, val after: Input?) : Step()
    /** Looks of cells changed (several at once when sorting): before and after. */
    private class LookStep(val looks: List<Triple<CellKey, CellLook?, CellLook?>>) : Step()
    private class WidthStep(val sheet: Int, val col: Int, val before: Float?, val after: Float?) : Step()
    /** Sorting: cells and looks moved together, one step. */
    private class SortStep(val cells: CellsStep, val looks: LookStep) : Step()
    /** Several cells changed at once ("replace all"): one step. */
    private class CellsStep(val steps: List<CellStep>) : Step()
    /** Rows inserted or deleted: the whole workbook before and after (a few kept, they can be large). */
    private class RowStep(val before: XlsxEdit.Snapshot, val after: XlsxEdit.Snapshot, val shownBefore: Workbook, val shownAfter: Workbook) : Step()
    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()

    fun content(sheet: Int, row: Int, col: Int): String {
        // Rows are being moved (the workbook is held meanwhile): the screen does not wait for it
        if (busy) return ""
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return ""
        return edit.content(CellKey(s, row, col))
    }

    private class Draft(val sheet: Int, val row: Int, val col: Int, val text: String)
    private var draft: Draft? = null

    /** What is being typed in a cell (not put in yet). */
    fun draft(sheet: Int, row: Int, col: Int, text: String) {
        draft = Draft(sheet, row, col, text)
        typing = text != content(sheet, row, col)
    }

    /** ✕: what was typed is not put in. */
    fun discard() { draft = null; typing = false }

    /** Something typed and not put in yet (saving is offered for it too). */
    var typing by mutableStateOf(false)
        private set

    /** Puts in what was being typed (the person went to another cell, saves or leaves); a formula that cannot be read stays out. */
    fun flush() {
        val d = draft ?: return
        draft = null; typing = false
        if (busy) return
        if (d.text != content(d.sheet, d.row, d.col)) commit(d.sheet, d.row, d.col, d.text)
    }

    /** Puts what was typed into a cell; false when the cell cannot be changed here. */
    fun commit(sheet: Int, row: Int, col: Int, typed: String): Boolean {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return false
        val key = CellKey(s, row, col)
        if (busy || edit.locked(key)) return false
        draft = null; typing = false
        // Typed exactly what is there: nothing to change
        if (typed == edit.content(key)) return true
        val step = put(sheet, key, typed) ?: return false
        push(step)
        changed()
        return true
    }

    /** Sets a cell (not yet a step to undo); null when what is typed cannot be taken. */
    private fun put(sheet: Int, key: CellKey, typed: String): CellStep? {
        val shown = original.sheets[sheet]
        val styleIndex = edit.style(key) ?: shown.row(key.row)?.let { r -> r.find(key.col).takeIf { it >= 0 }?.let { r.styles[it] } }
        val dateCell = styleIndex?.let { shown.styles.getOrNull(it) }?.format?.let { NumberFormat.isDate(it) } == true
        // A formula that cannot be read is not taken (the field shows it as wrong)
        val input = edit.parse(typed, dateCell, shown.date1904) ?: return null
        val before = edit.changes[key]
        edit.set(key, input)
        return CellStep(key, before, input)
    }

    /** A cell as one number for "Find and replace" (row and column). */
    private fun cellKey(row: Int, col: Int) = (row.toLong() shl 15) or col.toLong()

    /** The filled cells of shown sheet [sheet] in reading order, with what they hold (formulas as typed). */
    fun searchable(sheet: Int): List<Pair<Long, String>> {
        if (busy) return emptyList()
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return emptyList()
        val cells = java.util.TreeSet<Long>()
        original.sheets.getOrNull(sheet)?.rows?.forEach { r -> for (c in r.cols) cells += cellKey(r.index, c) }
        for (k in edit.changes.keys) if (k.sheet == s) cells += cellKey(k.row, k.col)
        return cells.mapNotNull { k ->
            val key = CellKey(s, (k shr 15).toInt(), (k and 0x7FFF).toInt())
            if (edit.locked(key)) null else edit.content(key).takeIf { it.isNotEmpty() }?.let { k to it }
        }
    }

    fun place(key: Long): Pair<Int, Int> = (key shr 15).toInt() to (key and 0x7FFF).toInt()

    /** Replaces places found on shown sheet [sheet] (one step to undo); how many cells changed. */
    fun replaceIn(sheet: Int, found: List<Found>, with: String): Int {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return 0
        if (busy || found.isEmpty()) return 0
        val d = draft
        val steps = ArrayList<CellStep>()
        var places = 0
        for ((k, list) in found.groupBy { it.key }) {
            val (row, col) = place(k)
            val key = CellKey(s, row, col)
            // A cell being typed in is left alone (what was found is no longer what it holds)
            if (edit.locked(key) || d != null && d.sheet == sheet && d.row == row && d.col == col) continue
            var text = edit.content(key)
            var n = 0
            for (f in list.sortedByDescending { it.start }) if (f.end <= text.length) { text = text.replaceRange(f.start, f.end, with); n++ }
            if (n == 0) continue
            put(sheet, key, text)?.let { steps += it; places += n }
        }
        if (steps.isEmpty()) return 0
        push(if (steps.size == 1) steps[0] else CellsStep(steps))
        changed()
        return places
    }

    private fun push(step: Step) {
        undoStack += step
        if (undoStack.size > 500) undoStack.removeAt(0)
        // Only the last few row changes can be undone: older steps go with them (they refer to rows as they were)
        // A big workbook keeps fewer (each holds a copy of its sheets' text)
        val most = if (edit.size > 8_000_000) 2 else MAX_ROW_STEPS
        while (undoStack.count { it is RowStep } > most) {
            val first = undoStack.indexOfFirst { it is RowStep }
            repeat(first + 1) { undoStack.removeAt(0) }
        }
        redoStack.clear()
    }

    fun undo() {
        if (busy) return
        draft = null; typing = false
        val st = undoStack.removeLastOrNull() ?: return
        when (st) {
            is CellStep -> edit.set(st.key, st.before)
            is CellsStep -> for (c in st.steps.asReversed()) edit.set(c.key, c.before)
            is RowStep -> { edit.restore(st.before); original = st.shownBefore }
            is LookStep -> for ((k, b, _) in st.looks) edit.setLook(k, b)
            is WidthStep -> edit.setWidth(st.sheet, st.col, st.before)
            is SortStep -> { for (c in st.cells.steps.asReversed()) edit.set(c.key, c.before); for ((k, b, _) in st.looks.looks) edit.setLook(k, b) }
        }
        redoStack += st
        changed()
    }

    fun redo() {
        if (busy) return
        draft = null; typing = false
        val st = redoStack.removeLastOrNull() ?: return
        when (st) {
            is CellStep -> edit.set(st.key, st.after)
            is CellsStep -> for (c in st.steps) edit.set(c.key, c.after)
            is RowStep -> { edit.restore(st.after); original = st.shownAfter }
            is LookStep -> for ((k, _, a) in st.looks) edit.setLook(k, a)
            is WidthStep -> edit.setWidth(st.sheet, st.col, st.after)
            is SortStep -> { for (c in st.cells.steps) edit.set(c.key, c.after); for ((k, _, a) in st.looks.looks) edit.setLook(k, a) }
        }
        undoStack += st
        changed()
    }

    /**
     * Inserts an empty row before ([below] false) or after row [row], or deletes it ([delete]), on
     * shown sheet [sheet]. Everything that refers to the rows follows; when that cannot be done here,
     * [notice] says why and nothing changes.
     */
    suspend fun rows(sheet: Int, row: Int, delete: Boolean, below: Boolean = false) {
        if (busy) return
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return
        // Anything typed was put in before (or dropped): it must not land in a row that moved
        draft = null; typing = false
        val at = if (delete || !below) row else row + 1
        val n = if (delete) -1 else 1
        busy = true
        // Finished even when the screen goes meanwhile (turned): a half-done change would leave the shown rows behind the workbook
        try { withContext(kotlinx.coroutines.NonCancellable) {
            val before = edit.snapshot()
            val shownBefore = original
            val refusal = try { withContext(Dispatchers.Default) { edit.moveRows(s, at, n) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { edit.restore(before); notice = R.string.rows_failed; return@withContext }
            if (refusal != null) { notice = refusalText(refusal); return@withContext }
            original = try { withContext(Dispatchers.Default) { Workbook(original.sheets.mapIndexed { i, sh -> if (i == sheet) sh.withRowsMoved(at, n) else sh }, original.inspection, charts = original.charts, comments = original.comments) } }
                catch (e: kotlinx.coroutines.CancellationException) { edit.restore(before); throw e }
                catch (e: Throwable) { edit.restore(before); notice = R.string.rows_failed; return@withContext }
            push(RowStep(before, edit.snapshot(), shownBefore, original))
            changed()
        } } finally { busy = false }
    }

    /** Changes how a cell looks (bold, fill, number format, alignment); false when the workbook cannot take new looks. */
    fun look(sheet: Int, row: Int, col: Int, change: CellLook): Boolean {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return false
        if (busy) return false
        flush()
        val key = CellKey(s, row, col)
        val before = edit.look(key)
        val after = (before ?: CellLook()).then(change)
        if (!edit.setLook(key, after)) { notice = R.string.sheet_no_styles; return false }
        push(LookStep(listOf(Triple(key, before, after))))
        changed()
        return true
    }

    /** The look shown for a cell: the file's, with changes made here. */
    fun currentLook(sheet: Int, row: Int, col: Int): ua.hramota.doc.sheet.CellStyle {
        val sh = workbook.sheets.getOrNull(sheet) ?: return ua.hramota.doc.sheet.CellStyle.DEFAULT
        val r = sh.row(row) ?: return ua.hramota.doc.sheet.CellStyle.DEFAULT
        val k = r.find(col)
        return if (k < 0) ua.hramota.doc.sheet.CellStyle.DEFAULT else sh.style(r, k)
    }

    /** Sets a column's width in characters (as Excel counts them). */
    fun width(sheet: Int, col: Int, chars: Float) {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return
        if (busy) return
        val before = edit.widthChanges[s to col]
        edit.setWidth(s, col, chars)
        push(WidthStep(s, col, before, chars))
        changed()
    }

    /** A column's width in characters as shown now. */
    fun widthChars(sheet: Int, col: Int): Float = (workbook.sheets.getOrNull(sheet)?.width(col) ?: 48f) / 5.25f

    /** The block of filled rows around [row] (no empty row inside it), and its columns. */
    private fun block(sh: ua.hramota.doc.sheet.Sheet, row: Int): IntArray? {
        fun filled(r: Int) = sh.row(r)?.let { x -> (x.cols.indices).any { sh.text(x, it).isNotBlank() } } == true
        if (!filled(row)) return null
        var top = row; while (top > 0 && filled(top - 1)) top--
        var bottom = row; while (filled(bottom + 1)) bottom++
        val left = (top..bottom).minOf { r -> sh.row(r)?.cols?.firstOrNull() ?: Int.MAX_VALUE }
        val right = (top..bottom).maxOf { r -> sh.row(r)?.cols?.lastOrNull() ?: -1 }
        return intArrayOf(top, left, bottom, right)
    }

    /**
     * Sorts the block of rows around [row] by column [col] (numbers before text, text in Ukrainian
     * order), the first row kept as headings if [headings]. Cells move with their looks. Refused
     * ([notice]) when the block holds formulas or merged cells: they would refer to other cells after.
     */
    fun sort(sheet: Int, row: Int, col: Int, descending: Boolean, headings: Boolean) {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return
        if (busy) return
        flush()
        val sh = workbook.sheets.getOrNull(sheet) ?: return
        val b = block(sh, row) ?: return
        val first = if (headings && b[2] > b[0]) b[0] + 1 else b[0]
        if (b[2] - first < 1) return
        if (sh.merges.any { m -> m.bottom >= first && m.top <= b[2] && m.right >= b[1] && m.left <= b[3] }) { notice = R.string.sort_refused; return }
        val cols = b[1]..b[3]
        val contents = (first..b[2]).map { r -> cols.map { c -> edit.content(CellKey(s, r, c)) } }
        if (contents.any { line -> line.any { it.startsWith("=") } } || (first..b[2]).any { r -> cols.any { c -> edit.locked(CellKey(s, r, c)) } }) { notice = R.string.sort_refused; return }
        // Each cell's look goes with it: a change made here, else the file's
        val looks = (first..b[2]).map { r -> cols.map { c -> CellKey(s, r, c).let { key -> edit.look(key) ?: edit.style(key)?.let { CellLook(style = it.toString()) } } } }
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk")).apply { strength = java.text.Collator.SECONDARY }
        val k = col - b[1]
        fun num(t: String) = ua.hramota.doc.sheet.edit.Formula.parseNumber(t.replace(',', '.').replace(" ", "").replace("\u00A0", ""))
        val order = contents.indices.sortedWith { x, y ->
            val a = contents[x].getOrElse(k) { "" }; val bb = contents[y].getOrElse(k) { "" }
            // Empty cells go last either way
            if (a.isEmpty() != bb.isEmpty()) return@sortedWith if (a.isEmpty()) 1 else -1
            val na = num(a); val nb = num(bb)
            val c = when { na != null && nb != null -> na.compareTo(nb); na != null -> -1; nb != null -> 1; else -> collator.compare(a, bb) }
            if (descending) -c else c
        }
        if (order == contents.indices.toList()) return
        val cellSteps = ArrayList<CellStep>(); val lookSteps = ArrayList<Triple<CellKey, CellLook?, CellLook?>>()
        for ((to, from) in order.withIndex()) {
            if (to == from) continue
            for ((j, c) in cols.withIndex()) {
                val key = CellKey(s, first + to, c)
                put(sheet, key, contents[from][j])?.let { cellSteps += it }
                val before = edit.look(key)
                val after = looks[from][j]
                if (after != null && edit.setLook(key, after) || after == null && before != null && edit.setLook(key, null)) lookSteps += Triple(key, before, after)
            }
        }
        push(SortStep(CellsStep(cellSteps), LookStep(lookSteps)))
        changed()
    }

    /** A filter shown here only (not written into the file): the column and the values kept. */
    class Filter(val sheet: Int, val top: Int, val bottom: Int, val col: Int, val keep: Set<String>)
    var filter by mutableStateOf<Filter?>(null)
        private set

    /** The values of the column [col] in the block around [row], with how many of each. */
    fun columnValues(sheet: Int, row: Int, col: Int): List<Pair<String, Int>> {
        val sh = workbook.sheets.getOrNull(sheet) ?: return emptyList()
        val b = block(sh, row) ?: return emptyList()
        return (b[0] + 1..b[2]).map { r -> sh.row(r)?.let { x -> x.find(col).takeIf { it >= 0 }?.let { sh.text(x, it) } } ?: "" }
            .groupingBy { it }.eachCount().toList().sortedBy { it.first }
    }

    fun setFilter(sheet: Int, row: Int, col: Int, keep: Set<String>?) {
        if (keep == null) { filter = null; version++; return }
        val sh = workbook.sheets.getOrNull(sheet) ?: return
        val b = block(sh, row) ?: return
        filter = Filter(sheet, b[0] + 1, b[2], col, keep)
        version++
    }

    private fun refusalText(r: XlsxEdit.RowRefusal) = when (r) {
        XlsxEdit.RowRefusal.WORKBOOK -> R.string.rows_refused_workbook
        XlsxEdit.RowRefusal.SHEET -> R.string.rows_refused_sheet
        XlsxEdit.RowRefusal.LOCKED -> R.string.rows_refused_locked
        XlsxEdit.RowRefusal.FORMULAS -> R.string.rows_refused_formulas
        XlsxEdit.RowRefusal.FULL -> R.string.rows_refused_full
    }

    private fun changed() {
        dirty = true
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        version++
    }

    /** Works the values out (off the main thread) and shows them. */
    suspend fun refresh() {
        // Formulas that cannot be worked out here (too deep a chain) keep their old values on screen
        // (the rows as they now are are still shown, with the values the file has)
        val values = try { withContext(Dispatchers.Default) { edit.recalc() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyMap() }
        workbook = withContext(Dispatchers.Default) { shownWith(values) }
    }

    private fun shownWith(values: Map<CellKey, Value>): Workbook {
        val bySheet = values.entries.groupBy { it.key.sheet }
        val sheets = original.sheets.mapIndexed { i, sh ->
            val mine = bySheet[shownSheets[i]] ?: return@mapIndexed sh
            val rows = sh.rows.associateByTo(java.util.TreeMap()) { it.index }
            for ((r, cells) in mine.groupBy { it.key.row }) {
                val old = rows[r]
                val map = java.util.TreeMap<Int, Triple<Int, Double, String?>>()
                if (old != null) for (k in old.cols.indices) map[old.cols[k]] = Triple(old.styles[k], old.numbers[k], old.texts[k])
                for ((key, v) in cells) {
                    val style = edit.style(key) ?: map[key.col]?.first ?: 0
                    when (v) {
                        is Value.Num -> map[key.col] = Triple(style, v.v, null)
                        is Value.Text -> map[key.col] = Triple(style, 0.0, v.v)
                        is Value.Bool -> map[key.col] = Triple(style, 0.0, if (v.v) "TRUE" else "FALSE")
                        is Value.Err -> map[key.col] = Triple(style, 0.0, v.code)
                        // Cleared: a cell with its own look keeps it, empty
                        Value.Empty -> if (style != 0) map[key.col] = Triple(style, 0.0, "") else map.remove(key.col)
                    }
                }
                val cols = map.keys.toIntArray()
                rows[r] = SheetRow(r, cols, IntArray(cols.size) { map[cols[it]]!!.first }, DoubleArray(cols.size) { map[cols[it]]!!.second }, Array(cols.size) { map[cols[it]]!!.third })
            }
            sh.withRows(rows.values.toList())
        }
        return Workbook(sheets.mapIndexed { i, sh -> dressed(i, sh) }, original.inspection, charts = original.charts, comments = original.comments)
    }

    /** Looks, widths and the filter put on a shown sheet. */
    private fun dressed(i: Int, sh: Sheet): Sheet {
        val s = shownSheets.getOrNull(i) ?: return sh
        val looks = edit.lookChanges.filterKeys { it.sheet == s }
        val widths = edit.widthChanges.filterKeys { it.first == s }.map { it.key.second to ua.hramota.doc.sheet.storedWidth(it.value.toDouble()) }.toMap()
        val f = filter?.takeIf { it.sheet == i }
        if (looks.isEmpty() && widths.isEmpty() && f == null) return sh
        val styles = ArrayList(sh.styles)
        val made = HashMap<Pair<Int, CellLook>, Int>()
        val rows = sh.rows.associateByTo(java.util.TreeMap()) { it.index }
        for ((key, look) in looks) {
            val old = rows[key.row]
            val k = old?.find(key.col) ?: -1
            val base = look.style?.toIntOrNull() ?: edit.style(key) ?: (if (k >= 0) old!!.styles[k] else 0)
            val index = made.getOrPut(base to look.copy(style = null)) {
                val b = original.sheets.getOrNull(i)?.styles?.getOrNull(base) ?: ua.hramota.doc.sheet.CellStyle.DEFAULT
                styles += b.copy(
                    bold = look.bold ?: b.bold, italic = look.italic ?: b.italic,
                    fill = look.fill?.let { if (it == 0) null else it } ?: b.fill,
                    format = look.format ?: b.format, align = look.align?.takeIf { it != "general" } ?: if (look.align == "general") null else b.align,
                )
                styles.size - 1
            }
            if (old != null && k >= 0) {
                val st = old.styles.copyOf(); st[k] = index
                rows[key.row] = SheetRow(old.index, old.cols, st, old.numbers, old.texts)
            } else {
                // An empty cell with a look: shown as one
                val map = java.util.TreeMap<Int, Triple<Int, Double, String?>>()
                if (old != null) for (j in old.cols.indices) map[old.cols[j]] = Triple(old.styles[j], old.numbers[j], old.texts[j])
                map[key.col] = Triple(index, 0.0, "")
                val cols = map.keys.toIntArray()
                rows[key.row] = SheetRow(key.row, cols, IntArray(cols.size) { map[cols[it]]!!.first }, DoubleArray(cols.size) { map[cols[it]]!!.second }, Array(cols.size) { map[cols[it]]!!.third })
            }
        }
        val hidden = HashSet<Int>()
        if (f != null) for (r in f.top..f.bottom) {
            val x = rows[r]
            val v = x?.find(f.col)?.takeIf { it >= 0 }?.let { sh.text(x, it) } ?: ""
            if (v !in f.keep) hidden += r
        }
        return sh.shown(rows.values.toList(), styles, widths, hidden)
    }

    companion object { private const val MAX_ROW_STEPS = 10 }

    /** What saving needs: the edits written into the workbook read again. */
    fun maker() = object : EditSaving.Maker {
        override val ext = "xlsx"
        override fun write(source: InputStream, out: OutputStream) = edit.save(source, out)
        override fun check(made: InputStream) { ua.hramota.doc.sheet.XlsxReader.read(made) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetEditorScreen(name: String, state: SheetEditorState, saving: Boolean, onSave: () -> Unit, onClose: () -> Unit, startCell: Pair<Int, Int>? = null) {
    var askClose by remember { mutableStateOf(false) }
    var sheetIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val view = remember { SheetViewState() }
    // What is being typed counts: it goes in first
    val save = { state.flush(); onSave() }
    val close = { state.flush(); if (state.dirty) askClose = true else onClose() }
    val find = remember { FindState() }
    var replacedCount by remember { mutableStateOf<Int?>(null) }
    BackHandler { if (find.open) find.open = false else close() }
    // What is being typed goes in before searching (places found refer to what the cells hold)
    LaunchedEffect(find.open) { if (find.open) state.flush() }
    LaunchedEffect(state.version) { if (state.version > 0) state.refresh() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The cell a tool was chosen for: its look, its column's width, a filter on its column
    var formatting by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var sizing by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var filtering by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    formatting?.let { (sh, r, c) -> CellFormatDialog(state.currentLook(sh, r, c), onDismiss = { formatting = null }) { look -> formatting = null; state.look(sh, r, c, look) } }
    sizing?.let { (sh, _, c) -> ColumnWidthDialog(ua.hramota.doc.sheet.columnName(c), state.widthChars(sh, c), onDismiss = { sizing = null }) { w -> sizing = null; state.width(sh, c, w) } }
    filtering?.let { (sh, r, c) ->
        val values = remember(sh, r, c) { state.columnValues(sh, r, c) }
        FilterDialog(ua.hramota.doc.sheet.columnName(c), values, onDismiss = { filtering = null }) { keep -> filtering = null; state.setFilter(sh, r, c, if (keep.size == values.size) null else keep) }
    }
    state.notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { state.notice = null },
            text = { Text(stringResource(msg)) },
            confirmButton = { TextButton(onClick = { state.notice = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    // "Find and replace" on the sheet shown
    val target = remember(sheetIndex) {
        object : Replaceable {
            override fun searchable() = state.searchable(sheetIndex)
            override fun show(f: Found) { val (r, c) = state.place(f.key); view.select(r, c) }
            override fun replace(f: Found, with: String) = state.replaceIn(sheetIndex, listOf(f), with) > 0
            override fun replaceAll(found: List<Found>, with: String) = state.replaceIn(sheetIndex, found, with)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
          androidx.compose.foundation.layout.Column {
            TopAppBar(
                title = { Text(name.substringBeforeLast('.', name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    val undoLabel = stringResource(R.string.edit_undo); val redoLabel = stringResource(R.string.edit_redo)
                    TextButton(onClick = { state.undo() }, enabled = state.canUndo && !saving && !state.busy) { Text("↶", modifier = Modifier.clearAndSetSemantics { contentDescription = undoLabel }) }
                    TextButton(onClick = { state.redo() }, enabled = state.canRedo && !saving && !state.busy) { Text("↷", modifier = Modifier.clearAndSetSemantics { contentDescription = redoLabel }) }
                    IconButton(onClick = { find.open = !find.open }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.find_replace)) }
                    TextButton(onClick = { save() }, enabled = (state.dirty || state.typing) && !saving && !state.busy) { Text(stringResource(if (saving) R.string.edit_saving else R.string.edit_save)) }
                },
            )
            if (find.open) FindBar(find, target, state.version to sheetIndex, enabled = !saving && !state.busy) { n -> if (n != 1) replacedCount = n }
            // A filter shows only some rows: said, with the way back (it is not written into the file)
            if (state.filter != null) androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.sheet_filter_on), style = MaterialTheme.typography.bodySmall, modifier = androidx.compose.ui.Modifier.weight(1f))
                    TextButton(onClick = { state.filter?.let { f -> state.setFilter(f.sheet, f.top, f.col, null) } }) { Text(stringResource(R.string.sheet_filter_off)) }
                }
            }
          }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            WorkbookDocument(
                state.workbook, sheetIndex, onSheet = { sheetIndex = it }, state = view,
                editing = SheetEditing(
                    content = state::content, commit = state::commit, enabled = !saving && !state.busy,
                    draft = state::draft, leave = state::flush, discard = state::discard, version = state.version,
                    rows = { sheet, row, delete, below -> scope.launch { state.rows(sheet, row, delete, below) } },
                    cellActions = { sheet, row, col ->
                        val ctx = context
                        listOfNotNull(
                            ctx.getString(R.string.sheet_format) to { formatting = Triple(sheet, row, col) },
                            ctx.getString(R.string.sheet_width) to { sizing = Triple(sheet, row, col) },
                            ctx.getString(R.string.sheet_sort_up) to { state.sort(sheet, row, col, descending = false, headings = true) },
                            ctx.getString(R.string.sheet_sort_down) to { state.sort(sheet, row, col, descending = true, headings = true) },
                            ctx.getString(R.string.sheet_filter) to { filtering = Triple(sheet, row, col) },
                            if (state.filter != null) ctx.getString(R.string.sheet_filter_off) to { state.setFilter(sheet, row, col, null) } else null,
                        )
                    },
                ),
                startCell = startCell,
            )
        }
    }
    replacedCount?.let { n ->
        AlertDialog(onDismissRequest = { replacedCount = null }, text = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.replaced_n, n, n)) },
            confirmButton = { TextButton(onClick = { replacedCount = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (askClose) AlertDialog(
        onDismissRequest = { askClose = false },
        text = { Text(stringResource(R.string.edit_unsaved)) },
        confirmButton = { TextButton(onClick = { askClose = false; onSave() }) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { askClose = false; onClose() }) { Text(stringResource(R.string.edit_discard), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { askClose = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
