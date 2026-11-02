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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.data.LibraryEntry
import ua.hramota.data.ShelfDef
import java.io.File

/**
 * A new backup: the memory always goes in; documents kept in Hramota go in if chosen (all at first,
 * or whole shelves, or one by one); a password typed twice locks the file.
 */
@Composable
fun BackupDialog(kept: List<LibraryEntry>, shelves: List<ShelfDef>, onCreate: (CharArray, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var withDocs by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(kept.map { it.id }.toSet()) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val sizes = remember(kept) { kept.associate { it.id to (it.copy?.let { p -> File(p).length() } ?: 0L) } }
    val size = if (withDocs) chosen.sumOf { sizes[it] ?: 0L } else 0L
    val ok = password.length >= 8 && password == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_memory_always), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (kept.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { withDocs = !withDocs }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.backup_with_documents), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.backup_selected, if (withDocs) chosen.size else 0, kept.size, megabytes(size)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = withDocs, onCheckedChange = null)
                    }
                    if (withDocs) {
                        // Whole shelves at a tap
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (d in shelves) {
                                val ids = if (d.id == ShelfDef.ALL) kept.map { it.id }.toSet() else kept.filter { d.id in it.shelves }.map { it.id }.toSet()
                                if (ids.isEmpty()) continue
                                val all = chosen.containsAll(ids)
                                Row(
                                    Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, if (all) shelfColor(d) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                        .clickable { chosen = if (all) chosen - ids else chosen + ids }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(shelfColor(d)))
                                    Text(if (d.id == ShelfDef.ALL) stringResource(R.string.shelf_all) else d.name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(kept, key = { it.id }) { e ->
                                Row(Modifier.fillMaxWidth().clickable { chosen = if (e.id in chosen) chosen - e.id else chosen + e.id }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = e.id in chosen, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                                    Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(megabytes(sizes[e.id] ?: 0L), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                PasswordField(again, { again = it }, stringResource(R.string.backup_password_again))
                Text(
                    stringResource(when {
                        password.isNotEmpty() && password.length < 8 -> R.string.backup_password_short
                        again.isNotEmpty() && again != password -> R.string.backup_password_differs
                        else -> R.string.backup_password_hint
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((password.isNotEmpty() && password.length < 8) || (again.isNotEmpty() && again != password)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onCreate(password.toCharArray(), if (withDocs) chosen else emptySet()) }) { Text(stringResource(R.string.backup_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The password of a backup being restored. */
@Composable
fun RestoreDialog(problem: ua.hramota.data.Backup.Reason?, onRestore: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_restore_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                if (problem != null) Text(stringResource(when (problem) {
                    ua.hramota.data.Backup.Reason.NOT_A_BACKUP -> R.string.backup_not_backup
                    ua.hramota.data.Backup.Reason.NO_SPACE -> R.string.backup_no_space
                    ua.hramota.data.Backup.Reason.LOCKED -> R.string.backup_locked
                    else -> R.string.backup_wrong
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { onRestore(password.toCharArray()) }) { Text(stringResource(R.string.backup_restore_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Work under way: what and how far. */
@Composable
fun BusyDialog(text: String, done: Int, total: Int) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, { onValue(it.take(200)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun megabytes(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, bytes)
