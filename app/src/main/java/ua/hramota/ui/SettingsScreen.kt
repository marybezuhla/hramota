package ua.hramota.ui

import androidx.compose.foundation.clickable
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
) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    var cleared by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title = stringResource(R.string.settings), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section(stringResource(R.string.settings_look))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                val themes = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    themes.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = settings.theme == key,
                            onClick = { onChange { it.copy(theme = key) } },
                            icon = {}, shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
            }

            Section(stringResource(R.string.settings_documents))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_docx_open), style = MaterialTheme.typography.bodyLarge)
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
            Row(Modifier.fillMaxWidth().clickable { onClearRecent(); cleared = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    stringResource(if (cleared) R.string.settings_cleared else R.string.settings_clear),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                )
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
