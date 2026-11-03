package ua.hramota.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.LaunchedEffect
import ua.hramota.R
import ua.hramota.doc.SearchHit
import ua.hramota.doc.sheet.Sheet
import ua.hramota.doc.sheet.SheetRow
import ua.hramota.doc.sheet.Workbook
import ua.hramota.doc.sheet.columnName
import kotlin.math.max
import kotlin.math.min

/** Between cells in a sheet row's search text: never part of a query, so a match stays in one cell. */
private const val CELL_SEPARATOR = '\u0000'

/** A sheet row as one text for search; cell [k] starts after the cells before it and a separator each. */
internal fun sheetRowText(sheet: Sheet, row: SheetRow): String =
    (row.cols.indices).joinToString(CELL_SEPARATOR.toString()) { sheet.text(row, it) }

/** Where the grid is scrolled and how far it is zoomed; search moves it to a cell. */
@Stable
class SheetViewState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    internal var target by mutableStateOf<Pair<Int, Int>?>(null)
    fun scrollTo(row: Int, col: Int) { target = row to col }
}

/** Column edges and row edges in points, computed once per sheet. */
private class Geometry(val sheet: Sheet, room: Boolean = false) {
    // While editing there is room to write beyond the last filled row and column
    val cols = (max(sheet.colCount, 1) + if (room) 6 else 0).coerceAtMost(16_384)
    val rows = (max(sheet.rowCount, 1) + if (room) 40 else 0).coerceAtMost(1_048_576)
    val colX = FloatArray(cols + 1).also { a -> for (c in 0 until cols) a[c + 1] = a[c] + sheet.width(c) }
    val rowY = FloatArray(rows + 1).also { a -> for (r in 0 until rows) a[r + 1] = a[r] + sheet.height(r) }
    /** For each index, the next one that is not hidden (zero size), so hidden ranges cost nothing to skip. */
    val nextRow = next(rowY)
    val nextCol = next(colX)

    private fun next(edges: FloatArray): IntArray {
        val n = edges.size - 1
        val out = IntArray(n + 1) { n }
        for (i in n - 1 downTo 0) out[i] = if (edges[i + 1] > edges[i]) i else out[i + 1]
        return out
    }

    /** The first index whose edge passes [v] (binary search over [edges]). */
    fun indexAt(edges: FloatArray, v: Float): Int {
        var lo = 0; var hi = edges.size - 2
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (edges[mid] <= v) lo = mid else hi = mid - 1 }
        return lo.coerceAtLeast(0)
    }
}

@Composable
fun WorkbookDocument(
    workbook: Workbook,
    sheetIndex: Int,
    onSheet: (Int) -> Unit,
    state: SheetViewState,
    hits: Map<Int, List<SearchHit>> = emptyMap(),
    current: SearchHit? = null,
    /** Editing: the bar under the grid writes into the chosen cell. */
    editing: SheetEditing? = null,
    startCell: Pair<Int, Int>? = null,
) {
    val index = sheetIndex.coerceIn(0, workbook.sheets.size - 1)
    val sheet = workbook.sheets[index]
    // The chosen cell stays when the phone is turned
    var selected by androidx.compose.runtime.saveable.rememberSaveable(index, stateSaver = androidx.compose.runtime.saveable.listSaver<Pair<Int, Int>?, Int>(save = { it?.toList() ?: emptyList() }, restore = { if (it.size == 2) it[0] to it[1] else null })) { mutableStateOf(startCell) }
    // Another sheet opens at its top left (an edited one stays where it is)
    var shown by remember { mutableStateOf(index) }
    if (shown != index) { shown = index; state.x = 0f; state.y = 0f }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SheetGrid(sheet, state, hits, current, selected, room = editing != null) { selected = it }
        }
        if (editing != null) {
            selected?.let { (r, c) ->
                EditBar(editing, index, r, c, onDone = { next -> selected = next }, onClose = { selected = null })
            }
        } else selected?.let { (r, c) ->
            val row = sheet.row(r)
            val k = row?.find(c) ?: -1
            val text = if (row != null && k >= 0) sheet.text(row, k) else ""
            CellBar("${columnName(c)}${r + 1}", text) { selected = null }
        }
        if (workbook.sheets.size > 1) SheetTabs(workbook.sheets.map { it.name }, sheetIndex, onSheet)
    }
}

@Composable
private fun CellBar(ref: String, text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ref, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (text.isNotEmpty()) TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(ref, text))
            }) { Text(stringResource(R.string.copy)) }
            TextButton(onClick = onClose) { val l = stringResource(R.string.cell_close); Text("✕", modifier = Modifier.clearAndSetSemantics { contentDescription = l }) }
        }
    }
}

@Composable
private fun SheetTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp).selectableGroup()) {
            names.forEachIndexed { i, n ->
                val on = i == selected
                Text(
                    n.ifBlank { "${i + 1}" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.SemiBold else null,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(on, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(i) }.padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetGrid(
    sheet: Sheet,
    state: SheetViewState,
    hits: Map<Int, List<SearchHit>>,
    current: SearchHit?,
    selected: Pair<Int, Int>?,
    room: Boolean = false,
    onSelect: (Pair<Int, Int>?) -> Unit,
) {
    val g = remember(sheet, room) { Geometry(sheet, room) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 512)
    var viewport by remember { mutableStateOf(Size.Zero) }
    val headerColor = MaterialTheme.colorScheme.surfaceVariant
    val headerText = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = headerText)
    // Points to pixels at the current zoom
    val unit = density.density * state.zoom
    val headerH = with(density) { 22.dp.toPx() }
    val headerW = with(density) { (12 + 7 * g.rows.toString().length).dp.toPx() }
    val frozenW = g.colX[sheet.frozenCols.coerceAtMost(g.cols)] * unit
    val frozenH = g.rowY[sheet.frozenRows.coerceAtMost(g.rows)] * unit
    fun maxX() = max(0f, g.colX[g.cols] * unit + headerW - viewport.width + 48 * unit)
    fun maxY() = max(0f, g.rowY[g.rows] * unit + headerH - viewport.height + 48 * unit)
    val hScroll = rememberScrollableState { d -> val old = state.x; state.x = (state.x - d).coerceIn(0f, maxX()); old - state.x }
    val vScroll = rememberScrollableState { d -> val old = state.y; state.y = (state.y - d).coerceIn(0f, maxY()); old - state.y }
    val zoom = rememberTransformableState { z, _, _ ->
        val nz = (state.zoom * z).coerceIn(0.4f, 3f)
        val f = nz / state.zoom
        state.zoom = nz; state.x *= f; state.y *= f
    }
    // Search moves to the found cell: it lands a little below and right of the frozen part
    LaunchedEffect(state.target, viewport) {
        val (r, c) = state.target ?: return@LaunchedEffect
        if (viewport == Size.Zero) return@LaunchedEffect
        state.x = (g.colX[c.coerceIn(0, g.cols - 1)] * unit - frozenW - viewport.width / 4).coerceIn(0f, maxX())
        state.y = (g.rowY[r.coerceIn(0, g.rows - 1)] * unit - frozenH - viewport.height / 3).coerceIn(0f, maxY())
        state.target = null
    }
    // Cells with matches, worked out off the main thread; the current one is checked when drawing
    val found by androidx.compose.runtime.produceState(emptyMap<Int, Set<Int>>(), hits, sheet) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { foundCells(sheet, hits) }
    }
    val currentCell = remember(current, sheet) { current?.let { cellOf(sheet, it) } }
    val texts = remember(sheet) { object : LinkedHashMap<Long, String>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, String>?) = size > 4000 } }
    fun cellText(row: SheetRow, k: Int): String = texts.getOrPut((row.index.toLong() shl 16) or row.cols[k].toLong()) { sheet.text(row, k) }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { viewport = it.toSize() }
            .background(Color.White)
            .transformable(zoom)
            .scrollable(hScroll, Orientation.Horizontal)
            .scrollable(vScroll, Orientation.Vertical)
            .pointerInput(sheet, unit) {
                detectTapGestures { p ->
                    if (p.x < headerW || p.y < headerH) { onSelect(null); return@detectTapGestures }
                    val cx = p.x - headerW; val cy = p.y - headerH
                    val col = g.indexAt(g.colX, (if (cx < frozenW) cx else cx + state.x) / unit)
                    val row = g.indexAt(g.rowY, (if (cy < frozenH) cy else cy + state.y) / unit)
                    onSelect(row to col)
                }
            },
    ) {
        val ctx = Ctx(this, g, sheet, measurer, unit, density.density, density.fontScale, found, currentCell, ::cellText)
        val fr = sheet.frozenRows.coerceAtMost(g.rows); val fc = sheet.frozenCols.coerceAtMost(g.cols)
        val body = Offset(headerW, headerH)
        // Four parts: the frozen corner, frozen rows (scroll sideways), frozen columns (scroll down), the rest
        ctx.region(fr, g.rows, fc, g.cols, body.x + frozenW - state.x - g.colX[fc] * unit, body.y + frozenH - state.y - g.rowY[fr] * unit, body.x + frozenW, body.y + frozenH, size.width, size.height)
        if (fr > 0) ctx.region(0, fr, fc, g.cols, body.x + frozenW - state.x - g.colX[fc] * unit, body.y, body.x + frozenW, body.y, size.width, body.y + frozenH)
        if (fc > 0) ctx.region(fr, g.rows, 0, fc, body.x, body.y + frozenH - state.y - g.rowY[fr] * unit, body.x, body.y + frozenH, body.x + frozenW, size.height)
        if (fr > 0 && fc > 0) ctx.region(0, fr, 0, fc, body.x, body.y, body.x, body.y, body.x + frozenW, body.y + frozenH)
        if (fr > 0) drawLine(Color(0xFF9E9E9E), Offset(body.x, body.y + frozenH), Offset(size.width, body.y + frozenH), 1.5f)
        if (fc > 0) drawLine(Color(0xFF9E9E9E), Offset(body.x + frozenW, body.y), Offset(body.x + frozenW, size.height), 1.5f)

        // Selected cell
        selected?.let { (r, c) ->
            val x = body.x + if (c < fc) g.colX[c] * unit else frozenW + (g.colX[c] - g.colX[fc]) * unit - state.x
            val y = body.y + if (r < fr) g.rowY[r] * unit else frozenH + (g.rowY[r] - g.rowY[fr]) * unit - state.y
            drawRect(accent, Offset(x, y), Size(sheet.width(c) * unit, sheet.height(r) * unit), style = Stroke(2.5f))
        }

        // Headers: letters above, numbers on the left, drawn last so they stay in place
        drawRect(headerColor, Offset(0f, 0f), Size(size.width, headerH))
        drawRect(headerColor, Offset(0f, 0f), Size(headerW, size.height))
        val colStart = g.indexAt(g.colX, (state.x / unit) + g.colX[fc])
        fun headerCol(c: Int, x: Float) {
            val w = sheet.width(c) * unit
            if (w <= 0f) return
            drawLine(Color(0xFFBDBDBD), Offset(x + w, 0f), Offset(x + w, headerH))
            val t = measurer.measure(columnName(c), labelStyle)
            if (t.size.width < w) drawText(t, topLeft = Offset(x + (w - t.size.width) / 2, (headerH - t.size.height) / 2))
        }
        clipRect(headerW, 0f, size.width, headerH) {
            for (c in 0 until fc) headerCol(c, body.x + g.colX[c] * unit)
            var c = max(colStart, fc)
            while (c < g.cols) {
                val x = body.x + frozenW + (g.colX[c] - g.colX[fc]) * unit - state.x
                if (x > size.width) break
                if (x >= body.x + frozenW - sheet.width(c) * unit) headerCol(c, x)
                c++
            }
        }
        val rowStart = g.indexAt(g.rowY, (state.y / unit) + g.rowY[fr])
        fun headerRow(r: Int, y: Float) {
            val h = sheet.height(r) * unit
            if (h <= 0f) return
            drawLine(Color(0xFFBDBDBD), Offset(0f, y + h), Offset(headerW, y + h))
            val t = measurer.measure("${r + 1}", labelStyle)
            if (t.size.height < h + 4) drawText(t, topLeft = Offset((headerW - t.size.width) / 2, y + (h - t.size.height) / 2))
        }
        clipRect(0f, headerH, headerW, size.height) {
            for (r in 0 until fr) headerRow(r, body.y + g.rowY[r] * unit)
            var r = max(rowStart, fr)
            while (r < g.rows) {
                val y = body.y + frozenH + (g.rowY[r] - g.rowY[fr]) * unit - state.y
                if (y > size.height) break
                if (y >= body.y + frozenH - sheet.height(r) * unit) headerRow(r, y)
                r++
            }
        }
        drawRect(headerColor, Offset(0f, 0f), Size(headerW, headerH))
    }
}

/** The cell a match is in: (row number, column). */
private fun cellOf(sheet: Sheet, hit: SearchHit): Pair<Int, Int>? {
    val row = sheet.rows.getOrNull(hit.page) ?: return null
    var at = 0
    for (k in row.cols.indices) { val len = sheet.text(row, k).length; if (hit.start <= at + len) return row.index to row.cols[k]; at += len + 1 }
    return null
}

/** Cells with a search match: row number → columns. */
private fun foundCells(sheet: Sheet, hits: Map<Int, List<SearchHit>>): Map<Int, Set<Int>> {
    if (hits.isEmpty()) return emptyMap()
    val out = HashMap<Int, HashSet<Int>>()
    for (list in hits.values) for (h in list) cellOf(sheet, h)?.let { (r, c) -> out.getOrPut(r) { HashSet() } += c }
    return out
}

/** Everything one frame of the grid needs to draw a part of the sheet. */
private class Ctx(
    val d: DrawScope, val g: Geometry, val sheet: Sheet, val measurer: TextMeasurer,
    val unit: Float, val density: Float, val fontScale: Float,
    val found: Map<Int, Set<Int>>, val current: Pair<Int, Int>?, val text: (SheetRow, Int) -> String,
) {
    private val grid = Color(0xFFE0E0E0)
    private val hit = Color(0x99FFD54F)
    private val currentHit = Color(0xCCFF9800)

    /**
     * Rows [r0, r1) and columns [c0, c1) of the sheet, with sheet point (colX[c0], rowY[r0]) at
     * ([ox] + colX[c0]·unit, [oy] + rowY[r0]·unit), clipped to the rectangle given.
     */
    fun region(r0: Int, r1: Int, c0: Int, c1: Int, ox: Float, oy: Float, left: Float, top: Float, right: Float, bottom: Float) = with(d) {
        if (right <= left || bottom <= top || r1 <= r0 || c1 <= c0) return
        clipRect(left, top, right, bottom) {
            fun x(c: Int) = ox + g.colX[c] * unit
            fun y(r: Int) = oy + g.rowY[r] * unit
            val firstCol = max(c0, g.indexAt(g.colX, (left - ox) / unit))
            val firstRow = max(r0, g.indexAt(g.rowY, (top - oy) / unit))
            // The last row and column in view: found by edge position (binary search), not by walking hidden ones
            val lastCol = min(c1 - 1, g.indexAt(g.colX, (right - ox) / unit))
            val lastRow = min(r1 - 1, g.indexAt(g.rowY, (bottom - oy) / unit))
            // Grid lines, skipping hidden rows and columns
            var c = firstCol
            while (c <= lastCol + 1 && c <= g.cols) { val xx = x(c); drawLine(grid, Offset(xx, top), Offset(xx, bottom)); c = if (c >= g.cols) c + 1 else g.nextCol[c + 1].coerceAtMost(g.cols).let { if (it == c) c + 1 else it } }
            var rr = firstRow
            while (rr <= lastRow + 1 && rr <= g.rows) { val yy = y(rr); drawLine(grid, Offset(left, yy), Offset(right, yy)); rr = if (rr >= g.rows) rr + 1 else g.nextRow[rr + 1].coerceAtMost(g.rows).let { if (it == rr) rr + 1 else it } }
            // Merged areas in view: blank them (no inner lines) and remember them
            val merges = sheet.merges.filter { it.bottom >= firstRow && it.top <= lastRow && it.right >= firstCol && it.left <= lastCol }
            for (m in merges) {
                val mx = x(m.left.coerceIn(0, g.cols - 1)); val my = y(m.top.coerceIn(0, g.rows - 1))
                drawRect(Color.White, Offset(mx + 0.5f, my + 0.5f), Size(x(min(m.right + 1, g.cols)) - mx - 1f, y(min(m.bottom + 1, g.rows)) - my - 1f))
            }
            // Cells covered by a merge (not its first cell), only those in view: a lookup per cell, not a search
            val coveredCells = HashSet<Long>()
            for (m in merges) for (r in max(m.top, firstRow)..min(m.bottom, lastRow)) for (cc in max(m.left, firstCol - 4)..min(m.right, lastCol)) {
                if (r != m.top || cc != m.left) coveredCells += (r.toLong() shl 20) or cc.toLong()
            }
            val anchors = merges.associateBy { (it.top.toLong() shl 20) or it.left.toLong() }
            var r = firstRow
            while (r <= lastRow) {
                val row = sheet.row(r)
                if (row != null) {
                    val rowHits = found[r]
                    // Cells left of the view may still spill text into it: start a few columns earlier
                    var k = row.cols.binarySearch(firstCol).let { if (it >= 0) it else -it - 1 }
                    k = max(0, k - 4)
                    while (k < row.cols.size && row.cols[k] <= lastCol) {
                        val cc = row.cols[k]
                        if (cc >= c0 && ((r.toLong() shl 20) or cc.toLong()) !in coveredCells) cell(row, k, r, cc, ::x, ::y, anchors, hitState(rowHits, r, cc), lastCol)
                        k++
                    }
                }
                r = g.nextRow[min(r + 1, g.rows)].let { if (it <= r) r + 1 else it }
            }
            // A merged area that starts outside this part (across the frozen line, or above or left of the view) still shows its content here
            for (m in merges) {
                // Anchors in view (or just left of it, which the rows above already drew) are done
                if (m.top >= firstRow && m.left >= max(c0, firstCol - 4) && m.top >= r0) continue
                val row = sheet.row(m.top) ?: continue
                val k = row.find(m.left)
                if (k >= 0) cell(row, k, m.top, m.left, ::x, ::y, anchors, hitState(found[m.top], m.top, m.left), lastCol)
            }
        }
    }

    /** Null: no match in the cell; false: a match; true: the current match. */
    private fun hitState(rowHits: Set<Int>?, r: Int, c: Int): Boolean? = if (rowHits != null && c in rowHits) (current?.first == r && current.second == c) else null

    private fun cell(row: SheetRow, k: Int, r: Int, c: Int, x: (Int) -> Float, y: (Int) -> Float, anchors: Map<Long, ua.hramota.doc.sheet.Merge>, found: Boolean?, lastCol: Int) = with(d) {
        val style = sheet.style(row, k)
        val m = anchors[(r.toLong() shl 20) or c.toLong()]
        val right = if (m != null) min(m.right + 1, g.cols) else c + 1
        val bottomRow = if (m != null) min(m.bottom + 1, g.rows) else r + 1
        val cx = x(c); val cy = y(r)
        val w = x(right) - cx; val h = y(bottomRow) - cy
        if (w <= 0f || h <= 0f) return
        style.fill?.let { drawRect(Color(it), Offset(cx, cy), Size(w, h)) }
        if (found != null) drawRect(if (found) currentHit else hit, Offset(cx, cy), Size(w, h))
        val line = Color(0xFF424242)
        if (style.borders and 1 != 0) drawLine(line, Offset(cx, cy), Offset(cx, cy + h), 1.2f)
        if (style.borders and 2 != 0) drawLine(line, Offset(cx, cy), Offset(cx + w, cy), 1.2f)
        if (style.borders and 4 != 0) drawLine(line, Offset(cx + w, cy), Offset(cx + w, cy + h), 1.2f)
        if (style.borders and 8 != 0) drawLine(line, Offset(cx, cy + h), Offset(cx + w, cy + h), 1.2f)
        val t = text(row, k)
        if (t.isEmpty()) return
        val number = sheet.isNumber(row, k)
        val align = style.align ?: if (number) "right" else "left"
        // Text that does not fit flows into empty cells on the right, as in Excel
        var spill = right
        if (!style.wrap && !number && align == "left" && m == null) {
            var kk = k + 1
            while (spill < min(g.cols, lastCol + 8)) {
                val nextFilled = kk < row.cols.size && row.cols[kk] == spill && text(row, kk).isNotEmpty()
                if (nextFilled) break
                if (kk < row.cols.size && row.cols[kk] == spill) kk++
                spill++
            }
        }
        val pad = 2.5f * unit
        val areaW = (x(spill) - cx - 2 * pad).coerceAtLeast(1f)
        // A point of the sheet is a dp on screen (times zoom), for text as for column widths; the system font size does not apply
        val ts = TextStyle(
            fontSize = (style.size * unit / (density * fontScale)).sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when { style.underline && style.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)); style.underline -> TextDecoration.Underline; style.strike -> TextDecoration.LineThrough; else -> null },
            color = style.color?.let { Color(it) } ?: Color.Black,
        )
        if (ts.fontSize.value < 2f) return
        val layout: TextLayoutResult = measurer.measure(
            t.take(2000), ts, overflow = TextOverflow.Clip, softWrap = style.wrap, maxLines = if (style.wrap) 50 else 1,
            constraints = Constraints(maxWidth = if (style.wrap) areaW.toInt().coerceAtLeast(1) else Constraints.Infinity),
        )
        // A number too wide for its cell shows ### as in Excel
        if (number && layout.size.width > w - 2 * pad) {
            val hashes = measurer.measure("#".repeat(((w - 2 * pad) / (layout.size.width / t.length.coerceAtLeast(1))).toInt().coerceIn(1, 40)), ts)
            drawText(hashes, topLeft = Offset(cx + pad, cy + h - hashes.size.height - pad / 2))
            return
        }
        val tw = layout.size.width.toFloat()
        val tx = when (align) { "center" -> cx + (w - tw) / 2; "right" -> cx + w - pad - tw; else -> cx + pad }
        val ty = when (style.vertical) { "top" -> cy + pad / 2; "center" -> cy + (h - layout.size.height) / 2; else -> cy + h - layout.size.height - pad / 2 }
        clipRect(cx, cy, if (spill > right) x(spill) else cx + w, cy + h) { drawText(layout, topLeft = Offset(tx, ty)) }
    }
}

/** What editing a workbook needs from the grid: a cell's content to edit, and putting new content in. */
class SheetEditing(
    val content: (sheet: Int, row: Int, col: Int) -> String,
    /** Puts [typed] into the cell; false if the cell cannot be changed here. */
    val commit: (sheet: Int, row: Int, col: Int, typed: String) -> Boolean,
    val enabled: Boolean = true,
    /** Inserts a row above or below ([below]), or deletes it ([delete]); null where rows are not changed. */
    val rows: ((sheet: Int, row: Int, delete: Boolean, below: Boolean) -> Unit)? = null,
)

/** The chosen cell's content to edit: Enter puts it in and goes to the cell below. */
@Composable
private fun EditBar(editing: SheetEditing, sheet: Int, row: Int, col: Int, onDone: (Pair<Int, Int>?) -> Unit, onClose: () -> Unit) {
    val ref = "${columnName(col)}${row + 1}"
    var value by androidx.compose.runtime.saveable.rememberSaveable(sheet, row, col, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(editing.content(sheet, row, col))) }
    var refused by remember(sheet, row, col) { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun put(): Boolean {
        val ok = editing.commit(sheet, row, col, value.text)
        refused = !ok
        return ok
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = Modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ref, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it; refused = false },
                singleLine = true,
                enabled = editing.enabled,
                isError = refused,
                placeholder = { Text(stringResource(R.string.sheet_edit_hint), maxLines = 1) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { if (put()) onDone(row + 1 to col) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            TextButton(onClick = { if (put()) onDone(row to col) }, enabled = editing.enabled) { val l = stringResource(R.string.cell_put); Text("✓", modifier = Modifier.clearAndSetSemantics { contentDescription = l }) }
            editing.rows?.let { rows ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = editing.enabled) { val l = stringResource(R.string.rows_menu); Text("⋮", modifier = Modifier.clearAndSetSemantics { contentDescription = l }) }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // What was typed is put in first (a wrong formula stays, and the rows wait)
                        fun act(delete: Boolean, below: Boolean) {
                            menu = false
                            if (value.text != editing.content(sheet, row, col) && !put()) return
                            rows(sheet, row, delete, below)
                            // The rows move under the field: it is closed, and the new place chosen again by a tap
                            onClose()
                        }
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.rows_insert_above)) }, onClick = { act(false, false) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.rows_insert_below)) }, onClick = { act(false, true) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.rows_delete, row + 1), color = MaterialTheme.colorScheme.error) }, onClick = { act(true, false) })
                    }
                }
            }
            TextButton(onClick = onClose) { val l = stringResource(R.string.cell_close); Text("✕", modifier = Modifier.clearAndSetSemantics { contentDescription = l }) }
        }
    }
}
