package ua.hramota.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.text.Speller
import ua.hramota.text.Spelling

/** Where the spelling check is: the words passed over this time, and the place after the last change. */
class SpellState {
    var current by mutableIntStateOf(0)
    var shown by mutableStateOf(false)
    /** Words passed over everywhere, until the editor closes (as the dictionaries keep them). */
    val skipped = HashSet<String>()
    var skips by mutableIntStateOf(0)
    /** Own words added: the check is made again. */
    var added by mutableIntStateOf(0)
    var after: Pair<Long, Int>? = null
}

/** A word that looks misspelt: where it is, and the word as written. */
class Mistake(val place: Found, val word: String)

/** All the words that look misspelt in [texts], in reading order; at most [MAX_MISTAKES]. */
fun spellingMistakes(speller: Speller, texts: List<Pair<Long, String>>, skipped: Set<String>): List<Mistake> {
    val out = ArrayList<Mistake>()
    for ((key, text) in texts) {
        if (out.size >= MAX_MISTAKES) break
        for (r in speller.mistakes(text, MAX_MISTAKES - out.size)) {
            val w = text.substring(r.first, r.last + 1)
            if (Speller.plain(w) in skipped) continue
            out += Mistake(Found(key, r.first, r.last + 1), w)
        }
    }
    return out
}

private const val MAX_MISTAKES = 5000

/**
 * The spelling check of an editor: each word that looks misspelt, shown in the text in turn, with the words
 * likely meant. Nothing is changed unless a word is tapped; a word can be passed over here or everywhere, or
 * added to one's own words.
 */
@Composable
fun SpellBar(state: SpellState, target: Replaceable, version: Any?, enabled: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    // Checked off the main thread; (null, …) while checking
    val result by produceState<Pair<List<Mistake>?, Map<Long, Int>>>(null to emptyMap(), version, state.skips, state.added) {
        value = withContext(Dispatchers.Default) {
            val speller = Spelling.speller(app) ?: return@withContext emptyList<Mistake>() to emptyMap()
            val texts = try { target.searchable() } catch (e: Exception) { emptyList() }
            spellingMistakes(speller, texts, state.skipped) to texts.withIndex().associate { it.value.first to it.index }
        }
    }
    val found = result.first
    val current = if (found.isNullOrEmpty()) -1 else state.current.coerceIn(0, found.size - 1)
    val place = found?.getOrNull(current)?.place
    val word = found?.getOrNull(current)?.word
    val suggestions by produceState(emptyList<String>(), word) {
        val w = word
        value = if (w == null) emptyList() else withContext(Dispatchers.Default) { Spelling.speller(app)?.suggestions(w) ?: emptyList() }
    }
    fun show(i: Int) {
        val f = found ?: return
        if (f.isEmpty()) return
        state.current = (i + f.size) % f.size
        state.shown = true
        target.show(f[state.current].place)
    }
    // The first place is shown at once; after a change, the next place after it
    LaunchedEffect(found) {
        val f = found ?: return@LaunchedEffect
        if (f.isEmpty()) return@LaunchedEffect
        val (key, at) = state.after ?: run { if (!state.shown) show(state.current.coerceIn(0, f.size - 1)); return@LaunchedEffect }
        state.after = null
        val rank = result.second
        val here = rank[key] ?: -1
        val i = f.indexOfFirst { m -> val p = m.place; val r = rank[p.key] ?: -1; r > here || r == here && p.start >= at }.let { if (it < 0) 0 else it }
        show(i)
    }
    androidx.compose.material3.Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        found == null -> stringResource(R.string.spell_checking)
                        found.isEmpty() -> stringResource(R.string.spell_none)
                        else -> word ?: ""
                    },
                    style = MaterialTheme.typography.titleMedium, fontWeight = if (found.isNullOrEmpty()) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!found.isNullOrEmpty()) Text("${current + 1}/${found.size}${if (found.size >= MAX_MISTAKES) "+" else ""}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                IconButton(onClick = { show(current - 1) }, enabled = !found.isNullOrEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous)) }
                IconButton(onClick = { show(current + 1) }, enabled = !found.isNullOrEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next)) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.spell_close)) }
            }
            if (place != null && word != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (suggestions.isEmpty()) Text(stringResource(R.string.spell_no_suggestions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (s in suggestions) AssistChip(
                        onClick = {
                            // The place is shown before it is changed: nothing changes unseen
                            if (!state.shown) { show(current); return@AssistChip }
                            if (target.replace(place, s)) { state.after = place.key to place.start + s.length; state.shown = false }
                        },
                        enabled = enabled, label = { Text(s) },
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { show(current + 1) }) { Text(stringResource(R.string.spell_skip)) }
                    TextButton(onClick = { state.skipped += Speller.plain(word); state.after = place.key to place.end; state.shown = false; state.skips++ }) { Text(stringResource(R.string.spell_skip_all)) }
                    TextButton(onClick = {
                        try { Spelling.addOwn(app, word) } catch (e: Exception) {}
                        state.after = place.key to place.end; state.shown = false; state.added++
                    }) { Text(stringResource(R.string.spell_add)) }
                }
            }
        }
    }
}
