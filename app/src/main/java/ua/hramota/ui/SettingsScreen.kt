package ua.hramota.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.data.AppSettings

/** Settings: look, documents, privacy, and what the app is made of. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    version: String,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearRecent: () -> Unit,
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
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                val themes = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
                Column(Modifier.padding(top = 4.dp).selectableGroup()) {
                    themes.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .selectable(selected = settings.theme == key, role = Role.RadioButton) { onChange { it.copy(theme = key) } }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.theme == key, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            Toggle(stringResource(R.string.settings_dynamic), stringResource(R.string.settings_dynamic_hint), settings.dynamicColor) { v -> onChange { it.copy(dynamicColor = v) } }
            // The schemes (not with the wallpaper's colours, which take their place)
            if (!settings.dynamicColor) {
                Text(stringResource(R.string.settings_scheme_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                SchemeRow(stringResource(R.string.settings_scheme_day), Schemes.day, settings.dayScheme) { id -> onChange { it.copy(dayScheme = id) } }
                SchemeRow(stringResource(R.string.settings_scheme_night), Schemes.night, settings.nightScheme) { id -> onChange { it.copy(nightScheme = id) } }
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
            Toggle(stringResource(R.string.settings_editing), stringResource(R.string.settings_editing_hint), checked = false, enabled = false) { }

            Section(stringResource(R.string.settings_privacy))
            Toggle(stringResource(R.string.settings_remember), stringResource(R.string.settings_remember_hint), settings.rememberRecent) { v -> onChange { it.copy(rememberRecent = v) } }
            Toggle(stringResource(R.string.settings_secure), stringResource(R.string.settings_secure_hint), settings.secureScreen) { v -> onChange { it.copy(secureScreen = v) } }
            Toggle(
                stringResource(R.string.vault_title),
                stringResource(if (vaultPossible || vault) R.string.vault_hint else R.string.vault_no_lock),
                vault, enabled = vaultPossible || vault,
            ) { v -> onVault(v) }
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
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A row of small pictures of the schemes, each in its own colours; a tap chooses one. */
@Composable
private fun SchemeRow(title: String, schemes: List<Scheme>, chosen: String, onChoose: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (sc in schemes) {
            val selected = sc.id == chosen || (schemes.none { it.id == chosen } && sc == schemes.first())
            val c = sc.colors
            Column(
                Modifier.width(104.dp).clip(RoundedCornerShape(14.dp)).selectable(selected, role = Role.RadioButton) { onChoose(sc.id) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val frame = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                // A picture only: the name below is what is read aloud
                Surface(shape = RoundedCornerShape(12.dp), border = frame, color = c.background, modifier = Modifier.fillMaxWidth().height(76.dp).clearAndSetSemantics { }) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        // A title, two lines of text on a card, and a button in the accent
                        Box(Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.onBackground))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.surfaceVariant).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurfaceVariant))
                            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurfaceVariant))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(Modifier.width(34.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.primary))
                        }
                    }
                }
                Text(
                    stringResource(sc.name), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
