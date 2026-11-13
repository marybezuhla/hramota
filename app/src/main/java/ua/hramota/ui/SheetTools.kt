package ua.hramota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.doc.sheet.CellStyle
import ua.hramota.doc.sheet.edit.CellLook

/** Fill colours to choose from: none, then soft colours text stays readable on. */
private val FILLS = listOf(0, 0xFFFFF2CC.toInt(), 0xFFE2EFDA.toInt(), 0xFFDDEBF7.toInt(), 0xFFFCE4D6.toInt(), 0xFFEDE2F6.toInt(), 0xFFD9D9D9.toInt(), 0xFFFFFF00.toInt())

/** Number formats people use most, by what they show. */
private val FORMATS = listOf(
    "General" to R.string.fmt_general, "0" to R.string.fmt_whole, "#,##0.00" to R.string.fmt_decimal, "0%" to R.string.fmt_percent,
    "#,##0.00\\ \"₴\"" to R.string.fmt_hryvnia, "dd.mm.yyyy" to R.string.fmt_date, "@" to R.string.fmt_text,
)

/** How a cell looks: bold, italic, fill, number format and alignment. Only what is changed here is applied. */
@Composable
internal fun CellFormatDialog(now: CellStyle, onDismiss: () -> Unit, onDone: (CellLook) -> Unit) {
    var bold by remember { mutableStateOf(now.bold) }
    var italic by remember { mutableStateOf(now.italic) }
    var fill by remember { mutableStateOf<Int?>(null) }
    var format by remember { mutableStateOf<String?>(null) }
    var align by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_format)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(bold, { bold = !bold }, label = { Text(stringResource(R.string.fmt_bold_short), fontWeight = FontWeight.Bold) })
                    FilterChip(italic, { italic = !italic }, label = { Text(stringResource(R.string.fmt_italic_short), fontStyle = FontStyle.Italic) })
                }
                Text(stringResource(R.string.sheet_fill), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in FILLS) {
                        val on = (fill ?: now.fill ?: 0) == c
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(if (c == 0) Color.White else Color(c))
                                .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .clickable { fill = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == 0) Text("∅", color = Color.Gray) }
                    }
                }
                Text(stringResource(R.string.sheet_number_format), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((code, label) in FORMATS) FilterChip((format ?: now.format) == code, { format = code }, label = { Text(stringResource(label)) })
                }
                Text(stringResource(R.string.sheet_align), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((side, label) in listOf("left" to "⇤", "center" to "↔", "right" to "⇥", "general" to "Auto")) {
                        val on = (align ?: now.align ?: "general") == side
                        FilterChip(on, { align = side }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(CellLook(bold = bold.takeIf { it != now.bold }, italic = italic.takeIf { it != now.italic }, fill = fill, format = format, align = align))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ColumnWidthDialog(column: String, chars: Float, onDismiss: () -> Unit, onDone: (Float) -> Unit) {
    var w by remember { mutableFloatStateOf(chars.coerceIn(2f, 80f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_width_of, column)) },
        text = {
            Column {
                Text(stringResource(R.string.sheet_width_chars, Math.round(w)))
                Slider(w, { w = it }, valueRange = 2f..80f)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(Math.round(w).toFloat()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Which values of a column stay shown. */
@Composable
internal fun FilterDialog(column: String, values: List<Pair<String, Int>>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val keep = remember { mutableStateOf(values.map { it.first }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_filter_of, column)) },
        text = {
            Column {
                Row {
                    TextButton(onClick = { keep.value = values.map { it.first }.toSet() }) { Text(stringResource(R.string.sheet_filter_all)) }
                    TextButton(onClick = { keep.value = emptySet() }) { Text(stringResource(R.string.sheet_filter_none)) }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(values) { (v, n) ->
                        Row(Modifier.fillMaxWidth().clickable { keep.value = if (v in keep.value) keep.value - v else keep.value + v }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(v in keep.value, { keep.value = if (v in keep.value) keep.value - v else keep.value + v })
                            Text(v.ifEmpty { stringResource(R.string.sheet_filter_empty) } + "  ($n)", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                Text(stringResource(R.string.sheet_filter_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(keep.value) }, enabled = keep.value.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
