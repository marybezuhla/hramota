package ua.hramota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.hramota.R
import ua.hramota.doc.Inspection

/** One finding of the inspector: a short label for the banner and a sentence for the details. */
private class Finding(val label: String, val detail: String, val risky: Boolean)

@Composable
private fun findings(i: Inspection): List<Finding> = buildList {
    if (i.macros) add(Finding(stringResource(R.string.insp_macros), stringResource(R.string.insp_macros_detail), true))
    if (i.javascript > 0) add(Finding(stringResource(R.string.insp_js), stringResource(R.string.insp_js_detail), true))
    if (i.launchActions > 0) add(Finding(stringResource(R.string.insp_launch), stringResource(R.string.insp_launch_detail), true))
    if (i.embedded.isNotEmpty()) add(Finding(
        pluralStringResource(R.plurals.insp_embedded, i.embedded.size, i.embedded.size),
        stringResource(R.string.insp_embedded_detail, i.embedded.filter { it != "?" }.take(10).joinToString(", ").ifEmpty { "—" }), true))
    if (i.remoteResources > 0) add(Finding(stringResource(R.string.insp_remote), stringResource(R.string.insp_remote_detail), true))
    if (i.dde) add(Finding(stringResource(R.string.insp_dde), stringResource(R.string.insp_dde_detail), true))
    if (i.links > 0) add(Finding(pluralStringResource(R.plurals.insp_links, i.links, i.links), stringResource(R.string.insp_links_detail), false))
    if (i.trackedChanges) add(Finding(stringResource(R.string.insp_changes), stringResource(R.string.insp_changes_detail), false))
    if (i.comments > 0) add(Finding(pluralStringResource(R.plurals.insp_comments, i.comments, i.comments), stringResource(R.string.insp_comments_detail), false))
    if (i.hiddenText) add(Finding(stringResource(R.string.insp_hidden), stringResource(R.string.insp_hidden_detail), false))
    val people = listOfNotNull(i.author, i.lastModifiedBy?.takeIf { it != i.author })
    if (people.isNotEmpty()) add(Finding(stringResource(R.string.insp_author), stringResource(R.string.insp_author_detail, people.joinToString(", ")), false))
}

/**
 * A quiet line under the top bar when a document carries more than its text. Risky things are
 * named first and tinted; a tap explains each one and what Hramota does about it.
 */
@Composable
fun InspectorBanner(inspection: Inspection) {
    val list = findings(inspection)
    if (list.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val risky = inspection.risky
    val bg = if (risky) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (risky) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(bg).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (risky) R.string.insp_banner_risky else R.string.insp_banner, list.joinToString(" · ") { it.label }),
            style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f), maxLines = 2,
        )
        Text(stringResource(R.string.insp_more), style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.insp_close)) } },
        title = { Text(stringResource(R.string.insp_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (f in list) Column {
                    Text(f.label, style = MaterialTheme.typography.titleSmall, color = if (f.risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.insp_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
