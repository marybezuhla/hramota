package ua.hramota.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import ua.hramota.R
import ua.hramota.doc.PageBox
import ua.hramota.doc.Pages
import ua.hramota.doc.Search
import ua.hramota.doc.SearchHit
import ua.hramota.doc.TextPages
import ua.hramota.doc.TextSelection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f

/**
 * Pages one under another, drawn when they scroll into view. Pinch or double-tap to zoom;
 * when zoom settles, visible pages are drawn again sharper (up to twice the screen width).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ZoomablePages(
    pages: Pages,
    list: LazyListState,
    bookmarks: Set<Int>,
    hits: Map<Int, List<SearchHit>> = emptyMap(),
    currentHit: SearchHit? = null,
    selection: TextSelection? = null,
    onSelection: (TextSelection?) -> Unit = {},
) {
    val text = pages as? TextPages
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    // The sharpness pages are drawn at follows the zoom once the fingers stop
    var quality by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { scale }.debounce(250).collect { quality = if (it > 1.4f) 2f else 1f }
    }
    val current by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val widthPx = with(density) { (maxWidth - 16.dp).roundToPx() }
        fun clampX(x: Float, s: Float): Float { val m = (s - 1f) * viewW / 2f; return x.coerceIn(-m, m) }

        LazyColumn(
            state = list,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val target = if (scale > 1.1f) 1f else 2.5f
                        // Keep the tapped point under the finger
                        val dy = tap.y / scale - tap.y / target
                        list.dispatchRawDelta(dy)
                        offsetX = clampX((offsetX - (tap.x - viewW / 2f)) * target / scale + (tap.x - viewW / 2f), target)
                        scale = target
                        if (target == 1f) offsetX = 0f
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            // One finger at normal size scrolls the list as usual
                            if (fingers >= 2 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = if (fingers >= 2) event.calculateCentroid() else Offset(viewW / 2f, 0f)
                                val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                // Zoom around the fingers: the point between them stays put
                                list.dispatchRawDelta(c.y / scale - c.y / next - pan.y / next)
                                offsetX = clampX((offsetX - (c.x - viewW / 2f)) * next / scale + (c.x - viewW / 2f) + pan.x, next)
                                scale = next
                                if (abs(zoom - 1f) > 0.001f || pan != Offset.Zero) event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offsetX
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            items(pages.pageCount, key = { it }) { index ->
                val renderW = (widthPx * quality).roundToInt()
                var image by remember(index) { mutableStateOf<ImageBitmap?>(null) }
                var failed by remember(index) { mutableStateOf(false) }
                LaunchedEffect(index, renderW) {
                    val drawn = pages.render(index, renderW)
                    if (drawn != null) { image = drawn; failed = false } else if (image == null) failed = true
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(pageRatio(pages.aspect(index))).background(Color.White)
                        .then(if (text != null) Modifier.selectText(text, index, selection, onSelection) else Modifier),
                ) {
                    image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    if (failed && image == null) PageProblem(pages.problem)
                    if (text != null) TextMarks(text, index, hits[index].orEmpty(), currentHit, selection)
                    if (index in bookmarks) Icon(
                        AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp).size(28.dp),
                    )
                }
            }
        }
        if (pages.pageCount > 1) Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) {
            Text(
                // "…" while a long document is still being laid out
                stringResource(R.string.page_of, current, pages.pageCount) + when {
                    pages.cutShort -> " · " + stringResource(R.string.pages_cut)
                    pages.complete -> ""
                    else -> "…"
                },
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * All pages laid out like on a table (as in Apple Books): pinch to see more or fewer per row,
 * tap to open a page. A second tab shows only bookmarked pages.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PageOverview(pages: Pages, bookmarks: Set<Int>, current: Int, onOpen: (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val shown = if (tab == 0) (0 until pages.pageCount).toList() else bookmarks.filter { it < pages.pageCount }.sorted()
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (tab == 0) current.coerceIn(0, maxOf(0, pages.pageCount - 1)) else 0)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.overview_all, pages.pageCount)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.overview_bookmarks, bookmarks.size)) })
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.overview_no_bookmarks), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val thumbW = with(density) { ((maxWidth - 16.dp * (columns + 1)) / columns).roundToPx() }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    // Pinch out for fewer, bigger pages; pinch in for more
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var acc = 1f
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                acc *= e.calculateZoom()
                                if (acc > 1.35f) { columns = (columns - 1).coerceAtLeast(1); acc = 1f }
                                if (acc < 0.75f) { columns = (columns + 1).coerceAtMost(6); acc = 1f }
                                e.changes.forEach { it.consume() }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                },
            ) {
                items(shown, key = { it }) { index ->
                    var image by remember(index) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(index, thumbW) { pages.render(index, thumbW.coerceAtLeast(64))?.let { image = it } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onOpen(index) }) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(pageRatio(pages.aspect(index)))
                                .shadow(if (index == current) 8.dp else 3.dp, RoundedCornerShape(2.dp))
                                .background(Color.White),
                        ) {
                            image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                            if (index in bookmarks) Icon(
                                AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).size(if (columns >= 4) 14.dp else 20.dp),
                            )
                        }
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == current) FontWeight.Bold else null,
                            color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Said on a page that could not be drawn, instead of leaving a blank sheet; the detail helps fix it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageProblem(detail: String?) {
    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.page_failed), color = Color(0xFF555555), style = MaterialTheme.typography.bodyMedium)
        if (detail != null) Text(detail.take(160), color = Color(0xFF888888), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Search matches and the selection drawn over a page; boxes are fetched when the page is shown. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TextMarks(
    text: TextPages, index: Int, hits: List<SearchHit>, current: SearchHit?, selection: TextSelection?,
) {
    var hitBoxes by remember(index) { mutableStateOf<List<Pair<SearchHit, List<PageBox>>>>(emptyList()) }
    LaunchedEffect(index, hits) { hitBoxes = hits.take(500).map { it to text.boxes(index, it.start, it.end) } }
    var selBoxes by remember(index) { mutableStateOf<List<PageBox>>(emptyList()) }
    LaunchedEffect(index, selection) {
        selBoxes = if (selection != null && selection.page == index) text.boxes(index, selection.start, selection.end) else emptyList()
    }
    if (hitBoxes.isEmpty() && selBoxes.isEmpty()) return
    val hitColor = Color(0x66FFD54F)
    val currentColor = Color(0x99FF9800)
    val selColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Canvas(Modifier.matchParentSize()) {
        fun box(b: PageBox, c: Color) = drawRect(c, Offset(b.left * size.width, b.top * size.height),
            androidx.compose.ui.geometry.Size((b.right - b.left) * size.width, (b.bottom - b.top) * size.height))
        for ((hit, boxes) in hitBoxes) boxes.forEach { box(it, if (hit == current) currentColor else hitColor) }
        selBoxes.forEach { box(it, selColor) }
    }
}

/**
 * Long press on a word selects it; keep the finger down and drag to select more. Character
 * look-ups go to the engine one at a time, so a fast drag never piles up work.
 */
private fun Modifier.selectText(text: TextPages, index: Int, selection: TextSelection?, onSelection: (TextSelection?) -> Unit): Modifier =
    pointerInput(text, index) {
        coroutineScope {
            var busy = false
            var anchor = -1
            fun look(p: Offset, then: (Int) -> Unit) {
                if (busy) return
                busy = true
                launch {
                    val c = text.charAt(index, p.x / size.width, p.y / size.height)
                    busy = false
                    if (c >= 0) then(c)
                }
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    look(p) { c ->
                        launch {
                            val w = Search.wordAt(text.pageText(index), c)
                            if (!w.isEmpty()) { anchor = w.first; onSelection(TextSelection(index, w.first, w.last)) }
                        }
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    look(change.position) { c -> if (anchor >= 0) onSelection(TextSelection(index, anchor, c)) }
                },
            )
        }
    }

/** Width / height for a page, never zero, negative or infinite, whatever the file says. */
fun pageRatio(aspect: Float): Float = (1f / aspect).takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 4f) ?: (1f / 1.414f)
