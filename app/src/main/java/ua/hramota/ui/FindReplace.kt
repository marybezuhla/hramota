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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** The place [current] names has been shown (selected): only then does "Replace" change it. */
    var shown by mutableStateOf(false)
    /** After a replacement: carry on from here (the slot and the position just after the new text). */
    var after: Pair<Long, Int>? = null
}

@Composable
fun FindBar(state: FindState, target: Replaceable, version: Any?, enabled: Boolean, onDone: (Int) -> Unit) {
    // Found off the main thread (a big sheet has many cells); nothing while there is nothing to find
    val result by produceState(emptyList<Found>() to emptyMap<Long, Int>(), version, state.query, state.matchCase) {
        value = if (state.query.isEmpty()) emptyList<Found>() to emptyMap() else withContext(Dispatchers.Default) {
            // (a change made meanwhile on the screen: found again with the next change)
            val texts = try { target.searchable() } catch (e: Exception) { emptyList() }
            findAll(texts, state.query, state.matchCase) to texts.withIndex().associate { it.value.first to it.index }
        }
    }
    val found = result.first
    val current = if (found.isEmpty()) -1 else state.current.coerceIn(0, found.size - 1)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    // After a replacement: the next place after the new text is shown (never the new text itself)
    LaunchedEffect(found) {
        val (key, at) = state.after ?: return@LaunchedEffect
        state.after = null
        if (found.isEmpty()) return@LaunchedEffect
        val rank = result.second
        val here = rank[key] ?: -1
        val i = found.indexOfFirst { f -> val r = rank[f.key] ?: -1; r > here || r == here && f.start >= at }.let { if (it < 0) 0 else it }
        state.current = i; state.shown = true
        target.show(found[i])
    }
    fun go(step: Int) {
        if (found.isEmpty()) return
        // The first press shows the place counted, the next ones move on
        state.current = if (state.shown) (current + step + found.size) % found.size else current.coerceAtLeast(0)
        state.shown = true
        target.show(found[state.current])
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.query, { state.query = it.replace("\n", "").replace("\r", ""); state.current = 0; state.shown = false }, singleLine = true,
                    label = { Text(stringResource(R.string.find_what)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(1) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Text(
                    if (state.query.isEmpty()) "" else if (found.isEmpty()) "0" else "${current + 1}/${found.size}",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = { go(-1) }, enabled = found.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous)) }
                IconButton(onClick = { go(1) }, enabled = found.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next)) }
                IconButton(onClick = { state.open = false }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.find_close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.replacement, { state.replacement = it.replace("\n", "").replace("\r", "") }, singleLine = true,
                    label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val f = found.getOrNull(current) ?: return@TextButton
                    // A place not yet shown is shown first: nothing is changed unseen
                    if (!state.shown) { go(0); return@TextButton }
                    if (target.replace(f, state.replacement)) { state.after = f.key to f.start + state.replacement.length; state.shown = false; onDone(1) }
                }, enabled = enabled && current >= 0) { Text(stringResource(R.string.replace_one)) }
                TextButton(onClick = { onDone(target.replaceAll(found, state.replacement)) }, enabled = enabled && found.isNotEmpty()) { Text(stringResource(R.string.replace_all)) }
            }
            FilterChip(state.matchCase, onClick = { state.matchCase = !state.matchCase; state.current = 0; state.shown = false }, label = { Text(stringResource(R.string.find_match_case)) })
        }
    }
}
