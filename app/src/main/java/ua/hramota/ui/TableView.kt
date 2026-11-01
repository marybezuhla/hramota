package ua.hramota.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import ua.hramota.doc.SearchHit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.doc.CsvTable

/**
 * A plain table (CSV): the first row stays on top as the header, all rows scroll sideways together,
 * column widths follow their content within limits, and only visible rows are composed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableDocument(
    table: CsvTable,
    list: LazyListState = rememberLazyListState(),
    hits: Map<Int, List<SearchHit>> = emptyMap(),
    current: SearchHit? = null,
) {
    val rows = table.rows
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp)) { Text(stringResource(R.string.table_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    // Width from the longest text in the first rows (measured while reading): about 8 dp a character, 56–280 dp
    val widths: List<Dp> = remember(table) { table.widths.map { (it * 8 + 24).coerceIn(56, 280).dp } }
    val scroll = rememberScrollState()
    val grid = MaterialTheme.colorScheme.outlineVariant
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            stickyHeader {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(scroll)) {
                    TableRow(rows[0], widths, header = true, grid = grid, hits = hits[0], current = current)
                }
            }
            items(rows.size - 1) { i ->
                val row = rows[i + 1]
                Row(Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background).horizontalScroll(scroll)) {
                    TableRow(row, widths, header = false, grid = grid, hits = hits[i + 1], current = current)
                }
            }
            if (table.truncated) item {
                Text(stringResource(R.string.table_cut, rows.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private const val SHOWN = 300

/** Between cells in a row's search text: never part of a query, so a match stays inside one cell. */
private const val CELL_SEPARATOR = '\u0000'

/** A row as one text for search; cells start where [rowText] puts them. */
internal fun rowText(row: List<String>): String = row.joinToString(CELL_SEPARATOR.toString())

@Composable
private fun TableRow(row: List<String>, widths: List<Dp>, header: Boolean, grid: Color, hits: List<SearchHit>?, current: SearchHit?) {
    var offset = 0
    for (c in widths.indices) {
        val text = row.getOrNull(c).orEmpty()
        Cell(text, widths[c], header, grid, hits?.filter { it.start < offset + text.length && it.end > offset }, current, offset)
        offset += text.length + 1
    }
}

@Composable
private fun Cell(text: String, width: Dp, header: Boolean, grid: Color, hits: List<SearchHit>?, current: SearchHit?, offset: Int) {
    // A cell shows at most four lines of a narrow column; more text would only cost layout time
    Text(
        // A match past the shown part is not drawn over the "…"
        highlighted(if (text.length > SHOWN) text.take(SHOWN) + "…" else text, hits?.map { if (it.end > offset + SHOWN) it.copy(end = offset + SHOWN) else it }, current?.let { if (it.end > offset + SHOWN) it.copy(end = offset + SHOWN) else it }, offset),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.SemiBold else null,
        maxLines = if (header) 2 else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).border(0.5.dp, grid).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
