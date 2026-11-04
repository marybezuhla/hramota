package ua.hramota.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.R
import ua.hramota.data.AppSettings
import kotlin.math.roundToInt

/** How a book looks, changed while reading: paper, type, size, spacing, margins, pages and turning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSheet(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReadingControls(settings, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingControls(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Paper: the theme's own, then the four papers
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val names = mapOf("auto" to R.string.paper_auto, "paper" to R.string.paper_paper, "sepia" to R.string.paper_sepia, "gray" to R.string.paper_gray, "night" to R.string.paper_night)
            for ((name, label) in names) {
                // «Тема»: the theme's own paper
                val p = paperFor(name)
                val selected = settings.readPaper == name
                val text = stringResource(label)
                Column(
                    Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.RadioButton) { onChange { it.copy(readPaper = name) } }
                        .semantics { this.selected = selected; contentDescription = text }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(p.background)
                            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = p.text, fontFamily = Literata, fontSize = 16.sp) }
                    Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                }
            }
        }
        Choice(
            listOf("serif" to stringResource(R.string.read_font_serif), "sans" to stringResource(R.string.read_font_sans)),
            settings.readFont,
        ) { v -> onChange { it.copy(readFont = v) } }
        Level(stringResource(R.string.read_size), "${settings.readSize.roundToInt()}", settings.readSize, 14f..32f, 17) { v -> onChange { it.copy(readSize = v.roundToInt().toFloat()) } }
        Level(stringResource(R.string.read_spacing), "%.1f".format(settings.readSpacing), settings.readSpacing, 1.2f..2.0f, 7) { v -> onChange { it.copy(readSpacing = (v * 10).roundToInt() / 10f) } }
        Level(stringResource(R.string.read_margin), "${settings.readMargin.roundToInt()}", settings.readMargin, 8f..48f, 9) { v -> onChange { it.copy(readMargin = (v / 4).roundToInt() * 4f) } }
        Toggle(stringResource(R.string.read_justify), stringResource(R.string.read_justify_hint), settings.readJustify) { v -> onChange { it.copy(readJustify = v) } }
        Choice(
            listOf("pages" to stringResource(R.string.read_pages), "scroll" to stringResource(R.string.read_scroll)),
            if (settings.readPaged) "pages" else "scroll",
        ) { v -> onChange { it.copy(readPaged = v == "pages") } }
        if (settings.readPaged) {
            Text(stringResource(R.string.read_turn), style = MaterialTheme.typography.bodyLarge)
            Choice(
                listOf("slide" to stringResource(R.string.read_turn_slide), "curl" to stringResource(R.string.read_turn_curl), "none" to stringResource(R.string.read_turn_none)),
                settings.readTurn,
            ) { v -> onChange { it.copy(readTurn = v) } }
            Toggle(stringResource(R.string.read_volume_keys), null, settings.readVolumeKeys) { v -> onChange { it.copy(readVolumeKeys = v) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(selected = value == key, onClick = { onPick(key) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Level(label: String, shown: String, value: Float, range: ClosedFloatingPointRange<Float>, stops: Int, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, steps = (stops - 2).coerceAtLeast(0))
    }
}

@Composable
private fun Toggle(label: String, hint: String?, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onValue(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = null)
    }
}

