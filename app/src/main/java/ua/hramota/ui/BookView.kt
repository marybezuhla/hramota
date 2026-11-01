package ua.hramota.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.doc.SearchHit
import ua.hramota.doc.book.Book
import ua.hramota.doc.book.BookBlock

/** The text of a block for search (a picture has none). */
internal fun blockText(b: BookBlock): String = (b as? BookBlock.Text)?.text ?: ""

/**
 * A book to read: serif type set justified with hyphens, chapters and quotes as in print, pictures
 * fitted to the width. Only the blocks on screen are laid out; pictures are decoded off the main thread.
 */
@Composable
internal fun BookDocument(book: Book, list: LazyListState, hits: Map<Int, List<SearchHit>>, current: SearchHit?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        SelectionContainer {
            // A comfortable line on a tablet too: about 70 characters at most
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 680.dp), state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 48.dp)) {
                itemsIndexed(book.blocks, contentType = { _, b -> if (b is BookBlock.Image) 1 else (b as BookBlock.Text).kind.ordinal + 2 }) { i, b ->
                    when (b) {
                        is BookBlock.Image -> BookImage(book, b.key, widthPx)
                        is BookBlock.Text -> BookText(b, hits[i], current, first = i == 0 || book.blocks[i - 1] !is BookBlock.Text || (book.blocks[i - 1] as BookBlock.Text).kind != BookBlock.Kind.PARAGRAPH)
                    }
                }
            }
        }
    }
}

private val bookBody = TextStyle(
    fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 1.5.em,
    textAlign = TextAlign.Justify, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
)

@Composable
private fun BookText(b: BookBlock.Text, hits: List<SearchHit>?, current: SearchHit?, first: Boolean) {
    val type = MaterialTheme.typography
    val color = MaterialTheme.colorScheme
    if (b.kind == BookBlock.Kind.BREAK) { Box(Modifier.padding(vertical = 10.dp)); return }
    val (style, modifier) = when (b.kind) {
        BookBlock.Kind.HEADING1 -> type.headlineSmall.copy(fontFamily = FontFamily.Serif, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold) to Modifier.padding(top = 40.dp, bottom = 20.dp)
        BookBlock.Kind.HEADING2 -> type.titleLarge.copy(fontFamily = FontFamily.Serif, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold) to Modifier.padding(top = 28.dp, bottom = 12.dp)
        BookBlock.Kind.HEADING3 -> type.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) to Modifier.padding(top = 20.dp, bottom = 8.dp)
        BookBlock.Kind.SUBTITLE -> bookBody.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold) to Modifier.padding(vertical = 12.dp)
        BookBlock.Kind.QUOTE -> bookBody.copy(fontSize = 16.sp, fontStyle = FontStyle.Italic, color = color.onSurfaceVariant) to Modifier.padding(start = 32.dp, top = 2.dp, bottom = 2.dp)
        BookBlock.Kind.POEM -> bookBody.copy(textAlign = TextAlign.Start, hyphens = Hyphens.None) to Modifier.padding(start = 36.dp)
        // A paragraph after a heading, a picture or a quote starts flush, as in print
        else -> bookBody.copy(textIndent = if (first) null else TextIndent(firstLine = 1.5.em)) to Modifier.padding(vertical = 1.dp)
    }
    Text(styled(b, hits, current), style = style.copy(color = if (style.color.isUnspecifiedColor()) color.onSurface else style.color), modifier = modifier.fillMaxWidth())
}

private fun androidx.compose.ui.graphics.Color.isUnspecifiedColor() = this == androidx.compose.ui.graphics.Color.Unspecified

private fun styled(b: BookBlock.Text, hits: List<SearchHit>?, current: SearchHit?): AnnotatedString {
    if (b.spans.isEmpty() && hits.isNullOrEmpty()) return AnnotatedString(b.text)
    return buildAnnotatedString {
        append(b.text)
        for (s in b.spans) {
            val a = s.start.coerceIn(0, b.text.length); val e = s.end.coerceIn(a, b.text.length)
            if (a < e) addStyle(SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                baselineShift = if (s.raised) BaselineShift.Superscript else null,
                fontSize = if (s.raised) 0.7.em else androidx.compose.ui.unit.TextUnit.Unspecified,
            ), a, e)
        }
        hits?.forEach { h ->
            val a = h.start.coerceIn(0, b.text.length); val e = h.end.coerceIn(a, b.text.length)
            if (a < e) addStyle(SpanStyle(background = if (h == current) androidx.compose.ui.graphics.Color(0xCCFF9800) else androidx.compose.ui.graphics.Color(0x80FFD54F)), a, e)
        }
    }
}

/** A picture decoded no larger than the screen needs, off the main thread. */
@Composable
private fun BookImage(book: Book, key: String, widthPx: Int) {
    val bitmap by produceState<ImageBitmap?>(null, key, widthPx) {
        value = withContext(Dispatchers.Default) { decodeFitting(book.images[key], widthPx) }
    }
    val img = bitmap
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        if (img != null) Image(
            img, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = 560.dp).let { if (img.width * 2 >= widthPx) it.fillMaxWidth() else it },
        ) else Box(Modifier.heightIn(min = 120.dp))
    }
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

/** The contents: chapters and bookmarks; a tap goes there. */
@Composable
internal fun BookContents(book: Book, bookmarks: Set<Int>, current: Int, onGo: (Int) -> Unit) {
    val heads = remember(book) {
        book.blocks.withIndex().filter { (_, b) -> b is BookBlock.Text && (b.kind == BookBlock.Kind.HEADING1 || b.kind == BookBlock.Kind.HEADING2) }
            .map { (i, b) -> Triple(i, (b as BookBlock.Text).text.replace('\n', ' ').take(120), b.kind == BookBlock.Kind.HEADING2) }
    }
    val marks = remember(book, bookmarks) {
        bookmarks.sorted().filter { it in book.blocks.indices }.map { i ->
            // Shown by the first words where the bookmark is
            val t = (i until minOf(book.blocks.size, i + 20)).asSequence().map { blockText(book.blocks[it]) }.firstOrNull { it.isNotBlank() } ?: ""
            i to t.replace('\n', ' ').take(80)
        }
    }
    val chapterNow = heads.lastOrNull { it.first <= current }?.first
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (book.title.isNotEmpty() || book.author.isNotEmpty()) item {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (book.title.isNotEmpty()) Text(book.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
                if (book.author.isNotEmpty()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (marks.isNotEmpty()) {
            item { Section(stringResource(R.string.book_bookmarks)) }
            for ((i, t) in marks) item(key = "m$i") {
                Row(Modifier.fillMaxWidth().clickable { onGo(i) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(AppIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(t.ifEmpty { "…" }, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (heads.isNotEmpty()) {
            item { Section(stringResource(R.string.book_contents)) }
            for ((i, t, sub) in heads) item(key = "h$i") {
                Text(
                    t, maxLines = 3,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (i == chapterNow) FontWeight.SemiBold else null,
                    color = if (i == chapterNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { onGo(i) }.padding(start = if (sub) 40.dp else 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}
