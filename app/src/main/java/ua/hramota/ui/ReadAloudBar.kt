package ua.hramota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.hramota.R
import ua.hramota.speech.ReadAloud

/** Speeds offered: a little slower to a little faster than a calm narrator. */
internal val READ_SPEEDS = listOf(0.8f, 0.9f, 1f, 1.2f, 1.5f)

internal fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()},0" else s.toString().replace('.', ',')

/**
 * The player at the bottom, within the thumb's reach: the sentence back and forward, pause, speed and the sleep
 * timer at hand; the bookmark, the voice and closing below them.
 */
@Composable
internal fun ReadAloudBar(state: ReadAloud.State, onBookmark: () -> Unit, onSettings: () -> Unit) {
    // A notice (no voice for a language) is said for a few seconds
    LaunchedEffect(state.notice) { if (state.notice != null) { delay(6000); ReadAloud.clearNotice() } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val notice = when { state.notice == ReadAloud.NOTICE_NO_VOICE -> R.string.read_no_voice; state.notice == ReadAloud.NOTICE_NO_LANGUAGE -> R.string.read_no_language; state.preparing -> R.string.read_preparing; else -> null }
            if (notice != null) Text(
                stringResource(notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(speedLabel(state.speed) + "×", maxLines = 1) }
                IconButton(onClick = { ReadAloud.skip(-1) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_previous), contentDescription = stringResource(R.string.read_previous))
                }
                FilledIconButton(onClick = { ReadAloud.toggle() }, modifier = Modifier.size(64.dp)) {
                    Icon(painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (state.playing) R.string.read_pause else R.string.read_play), modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { ReadAloud.skip(1) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_next), contentDescription = stringResource(R.string.read_next))
                }
                TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        when (val at = state.sleepAt) {
                            null -> "☾"
                            ReadAloud.SLEEP_CHAPTER -> "☾ §"
                            else -> "☾ " + ((at - android.os.SystemClock.uptimeMillis()) / 60_000 + 1).coerceAtLeast(1)
                        }, maxLines = 1,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBookmark, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(AppIcons.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.read_bookmark), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
                TextButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.read_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { ReadAloud.stop() }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.read_stop))
                }
            }
        }
    }
}

/** The voice, the speed and the sleep timer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadAloudSheet(state: ReadAloud.State, chapters: Boolean, onVoice: (String) -> Unit, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.read_aloud), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.read_voice).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.selectableGroup()) {
                for ((id, title, hint) in listOf(
                    Triple(ReadAloud.HRAMOTA, R.string.read_voice_hramota, R.string.read_voice_hramota_hint),
                    Triple(ReadAloud.PHONE, R.string.read_voice_phone, R.string.read_voice_phone_hint),
                )) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(state.voice == id, role = Role.RadioButton) { onVoice(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.voice == id, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.read_speed, speedLabel(state.speed)).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in READ_SPEEDS) FilterChip(selected = state.speed == s, onClick = { onSpeed(s) }, label = { Text(speedLabel(s) + "×") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text(stringResource(R.string.read_sleep).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val on = state.sleepAt
                FilterChip(selected = on == null, onClick = { ReadAloud.sleep(0) }, label = { Text(stringResource(R.string.read_sleep_off)) }, modifier = Modifier.heightIn(min = 48.dp))
                for (m in listOf(15L, 30L, 60L)) FilterChip(selected = false, onClick = { ReadAloud.sleep(m) }, label = { Text(stringResource(R.string.read_sleep_minutes, m.toInt())) }, modifier = Modifier.heightIn(min = 48.dp))
                if (chapters) FilterChip(selected = on == ReadAloud.SLEEP_CHAPTER, onClick = { ReadAloud.sleep(ReadAloud.SLEEP_CHAPTER) }, label = { Text(stringResource(R.string.read_sleep_chapter)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
