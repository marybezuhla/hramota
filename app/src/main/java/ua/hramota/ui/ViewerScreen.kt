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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
    data class Table(val table: ua.hramota.doc.CsvTable) : ViewerState
    /** A spreadsheet (XLSX, XLS). */
    data class Sheets(val workbook: ua.hramota.doc.sheet.Workbook) : ViewerState
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
    /** Where reading stopped last time (page, or position in the text); reported back as it changes. */
    startPosition: Int = 0,
    onPosition: (Int, Int) -> Unit = { _, _ -> },
) {
    var reading by rememberSaveable { mutableStateOf(startInReading) }
    var overview by rememberSaveable { mutableStateOf(startInOverview) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current by remember { derivedStateOf { list.firstVisibleItemIndex } }
    // After rotation the saved page may not be laid out yet: go there once it is
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    // Opening a document again: go to where reading stopped (once its pages are there)
    LaunchedEffect(startPosition) { if (startPosition > 0 && savedPage == 0) { savedPage = startPosition; restored = false } }
    val laidPages = (state as? ViewerState.Paged)?.pages
    LaunchedEffect(current, laidPages?.pageCount, laidPages?.complete) {
        if (laidPages == null) return@LaunchedEffect
        if (!restored) {
            when {
                savedPage <= current || (laidPages.complete && savedPage >= laidPages.pageCount) -> restored = true
                savedPage < laidPages.pageCount -> { list.scrollToItem(savedPage); restored = true }
            }
        } else savedPage = current
    }
    val textPages = (state as? ViewerState.Paged)?.pages as? TextPages
    // The reading view, plain text and tables scroll in their own list
    val textList = rememberLazyListState()
    var sheetIndex by rememberSaveable { mutableIntStateOf(0) }
    val sheetState = remember { SheetViewState() }
    val currentSheet = (state as? ViewerState.Sheets)?.workbook?.sheets?.getOrNull(sheetIndex)
    val readingParagraphs = (state as? ViewerState.Paged)?.reading?.takeIf { reading }
    // What search looks through: pages, paragraphs or table rows
    val source: Searchable? = when {
        overview -> null
        readingParagraphs != null -> remember(readingParagraphs) { Searchable(readingParagraphs.size) { readingParagraphs[it].text } }
        textPages != null -> remember(textPages) { Searchable({ textPages.pageCount }, { textPages.pageText(it) }, { textPages.complete }) }
        state is ViewerState.Text -> remember(state) { Searchable(state.paragraphs.size) { state.paragraphs[it].text } }
        state is ViewerState.Table -> remember(state) { Searchable(state.table.rows.size) { rowText(state.table.rows[it]) } }
        currentSheet != null -> remember(currentSheet) { Searchable(currentSheet.rows.size) { sheetRowText(currentSheet, currentSheet.rows[it]) } }
        else -> null
    }
    val inPages = readingParagraphs == null && textPages != null

    // Where reading is, saved a moment after scrolling stops; a text (a book) also opens where it stopped
    val position by remember(inPages) { derivedStateOf { if (inPages) list.firstVisibleItemIndex else textList.firstVisibleItemIndex } }
    var textRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state is ViewerState.Text, startPosition) {
        if (state is ViewerState.Text && !textRestored && startPosition > 0) { textList.scrollToItem(startPosition); textRestored = true }
    }
    LaunchedEffect(position, restored) {
        if (state is ViewerState.Loading || (inPages && !restored)) return@LaunchedEffect
        delay(800)
        val total = if (inPages) laidPages?.pageCount ?: 0 else textList.layoutInfo.totalItemsCount
        onPosition(position, total)
    }

    // Search: pages are scanned in order and results appear as they are found
    var searching by rememberSaveable { mutableStateOf(startSearch != null) }
    var query by rememberSaveable { mutableStateOf(startSearch ?: "") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    // The query whose search has finished: "not found" is said only about that one
    var searched by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source, query, searching) {
        hits = emptyList(); hitIndex = 0; scanning = false; searched = null
        if (!searching || source == null || query.isBlank()) return@LaunchedEffect
        scanning = true
        delay(300)
        val found = ArrayList<SearchHit>()
        // Pages are fetched one by one (a PDF page comes from the engine); paragraphs and rows in batches
        val batch = if (inPages) 1 else 2000
        var p = 0
        while (found.size < MAX_HITS) {
            // A document still being laid out: wait for its next pages
            if (p >= source.count) { if (source.complete && p >= source.count) break; delay(150); continue }
            val end = minOf(source.count, p + batch)
            val start = p
            // A page comes from its engine on its own thread; paragraphs and rows are joined here, off the main thread
            val texts = if (inPages) listOf(source.text(p)) else null
            withContext(Dispatchers.Default) {
                for (i in start until end) {
                    val t = texts?.get(i - start) ?: source.text(i)
                    for (r in Search.find(t, query)) { found += SearchHit(i, r.first, r.last + 1); if (found.size >= MAX_HITS) break }
                    if (found.size >= MAX_HITS) break
                }
            }
            p = end
            if (!inPages || p % 8 == 0 || p >= source.count || found.size >= MAX_HITS) hits = found.toList()
        }
        hits = found.toList()
        scanning = false
        searched = query
    }
    val hitsByPage = remember(hits) { hits.groupBy { it.page } }
    val currentHit = hits.getOrNull(hitIndex)
    LaunchedEffect(currentHit) {
        val hit = currentHit ?: return@LaunchedEffect
        when {
            inPages -> list.animateScrollToItem(hit.page)
            // The first table row stays on top: show the found row just below it
            state is ViewerState.Table -> textList.animateScrollToItem(maxOf(0, hit.page - 1))
            currentSheet != null -> currentSheet.rows.getOrNull(hit.page)?.let { row ->
                // The cell the match is in: count the cells before it in the row's search text
                var at = 0; var col = row.cols.firstOrNull() ?: 0
                for (k in row.cols.indices) { val len = currentSheet.text(row, k).length; if (hit.start <= at + len) { col = row.cols[k]; break }; at += len + 1 }
                sheetState.scrollTo(row.index, col)
            }
            else -> textList.animateScrollToItem(hit.page + if (state is ViewerState.Text && hasNote(state)) 1 else 0)
        }
    }

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
            if (source != null) IconButton(onClick = { searching = true; selection = null }) {
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
        val inspection = (state as? ViewerState.Paged)?.inspection ?: (state as? ViewerState.Sheets)?.workbook?.inspection
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
                is ViewerState.Text -> TextDocument(state, textList, if (searching) hitsByPage else emptyMap(), if (searching) currentHit else null)
                is ViewerState.Table -> TableDocument(state.table, textList, if (searching) hitsByPage else emptyMap(), if (searching) currentHit else null)
                is ViewerState.Sheets -> WorkbookDocument(
                    state.workbook, sheetIndex, { sheetIndex = it }, sheetState,
                    if (searching) hitsByPage else emptyMap(), if (searching) currentHit else null,
                )
                is ViewerState.Paged -> when {
                    reading && state.reading != null -> TextDocument(
                        ViewerState.Text(state.reading, Format.DOCX), textList,
                        if (searching) hitsByPage else emptyMap(), if (searching) currentHit else null, note = false,
                    )
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

/** Something search can look through, item by item (a page, a paragraph or a table row). */
private class Searchable(private val size: () -> Int, val text: suspend (Int) -> String, private val done: () -> Boolean = { true }) {
    constructor(count: Int, text: suspend (Int) -> String) : this({ count }, text)
    val count: Int get() = size()
    val complete: Boolean get() = done()
}

private fun hasNote(state: ViewerState.Text) = state.format == Format.ODT || state.format == Format.DOC

/** Text with found matches marked; [current] is the match search is on. */
@Composable
internal fun highlighted(text: String, hits: List<SearchHit>?, current: SearchHit?, offset: Int = 0): AnnotatedString {
    if (hits.isNullOrEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (h in hits) {
            val a = (h.start - offset).coerceIn(0, text.length)
            val b = (h.end - offset).coerceIn(0, text.length)
            if (a < b) addStyle(SpanStyle(background = if (h == current) CURRENT_HIT else HIT), a, b)
        }
    }
}

private val HIT = Color(0x80FFD54F)
private val CURRENT_HIT = Color(0xCCFF9800)

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
private fun TextDocument(
    state: ViewerState.Text,
    list: LazyListState,
    hits: Map<Int, List<SearchHit>> = emptyMap(),
    current: SearchHit? = null,
    note: Boolean = hasNote(state),
) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            if (note) item {
                Text(
                    stringResource(R.string.simplified_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                )
            }
            itemsIndexed(state.paragraphs) { i, p -> ParagraphText(p, hits[i], current) }
        }
    }
}

@Composable
private fun ParagraphText(p: Paragraph, hits: List<SearchHit>? = null, current: SearchHit? = null) {
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
            highlighted(p.text, hits, current),
            style = style,
            fontWeight = if (p.heading > 0) FontWeight.SemiBold else null,
            modifier = Modifier.padding(top = if (p.heading > 0) 16.dp else 4.dp, bottom = 4.dp),
        )
    }
}
