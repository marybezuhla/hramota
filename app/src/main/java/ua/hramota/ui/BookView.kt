package ua.hramota.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.ensureActive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import ua.hramota.data.Note
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.doc.SearchHit
import ua.hramota.doc.book.Book
import ua.hramota.doc.book.BookBlock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The text of a block for search (a picture has none). */
internal fun blockText(b: BookBlock): String = (b as? BookBlock.Text)?.text ?: ""

/** A place in a book to go to: a block and a character in it; [nonce] makes the same place a new request. */
data class BookTarget(val block: Int, val char: Int = 0, val nonce: Long = System.nanoTime())

/** Volume keys while a book is shown in pages: +1 next page, −1 previous. */
object PageKeys {
    @Volatile var handler: ((Int) -> Boolean)? = null
}

private fun flush(book: Book, i: Int): Boolean {
    val prev = book.blocks.getOrNull(i - 1) ?: return true
    return prev !is BookBlock.Text || (prev.kind != BookBlock.Kind.PARAGRAPH && prev.kind != BookBlock.Kind.CONTINUED)
}

/**
 * A book to read, in pages to turn or as one scroll. [list] holds where reading is (its first visible
 * item is the block); in pages it is updated as pages turn.
 */
@Composable
internal fun BookDocument(
    book: Book, list: LazyListState, look: BookLook, hits: Map<Int, List<SearchHit>>, current: SearchHit?,
    target: BookTarget?, bookmarks: Set<Int>,
    notes: List<Note> = emptyList(), onNote: (Note) -> Unit = {}, onRemoveNote: (Long) -> Unit = {},
    onTargetUsed: () -> Unit = {},
    onFind: (String) -> Unit = {},
) {
    val selection = remember { BookSelection() }
    val real = LocalClipboard.current
    val taking = remember(real) { TakingClipboard(real) }
    // The blocks on screen, where selected text is looked for
    val visible = remember { Visible() }
    var editing by remember { mutableStateOf<Pair<Note, Boolean>?>(null) }
    var lookingUp by remember { mutableStateOf<String?>(null) }
    val view = BookView(look, hits, current, notes, selection.reset, visible, selection)
    // Where this view sits in the window: the selection's place comes in window coordinates
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(look.paper.background).onGloballyPositioned { origin = it.positionInWindow() }) {
        CompositionLocalProvider(LocalTextToolbar provides selection, LocalClipboard provides taking) {
            if (look.settings.readPaged) BookPages(book, list, view, target, bookmarks, onTargetUsed)
            else BookScroll(book, list, view)
        }
        SelectionMenu(
            selection, taking, origin,
            onMark = { t, c -> locate(book, visible.blocks(), t)?.let { onNote(it.copy(color = c)) } },
            onNote = { t -> locate(book, visible.blocks(), t)?.let { editing = it to true } },
            onDictionary = { t -> lookingUp = t },
            onFind = onFind,
        )
    }
    lookingUp?.let { w -> DictionarySheet(w) { lookingUp = null } }
    editing?.let { (n, new) ->
        NoteDialog(excerpt(book, n), n, onSave = { onNote(it); editing = null }, onDelete = if (new) null else ({ onRemoveNote(n.at); editing = null }), onDismiss = { editing = null })
    }
}

/** The parts of blocks on screen (block, first and last character shown), where selected text is looked for. */
internal class Visible {
    data class Part(val block: Int, val from: Int, val to: Int)
    var blocks: () -> List<Part> = { emptyList() }
}

/** What every block on screen is drawn with. */
private class BookView(
    val look: BookLook, val hits: Map<Int, List<SearchHit>>, val current: SearchHit?, val notes: List<Note>, val reset: Int, val visible: Visible,
    val selection: BookSelection,
) {
    fun text(b: BookBlock.Text, i: Int) = look.text(b, hits[i], current, marksIn(notes, i, b.text.length, look.paper.dark))
}

@Composable
private fun BookScroll(book: Book, list: LazyListState, v: BookView) {
    val look = v.look
    v.visible.blocks = { list.layoutInfo.visibleItemsInfo.map { Visible.Part(it.index, 0, Int.MAX_VALUE) } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        key(v.reset) { SelectionContainer {
            // A comfortable line on a tablet too: about 60–70 characters at most
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 640.dp + look.margin * 2), state = list, contentPadding = PaddingValues(start = look.margin, end = look.margin, top = 12.dp, bottom = 64.dp)) {
                itemsIndexed(book.blocks, contentType = { _, b -> if (b is BookBlock.Image) 1 else (b as BookBlock.Text).kind.ordinal + 2 }) { i, b ->
                    when (b) {
                        is BookBlock.Image -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { BookImage(book, b.key, widthPx, 560.dp) }
                        is BookBlock.Text -> {
                            val (top, bottom, indent) = look.space(b.kind)
                            if (b.kind == BookBlock.Kind.BREAK) Box(Modifier.height(top))
                            else Text(v.text(b, i), style = look.style(b, flush(book, i)), modifier = Modifier.fillMaxWidth().padding(top = top, bottom = bottom, start = indent))
                        }
                    }
                }
            }
        } }
    }
}

/** One part of a block on a page: [top] and [height] within the block's own layout, in pixels. */
private class Slice(val block: Int, val top: Int, val height: Int, val padTop: Int, val from: Int = 0, val to: Int = Int.MAX_VALUE)
private class Page(val slices: List<Slice>, val firstBlock: Int, val firstChar: Int, val chapter: String)

/**
 * Lays a book out in pages the size of the screen. Chapters start on a new page, a heading stays
 * with the text after it, a paragraph leaves no single line alone at the foot or head of a page.
 * Chapters are independent, so they are laid out in parallel, the one being read first.
 */
private suspend fun paginate(
    book: Book, look: BookLook, resolver: FontFamily.Resolver, density: Density, width: Int, height: Int,
    startBlock: Int, publish: (List<Page>, Boolean, Boolean, IntRange) -> Unit,
) = coroutineScope {
    val starts = ArrayList<Int>().apply {
        add(0)
        for (i in 1 until book.blocks.size) if ((book.blocks[i] as? BookBlock.Text)?.kind == BookBlock.Kind.HEADING1) add(i)
    }
    var chapters = starts.indices.map { k -> starts[k] until (starts.getOrNull(k + 1) ?: book.blocks.size) }
    // A book without chapters would be laid out in one piece: cut it at lower headings, else in long runs
    // (each piece starts a page, so runs are long enough for that to pass unnoticed)
    if (chapters.size == 1 && book.blocks.size > 2000) {
        val cuts = (1 until book.blocks.size).filter { (book.blocks[it] as? BookBlock.Text)?.kind == BookBlock.Kind.HEADING2 }
        val at = (listOf(0) + cuts).toMutableList()
        val runs = ArrayList<Int>()
        for (k in at.indices) {
            val end = at.getOrNull(k + 1) ?: book.blocks.size
            var a = at[k]
            runs += a
            while (end - a > 3000) { a += 1500; runs += a }
        }
        chapters = runs.indices.map { k -> runs[k] until (runs.getOrNull(k + 1) ?: book.blocks.size) }
    }
    val target = chapters.indexOfFirst { startBlock in it }.coerceAtLeast(0)
    val done = arrayOfNulls<List<Page>>(chapters.size)
    val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
    // The chapter being read first: it is shown alone at once (the book opens without waiting for the
    // chapters before it), then the book from its start as soon as those are laid out too
    val order = listOf(target) + (0 until target) + (target + 1 until chapters.size)
    var published = -1
    var alone = false
    fun prefix(): Int { var k = 0; while (k < done.size && done[k] != null) k++; return k }
    for (batch in order.chunked(threads * 2)) {
        batch.map { k ->
            async(Dispatchers.Default) {
                val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 0)
                done[k] = chapterPages(book, look, measurer, density, chapters[k], width, height)
            }
        }.awaitAll()
        val have = prefix()
        if (have > target && have > published) {
            published = have
            publish(done.take(have).flatMap { it!! }, have == done.size, false, 0 until chapters[have - 1].last + 1)
        } else if (published < 0 && !alone && done[target] != null) {
            alone = true
            publish(done[target]!!, false, true, chapters[target])
        }
    }
    if (published < done.size) publish(done.flatMap { it.orEmpty() }, true, false, 0 until book.blocks.size)
}

private suspend fun chapterPages(book: Book, look: BookLook, measurer: TextMeasurer, density: Density, range: IntRange, width: Int, height: Int): List<Page> {
    val pages = ArrayList<Page>()
    var slices = ArrayList<Slice>()
    var y = 0
    var firstBlock = range.first; var firstChar = 0
    var chapter = ""
    // Space owed below the last thing placed, given to the next one (dropped at a page top)
    var owed = 0
    fun px(d: androidx.compose.ui.unit.Dp) = with(density) { d.roundToPx() }
    val line = with(density) { (look.settings.readSize * look.settings.readSpacing).sp.roundToPx() }
    fun newPage(block: Int, char: Int) {
        if (slices.isNotEmpty()) pages += Page(slices, firstBlock, firstChar, chapter)
        slices = ArrayList(); y = 0; firstBlock = block; firstChar = char; owed = 0
    }
    for (i in range) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        when (val b = book.blocks[i]) {
            is BookBlock.Image -> {
                val bounds = imageSize(book.images[b.key]) ?: continue
                val h = min(height, (width.toLong() * bounds.second / max(1, bounds.first)).toInt().coerceAtLeast(1)).let { min(it, bounds.second * 3) }
                val pad = if (y == 0) 0 else max(owed, px(12.dp))
                if (y + pad + h > height) newPage(i, 0)
                val p = if (y == 0) 0 else pad
                slices += Slice(i, 0, h, p); y += p + h; owed = px(12.dp)
            }
            is BookBlock.Text -> {
                val (topDp, bottomDp, indentDp) = look.space(b.kind)
                if (b.kind == BookBlock.Kind.BREAK) { if (y > 0) owed += px(topDp); continue }
                if (b.kind == BookBlock.Kind.HEADING1 && chapter.isEmpty()) chapter = b.text.replace('\n', ' ')
                val layout = measurer.measure(look.text(b, null, null), look.style(b, flush(book, i)), constraints = Constraints(maxWidth = max(1, width - px(indentDp))), skipCache = true)
                val heading = b.kind == BookBlock.Kind.HEADING1 || b.kind == BookBlock.Kind.HEADING2 || b.kind == BookBlock.Kind.HEADING3
                var pad = if (y == 0) 0 else owed + px(topDp)
                // A heading keeps two lines of the text after it on its page
                if (heading && y > 0 && y + pad + layout.size.height + line * 2 > height) { newPage(i, 0); pad = 0 }
                val lines = layout.lineCount
                var from = 0
                while (from < lines) {
                    val top = layout.getLineTop(from).toInt()
                    val room = height - y - pad
                    var to = from
                    while (to < lines && layout.getLineBottom(to).toInt() - top <= room) to++
                    if (to == from && y == 0) to = from + 1  // a line taller than the page
                    if (to < lines && to > from) {
                        // No widow or orphan: never one line left alone, if the paragraph can give one
                        if (to - from == 1 && from == 0 && lines > 1 && y > 0) to = from
                        else if (lines - to == 1 && to - from >= 3) to--
                    }
                    if (to == from) { newPage(i, layout.getLineStart(from)); pad = 0; continue }
                    val h = layout.getLineBottom(to - 1).toInt() - top
                    slices += Slice(i, top, h, pad, layout.getLineStart(from), layout.getLineEnd(to - 1))
                    y += pad + h
                    pad = 0
                    from = to
                    if (from < lines) newPage(i, layout.getLineStart(from))
                }
                owed = px(bottomDp)
            }
        }
    }
    newPage(range.last + 1, 0)
    return pages
}

private fun imageSize(bytes: ByteArray?): Pair<Int, Int>? {
    bytes ?: return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

/** The page a place in the book is on. */
private fun pageOf(pages: List<Page>, block: Int, char: Int): Int {
    var lo = 0; var hi = pages.size - 1; var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val p = pages[mid]
        if (p.firstBlock < block || (p.firstBlock == block && p.firstChar <= char)) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

@Composable
private fun BookPages(book: Book, list: LazyListState, v: BookView, target: BookTarget?, bookmarks: Set<Int>, onTargetUsed: () -> Unit) {
    val look = v.look
    val current = v.current
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = with(density) { look.margin.roundToPx() }
        val header = with(density) { 36.dp.roundToPx() }
        val footer = with(density) { 36.dp.roundToPx() }
        // A comfortable line on a tablet: the text block is no wider than about 70 characters
        val maxText = with(density) { (640.dp).roundToPx() }
        val textWidth = min(constraints.maxWidth - margin * 2, maxText).coerceAtLeast(1)
        val textHeight = (constraints.maxHeight - header - footer).coerceAtLeast(1)
        var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
        var complete by remember { mutableStateOf(false) }
        // Only the chapter being read is laid out yet: its page numbers are not the book's
        var partialShown by remember { mutableStateOf(false) }
        // The blocks the pages laid out so far hold (all of them once the book is complete)
        var laidBlocks by remember { mutableStateOf(0 until 0) }
        // What the pages were laid out for: pages of another size or type are not shown
        val layoutKey = listOf(book, textWidth, textHeight, look.settings.readSize, look.settings.readSpacing, look.settings.readMargin, look.settings.readFont, look.settings.readJustify)
        var laidFor by remember { mutableStateOf<List<Any>?>(null) }
        // Where reading was before the pages were laid out again (another size, font or screen turned)
        val anchor = remember { mutableStateOf(BookTarget(list.firstVisibleItemIndex)) }
        val pager = rememberPagerState { pages.size }
        val settings = look.settings
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(layoutKey) {
            // A new layout (another size, font, or the screen turned) keeps the place being read
            if (shown) pages.getOrNull(pager.currentPage)?.let { anchor.value = BookTarget(it.firstBlock, it.firstChar) }
            else if (pages.isEmpty()) anchor.value = BookTarget(list.firstVisibleItemIndex)
            val again = pages.isNotEmpty()
            shown = false
            // While a slider moves, wait until it rests
            if (again) kotlinx.coroutines.delay(250)
            var partial = false
            paginate(book, look, resolver, density, textWidth, textHeight, anchor.value.block) { laid, all, alone, covers ->
                // From the chapter alone to the whole book: the place being read is found again in it
                if (partial && !alone) shown = false
                partial = alone
                partialShown = alone
                pages = laid; complete = all; laidBlocks = covers
            }
        }
        // Once the page being read is laid out, go there; only then are the pages shown (no other page flashes first)
        LaunchedEffect(pages) {
            if (pages.isEmpty() || shown) return@LaunchedEffect
            // A place not laid out yet (a jump while only part of the book is): wait for it
            if (!complete && anchor.value.block !in laidBlocks) { if (laidFor != layoutKey) laidFor = layoutKey; return@LaunchedEffect }
            pager.requestScrollToPage(pageOf(pages, anchor.value.block, anchor.value.char))
            laidFor = layoutKey
            shown = true
        }
        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.currentPage to shown }.collect { (p, ready) ->
                val page = pages.getOrNull(p) ?: return@collect
                v.selection.hide()
                if (ready) { anchor.value = BookTarget(page.firstBlock, page.firstChar); list.requestScrollToItem(page.firstBlock) }
            }
        }
        LaunchedEffect(target, pages.isNotEmpty()) {
            val t = target ?: return@LaunchedEffect
            if (pages.isEmpty()) return@LaunchedEffect
            anchor.value = t
            // Kept as the place to go once the part of the book it is in is laid out
            if (!complete && t.block !in laidBlocks) shown = false
            else pager.requestScrollToPage(pageOf(pages, t.block, t.char))
            // Used once: coming back from the contents later must not jump here again
            onTargetUsed()
        }
        LaunchedEffect(current, pages.isNotEmpty()) {
            val h = current ?: return@LaunchedEffect
            if (pages.isEmpty()) return@LaunchedEffect
            if (!complete && h.page !in laidBlocks) { anchor.value = BookTarget(h.page, h.start); shown = false }
            else pager.requestScrollToPage(pageOf(pages, h.page, h.start))
        }
        // (also while waiting for a place not laid out yet, or for the page to be found again)
        if (pages.isEmpty() || laidFor != layoutKey || !shown) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return@BoxWithConstraints }

        val scope = rememberCoroutineScope()
        val turnStyle = settings.readTurn
        val curl = remember { CurlState() }
        val turn: (Int) -> Boolean = { dir ->
            // From where a turn already under way is going, so quick taps go one page each
            val to = (if (pager.isScrollInProgress) pager.targetPage else pager.currentPage) + dir
            if (curl.dir != 0) true
            else if (to in pages.indices) {
                scope.launch {
                    when (turnStyle) {
                        "curl" -> curl.turn(dir) { pager.requestScrollToPage(to) }
                        "none" -> pager.requestScrollToPage(to)
                        else -> pager.animateScrollToPage(to)
                    }
                }
                true
            } else false
        }
        val turnNow by rememberUpdatedState(turn)
        // The volume keys turn pages only while this window is in front
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        if (settings.readVolumeKeys) DisposableEffect(lifecycle) {
            val h: (Int) -> Boolean = { turnNow(it) }
            val watch = androidx.lifecycle.LifecycleEventObserver { _, e ->
                when (e) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> PageKeys.handler = h
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> if (PageKeys.handler === h) PageKeys.handler = null
                    else -> Unit
                }
            }
            lifecycle.addObserver(watch)
            onDispose { lifecycle.removeObserver(watch); if (PageKeys.handler === h) PageKeys.handler = null }
        }
        v.visible.blocks = { pages.getOrNull(pager.currentPage)?.slices?.map { Visible.Part(it.block, it.from, it.to) }.orEmpty() }
        val page: @Composable (Int) -> Unit = { p ->
            pages.getOrNull(p)?.let { BookPage(book, v, it, p, if (partialShown) 0 else pages.size, complete, bookmarks, pages.getOrNull(p + 1)?.firstBlock ?: Int.MAX_VALUE, textWidth, header, footer) }
        }
        val taps = Modifier.pointerInput(Unit) {
            // The edges of the page turn it, as in a paper book
            detectTapGestures { o -> if (o.x < size.width * 0.3f) turnNow(-1) else if (o.x > size.width * 0.7f) turnNow(1) }
        }
        if (turnStyle == "curl") CurlPages(curl, pager, pages.size, look.paper, taps, page)
        else HorizontalPager(pager, Modifier.fillMaxSize().then(taps), beyondViewportPageCount = 1) { p -> page(p) }
    }
}

@Composable
private fun BookPage(
    book: Book, v: BookView, page: Page, index: Int, count: Int, complete: Boolean, bookmarks: Set<Int>, nextFirst: Int,
    textWidth: Int, header: Int, footer: Int,
) {
    val look = v.look
    val density = LocalDensity.current
    val marked = bookmarks.any { it >= page.firstBlock && it < nextFirst } || page.firstBlock in bookmarks
    Box(Modifier.fillMaxSize().background(look.paper.background)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(with(density) { header.toDp() }).fillMaxWidth().padding(horizontal = look.margin), contentAlignment = Alignment.Center) {
                Text(page.chapter.ifEmpty { book.title }, style = MaterialTheme.typography.labelMedium.copy(fontFamily = look.family), color = look.paper.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            key(v.reset) { SelectionContainer(Modifier.weight(1f)) {
                // Exactly the width the pages were laid out for, so every line breaks where it was measured
                Column(Modifier.layout { m, c -> val p = m.measure(c.copy(minWidth = textWidth, maxWidth = textWidth)); layout(p.width, p.height) { p.place(0, 0) } }) {
                    for (s in page.slices) {
                        val b = book.blocks[s.block]
                        val h = with(density) { s.height.toDp() }
                        val padTop = with(density) { s.padTop.toDp() }
                        when (b) {
                            is BookBlock.Image -> Box(Modifier.padding(top = padTop).fillMaxWidth().height(h), contentAlignment = Alignment.Center) { BookImage(book, b.key, textWidth, h) }
                            is BookBlock.Text -> {
                                val indent = look.space(b.kind).third
                                Box(Modifier.padding(top = padTop, start = indent).fillMaxWidth().height(h).clipToBounds()) {
                                    Text(
                                        v.text(b, s.block), style = look.style(b, flush(book, s.block)),
                                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, -s.top) },
                                    )
                                }
                            }
                        }
                    }
                }
            } }
            Box(Modifier.height(with(density) { footer.toDp() }).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (count == 0) "" else if (complete) "${index + 1} / $count" else "${index + 1}", style = MaterialTheme.typography.labelMedium, color = look.paper.muted)
            }
        }
        if (marked) Icon(AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp).size(24.dp))
    }
}

/** The page being turned by its corner: where the corner is now, and whether it turns forward or back. */
private class CurlState {
    val corner = Animatable(Offset.Zero, Offset.VectorConverter)
    var dir by mutableIntStateOf(0)
    var top by mutableStateOf(false)
    var size = androidx.compose.ui.geometry.Size.Zero

    /** A turn by tap or key: the corner sweeps across with a little lift. */
    suspend fun turn(d: Int, done: suspend () -> Unit) {
        if (dir != 0) return
        if (size.width <= 0f) { done(); return }
        val w = size.width; val h = size.height
        top = false
        dir = d
        val from = if (d > 0) Offset(w, h) else Offset(-w, h)
        val to = if (d > 0) Offset(-w, h) else Offset(w, h)
        try {
            corner.snapTo(from)
            corner.animateTo(to, keyframes { durationMillis = 420; Offset(0f, h * 0.82f) at 210 })
            done()
        } finally { dir = 0 }
    }
}

/**
 * Pages turned like paper, as in the old Apple Books: the corner follows the finger, the page folds
 * along the line between, its back shows faintly through, and a shadow falls along the fold.
 */
@Composable
private fun CurlPages(
    curl: CurlState, pager: androidx.compose.foundation.pager.PagerState, count: Int, paper: Paper, taps: Modifier,
    page: @Composable (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val index = pager.currentPage
    val indexNow by rememberUpdatedState(index)
    val goTo: (Int) -> Unit = { pager.requestScrollToPage(it) }
    val countNow by rememberUpdatedState(count)
    val layer = rememberGraphicsLayer()
    Box(
        Modifier.fillMaxSize().onSizeChanged { curl.size = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }.then(taps).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var started = false
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    val d = if (over < 0) 1 else -1
                    val to = indexNow + d
                    if (to in 0 until countNow && curl.dir == 0) {
                        change.consume(); started = true
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        curl.size = androidx.compose.ui.geometry.Size(w, h)
                        curl.top = down.position.y < h / 2
                        curl.dir = d
                        val cy = if (curl.top) 0f else h
                        scope.launch { curl.corner.snapTo(if (d > 0) Offset(w, cy) else Offset(-w, cy)) }
                    }
                } ?: return@awaitEachGesture
                if (!started) return@awaitEachGesture
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val cy = if (curl.top) 0f else h
                val origin = if (curl.dir > 0) Offset(w, cy) else Offset(-w, cy)
                var last = drag.position
                horizontalDrag(drag.id) { c ->
                    c.consume()
                    last = c.position
                    val delta = c.position - down.position
                    scope.launch { curl.corner.snapTo(clampCorner(origin + Offset(delta.x * 1.6f, delta.y * 0.6f), w, cy)) }
                }
                val d = curl.dir
                scope.launch {
                    try {
                        val turned = if (d > 0) curl.corner.value.x < w * 0.5f else curl.corner.value.x > -w * 0.5f
                        val end = if (turned == (d > 0)) Offset(-w, cy) else Offset(w, cy)
                        curl.corner.animateTo(end, tween(260))
                        if (turned) goTo(indexNow + d)
                    } finally { curl.dir = 0 }
                }
            }
        },
    ) {
        // The pages at rest; a page being turned is drawn over them
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = false) { p -> page(p) }
        val d = curl.dir
        if (d != 0) {
            // Beneath: the page that is revealed; above: the page being turned
            val under = if (d > 0) index + 1 else index
            val over = if (d < 0) index - 1 else index
            if (under in 0 until count) Box(Modifier.fillMaxSize()) { page(under) }
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawCurl(layer, curl.corner.value, if (curl.top) 0f else size.height, paper)
                },
            ) { page(over.coerceIn(0, count - 1)) }
        }
    }
}

/** The corner cannot go further from the spine than the page is wide (paper does not stretch). */
private fun clampCorner(t: Offset, w: Float, cy: Float): Offset {
    val spine = Offset(0f, cy)
    val v = t - spine
    val len = hypot(v.x, v.y)
    return if (len > w) spine + v * (w / len) else t
}

private fun DrawScope.drawCurl(layer: GraphicsLayer, t: Offset, cy: Float, paper: Paper) {
    val w = size.width; val h = size.height
    val c = Offset(w, cy)
    val v = c - t
    val len = hypot(v.x, v.y)
    if (len < 1f) { drawLayer(layer); return }
    val n = Offset(v.x / len, v.y / len)
    val m = (c + t) / 2f
    val rect = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
    fun side(p: Offset) = (p.x - m.x) * n.x + (p.y - m.y) * n.y
    val kept = clip(rect) { side(it) <= 0f }
    val folded = clip(rect) { side(it) >= 0f }
    fun reflect(p: Offset): Offset { val s = 2f * side(p); return Offset(p.x - s * n.x, p.y - s * n.y) }
    fun path(pts: List<Offset>) = Path().apply { if (pts.isNotEmpty()) { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() } }
    // A shadow on the revealed page along the fold
    clipPath(path(folded)) {
        drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), start = m, end = m + n * min(60f, len)))
    }
    // The page itself, without the part lifted
    clipPath(path(kept)) { drawLayer(layer) }
    // The lifted part: its back, the text showing faintly through the paper
    val flap = folded.map(::reflect)
    if (flap.size >= 3) {
        val flapPath = path(flap)
        clipPath(flapPath) {
            // Reflection across the fold line: I − 2nnᵀ, moved so the line stays in place
            val mx = Matrix(floatArrayOf(
                1 - 2 * n.x * n.x, -2 * n.x * n.y, 0f, 0f,
                -2 * n.x * n.y, 1 - 2 * n.y * n.y, 0f, 0f,
                0f, 0f, 1f, 0f,
                2 * (m.x * n.x + m.y * n.y) * n.x, 2 * (m.x * n.x + m.y * n.y) * n.y, 0f, 1f,
            ))
            withTransform({ transform(mx) }) { drawLayer(layer) }
            drawRect(paper.background.copy(alpha = 0.86f))
            drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.04f)), start = m, end = m - n * max(1f, len / 2f)))
        }
        // The lifted part casts a soft shadow on the page beneath it
        drawPath(flapPath, Color.Black.copy(alpha = 0.06f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
    }
}

/** A polygon cut by a half-plane (Sutherland–Hodgman, one edge). */
private fun clip(poly: List<Offset>, inside: (Offset) -> Boolean): List<Offset> {
    val out = ArrayList<Offset>()
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val ia = inside(a); val ib = inside(b)
        if (ia) out += a
        if (ia != ib) {
            // Where the edge crosses: found by halving, exact enough for drawing
            var lo = a; var hi = b
            repeat(24) { val mid = (lo + hi) / 2f; if (inside(mid) == ia) lo = mid else hi = mid }
            out += (lo + hi) / 2f
        }
    }
    return out
}

/** A picture decoded no larger than the screen needs, off the main thread. */
@Composable
private fun BookImage(book: Book, key: String, widthPx: Int, maxHeight: androidx.compose.ui.unit.Dp) {
    val bitmap by produceState<ImageBitmap?>(null, key, widthPx) {
        value = withContext(Dispatchers.Default) { decodeFitting(book.images[key], widthPx) }
    }
    val img = bitmap
    if (img != null) Image(
        img, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.heightIn(max = maxHeight).let { if (img.width * 2 >= widthPx) it.fillMaxWidth() else it },
    ) else Box(Modifier.heightIn(min = 120.dp))
}

internal fun decodeFitting(bytes: ByteArray?, widthPx: Int): ImageBitmap? {
    bytes ?: return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // Never more than the screen's width, nor more than 16 megapixels
        while (bounds.outWidth / (sample * 2) >= widthPx.coerceAtLeast(1) || bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 16_000_000L) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
}

/**
 * The book's own page: its cover and how far it is read; bookmarks and notes as cards under the
 * chapter they are in; and the contents. A tap goes there.
 */
@Composable
internal fun BookContents(
    book: Book, bookmarks: Set<Int>, current: Int, onGo: (Int) -> Unit,
    notes: List<Note> = emptyList(), onNote: (Note) -> Unit = {}, onRemoveNote: (Long) -> Unit = {},
) {
    var editing by remember { mutableStateOf<Note?>(null) }
    editing?.let { n -> NoteDialog(excerpt(book, n), n, onSave = { onNote(it); editing = null }, onDelete = { onRemoveNote(n.at); editing = null }, onDismiss = { editing = null }) }
    // The chapters, found off the main thread (a big book has hundreds of thousands of blocks)
    val heads by produceState(emptyList<Triple<Int, String, Boolean>>(), book) {
        value = withContext(Dispatchers.Default) {
            book.blocks.withIndex().filter { (_, b) -> b is BookBlock.Text && (b.kind == BookBlock.Kind.HEADING1 || b.kind == BookBlock.Kind.HEADING2) }
                .take(5000).map { (i, b) -> Triple(i, (b as BookBlock.Text).text.replace('\n', ' ').take(120), b.kind == BookBlock.Kind.HEADING2) }
        }
    }
    val size = book.blocks.size.coerceAtLeast(1)
    fun percent(block: Int) = (block * 100L / size).toInt()
    // Bookmarks and notes together, in the order of the book, each under its chapter
    class Item(val block: Int, val mark: String?, val note: Note?)
    val items = remember(book, bookmarks, notes) {
        val marks = bookmarks.filter { it in book.blocks.indices }.map { i ->
            // Shown by the first words where the bookmark is
            val t = (i until minOf(book.blocks.size, i + 20)).asSequence().map { blockText(book.blocks[it]) }.firstOrNull { it.isNotBlank() } ?: ""
            Item(i, t.replace('\n', ' ').take(160), null)
        }
        (marks + notes.map { Item(it.block, null, it) }).sortedBy { it.block }
    }
    val chapterNow = heads.lastOrNull { it.first <= current }?.first
    // Each item's chapter, found once by halving (the headings are in book order), not on every redraw
    val chapters = remember(items, heads) {
        val main = heads.filter { !it.third }
        fun lastAt(list: List<Triple<Int, String, Boolean>>, block: Int): Triple<Int, String, Boolean>? {
            var lo = 0; var hi = list.size - 1; var ans = -1
            while (lo <= hi) { val mid = (lo + hi) / 2; if (list[mid].first <= block) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
            return list.getOrNull(ans)
        }
        items.map { lastAt(main, it.block) ?: lastAt(heads, it.block) }
    }
    var tab by rememberSaveable { mutableIntStateOf(if (items.isEmpty()) 1 else 0) }
    val cover by produceState<ImageBitmap?>(null, book) { value = withContext(Dispatchers.Default) { book.cover?.let { decodeFitting(book.images[it], 200) } } }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                cover?.let { Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(width = 58.dp, height = 86.dp).clip(RoundedCornerShape(4.dp))) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title.ifEmpty { "…" }, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Literata), fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotEmpty()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (current.toFloat() / size).coerceIn(0f, 1f) }, color = colors.primary, trackColor = colors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Text(stringResource(R.string.book_read_percent, percent(current)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item(key = "tabs") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, label) in listOf(stringResource(R.string.book_marks_notes) + " · " + items.size, stringResource(R.string.book_contents)).withIndex()) {
                    val on = tab == k
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (on) colors.onPrimary else colors.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) colors.primary else Color.Transparent)
                            .border(1.dp, if (on) colors.primary else colors.outlineVariant, RoundedCornerShape(20.dp))
                            .selectable(on, role = androidx.compose.ui.semantics.Role.Tab) { tab = k }
                            .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (tab == 0) {
            if (items.isEmpty()) item(key = "none") {
                Text(stringResource(R.string.book_marks_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }
            var lastChapter = -2
            for ((k, it) in items.withIndex()) {
                val ch = chapters[k]
                val chId = ch?.first ?: -1
                if (chId != lastChapter) {
                    lastChapter = chId
                    if (ch != null) item(key = "c$k") {
                        Text(ch.second.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp), fontWeight = FontWeight.SemiBold,
                            color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    }
                }
                item(key = "i$k") {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant)
                            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
                            .clickable { onGo(it.block) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (it.note == null) Icon(AppIcons.Bookmark, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                            else Box(Modifier.size(12.dp).clip(CircleShape).background(NOTE_COLORS[it.note.color.coerceIn(0, NOTE_COLORS.size - 1)]))
                            Text(
                                stringResource(if (it.note == null) R.string.book_mark_label else R.string.book_note_label, percent(it.block)),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                            )
                            if (it.note != null) androidx.compose.material3.IconButton(onClick = { editing = it.note }) {
                                Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = stringResource(R.string.note_edit), modifier = Modifier.size(20.dp))
                            }
                        }
                        if (it.note == null) Text(it.mark.orEmpty().ifEmpty { "…" }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata, lineHeight = 24.sp))
                        else {
                            Text(excerpt(book, it.note), maxLines = 5, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata, lineHeight = 24.sp),
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(NOTE_COLORS[it.note.color.coerceIn(0, NOTE_COLORS.size - 1)].copy(alpha = 0.30f)).padding(horizontal = 4.dp, vertical = 2.dp))
                            if (it.note.text.isNotEmpty()) {
                                androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant)
                                Text(it.note.text, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        } else {
            for ((i, t, sub) in heads) item(key = "h$i") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i == chapterNow) colors.secondaryContainer else Color.Transparent)
                        .clickable { onGo(i) }.padding(start = if (sub) 28.dp else 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t, maxLines = 3, modifier = Modifier.weight(1f),
                        style = if (sub) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata),
                        fontWeight = if (i == chapterNow) FontWeight.SemiBold else null,
                        color = if (i == chapterNow) colors.onSecondaryContainer else colors.onSurface)
                    Text("${percent(i)}%", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}
