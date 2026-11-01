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
) {
    var reading by rememberSaveable { mutableStateOf(startInReading) }
    var overview by rememberSaveable { mutableStateOf(startInOverview) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { list.firstVisibleItemIndex } }
    BackHandler(enabled = overview) { overview = false }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title = name, onBack = { if (overview) overview = false else onBack() }) {
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
        if (inspection != null && inspection.notable && !overview) InspectorBanner(inspection)
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
                is ViewerState.Paged -> when {
                    reading && state.reading != null -> TextDocument(ViewerState.Text(state.reading, Format.DOCX), note = false)
                    overview -> PageOverview(state.pages, bookmarks, current) { page ->
                        overview = false
                        scope.launch { list.scrollToItem(page) }
                    }
                    else -> ZoomablePages(state.pages, list, bookmarks)
                }
            }
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
private fun TextDocument(state: ViewerState.Text, note: Boolean = state.format == Format.ODT) {
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
