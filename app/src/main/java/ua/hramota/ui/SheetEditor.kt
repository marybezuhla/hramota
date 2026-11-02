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
class SheetEditorState(val edit: XlsxEdit, private val original: Workbook) {
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

    // Shown sheets (the file's visible ones) in the editor's numbering of all sheets
    private val shownSheets: List<Int> = original.sheets.map { s -> edit.sheets.indexOfFirst { it.visible && it.name == s.name } }

    private class Step(val key: CellKey, val before: Input?, val after: Input?)
    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()

    fun content(sheet: Int, row: Int, col: Int): String {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return ""
        return edit.content(CellKey(s, row, col))
    }

    /** Puts what was typed into a cell; false when the cell cannot be changed here. */
    fun commit(sheet: Int, row: Int, col: Int, typed: String): Boolean {
        val s = shownSheets.getOrNull(sheet)?.takeIf { it >= 0 } ?: return false
        val key = CellKey(s, row, col)
        if (edit.locked(key)) return false
        // Typed exactly what is there: nothing to change
        if (typed == edit.content(key)) return true
        val shown = original.sheets[sheet]
        val styleIndex = edit.style(key) ?: shown.row(row)?.let { r -> r.find(col).takeIf { it >= 0 }?.let { r.styles[it] } }
        val dateCell = styleIndex?.let { shown.styles.getOrNull(it) }?.format?.let { NumberFormat.isDate(it) } == true
        val input = edit.parse(typed, dateCell, shown.date1904)
        val before = edit.changes[key]
        edit.set(key, input)
        undoStack += Step(key, before, input); if (undoStack.size > 500) undoStack.removeAt(0)
        redoStack.clear()
        changed()
        return true
    }

    fun undo() {
        val st = undoStack.removeLastOrNull() ?: return
        edit.set(st.key, st.before); redoStack += st; changed()
    }

    fun redo() {
        val st = redoStack.removeLastOrNull() ?: return
        edit.set(st.key, st.after); undoStack += st; changed()
    }

    private fun changed() {
        dirty = true
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        version++
    }

    /** Works the values out (off the main thread) and shows them. */
    suspend fun refresh() {
        val values = withContext(Dispatchers.Default) { edit.recalc() }
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
                    TextButton(onClick = { state.undo() }, enabled = state.canUndo && !saving) { Text("↶") }
                    TextButton(onClick = { state.redo() }, enabled = state.canRedo && !saving) { Text("↷") }
                    TextButton(onClick = onSave, enabled = state.dirty && !saving) { Text(stringResource(if (saving) R.string.edit_saving else R.string.edit_save)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            WorkbookDocument(
                state.workbook, sheetIndex, onSheet = { sheetIndex = it }, state = view,
                editing = SheetEditing(content = state::content, commit = state::commit, enabled = !saving),
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
