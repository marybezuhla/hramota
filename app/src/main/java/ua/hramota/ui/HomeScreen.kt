package ua.hramota.ui

import android.text.format.DateUtils
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.data.RecentFile
import ua.hramota.doc.Format

/**
 * Home: the recent files and the shelf on nearly the whole screen; opening, making and comparing files
 * behind one round button at the bottom, within reach of the thumb. [comparePrompt] replaces that button
 * while the second file of a comparison is being chosen.
 */
@Composable
fun HomeScreen(
    recents: List<RecentFile>,
    comparePrompt: String?,
    onOpenFile: () -> Unit,
    onCompare: () -> Unit,
    onCreate: () -> Unit = {},
    onScan: () -> Unit = {},
    onOpenRecent: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit = {},
    shelf: List<ua.hramota.data.LibraryEntry> = emptyList(),
    shelves: List<ua.hramota.data.ShelfDef> = listOf(ua.hramota.data.ShelfDef(ua.hramota.data.ShelfDef.ALL, "")),
    onOpenShelf: (ua.hramota.data.LibraryEntry) -> Unit = {},
    onShelfAction: (ua.hramota.data.LibraryEntry, ShelfAction) -> Unit = { _, _ -> },
    onPutShelf: (ua.hramota.data.ShelfDef) -> Unit = {},
    onRemoveShelf: (String) -> Unit = {},
    onSetShelves: (ua.hramota.data.LibraryEntry, Set<String>) -> Unit = { _, _ -> },
    startShelf: String = ua.hramota.data.ShelfDef.ALL,
    startOnShelf: Boolean = false,
    now: Long = System.currentTimeMillis(),
    /** Recent files that cannot be opened again from here (shown quieter, with why). */
    unreachable: Set<String> = emptySet(),
    /** Documents Hramota remembers (their marks and notes are shown in the notebook). */
    documents: List<ua.hramota.data.LibraryEntry> = emptyList(),
    onOpenDocument: (ua.hramota.data.LibraryEntry) -> Unit = {},
) {
    // Nearly the whole screen for the shelf and the recent files: the tabs and settings in one row at the
    // top; opening, making and comparing files behind one round button within reach of the thumb
    // Three tabs: recent files, the shelf of kept documents, the notebook
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (startOnShelf) 1 else 0) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          // With a large font the tabs scroll sideways rather than break words
          Row(Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            for ((i, label) in listOf(stringResource(R.string.recent), stringResource(R.string.shelf, shelf.size), stringResource(R.string.notebook)).withIndex()) {
                val on = tab == i
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.SemiBold else null, maxLines = 1, softWrap = false,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(on, role = androidx.compose.ui.semantics.Role.Tab) { tab = i }.padding(horizontal = 14.dp, vertical = 12.dp))
            }
          }
            if (tab == 0 && recents.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.recent_clear), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
            androidx.compose.material3.IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tab == 2) {
            ua.hramota.notebook.NotebookHome(Modifier.weight(1f).fillMaxWidth(), documents, onOpenDocument)
        } else if (tab == 1) {
            Shelf(shelf, shelves, onOpenShelf, onShelfAction, onPutShelf, onRemoveShelf, onSetShelves, Modifier.weight(1f).fillMaxWidth(), startShelf)
        } else if (recents.isEmpty()) {
            Text(
                stringResource(R.string.recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).weight(1f),
            )
        } else {
            // (room at the foot, so the round button never covers the last file)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recents, key = { it.uri }) { file -> RecentRow(file, now, file.uri in unreachable, { onOpenRecent(file) }, { onRemove(file) }) }
            }
        }
        // Choosing the second file of a comparison: said plainly, with the way to choose it
        if (comparePrompt != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(comparePrompt, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_file))
                }
            }
        }
      }
        if (comparePrompt == null && tab != 2) {
            var actions by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                androidx.compose.material3.FloatingActionButton(onClick = { actions = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_actions))
                }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.open_file)) }, onClick = { actions = false; onOpenFile() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.create_new)) }, onClick = { actions = false; onCreate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.scan)) }, onClick = { actions = false; onScan() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.compare_versions)) }, onClick = { actions = false; onCompare() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(file: RecentFile, now: Long, unreachable: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val format = Format.detect(file.name, null)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.recent_remove))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(format, Modifier.alpha(if (unreachable) 0.5f else 1f))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(file.name.substringBeforeLast('.', file.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (unreachable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(if (unreachable) stringResource(R.string.recent_elsewhere) else whenOpened(file.openedAt, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.recent_remove)) }, onClick = { menu = false; onRemove() })
        }
    }
}

/** "Today, 14:26", "Yesterday, 09:10" or a short date. */
@Composable
private fun whenOpened(at: Long, now: Long): String {
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)
    val day = 86_400_000L
    val zone = java.util.TimeZone.getDefault()
    fun dayIndex(t: Long) = (t + zone.getOffset(t)) / day
    return when (dayIndex(now) - dayIndex(at)) {
        0L -> stringResource(R.string.today_at, time)
        1L -> stringResource(R.string.yesterday_at, time)
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
            (if (dayIndex(now) - dayIndex(at) < 300) DateUtils.FORMAT_NO_YEAR else 0))
    }
}
