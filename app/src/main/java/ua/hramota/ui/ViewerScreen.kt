package ua.hramota.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
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
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.Edit
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
    /** A book (EPUB, FB2): read as a flowing text; its position and bookmarks are block numbers. */
    data class Reader(val book: ua.hramota.doc.book.Book) : ViewerState
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
    /** Extra actions in the ⋮ menu: label and what it does. */
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    /** How books look (paper, type, pages), and how to change it. */
    readSettings: ua.hramota.data.AppSettings = ua.hramota.data.AppSettings(),
    onReadSettings: ((ua.hramota.data.AppSettings) -> ua.hramota.data.AppSettings) -> Unit = {},
    /** A book's marked passages and notes. */
    notes: List<ua.hramota.data.Note> = emptyList(),
    onNote: (ua.hramota.data.Note) -> Unit = {},
    onRemoveNote: (Long) -> Unit = {},
    /** Which document this window shows, for reading aloud (two files of the same name stay apart). */
    documentId: String = name,
) {
    // A book takes the colours of its paper, the bars too
    BookTheme(state is ViewerState.Reader, readSettings) { ViewerContent(documentId = documentId, name = name, state = state, onBack = onBack, startInReading = startInReading, bookmarks = bookmarks, onToggleBookmark = onToggleBookmark, startInOverview = startInOverview, startSearch = startSearch, showInspector = showInspector, startPosition = startPosition, onPosition = onPosition, menu = menu, readSettings = readSettings, onReadSettings = onReadSettings, notes = notes, onNote = onNote, onRemoveNote = onRemoveNote) }
}

@Composable
private fun ViewerContent(
    documentId: String,
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
    /** Extra actions in the ⋮ menu: label and what it does. */
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    /** How books look (paper, type, pages), and how to change it. */
    readSettings: ua.hramota.data.AppSettings = ua.hramota.data.AppSettings(),
    onReadSettings: ((ua.hramota.data.AppSettings) -> ua.hramota.data.AppSettings) -> Unit = {},
    /** A book's marked passages and notes. */
    notes: List<ua.hramota.data.Note> = emptyList(),
    onNote: (ua.hramota.data.Note) -> Unit = {},
    onRemoveNote: (Long) -> Unit = {},
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
        state is ViewerState.Reader -> remember(state) { Searchable(state.book.blocks.size) { blockText(state.book.blocks[it]) } }
        state is ViewerState.Table -> remember(state) { Searchable(state.table.rows.size) { rowText(state.table.rows[it]) } }
        currentSheet != null -> remember(currentSheet) { Searchable(currentSheet.rows.size) { sheetRowText(currentSheet, currentSheet.rows[it]) } }
        else -> null
    }
    val inPages = readingParagraphs == null && textPages != null

    // Where reading is, saved a moment after scrolling stops; a text (a book) also opens where it stopped
    val position by remember(inPages) { derivedStateOf { if (inPages) list.firstVisibleItemIndex else textList.firstVisibleItemIndex } }
    var textRestored by rememberSaveable { mutableStateOf(false) }
    // A place in a book to show (from the contents or a bookmark)
    var bookTarget by remember { mutableStateOf<BookTarget?>(null) }
    var lookSheet by remember { mutableStateOf(false) }
    // A note being written on a page, the words it marks, and a word being looked up
    var pageNote by remember { mutableStateOf<ua.hramota.data.Note?>(null) }
    var pageNoteText by remember { mutableStateOf("") }
    var lookingUp by remember { mutableStateOf<String?>(null) }
    pageNote?.let { n -> NoteDialog(pageNoteText, n, onSave = { onNote(it); pageNote = null }, onDelete = null, onDismiss = { pageNote = null }) }
    lookingUp?.let { w -> DictionarySheet(w) { lookingUp = null } }
    var notesOpen by remember { mutableStateOf(false) }
    val pagedText = (state as? ViewerState.Paged)?.pages as? TextPages
    if (notesOpen && pagedText != null) PageNotesSheet(name, pagedText, notes, onGo = { p -> notesOpen = false; scope.launch { list.scrollToItem(p) } }, onNote = onNote, onRemove = onRemoveNote) { notesOpen = false }
    if (lookSheet) ReadingSheet(readSettings, onReadSettings) { lookSheet = false }
    val flowing = state is ViewerState.Text || state is ViewerState.Reader
    LaunchedEffect(flowing, startPosition) {
        if (flowing && !textRestored && startPosition > 0) { textList.requestScrollToItem(startPosition); textRestored = true }
    }
    LaunchedEffect(position, restored) {
        if (state is ViewerState.Loading || (inPages && !restored)) return@LaunchedEffect
        delay(800)
        val total = when { inPages -> laidPages?.pageCount ?: 0; state is ViewerState.Reader -> state.book.blocks.size; else -> textList.layoutInfo.totalItemsCount }
        onPosition(position, total)
    }

    // Search: pages are scanned in order and results appear as they are found
    var searching by rememberSaveable { mutableStateOf(startSearch != null) }
    var query by rememberSaveable { mutableStateOf(startSearch ?: "") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    // The list of what was found, open under the document while going from one to another
    var hitList by rememberSaveable { mutableStateOf(false) }
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

    // Reading aloud: the sentence being said is shown like a found match (search, when open, comes first)
    val readState by ua.hramota.speech.ReadAloud.state.collectAsStateWithLifecycle()
    val readingHere = readState.document == documentId
    val spoken = if (readingHere && !searching && readState.end > readState.start) SearchHit(readState.unit, readState.start, readState.end) else null
    val shownHit = if (searching) currentHit else spoken
    val spokenHits = remember(spoken) { spoken?.let { mapOf(it.page to listOf(it)) } ?: emptyMap() }
    val shownHits = if (searching) hitsByPage else spokenHits
    // A sentence on another page or paragraph: brought into view (not again for each sentence on the same one)
    LaunchedEffect(spoken?.page) {
        val unit = spoken?.page ?: return@LaunchedEffect
        val l = if (inPages) list else textList
        val offset = if (state is ViewerState.Text && hasNote(state)) 1 else 0
        if (state is ViewerState.Reader && readSettings.readPaged) return@LaunchedEffect
        if (l.layoutInfo.visibleItemsInfo.none { it.index == unit + offset }) l.animateScrollToItem(unit + offset)
    }
    val readable = source != null && !overview && (state is ViewerState.Text || state is ViewerState.Reader || state is ViewerState.Paged)
    var readSheet by remember { mutableStateOf(false) }
    if (readSheet && readingHere) ReadAloudSheet(
        readState, chapters = state is ViewerState.Reader,
        onVoice = { v -> ua.hramota.speech.ReadAloud.setVoice(v); onReadSettings { it.copy(readVoice = v) } },
        onSpeed = { v -> ua.hramota.speech.ReadAloud.setSpeed(v); onReadSettings { it.copy(readSpeed = v) } },
    ) { readSheet = false }
    // What reading aloud reads in this view; when the screen turns, the new view's text takes over
    val readDocument = remember(source, documentId) {
        source?.let { src ->
            val book = (state as? ViewerState.Reader)?.book
            ua.hramota.speech.ReadAloud.Document(
                id = documentId, title = name.substringBeforeLast('.', name), count = { src.count }, text = src.text,
                isHeading = { i -> (book?.blocks?.getOrNull(i) as? ua.hramota.doc.book.BookBlock.Text)?.kind?.let { it == ua.hramota.doc.book.BookBlock.Kind.HEADING1 || it == ua.hramota.doc.book.BookBlock.Kind.HEADING2 } == true },
            )
        }
    }
    LaunchedEffect(readDocument) { readDocument?.let { ua.hramota.speech.ReadAloud.rebind(it) } }
    val startReading: () -> Unit = {
        readDocument?.let { doc ->
            // The first item seen; a note shown above a document's text is not part of it
            val offset = if (!inPages && state is ViewerState.Text && hasNote(state)) 1 else 0
            val from = if (inPages) list.firstVisibleItemIndex else (textList.firstVisibleItemIndex - offset).coerceAtLeast(0)
            ua.hramota.speech.ReadAloud.start(context, doc, from, 0, readSettings.readSpeed, readSettings.readVoice)
        }
    }
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
            // A book in pages goes to the page with the match itself
            state is ViewerState.Reader && readSettings.readPaged -> Unit
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
            listOpen = hitList,
            onList = { hitList = !hitList },
        ) else TopBar(title = name, onBack = { if (overview) overview = false else onBack() }) {
            val pageNotes = state is ViewerState.Paged && (state.pages is TextPages) && notes.isNotEmpty()
            if ((menu.isNotEmpty() || pageNotes || readable) && !overview) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more)) }
                    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        if (readable) androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.read_aloud_start)) }, onClick = { open = false; startReading() })
                        if (pageNotes) androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.notes_count, notes.size)) }, onClick = { open = false; notesOpen = true })
                        for ((label, action) in menu) androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                    }
                }
            }
            // Text that flows (the reading view, plain text): its size, smaller or larger
            if ((readingParagraphs != null || state is ViewerState.Text) && !overview) {
                var sizing by remember { mutableStateOf(false) }
                Box {
                    val sizeLabel = stringResource(R.string.text_size)
                    TextButton(onClick = { sizing = true }, modifier = Modifier.semantics { contentDescription = sizeLabel }) { Text("Aa", style = MaterialTheme.typography.titleMedium) }
                    androidx.compose.material3.DropdownMenu(expanded = sizing, onDismissRequest = { sizing = false }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            val smaller = stringResource(R.string.text_smaller); val larger = stringResource(R.string.text_larger)
                            TextButton(onClick = { onReadSettings { it.copy(textScale = (it.textScale - 0.1f).coerceAtLeast(0.8f)) } }, modifier = Modifier.semantics { contentDescription = smaller }) { Text("A−", style = MaterialTheme.typography.titleSmall) }
                            Text("${(readSettings.textScale * 100).roundToInt()}%", modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                            TextButton(onClick = { onReadSettings { it.copy(textScale = (it.textScale + 0.1f).coerceAtMost(2.2f)) } }, modifier = Modifier.semantics { contentDescription = larger }) { Text("A+", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
            if (state is ViewerState.Reader && !overview) TextButton(onClick = { lookSheet = true }) {
                Text("Aa", style = MaterialTheme.typography.titleMedium, fontFamily = Literata)
            }
            if (source != null) IconButton(onClick = { searching = true; selection = null }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_open))
            }
            if ((state is ViewerState.Paged && !reading) || state is ViewerState.Reader) {
                val here = if (state is ViewerState.Reader) textList.firstVisibleItemIndex else current
                if (!overview) IconButton(onClick = { onToggleBookmark(here) }) {
                    val marked = here in bookmarks
                    Icon(
                        if (marked) AppIcons.Bookmark else AppIcons.BookmarkOutline,
                        contentDescription = stringResource(if (marked) R.string.bookmark_remove else R.string.bookmark_add),
                        tint = if (marked) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { overview = !overview }) {
                    Icon(AppIcons.Overview, contentDescription = stringResource(if (state is ViewerState.Reader) R.string.book_contents else R.string.overview), tint = if (overview) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
                is ViewerState.Text -> TextDocument(state, textList, readSettings.textScale, shownHits, shownHit)
                is ViewerState.Reader -> Box(Modifier.fillMaxSize()) {
                    // The book stays laid out under the contents, so coming back is instant
                    val paper = paperFor(readSettings.readPaper)
                    val language = remember(state.book) { BookLook.languageOf(state.book) }
                    val look = remember(readSettings, paper, language) { BookLook(readSettings, paper, language) }
                    BookDocument(
                        state.book, textList, look, shownHits, shownHit, bookTarget, bookmarks,
                        notes, onNote, onRemoveNote, onTargetUsed = { bookTarget = null },
                        onFind = { t -> query = t.trim().take(Search.MAX_QUERY); searching = true },
                    )
                    // Over the book: touches stop here and do not turn its pages
                    if (overview) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }) {
                        BookContents(state.book, bookmarks, textList.firstVisibleItemIndex, { i ->
                            overview = false
                            textList.requestScrollToItem(i)
                            bookTarget = BookTarget(i)
                        }, notes, onNote, onRemoveNote)
                    }
                }
                is ViewerState.Table -> TableDocument(state.table, textList, shownHits, shownHit)
                is ViewerState.Sheets -> WorkbookDocument(
                    state.workbook, sheetIndex, { sheetIndex = it }, sheetState,
                    shownHits, shownHit,
                )
                is ViewerState.Paged -> when {
                    reading && state.reading != null -> TextDocument(
                        ViewerState.Text(state.reading, Format.DOCX), textList, readSettings.textScale,
                        shownHits, shownHit, note = false,
                    )
                    overview -> PageOverview(state.pages, bookmarks, current) { page ->
                        overview = false
                        scope.launch { list.scrollToItem(page) }
                    }
                    else -> ZoomablePages(
                        state.pages, list, bookmarks,
                        hits = shownHits,
                        currentHit = shownHit,
                        selection = selection,
                        onSelection = { selection = it },
                        notes = notes,
                    )
                }
            }
        }
        // What was found, each with words around it; a tap goes there and the list stays
        if (searching && hitList && hits.isNotEmpty() && source != null) HitList(
            hits, hitIndex, source,
            label = { h ->
                when {
                    inPages -> stringResource(R.string.search_page, h.page + 1)
                    state is ViewerState.Reader -> "${(h.page * 100L / state.book.blocks.size.coerceAtLeast(1)).toInt()}%"
                    else -> null
                }
            },
            onPick = { hitIndex = it },
        )
        val sel = selection
        if (sel != null && textPages != null) SelectionBar(
            onMark = { color ->
                onNote(ua.hramota.data.Note(sel.page, minOf(sel.start, sel.end), sel.page, maxOf(sel.start, sel.end), color, at = System.currentTimeMillis()))
                selection = null
            },
            onNote = {
                scope.launch {
                    pageNoteText = selectedText(textPages, sel)
                    pageNote = ua.hramota.data.Note(sel.page, minOf(sel.start, sel.end), sel.page, maxOf(sel.start, sel.end), 0, at = System.currentTimeMillis())
                    selection = null
                }
            },
            onDictionary = { scope.launch { lookingUp = selectedText(textPages, sel); selection = null } },
            onFind = { scope.launch { query = selectedText(textPages, sel).trim().take(Search.MAX_QUERY); selection = null; searching = true } },
            onApp = { component -> scope.launch {
                val t = selectedText(textPages, sel)
                try {
                    context.startActivity(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, t.take(5000)).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
                } catch (_: Exception) {}
            } },
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
        // The player, under the thumb, while this document is read aloud
        if (readingHere && selection == null) ReadAloudBar(readState, onBookmark = { onToggleBookmark(readState.unit) }, onSettings = { readSheet = true })
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
    listOpen: Boolean = false, onList: () -> Unit = {},
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
        IconButton(onClick = onList, enabled = total > 0) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.search_list),
                tint = if (listOpen && total > 0) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    }
}

/**
 * Everything found, one line each: where (page or how far into a book) and the words around it,
 * the match in bold. A tap goes there; the list stays open, the one shown marked.
 */
@Composable
private fun HitList(hits: List<SearchHit>, current: Int, source: Searchable, label: @Composable (SearchHit) -> String?, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    // Each page's text fetched once (a PDF page comes from its engine)
    val texts = remember(source) { java.util.concurrent.ConcurrentHashMap<Int, String>() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // The one shown stays in sight in the list
    LaunchedEffect(current) { if (list.layoutInfo.visibleItemsInfo.none { it.index == current }) list.animateScrollToItem(current.coerceAtLeast(0)) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.35f).dp.coerceIn(120.dp, 300.dp)), state = list) {
            itemsIndexed(hits, key = { i, h -> "${h.page}:${h.start}:$i" }) { i, h ->
                val snippet by produceState<AnnotatedString?>(null, h) {
                    value = withContext(Dispatchers.Default) {
                        val t = texts[h.page] ?: source.text(h.page).also { texts[h.page] = it }
                        val from = (h.start - 40).coerceAtLeast(0); val to = (h.end + 60).coerceAtMost(t.length)
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (from > 0) append("…")
                            append(t.substring(from, h.start.coerceIn(from, to)).replace('\n', ' '))
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                            append(t.substring(h.start.coerceIn(from, to), h.end.coerceIn(from, to)).replace('\n', ' '))
                            pop()
                            append(t.substring(h.end.coerceIn(from, to), to).replace('\n', ' '))
                            if (to < t.length) append("…")
                        }
                    }
                }
                val on = i == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { keyboard?.hide(); onPick(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    label(h)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 64.dp).padding(end = 8.dp)) }
                    Text(snippet ?: AnnotatedString("…"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Actions for selected text, at the bottom within reach of the thumb. */
@Composable
private fun SelectionBar(
    onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit,
    onMark: (Int) -> Unit = {}, onNote: () -> Unit = {}, onDictionary: () -> Unit = {}, onApp: (android.content.ComponentName) -> Unit = {},
    onFind: () -> Unit = {},
) {
    val context = LocalContext.current
    val apps = remember { textApps(context) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                // Markers, a note, the dictionary, then copy, share and what other apps offer for text
                for ((i, c) in NOTE_COLORS.withIndex()) Box(
                    Modifier.padding(horizontal = 4.dp).size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                        .border(1.dp, Color.Black.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape).clickable { onMark(i) },
                )
                TextButton(onClick = onNote) { Text(stringResource(R.string.note_add), maxLines = 1) }
                TextButton(onClick = onDictionary) { Text(stringResource(R.string.dict_open), maxLines = 1) }
                TextButton(onClick = onFind) { Text(stringResource(R.string.search_find), maxLines = 1) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy), maxLines = 1) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.share), maxLines = 1) }
                for ((label, component) in apps) TextButton(onClick = { onApp(component) }) { Text(label, maxLines = 1) }
            }
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
    scale: Float = 1f,
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
            itemsIndexed(state.paragraphs) { i, p -> ParagraphText(p, hits[i], current, scale) }
        }
    }
}

@Composable
private fun ParagraphText(p: Paragraph, hits: List<SearchHit>? = null, current: SearchHit? = null, scale: Float = 1f) {
    val base = when (p.heading) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        0 -> MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)
        else -> MaterialTheme.typography.titleSmall
    }
    val style = if (scale == 1f) base else base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
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

/** A document's notes on its pages: what each marks, on which page; go there, change, remove, or send them all. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PageNotesSheet(
    name: String, pages: TextPages, notes: List<ua.hramota.data.Note>,
    onGo: (Int) -> Unit, onNote: (ua.hramota.data.Note) -> Unit, onRemove: (Long) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sorted = remember(notes) { notes.sortedWith(compareBy({ it.block }, { it.start })) }
    // The marked words, read from the pages off the main thread
    val words by androidx.compose.runtime.produceState(emptyMap<Long, String>(), sorted) {
        val out = HashMap<Long, String>()
        val texts = HashMap<Int, String>()
        for (n in sorted.take(500)) {
            val t = texts.getOrPut(n.block) { try { pages.pageText(n.block) } catch (e: Exception) { "" } }
            val a = n.start.coerceIn(0, t.length); val b = n.end.coerceIn(a, t.length)
            out[n.at] = t.substring(a, b).replace('\uFFFC', ' ').trim()
        }
        value = out
    }
    var editing by remember { mutableStateOf<ua.hramota.data.Note?>(null) }
    editing?.let { n -> NoteDialog(words[n.at].orEmpty(), n, onSave = { onNote(it); editing = null }, onDelete = { onRemove(n.at); editing = null }, onDismiss = { editing = null }) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.book_notes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val text = buildString {
                        append("# ").append(name).append('\n')
                        for (n in sorted) {
                            append('\n').append(context.getString(R.string.page_n, n.block + 1)).append('\n')
                            append("> ").append(words[n.at].orEmpty()).append('\n')
                            if (n.text.isNotEmpty()) append('\n').append(n.text).append('\n')
                        }
                    }.take(200_000)
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text), null))
                    } catch (_: Exception) {}
                }
            }) { Text(stringResource(R.string.notes_export)) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(sorted, key = { k, _ -> k }) { _, n ->
                Row(Modifier.fillMaxWidth().clickable { onGo(n.block) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 40.dp).background(NOTE_COLORS[n.color.coerceIn(0, NOTE_COLORS.size - 1)]))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.page_n, n.block + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(words[n.at].orEmpty().ifEmpty { "…" }, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        if (n.text.isNotEmpty()) Text(n.text, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = n }) { Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = stringResource(R.string.note_edit)) }
                }
            }
        }
    }
}
