package ua.hramota.notebook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import ua.hramota.R
import ua.hramota.data.LibraryEntry

/** Where the list is looking: everything, one notebook, the starred ones, the documents' marks, or the bin. */
private const val ALL = "all"
private const val STARRED = "starred"
private const val FROM_DOCS = "docs"
private const val BIN = "bin"

/** The text found and a little around it, for the search results. */
internal fun snippet(text: String, query: String, around: Int = 40): Pair<String, IntRange>? {
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return null
    val from = (i - around).coerceAtLeast(0)
    val to = (i + query.length + around).coerceAtMost(text.length)
    val prefix = if (from > 0) "…" else ""
    val s = prefix + text.substring(from, to).replace('\n', ' ') + if (to < text.length) "…" else ""
    val at = prefix.length + (i - from)
    return s to (at until at + query.length)
}

/** Notes that have [query] (all its words, in any order) in their title or text; locked ones are not searched. */
fun searchNotes(notes: List<NoteMeta>, query: String): List<NoteMeta> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return notes
    return notes.filter { n -> !n.locked && words.all { w -> n.text.contains(w, ignoreCase = true) } }
}

/**
 * The notebook tab: notebooks as coloured covers, the notes in a list or as tiles (pinned first),
 * starred ones, the marks and notes made in documents, the bin, and search through everything
 * written, handwriting included.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookHome(modifier: Modifier, documents: List<LibraryEntry>, onOpenDocument: (LibraryEntry) -> Unit) {
    val context = LocalContext.current
    val books by Notebook.books(context).collectAsState()
    val all by Notebook.notes(context).collectAsState()
    var where by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var tiles by rememberSaveable { mutableStateOf(false) }
    var newBook by remember { mutableStateOf<Book?>(null) }
    var bookMenu by remember { mutableStateOf<Book?>(null) }
    var noteMenu by remember { mutableStateOf<NoteMeta?>(null) }
    var creating by remember { mutableStateOf(false) }
    var emptyBin by remember { mutableStateOf(false) }

    fun open(m: NoteMeta) = context.startActivity(NoteActivity.intent(context, m.id))
    fun create(template: String?, ink: Boolean = false) {
        val book = books.firstOrNull { it.id == where }?.id
        val (title, blocks) = when {
            template != null -> NoteTemplates.make(context, template)
            ink -> "" to listOf(NBlock.Ink(1, emptyList(), 600f), NBlock.Text(2, ""))
            else -> "" to listOf(NBlock.Text(1, ""))
        }
        val m = try { Notebook.create(context, book, if (ink) "lines" else "plain", blocks, title) } catch (e: Exception) { null } ?: return
        open(m)
    }

    val shown = remember(all, where, query) {
        val base = when (where) {
            BIN -> all.filter { it.deleted > 0 }.sortedByDescending { it.deleted }
            STARRED -> all.filter { it.deleted == 0L && it.starred }
            ALL, FROM_DOCS -> all.filter { it.deleted == 0L }
            else -> all.filter { it.deleted == 0L && it.book == where }
        }
        searchNotes(base, query).let { l -> if (where == BIN) l else l.sortedWith(compareByDescending<NoteMeta> { it.pinned }.thenByDescending { it.updated }) }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                query, { query = it.replace("\n", "") }, singleLine = true,
                placeholder = { Text(stringResource(R.string.notebook_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            // Notebooks and the other places, in one row that scrolls sideways
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(where == ALL, { where = ALL }, label = { Text(stringResource(R.string.notebook_all, all.count { it.deleted == 0L })) })
                FilterChip(where == STARRED, { where = STARRED }, label = { Text("★ " + stringResource(R.string.notebook_starred)) })
                for (b in books) Box {
                    // A chosen notebook: tapped again, its name and colour can be changed
                    FilterChip(where == b.id, { if (where == b.id) bookMenu = b else where = b.id }, label = { Text(b.title + if (where == b.id) "  ⋯" else "", maxLines = 1) },
                        leadingIcon = { Box(Modifier.size(12.dp).background(Color(b.color), CircleShape)) })
                    DropdownMenu(bookMenu == b, onDismissRequest = { bookMenu = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.notebook_rename)) }, onClick = { bookMenu = null; newBook = b })
                        DropdownMenuItem(text = { Text(stringResource(R.string.notebook_remove), color = MaterialTheme.colorScheme.error) }, onClick = {
                            bookMenu = null; if (where == b.id) where = ALL; runCatching { Notebook.removeBook(context, b.id) }
                        })
                    }
                }
                TextButton(onClick = { newBook = Notebook.newBook("", Notebook.COLORS[books.size % Notebook.COLORS.size]) }) { Text(stringResource(R.string.notebook_new)) }
                FilterChip(where == FROM_DOCS, { where = FROM_DOCS }, label = { Text(stringResource(R.string.notebook_from_docs)) })
                FilterChip(where == BIN, { where = BIN }, label = { Text(stringResource(R.string.notebook_bin)) })
                TextButton(onClick = { tiles = !tiles }) { Text(stringResource(if (tiles) R.string.notebook_as_list else R.string.notebook_as_tiles)) }
            }
            if (Notebook.unreadable) Text(stringResource(R.string.delete_kept_locked), modifier = Modifier.padding(16.dp))
            if (where == FROM_DOCS) {
                FromDocuments(documents, query, onOpenDocument)
            } else {
                if (where == BIN) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notebook_bin_hint, Notebook.BIN_DAYS.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (shown.isNotEmpty()) TextButton(onClick = { emptyBin = true }) { Text(stringResource(R.string.notebook_empty_bin), color = MaterialTheme.colorScheme.error) }
                }
                if (shown.isEmpty()) Text(
                    stringResource(if (query.isNotBlank()) R.string.notebook_nothing_found else if (where == BIN) R.string.notebook_bin_empty else R.string.notebook_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                )
                LazyVerticalGrid(
                    if (tiles) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { m ->
                        Box {
                            NoteCard(m, books.firstOrNull { it.id == m.book }, query, tiles, onClick = { if (where == BIN) noteMenu = m else open(m) }, onLong = { noteMenu = m })
                            DropdownMenu(noteMenu == m, onDismissRequest = { noteMenu = null }) {
                                if (m.deleted > 0) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notebook_restore)) }, onClick = { noteMenu = null; runCatching { Notebook.toBin(context, m.id, back = true) } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notebook_delete_forever), color = MaterialTheme.colorScheme.error) }, onClick = { noteMenu = null; runCatching { Notebook.deleteForGood(context, m.id) } })
                                } else {
                                    DropdownMenuItem(text = { Text(stringResource(if (m.pinned) R.string.note_unpin else R.string.note_pin)) }, onClick = { noteMenu = null; runCatching { Notebook.update(context, m.id) { it.copy(pinned = !it.pinned) } } })
                                    DropdownMenuItem(text = { Text(stringResource(if (m.starred) R.string.note_unstar else R.string.note_star)) }, onClick = { noteMenu = null; runCatching { Notebook.update(context, m.id) { it.copy(starred = !it.starred) } } })
                                    for (b in listOf<Book?>(null) + books) if (b?.id != m.book) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.note_move_to_named, b?.title ?: stringResource(R.string.notebook_none))) },
                                        onClick = { noteMenu = null; runCatching { Notebook.update(context, m.id) { it.copy(book = b?.id) } } },
                                    )
                                    DropdownMenuItem(text = { Text(stringResource(R.string.note_to_bin), color = MaterialTheme.colorScheme.error) }, onClick = { noteMenu = null; runCatching { Notebook.toBin(context, m.id) } })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (where != BIN && where != FROM_DOCS) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { creating = true }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            DropdownMenu(creating, onDismissRequest = { creating = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.note_new_text)) }, onClick = { creating = false; create(null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.note_new_ink)) }, onClick = { creating = false; create(null, ink = true) })
                for (k in NoteTemplates.KEYS) DropdownMenuItem(text = { Text(stringResource(NoteTemplates.label(k))) }, onClick = { creating = false; create(k) })
            }
        }
    }

    newBook?.let { b -> BookDialog(b, onDismiss = { newBook = null }, onDone = { done -> newBook = null; runCatching { Notebook.putBook(context, done) }; where = done.id }) }
    if (emptyBin) AlertDialog(
        onDismissRequest = { emptyBin = false },
        text = { Text(stringResource(R.string.notebook_empty_bin_confirm)) },
        confirmButton = { TextButton(onClick = { emptyBin = false; runCatching { Notebook.emptyBin(context) } }) { Text(stringResource(R.string.notebook_empty_bin), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { emptyBin = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(m: NoteMeta, book: Book?, query: String, tile: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val date = remember(m.updated) { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(m.updated)) }
    val title = m.title.ifBlank { m.preview.lineSequence().firstOrNull()?.take(80) ?: "" }.ifBlank { stringResource(R.string.note_untitled) }
    Surface(
        shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().then(if (tile) Modifier.heightIn(min = 150.dp) else Modifier).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        Row {
            // The notebook's colour along the edge, as a cover
            Box(Modifier.width(6.dp).heightIn(min = if (tile) 150.dp else 64.dp).background(book?.let { Color(it.color) } ?: colors.outlineVariant))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.pinned) Text("📌 ", style = MaterialTheme.typography.bodySmall)
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (m.starred) Text(" ★", color = colors.primary)
                }
                when {
                    m.locked -> Text("🔒 " + stringResource(R.string.note_locked_list), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    query.isNotBlank() -> snippet(m.text, query.trim().split(Regex("\\s+")).first())?.let { (s, r) ->
                        Text(buildAnnotatedString {
                            append(s.substring(0, r.first))
                            withStyle(SpanStyle(background = Color(NoteExport.HIGHLIGHT).copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)) { append(s.substring(r.first, r.last + 1)) }
                            append(s.substring(r.last + 1))
                        }, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                    m.preview.isNotBlank() -> Text(m.preview, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = if (tile) 6 else 2, overflow = TextOverflow.Ellipsis)
                }
                Text((if (m.hasInk) "✍ " else "") + date + (book?.let { " · " + it.title } ?: ""), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BookDialog(b: Book, onDismiss: () -> Unit, onDone: (Book) -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var color by remember { mutableStateOf(b.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it.replace("\n", "").take(60) }, singleLine = true, label = { Text(stringResource(R.string.notebook_name)) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in Notebook.COLORS) Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                            .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
                            .clickable { color = c },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(b.copy(title = title.trim(), color = color)) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Marks and notes made while reading documents: by document, opened where they are. */
@Composable
private fun FromDocuments(documents: List<LibraryEntry>, query: String, onOpen: (LibraryEntry) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val withMarks = documents.filter { it.notes.isNotEmpty() || it.bookmarks.isNotEmpty() }
        .filter { e -> query.isBlank() || e.title.contains(query, true) || (e.docTitle ?: "").contains(query, true) || e.notes.any { it.text.contains(query, true) } }
        .sortedByDescending { e -> e.notes.maxOfOrNull { it.at } ?: e.openedAt }
    if (withMarks.isEmpty()) { Text(stringResource(R.string.notebook_from_docs_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(20.dp)); return }
    LazyVerticalGrid(GridCells.Fixed(1), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(withMarks, key = { it.id }) { e ->
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(e) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.coverTitle ?: e.docTitle ?: e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(stringResource(R.string.notebook_doc_marks, e.notes.size, e.bookmarks.size), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    for (n in e.notes.filter { it.text.isNotBlank() }.take(5)) Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp, end = 8.dp).size(10.dp).background(ua.hramota.ui.NOTE_COLORS[n.color.coerceIn(0, 3)], CircleShape))
                        Text(n.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

