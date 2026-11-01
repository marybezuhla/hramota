package ua.hramota.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.hramota.doc.Search
import ua.hramota.doc.SearchHit
import ua.hramota.doc.TextPages
import ua.hramota.doc.TextSelection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.R
import ua.hramota.doc.Format
import ua.hramota.doc.Inspection
import ua.hramota.doc.Paragraph
import ua.hramota.doc.Pages

/** What the viewer shows for one file. */
sealed interface ViewerState {
    data object Loading : ViewerState
    data object Failed : ViewerState
    data object Password : ViewerState
    data class Unsupported(val format: Format) : ViewerState
    data class Text(val paragraphs: List<Paragraph>, val format: Format) : ViewerState
    /** A table of plain cells (CSV); the first row is shown as the header. */
    data class Table(val rows: List<List<String>>) : ViewerState
    /** Pages as they print; [reading] is the same text for the reading view, when there is one. */
    data class Paged(val pages: Pages, val reading: List<Paragraph>? = null, val inspection: Inspection? = null) : ViewerState
}

@Composable
fun ViewerScreen(
    name: String,
    state: ViewerState,
    onBack: () -> Unit,
    startInReading: Boolean = false,
    bookmarks: Set<Int> = emptySet(),
    onToggleBookmark: (Int) -> Unit = {},
    startInOverview: Boolean = false,
    startSearch: String? = null,
    showInspector: Boolean = true,
) {
    var reading by rememberSaveable { mutableStateOf(startInReading) }
    var overview by rememberSaveable { mutableStateOf(startInOverview) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val textPages = (state as? ViewerState.Paged)?.pages as? TextPages

    // Search: pages are scanned in order and results appear as they are found
    var searching by rememberSaveable { mutableStateOf(startSearch != null) }
    var query by rememberSaveable { mutableStateOf(startSearch ?: "") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    // The query whose search has finished: "not found" is said only about that one
    var searched by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(textPages, query, searching) {
        hits = emptyList(); hitIndex = 0; scanning = false; searched = null
        if (!searching || textPages == null || query.isBlank()) return@LaunchedEffect
        scanning = true
        delay(300)
        val found = ArrayList<SearchHit>()
        for (p in 0 until textPages.pageCount) {
            val pageText = textPages.pageText(p)
            withContext(Dispatchers.Default) { Search.find(pageText, query) }.forEach { found += SearchHit(p, it.first, it.last + 1) }
            if (p % 8 == 7 || p == textPages.pageCount - 1 || found.size >= MAX_HITS) hits = found.toList()
            if (found.size >= MAX_HITS) break
        }
        hits = found.toList()
        scanning = false
        searched = query
    }
    val hitsByPage = remember(hits) { hits.groupBy { it.page } }
    val currentHit = hits.getOrNull(hitIndex)
    LaunchedEffect(currentHit) { currentHit?.let { list.animateScrollToItem(it.page) } }

    var selection by remember { mutableStateOf<TextSelection?>(null) }
    BackHandler(enabled = overview || searching || selection != null) {
        when { selection != null -> selection = null; searching -> searching = false; else -> overview = false }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (searching) SearchBar(
            query = query,
            onQuery = { query = it },
            position = if (hits.isEmpty()) 0 else hitIndex + 1,
            total = hits.size,
            scanning = scanning || searched != query,
            onPrev = { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size },
            onNext = { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size },
            onClose = { searching = false },
        ) else TopBar(title = name, onBack = { if (overview) overview = false else onBack() }) {
            if (textPages != null && !reading && !overview) IconButton(onClick = { searching = true; selection = null }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_open))
            }
            if (state is ViewerState.Paged && !reading) {
                if (!overview) IconButton(onClick = { onToggleBookmark(current) }) {
                    val marked = current in bookmarks
                    Icon(
                        if (marked) AppIcons.Bookmark else AppIcons.BookmarkOutline,
                        contentDescription = stringResource(if (marked) R.string.bookmark_remove else R.string.bookmark_add),
                        tint = if (marked) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { overview = !overview }) {
                    Icon(AppIcons.Overview, contentDescription = stringResource(R.string.overview), tint = if (overview) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            if (state is ViewerState.Paged && state.reading != null && !overview) {
                TextButton(onClick = { reading = !reading }) {
                    Text(stringResource(if (reading) R.string.mode_pages else R.string.mode_reading))
                }
            }
        }
        val inspection = (state as? ViewerState.Paged)?.inspection
        if (showInspector && inspection != null && inspection.notable && !overview) InspectorBanner(inspection)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                ViewerState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading), modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ViewerState.Failed -> Message(stringResource(R.string.open_failed), null)
                ViewerState.Password -> Message(stringResource(R.string.password_title), stringResource(R.string.password_body))
                is ViewerState.Unsupported -> Message(
                    stringResource(R.string.unsupported_title),
                    stringResource(R.string.unsupported_body, state.format.label.ifEmpty { name.substringAfterLast('.', "?").uppercase() }),
                )
                is ViewerState.Text -> TextDocument(state)
                is ViewerState.Table -> TableDocument(state.rows)
                is ViewerState.Paged -> when {
                    reading && state.reading != null -> TextDocument(ViewerState.Text(state.reading, Format.DOCX), note = false)
                    overview -> PageOverview(state.pages, bookmarks, current) { page ->
                        overview = false
                        scope.launch { list.scrollToItem(page) }
                    }
                    else -> ZoomablePages(
                        state.pages, list, bookmarks,
                        hits = if (searching) hitsByPage else emptyMap(),
                        currentHit = if (searching) currentHit else null,
                        selection = selection,
                        onSelection = { selection = it },
                    )
                }
            }
        }
        val sel = selection
        if (sel != null && textPages != null) SelectionBar(
            onCopy = {
                scope.launch {
                    copyText(context, selectedText(textPages, sel))
                    selection = null
                }
            },
            onShare = {
                scope.launch {
                    val t = selectedText(textPages, sel)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
                }
            },
            onClose = { selection = null },
        )
    }
}

/** Most matches collected for one search. */
private const val MAX_HITS = 5000

private suspend fun selectedText(pages: TextPages, sel: TextSelection): String {
    val t = pages.pageText(sel.page)
    return t.substring(sel.start.coerceIn(0, t.length), sel.end.coerceIn(0, t.length)).replace('\uFFFC', ' ').trim()
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

/** The search line in place of the title: field, "3 of 12", previous / next, close. */
@Composable
private fun SearchBar(
    query: String, onQuery: (String) -> Unit, position: Int, total: Int, scanning: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
    // The field keeps its own cursor; it starts at the end of any text already there
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close)) }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = field,
                onValueChange = { v -> field = v; onQuery(v.text.take(Search.MAX_QUERY)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotBlank()) Text(
            when {
                total > 0 -> stringResource(R.string.search_position, position, total) + if (scanning) "…" else ""
                scanning -> "…"
                else -> stringResource(R.string.search_none)
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = onPrev, enabled = total > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.search_prev)) }
        IconButton(onClick = onNext, enabled = total > 0) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next)) }
    }
}

/** Actions for selected text, at the bottom within reach of the thumb. */
@Composable
private fun SelectionBar(onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
            TextButton(onClick = onShare) { Text(stringResource(R.string.share)) }
            Box(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection)) }
        }
    }
}

@Composable
private fun Message(title: String, body: String?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Reading view: text flows to the screen width, headings stand out, text can be selected and copied. */
@Composable
private fun TextDocument(state: ViewerState.Text, note: Boolean = state.format == Format.ODT || state.format == Format.DOC) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            if (note) item {
                Text(
                    stringResource(R.string.simplified_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                )
            }
            itemsIndexed(state.paragraphs) { _, p -> ParagraphText(p) }
        }
    }
}

@Composable
private fun ParagraphText(p: Paragraph) {
    val style = when (p.heading) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        0 -> MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
        else -> MaterialTheme.typography.titleSmall
    }
    if (p.text.isBlank()) {
        Box(Modifier.padding(vertical = 4.dp))
    } else {
        Text(
            p.text,
            style = style,
            fontWeight = if (p.heading > 0) FontWeight.SemiBold else null,
            modifier = Modifier.padding(top = if (p.heading > 0) 16.dp else 4.dp, bottom = 4.dp),
        )
    }
}
