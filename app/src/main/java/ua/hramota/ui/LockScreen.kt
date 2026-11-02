package ua.hramota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.hramota.R

/** Locked storage, closed: nothing of the documents is shown until the owner confirms on the phone's lock screen. */
@Composable
fun LockScreen(keyLost: Boolean = false, onUnlock: () -> Unit, onStartOver: () -> Unit = {}) {
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.vault_start_over)) },
        text = { Text(stringResource(R.string.vault_start_over_confirm)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; onStartOver() }) { Text(stringResource(R.string.vault_start_over), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (keyLost) {
            // The phone no longer has the key (its screen lock was removed, say): say so plainly
            Text(stringResource(R.string.vault_key_lost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            androidx.compose.material3.OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.vault_start_over)) }
        } else {
            Text(stringResource(R.string.lock_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
        }
    }
}
