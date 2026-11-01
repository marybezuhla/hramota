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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import ua.hramota.doc.Paragraph
import ua.hramota.doc.PdfPages

/** What the viewer shows for one file. */
sealed interface ViewerState {
    data object Loading : ViewerState
    data object Failed : ViewerState
    data class Unsupported(val format: Format) : ViewerState
    data class Text(val paragraphs: List<Paragraph>, val format: Format) : ViewerState
    data class Pdf(val pages: PdfPages) : ViewerState
}

@Composable
fun ViewerScreen(name: String, state: ViewerState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title = name, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                ViewerState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading), modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ViewerState.Failed -> Message(stringResource(R.string.open_failed), null)
                is ViewerState.Unsupported -> Message(
                    stringResource(R.string.unsupported_title),
                    stringResource(R.string.unsupported_body, state.format.label.ifEmpty { name.substringAfterLast('.', "?").uppercase() }),
                )
                is ViewerState.Text -> TextDocument(state)
                is ViewerState.Pdf -> PdfDocument(state.pages)
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
private fun TextDocument(state: ViewerState.Text) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            if (state.format == Format.DOCX || state.format == Format.ODT) item {
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

/** PDF pages one under another; each is drawn when it scrolls into view, at the screen's width. */
@Composable
private fun PdfDocument(pages: PdfPages) {
    val list = rememberLazyListState()
    val current by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 16.dp).roundToPx() }
        LazyColumn(state = list, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages.pageCount, key = { it }) { index ->
                var image by remember(index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(index, widthPx) { image = pages.render(index, widthPx) }
                Box(Modifier.fillMaxWidth().aspectRatio(1f / pages.aspect(index)).background(Color.White)) {
                    image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        if (pages.pageCount > 1) Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) {
            Text(
                stringResource(R.string.page_of, current, pages.pageCount),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
