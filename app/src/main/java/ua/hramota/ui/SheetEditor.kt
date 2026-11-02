package ua.hramota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    /** Puts what was typed into a cell; false when the cell cannot be changed here. */
    fun commit(sheet: Int, row: Int, col: Int, typed: String): Boolean {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return false
        val key = CellKey(s, row, col)
        if (busy || edit.locked(key)) return false
        // Typed exactly what is there: nothing to change
        if (typed == edit.content(key)) return true
        val shown = original.sheets[sheet]
        val styleIndex = edit.style(key) ?: shown.row(row)?.let { r -> r.find(col).takeIf { it >= 0 }?.let { r.styles[it] } }
        val dateCell = styleIndex?.let { shown.styles.getOrNull(it) }?.format?.let { NumberFormat.isDate(it) } == true
        // A formula that cannot be read is not taken (the field shows it as wrong)
        val input = edit.parse(typed, dateCell, shown.date1904) ?: return false
        val before = edit.changes[key]
        edit.set(key, input)
        push(CellStep(key, before, input))
        changed()
        return true
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
        val st = undoStack.removeLastOrNull() ?: return
        when (st) {
            is CellStep -> edit.set(st.key, st.before)
            is RowStep -> { edit.restore(st.before); original = st.shownBefore }
        }
        redoStack += st
        changed()
    }

    fun redo() {
        if (busy) return
        val st = redoStack.removeLastOrNull() ?: return
        when (st) {
            is CellStep -> edit.set(st.key, st.after)
            is RowStep -> { edit.restore(st.after); original = st.shownAfter }
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
            original = withContext(Dispatchers.Default) { Workbook(original.sheets.mapIndexed { i, sh -> if (i == sheet) sh.withRowsMoved(at, n) else sh }, original.inspection) }
            push(RowStep(before, edit.snapshot(), shownBefore, original))
            changed()
        } } finally { busy = false }
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
        return Workbook(sheets, original.inspection)
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
    var sheetIndex by remember { mutableIntStateOf(0) }
    val view = remember { SheetViewState() }
    BackHandler { if (state.dirty) askClose = true else onClose() }
    LaunchedEffect(state.version) { if (state.version > 0) state.refresh() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    state.notice?.let { msg ->
        AlertDialog(
            onDismissRequest = { state.notice = null },
            text = { Text(stringResource(msg)) },
            confirmButton = { TextButton(onClick = { state.notice = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringBeforeLast('.', name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) askClose = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    TextButton(onClick = { state.undo() }, enabled = state.canUndo && !saving && !state.busy) { Text("↶") }
                    TextButton(onClick = { state.redo() }, enabled = state.canRedo && !saving && !state.busy) { Text("↷") }
                    TextButton(onClick = onSave, enabled = state.dirty && !saving && !state.busy) { Text(stringResource(if (saving) R.string.edit_saving else R.string.edit_save)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            WorkbookDocument(
                state.workbook, sheetIndex, onSheet = { sheetIndex = it }, state = view,
                editing = SheetEditing(
                    content = state::content, commit = state::commit, enabled = !saving && !state.busy,
                    rows = { sheet, row, delete, below -> scope.launch { state.rows(sheet, row, delete, below) } },
                ),
                startCell = startCell,
            )
        }
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
