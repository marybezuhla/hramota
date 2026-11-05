package ua.hramota.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.ui.theme.Segments
import ua.hramota.ui.theme.Theme
import ua.hramota.ui.theme.ThemeRow
import ua.hramota.data.AppSettings

/** Settings: look, documents, privacy, and what the app is made of. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    version: String,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearRecent: () -> Unit,
    /** Deletes every document kept in Hramota (the shelf); bookmarks and notes stay. */
    onDeleteKept: () -> Boolean = { false },
    onUndoKept: () -> Unit = {},
    onBack: () -> Unit,
    /** Locked storage: on, possible on this phone (a screen lock is set), and turning it on or off. */
    vault: Boolean = false,
    vaultPossible: Boolean = true,
    onVault: (Boolean) -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    dictionaries: List<ua.hramota.data.Dictionary> = emptyList(),
    onAddDictionary: () -> Unit = {},
    onRemoveDictionary: (String) -> Unit = {},
) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    var cleared by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmKept by rememberSaveable { mutableStateOf(false) }
    // (not kept past Hramota stopping: what was put aside is gone by then)
    var keptDeleted by remember { mutableStateOf(false) }
    if (confirmKept) AlertDialog(
        onDismissRequest = { confirmKept = false },
        text = { Text(stringResource(R.string.delete_kept_confirm)) },
        confirmButton = { TextButton(onClick = { confirmKept = false; keptDeleted = onDeleteKept() }) { Text(stringResource(R.string.delete_kept_go), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmKept = false }) { Text(stringResource(R.string.cancel)) } },
    )
    // Forgetting everything deletes kept documents too: said plainly, and asked first
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_confirm_title)) },
        text = { Text(stringResource(R.string.clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClearRecent(); cleared = true }) { Text(stringResource(R.string.forget_go), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title = stringResource(R.string.settings), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section(stringResource(R.string.settings_look))
            // The same as in all the apps: light or dark, then the day and the night theme as small pictures
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.look_mode), style = MaterialTheme.typography.bodyLarge)
                Segments(
                    listOf("system" to stringResource(R.string.mode_auto), "light" to stringResource(R.string.mode_light), "dark" to stringResource(R.string.mode_dark)),
                    settings.theme,
                ) { key -> onChange { it.copy(theme = key) } }
                Text(stringResource(R.string.day_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                // Choosing either brings its pair along (Starry night with Sky, and so on); the other can still be changed after
                ThemeRow(Theme.DAY, Theme.of(settings.dayScheme, Theme.SYSTEM)) { t -> onChange { it.copy(dayScheme = t.key, nightScheme = Theme.pairOf(t).key) } }
                Text(stringResource(R.string.night_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                ThemeRow(Theme.NIGHT, Theme.of(settings.nightScheme, Theme.SYSTEM_DARK)) { t -> onChange { it.copy(nightScheme = t.key, dayScheme = Theme.pairOf(t).key) } }
                Text(stringResource(R.string.look_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section(stringResource(R.string.settings_documents))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_docx_open), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_docx_open_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SegmentedButton(selected = !settings.docxReading, onClick = { onChange { it.copy(docxReading = false) } },
                        icon = {}, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.mode_pages)) }
                    SegmentedButton(selected = settings.docxReading, onClick = { onChange { it.copy(docxReading = true) } },
                        icon = {}, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.mode_reading)) }
                }
            }
            Toggle(stringResource(R.string.settings_inspector), stringResource(R.string.settings_inspector_hint), settings.inspector) { v -> onChange { it.copy(inspector = v) } }
            Toggle(stringResource(R.string.settings_windows), stringResource(R.string.settings_windows_hint), settings.separateWindows) { v -> onChange { it.copy(separateWindows = v) } }

            Section(stringResource(R.string.settings_privacy))
            Toggle(stringResource(R.string.settings_remember), stringResource(R.string.settings_remember_hint), settings.rememberRecent) { v -> onChange { it.copy(rememberRecent = v) } }
            Toggle(stringResource(R.string.settings_secure), stringResource(R.string.settings_secure_hint), settings.secureScreen) { v -> onChange { it.copy(secureScreen = v) } }
            Toggle(
                stringResource(R.string.vault_title),
                stringResource(if (vaultPossible || vault) R.string.vault_hint else R.string.vault_no_lock),
                vault, enabled = vaultPossible || vault,
            ) { v -> onVault(v) }
            if (keptDeleted) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.delete_kept_done), Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { onUndoKept(); keptDeleted = false }) { Text(stringResource(R.string.edit_undo)) }
            }
            else Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { confirmKept = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.delete_kept), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    stringResource(if (cleared) R.string.settings_cleared else R.string.settings_clear),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                )
            }

            Section(stringResource(R.string.dict_section))
            Text(stringResource(R.string.dict_section_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            for (d in dictionaries) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.dict_words, d.words), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onRemoveDictionary(d.id) }) { Text(stringResource(R.string.note_delete)) }
            }
            Row(Modifier.fillMaxWidth().clickable { onAddDictionary() }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.dict_add), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }

            Section(stringResource(R.string.backup_section))
            Text(stringResource(R.string.backup_section_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().clickable { onBackup() }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.backup_create), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().clickable { onRestore() }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.backup_restore), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }

            Section(stringResource(R.string.settings_about))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_privacy_statement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().clickable { licenses = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            // The author's letter and how to support Ukraine, as in the author's other apps
            val context = androidx.compose.ui.platform.LocalContext.current
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                AuthorLetter()
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))
                SupportUkraine(open = { url ->
                    // In the browser; a phone without one simply opens nothing
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                })
            }
        }
    }
    if (licenses) AlertDialog(
        onDismissRequest = { licenses = false },
        confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.insp_close)) } },
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = { Text(stringResource(R.string.licenses_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        // One stop for TalkBack: the row is the switch
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch) { onChange(it) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
