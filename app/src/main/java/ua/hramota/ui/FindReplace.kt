package ua.hramota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ua.hramota.R

/** One place a searched text is found: [key] names a paragraph (or a cell), [start]..[end] the characters. */
data class Found(val key: Long, val start: Int, val end: Int)

/** What an editor gives "Find and replace": its texts, showing a place, and changing text there. */
interface Replaceable {
    /** Texts that can be changed, in reading order: key and text. */
    fun searchable(): List<Pair<Long, String>>
    /** Brings a place into view, selected. */
    fun show(f: Found)
    /** Replaces one place (as one step to undo); false if it changed meanwhile. */
    fun replace(f: Found, with: String): Boolean
    /** Replaces all [found] (one step to undo); how many. */
    fun replaceAll(found: List<Found>, with: String): Int
}

/** Every place [query] is found in [texts] (whole, not overlapping). */
fun findAll(texts: List<Pair<Long, String>>, query: String, matchCase: Boolean): List<Found> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Found>()
    for ((key, text) in texts) {
        var from = 0
        while (out.size < MAX_FOUND) {
            val i = text.indexOf(query, from, ignoreCase = !matchCase)
            if (i < 0) break
            out += Found(key, i, i + query.length)
            from = i + query.length
        }
    }
    return out
}

private const val MAX_FOUND = 10_000

class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var current by mutableIntStateOf(0)
}

/**
 * "Find and replace" above an editor's text: what to find and what to put instead, the place shown
 * (N of M), previous and next, replace this one or all. Line breaks are not part of either text.
 */
@Composable
fun FindBar(state: FindState, target: Replaceable, version: Any?, enabled: Boolean, onDone: (Int) -> Unit) {
    val found = remember(version, state.query, state.matchCase) { findAll(target.searchable(), state.query, state.matchCase) }
    val current = if (found.isEmpty()) -1 else state.current.coerceIn(0, found.size - 1)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    fun go(i: Int) {
        if (found.isEmpty()) return
        state.current = (i + found.size) % found.size
        target.show(found[state.current])
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.query, { state.query = it.replace("\n", ""); state.current = 0 }, singleLine = true,
                    label = { Text(stringResource(R.string.find_what)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(current + 1) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Text(
                    if (state.query.isEmpty()) "" else if (found.isEmpty()) "0" else "${current + 1}/${found.size}",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = { go(current - 1) }, enabled = found.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous)) }
                IconButton(onClick = { go(current + 1) }, enabled = found.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next)) }
                IconButton(onClick = { state.open = false }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.find_close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.replacement, { state.replacement = it.replace("\n", "") }, singleLine = true,
                    label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val f = found.getOrNull(current) ?: return@TextButton
                    if (target.replace(f, state.replacement)) onDone(1)
                }, enabled = enabled && current >= 0) { Text(stringResource(R.string.replace_one)) }
                TextButton(onClick = { onDone(target.replaceAll(found, state.replacement)) }, enabled = enabled && found.isNotEmpty()) { Text(stringResource(R.string.replace_all)) }
            }
            FilterChip(state.matchCase, onClick = { state.matchCase = !state.matchCase; state.current = 0 }, label = { Text(stringResource(R.string.find_match_case)) })
        }
    }
}
