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
import androidx.compose.foundation.lazy.itemsIndexed
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
import ua.hramota.doc.CsvReader

/**
 * A plain table (CSV): the first row stays on top as the header, all rows scroll sideways together,
 * column widths follow their content within limits, and only visible rows are composed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableDocument(rows: List<List<String>>) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp)) { Text(stringResource(R.string.table_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val columns = rows.maxOf { it.size }
    // Width from the longest text in the first rows: about 8 dp a character, 56–280 dp
    val widths: List<Dp> = remember(rows) {
        List(columns) { c ->
            val longest = rows.asSequence().take(300).maxOfOrNull { r -> r.getOrNull(c)?.lineSequence()?.maxOfOrNull { it.length } ?: 0 } ?: 0
            (longest * 8 + 24).coerceIn(56, 280).dp
        }
    }
    val scroll = rememberScrollState()
    val grid = MaterialTheme.colorScheme.outlineVariant
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize()) {
            stickyHeader {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(scroll)) {
                    for (c in 0 until columns) Cell(rows[0].getOrNull(c).orEmpty(), widths[c], header = true, grid = grid)
                }
            }
            itemsIndexed(rows.drop(1)) { i, row ->
                Row(Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background).horizontalScroll(scroll)) {
                    for (c in 0 until columns) Cell(row.getOrNull(c).orEmpty(), widths[c], header = false, grid = grid)
                }
            }
            if (rows.size >= CsvReader.MAX_ROWS) item {
                Text(stringResource(R.string.table_cut, rows.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, header: Boolean, grid: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.SemiBold else null,
        maxLines = if (header) 2 else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).border(0.5.dp, grid).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
