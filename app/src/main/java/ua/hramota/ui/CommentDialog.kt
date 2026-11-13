package ua.hramota.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.hramota.R

/** Writing a comment (a note on a cell, a slide or a passage): the words, then «Done». */
@Composable
fun CommentDialog(start: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cell_note_title)) },
        text = {
            OutlinedTextField(text, { text = it.take(MAX) }, placeholder = { Text(stringResource(R.string.cell_note_hint)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank() || start.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX = 5000
