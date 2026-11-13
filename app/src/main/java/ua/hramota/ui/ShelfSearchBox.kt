package ua.hramota.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.data.LibraryEntry
import ua.hramota.data.ShelfSearch

/**
 * Search through all the shelf's documents: their text is taken out once, in the background, and
 * the places found are listed by document, with the page (PDF) or the words around them.
 */
@Composable
fun ShelfSearchBox(shelf: List<LibraryEntry>, modifier: Modifier, onOpen: (LibraryEntry, Int, Boolean) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var preparing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var ready by remember { mutableStateOf(0) }
    // The documents not read yet: read one by one (a big PDF takes a while), the search grows as they come
    LaunchedEffect(shelf) {
        val todo = withContext(Dispatchers.IO) { ShelfSearch.clean(context, shelf.map { it.id }.toSet()); ShelfSearch.missing(context, shelf) }
        for ((i, e) in todo.withIndex()) {
            preparing = i to todo.size
            withContext(Dispatchers.IO) { ShelfSearch.index(context, e) }
            ready++
        }
        preparing = null
    }
    val hits by produceState(emptyList<ShelfSearch.Hit>(), query, ready, shelf) {
        if (query.trim().length < 2) { value = emptyList(); return@produceState }
        delay(250)
        value = withContext(Dispatchers.IO) { ShelfSearch.search(context, shelf, query.trim()) }
    }
    Column(modifier) {
        OutlinedTextField(query, { query = it.replace("\n", "").take(200) }, singleLine = true,
            placeholder = { Text(stringResource(R.string.shelf_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        preparing?.let { (i, n) ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.shelf_search_preparing, i + 1, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (query.trim().length >= 2 && hits.isEmpty() && preparing == null) Text(stringResource(R.string.notebook_nothing_found), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits, key = { "${it.entry.id}-${it.place}-${it.match.first}" }) { h ->
                Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(h.entry, h.place, h.pages) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(shownTitle(h.entry), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h.place >= 0 && h.pages) Text(stringResource(R.string.shelf_search_page, h.place + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(buildAnnotatedString {
                            val s = h.snippet; val r = h.match
                            val a = r.first.coerceIn(0, s.length); val b = (r.last + 1).coerceIn(a, s.length)
                            append(s.substring(0, a))
                            withStyle(SpanStyle(background = Color(0x99FFE066), fontWeight = FontWeight.SemiBold)) { append(s.substring(a, b)) }
                            append(s.substring(b))
                        }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
