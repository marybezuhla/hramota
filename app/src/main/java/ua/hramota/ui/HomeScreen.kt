package ua.hramota.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Home: the two main actions at the bottom, within reach of the thumb, and the recent files above.
 * [comparePrompt] replaces the actions while the second file of a comparison is being chosen.
 */
@Composable
fun HomeScreen(
    recents: List<RecentFile>,
    comparePrompt: String?,
    onOpenFile: () -> Unit,
    onCompare: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onClear: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (recents.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.recent_clear), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (recents.isEmpty()) {
            Text(
                stringResource(R.string.recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recents, key = { it.uri }) { file -> RecentRow(file, now, { onOpenRecent(file) }, { onRemove(file) }) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comparePrompt != null) {
                Text(comparePrompt, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
            }
            Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_file))
            }
            if (comparePrompt == null) {
                OutlinedButton(onClick = onCompare, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.compare_versions))
                }
            }
            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(file: RecentFile, now: Long, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val format = Format.detect(file.name, null)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.recent_remove))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(format)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(file.name.substringBeforeLast('.', file.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(whenOpened(file.openedAt, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
