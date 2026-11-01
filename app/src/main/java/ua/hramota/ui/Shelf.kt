package ua.hramota.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.data.Library
import ua.hramota.data.LibraryEntry
import java.io.File

/** What can be done with a document on the shelf. */
enum class ShelfAction { REMOVE, KEEP_COPY, DROP_COPY, FORGET }

/** The bookshelf: documents kept with their covers and where reading stopped. */
@Composable
fun Shelf(entries: List<LibraryEntry>, wood: Boolean, onOpen: (LibraryEntry) -> Unit, onAction: (LibraryEntry, ShelfAction) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.shelf_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        return
    }
    if (wood) WoodShelf(entries, onOpen, onAction, modifier) else ModernShelf(entries, onOpen, onAction, modifier)
}

@Composable
private fun ModernShelf(entries: List<LibraryEntry>, onOpen: (LibraryEntry) -> Unit, onAction: (LibraryEntry, ShelfAction) -> Unit, modifier: Modifier) {
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), modifier,
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(entries, key = { it.id }) { e ->
            Column {
                Book(e, onOpen, onAction, Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)))
                Text(e.title.substringBeforeLast('.', e.title), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Progress(e)
            }
        }
    }
}

/** The old wooden bookshelf: covers standing on planks, warm wood behind. */
@Composable
private fun WoodShelf(entries: List<LibraryEntry>, onOpen: (LibraryEntry) -> Unit, onAction: (LibraryEntry, ShelfAction) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFB98552), Color(0xFFA06C3D), Color(0xFFB4804F))))
        .drawBehind {
            // Wood grain: faint wavy lines
            var y = 0f
            var k = 0
            while (y < size.height) {
                drawLine(Color(0x22000000), Offset(0f, y), Offset(size.width, y + 6f * ((k % 3) - 1)), strokeWidth = 1.2f)
                y += 9f + (k * 7 % 11); k++
            }
        }) {
        val perRow = (maxWidth / 112.dp).toInt().coerceIn(2, 8)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)) {
            items(entries.chunked(perRow)) { row ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        for (e in row) Book(e, onOpen, onAction, Modifier.weight(1f).shadow(6.dp, RoundedCornerShape(2.dp)).clip(RoundedCornerShape(2.dp)))
                        repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                    }
                    // The plank the books stand on
                    Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(Color(0xFF7A4A22), Color(0xFF5C3517), Color(0xFF3E2410)))))
                    Box(Modifier.fillMaxWidth().height(10.dp).background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))))
                    Box(Modifier.height(14.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Book(e: LibraryEntry, onOpen: (LibraryEntry) -> Unit, onAction: (LibraryEntry, ShelfAction) -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, e.id) {
        value = withContext(Dispatchers.IO) {
            File(Library.coversDir(context), "${e.id}.png").takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    Box(modifier.aspectRatio(0.72f).combinedClickable(onClick = { onOpen(e) }, onLongClick = { menu = true })) {
        val c = cover
        if (c != null) Image(c, contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color.White))
        else Box(Modifier.fillMaxSize().background(coverColor(e.format)).padding(10.dp)) {
            Text(e.title.substringBeforeLast('.', e.title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start, modifier = Modifier.align(Alignment.TopStart))
            Text(e.format, color = Color(0xCCFFFFFF), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
        }
        if (e.bookmarks.isNotEmpty()) Icon(AppIcons.Bookmark, contentDescription = null, tint = Color(0xFFE5484D), modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp).width(18.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_remove)) }, onClick = { menu = false; onAction(e, ShelfAction.REMOVE) })
            if (e.copy == null) DropdownMenuItem(text = { Text(stringResource(R.string.shelf_keep_copy)) }, onClick = { menu = false; onAction(e, ShelfAction.KEEP_COPY) })
            else DropdownMenuItem(text = { Text(stringResource(R.string.shelf_drop_copy)) }, onClick = { menu = false; onAction(e, ShelfAction.DROP_COPY) })
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_forget)) }, onClick = { menu = false; onAction(e, ShelfAction.FORGET) })
        }
    }
}

@Composable
private fun Progress(e: LibraryEntry) {
    if (e.total <= 1) return
    val page = (e.position + 1).coerceAtMost(e.total)
    Text(stringResource(R.string.shelf_progress, page, e.total) + if (e.bookmarks.isNotEmpty()) " · " + stringResource(R.string.shelf_bookmarks, e.bookmarks.size) else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    LinearProgressIndicator(progress = { page.toFloat() / e.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)))
}

private fun coverColor(format: String): Color = when (format) {
    "PDF" -> Color(0xFF9C2F2F); "DOCX", "DOC", "ODT" -> Color(0xFF1F4E79); "XLSX", "XLS", "CSV" -> Color(0xFF2E6B3A)
    "PPTX", "PPT" -> Color(0xFFB2541A); "EPUB", "FB2" -> Color(0xFF5B3F8C); else -> Color(0xFF4A4A4A)
}
