package ua.hramota.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.data.LibraryEntry
import ua.hramota.data.RecentFile
import ua.hramota.doc.Format

/**
 * Where a version to compare comes from: the shelf, the recent files, or any other file through the
 * system's file picker. Only documents whose text can be compared are listed.
 */
@Composable
fun ComparePicker(
    title: String, shelf: List<LibraryEntry>, recents: List<RecentFile>,
    onShelf: (LibraryEntry) -> Unit, onRecent: (RecentFile) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { ComparePickerList(shelf, recents, onShelf, onRecent, onOther) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The choices themselves (apart from the dialog, so they can be seen in screenshots). */
@Composable
fun ComparePickerList(
    shelf: List<LibraryEntry>, recents: List<RecentFile>,
    onShelf: (LibraryEntry) -> Unit, onRecent: (RecentFile) -> Unit, onOther: () -> Unit,
) {
    val kept = shelf.filter { Format.detect(it.title, null).hasText }
    val keptTitles = kept.map { it.title }.toSet()
    val recent = recents.filter { Format.detect(it.name, null).hasText && it.name !in keptTitles }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        if (kept.isNotEmpty()) {
            item { Head(stringResource(R.string.shelf_edit)) }
            items(kept, key = { "s" + it.id }) { e -> Line(e.title, e.format) { onShelf(e) } }
        }
        if (recent.isNotEmpty()) {
            item { Head(stringResource(R.string.recent)) }
            items(recent, key = { "r" + it.uri }) { r -> Line(r.name, Format.detect(r.name, null).label) { onRecent(r) } }
        }
        if (kept.isEmpty() && recent.isEmpty()) item {
            Text(stringResource(R.string.compare_none_here), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Text(stringResource(R.string.compare_other_file), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { onOther() }.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun Head(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun Line(title: String, format: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.substringBeforeLast('.', title), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
