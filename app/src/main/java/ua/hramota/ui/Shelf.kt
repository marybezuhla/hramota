package ua.hramota.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.heightIn
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import ua.hramota.data.ShelfDef
import java.io.File

/** What can be done with a document on the shelf. */
enum class ShelfAction { DROP_COPY, FORGET }

/** The colour that marks a shelf: its own hue, or the natural one of its style. */
@Composable
fun shelfColor(def: ShelfDef): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        def.hue >= 0f -> Color.hsl(def.hue, 0.5f, if (dark) 0.62f else 0.45f)
        def.style == "wood" -> Color(0xFF9A6A3C)
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * The bookshelf: only documents kept inside Hramota. "All" holds every one; shelves of one's own are
 * like tags, each with its look and colour. Opened-only files live in the recent list instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(
    entries: List<LibraryEntry>, shelves: List<ShelfDef>,
    onOpen: (LibraryEntry) -> Unit, onAction: (LibraryEntry, ShelfAction) -> Unit,
    onPutShelf: (ShelfDef) -> Unit = {}, onRemoveShelf: (String) -> Unit = {}, onSetShelves: (LibraryEntry, Set<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier, startShelf: String = ShelfDef.ALL,
) {
    var selected by rememberSaveable { mutableStateOf(startShelf) }
    val current = shelves.firstOrNull { it.id == selected } ?: shelves.firstOrNull() ?: ShelfDef(ShelfDef.ALL, "")
    // Shelves that fill themselves: by kind of document (shown when there is more than one kind)
    val kinds = remember(entries) { KINDS.map { k -> k to entries.count { kindOf(it.format) == k.first } }.filter { it.second > 0 } }
    val kind = KINDS.firstOrNull { "kind:" + it.first == selected }
    var editing by remember { mutableStateOf<ShelfDef?>(null) }
    var choosing by remember { mutableStateOf<LibraryEntry?>(null) }
    // Both delete the copy kept in Hramota: asked first
    var confirming by remember { mutableStateOf<Pair<LibraryEntry, ShelfAction>?>(null) }
    confirming?.let { (e, a) ->
        val title = e.title.substringBeforeLast('.', e.title)
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { Text(stringResource(if (a == ShelfAction.FORGET) R.string.forget_confirm else R.string.drop_confirm, title)) },
            confirmButton = { TextButton(onClick = { confirming = null; onAction(e, a) }) { Text(stringResource(if (a == ShelfAction.FORGET) R.string.forget_go else R.string.drop_go), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Column(modifier) {
        // The shelves, as tabs with their colours; the selected one opens its settings on a second tap
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shelves, key = { it.id }) { d ->
                val count = if (d.id == ShelfDef.ALL) entries.size else entries.count { d.id in it.shelves }
                val on = kind == null && d.id == current.id
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (on) shelfColor(d).copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, if (on) shelfColor(d) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .combinedClickable(onClick = { if (on) editing = d else selected = d.id }, onLongClick = { editing = d }, onLongClickLabel = stringResource(R.string.shelf_edit))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(shelfColor(d)))
                    Text(
                        "${if (d.id == ShelfDef.ALL) stringResource(R.string.shelf_all) else d.name} · $count",
                        style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else null,
                        modifier = Modifier.padding(start = 8.dp), maxLines = 1,
                    )
                }
            }
            if (kinds.size > 1) items(kinds, key = { "kind:" + it.first.first }) { (k, count) ->
                val on = kind == k
                Text(
                    "${stringResource(k.second)} · $count", style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else null, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .clickable { selected = if (on) ShelfDef.ALL else "kind:" + k.first }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            item(key = "+") {
                Text(
                    "+ " + stringResource(R.string.shelf_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { editing = ShelfDef("s" + System.currentTimeMillis(), "", hue = 140f, created = System.currentTimeMillis()) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        val shown = when {
            kind != null -> entries.filter { kindOf(it.format) == kind.first }
            current.id == ShelfDef.ALL -> entries
            else -> entries.filter { current.id in it.shelves }
        }
        val menu: @Composable (LibraryEntry, () -> Unit) -> Unit = { e, close ->
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_choose)) }, onClick = { close(); choosing = e })
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_drop_copy)) }, onClick = { close(); confirming = e to ShelfAction.DROP_COPY })
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_forget)) }, onClick = { close(); confirming = e to ShelfAction.FORGET })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) Text(
                stringResource(if (current.id == ShelfDef.ALL) R.string.shelf_empty else R.string.shelf_own_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) else when (current.style) {
                "wood" -> WoodShelf(shown, current.hue, onOpen, menu)
                "list" -> ListShelf(shown, shelfColor(current), onOpen, menu)
                else -> ModernShelf(shown, current.hue, onOpen, menu)
            }
        }
    }
    editing?.let { d ->
        ShelfEditor(d, isNew = shelves.none { it.id == d.id }, onSave = { onPutShelf(it); selected = it.id; editing = null },
            onDelete = { onRemoveShelf(d.id); selected = ShelfDef.ALL; editing = null }, onDismiss = { editing = null })
    }
    choosing?.let { e ->
        ChooseShelves(e, shelves.filter { it.id != ShelfDef.ALL }, onPutShelf, onDone = { onSetShelves(e, it); choosing = null }, onDismiss = { choosing = null })
    }
}

@Composable
private fun ModernShelf(entries: List<LibraryEntry>, hue: Float, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = if (hue >= 0f) Color.hsl(hue, 0.35f, if (dark) 0.13f else 0.94f) else Color.Transparent
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize().background(tint),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The one being read, at the top with how far it is, to go on with one tap
        entries.filter { it.total > 1 && it.position in 1 until it.total - 1 }.maxByOrNull { it.openedAt }?.let { now ->
            item(key = "now", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.shelf_reading_now), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Book(now, onOpen, menu, Modifier.width(104.dp).shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)), spine = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(now.title.substringBeforeLast('.', now.title), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Progress(now)
                            androidx.compose.material3.Button(onClick = { onOpen(now) }, modifier = Modifier.padding(top = 4.dp).heightIn(min = 44.dp)) { Text(stringResource(R.string.shelf_continue)) }
                        }
                    }
                    Text(stringResource(R.string.shelf_everything), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        items(entries, key = { it.id }) { e ->
            Column {
                Book(e, onOpen, menu, Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)), spine = true)
                Text(e.title.substringBeforeLast('.', e.title), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Progress(e)
            }
        }
    }
}

/** Wood in its natural colour, or stained in the shelf's hue. */
private fun woodTones(hue: Float): List<Color> = if (hue < 0f) listOf(
    Color(0xFFB98552), Color(0xFFA06C3D), Color(0xFFB4804F), Color(0xFF7A4A22), Color(0xFF5C3517), Color(0xFF3E2410),
) else listOf(
    Color.hsl(hue, 0.36f, 0.44f), Color.hsl(hue, 0.38f, 0.36f), Color.hsl(hue, 0.36f, 0.42f),
    Color.hsl(hue, 0.36f, 0.30f), Color.hsl(hue, 0.38f, 0.23f), Color.hsl(hue, 0.40f, 0.15f),
)

/** The old wooden bookshelf: covers standing on planks, wood grain behind. */
@Composable
private fun WoodShelf(entries: List<LibraryEntry>, hue: Float, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit) {
    val t = woodTones(hue)
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(t.take(3)))
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
                    // The back of the shelf darkens towards the plank above it: the books stand in a recess
                    Row(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color(0x70000000), 0.3f to Color.Transparent))
                            .padding(start = 18.dp, end = 18.dp, top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom,
                    ) {
                        for (e in row) Book(e, onOpen, menu, Modifier.weight(1f).shadow(8.dp, RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                            .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 4.dp, bottomEnd = 4.dp)), spine = true)
                        repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                    }
                    // The plank the books stand on: a lit front edge, then its shadow on the wall
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(16.dp).shadow(8.dp, RoundedCornerShape(2.dp)).clip(RoundedCornerShape(2.dp))
                            .background(Brush.verticalGradient(listOf(t[0], t[2], t[3], t[4])))
                            .drawBehind { drawLine(Color(0x99FFE6C8), Offset(0f, 1f), Offset(size.width, 1f), strokeWidth = 2f) },
                    )
                    Box(Modifier.height(10.dp))
                }
            }
        }
    }
}

/** A simple list: small cover, title, progress, the shelf's colour along the edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListShelf(entries: List<LibraryEntry>, color: Color, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(entries, key = { it.id }) { e ->
            var open by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { onOpen(e) }, onLongClick = { open = true })
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(64.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Box(Modifier.padding(start = 10.dp).width(46.dp)) { Cover(e, Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.title.substringBeforeLast('.', e.title), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Progress(e)
                    }
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu(e) { open = false } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Book(e: LibraryEntry, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit, modifier: Modifier, spine: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = { onOpen(e) }, onLongClick = { open = true })) {
        Cover(e, Modifier.fillMaxWidth())
        // Light along the spine, as on a real book's binding
        if (spine) Box(Modifier.matchParentSize().drawBehind {
            drawRect(Brush.horizontalGradient(0f to Color(0x47FFFFFF), 0.04f to Color(0x22000000), 0.09f to Color.Transparent))
        })
        if (e.bookmarks.isNotEmpty()) Icon(AppIcons.Bookmark, contentDescription = null, tint = Color(0xFFE5484D), modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp).width(18.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu(e) { open = false } }
    }
}

@Composable
private fun Cover(e: LibraryEntry, modifier: Modifier) {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, e.id) {
        value = withContext(Dispatchers.IO) {
            ua.hramota.data.Shelf.loadCover(context, e.id)?.asImageBitmap()
        }
    }
    BoxWithConstraints(modifier.aspectRatio(0.72f)) {
        val width = maxWidth
        val c = cover
        if (c != null) Image(c, contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color.White))
        // A small cover has no room for the title: only the format
        else if (maxWidth < 72.dp) Box(Modifier.fillMaxSize().background(coverColor(e.format)), contentAlignment = Alignment.Center) {
            Text(e.format, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        else Box(Modifier.fillMaxSize().background(coverColor(e.format)).padding(8.dp)) {
            val title = e.title.substringBeforeLast('.', e.title)
            // The longest word must fit on one line: never «Порівняльн-а» broken mid-word
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val room = with(density) { (width - 26.dp).toPx() }
            val size = remember(title, room) {
                val word = title.split(' ', '-', '_').maxByOrNull { it.length } ?: ""
                var s = 13f
                while (s > 9f && measurer.measure(word, TextStyle(fontSize = s.sp, fontWeight = FontWeight.SemiBold)).size.width > room) s -= 0.5f
                s
            }
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size + 3).sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start, modifier = Modifier.align(Alignment.TopStart).padding(end = 10.dp))
            Text(e.format, color = Color(0xCCFFFFFF), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun Progress(e: LibraryEntry) {
    if (e.total <= 1) return
    val page = (e.position + 1).coerceAtMost(e.total)
    val percent = ((page.toLong() * 100) / e.total).toInt()
    Text(if (e.format == "EPUB" || e.format == "FB2") "$percent%" else stringResource(R.string.shelf_progress, page, e.total),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    LinearProgressIndicator(progress = { page.toFloat() / e.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)))
}

private fun coverColor(format: String): Color = when (format) {
    "PDF" -> Color(0xFF9C2F2F); "DOCX", "DOC", "ODT" -> Color(0xFF1F4E79); "XLSX", "XLS", "CSV" -> Color(0xFF2E6B3A)
    "PPTX", "PPT" -> Color(0xFFB2541A); "EPUB", "FB2" -> Color(0xFF5B3F8C); else -> Color(0xFF4A4A4A)
}

/** A shelf's name, look and colour; a slider picks the colour, or the natural one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfEditor(def: ShelfDef, isNew: Boolean, onSave: (ShelfDef) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(def.name) }
    var style by remember { mutableStateOf(def.style) }
    var natural by remember { mutableStateOf(def.hue < 0f) }
    var hue by remember { mutableFloatStateOf(if (def.hue < 0f) 30f else def.hue) }
    val all = def.id == ShelfDef.ALL
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.shelf_new else R.string.shelf_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!all) OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.shelf_name)) })
                val styles = listOf("modern" to R.string.shelf_style_modern, "wood" to R.string.shelf_style_wood, "list" to R.string.shelf_style_list)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    styles.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = style == k, onClick = { style = k }, shape = SegmentedButtonDefaults.itemShape(i, styles.size)) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { natural = !natural }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.shelf_natural), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = natural, onCheckedChange = null)
                }
                // The colour: a rainbow to slide along, and how it looks on this style
                val preview = ShelfDef(def.id, name, style, if (natural) -1f else hue)
                if (!natural) Box(Modifier.fillMaxWidth().height(40.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient((0..12).map { Color.hsl(it * 30f, 0.5f, 0.5f) })))
                    Slider(hue, { hue = it }, valueRange = 0f..360f, colors = SliderDefaults.colors(thumbColor = shelfColor(preview), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
                }
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).let { m ->
                    if (style == "wood") m.background(Brush.verticalGradient(woodTones(preview.hue))) else m.background(shelfColor(preview).copy(alpha = 0.25f))
                })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(def.copy(name = name.trim(), style = style, hue = if (natural) -1f else hue)) }, enabled = all || name.isNotBlank()) { Text(stringResource(R.string.note_save)) }
        },
        dismissButton = {
            Row {
                if (!all && !isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.shelf_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Which of one's shelves a document stands on; a new shelf can be made here too. */
@Composable
private fun ChooseShelves(e: LibraryEntry, shelves: List<ShelfDef>, onPutShelf: (ShelfDef) -> Unit, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(e.shelves) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelf_choose)) },
        text = {
            Column {
                for (d in shelves) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { chosen = if (d.id in chosen) chosen - d.id else chosen + d.id }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = d.id in chosen, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(shelfColor(d)))
                    Text(d.name, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(newName, { newName = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.shelf_new)) })
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        val now = System.currentTimeMillis()
                        // A new shelf gets a colour of its own, going round the rainbow
                        val d = ShelfDef("s$now", newName.trim(), hue = (shelves.size * 67f + 140f) % 360f, created = now)
                        onPutShelf(d); chosen = chosen + d.id; newName = ""
                    }) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text(stringResource(R.string.note_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The kinds of document the shelf sorts itself by: id and name. */
private val KINDS = listOf(
    "books" to R.string.kind_books, "docs" to R.string.kind_docs, "sheets" to R.string.kind_sheets,
    "slides" to R.string.kind_slides, "pdf" to R.string.kind_pdf,
)

/** Which kind a format ("EPUB", "DOCX"…) is. */
private fun kindOf(format: String): String = when (format.uppercase()) {
    "EPUB", "FB2" -> "books"
    "XLSX", "XLS", "ODS", "CSV" -> "sheets"
    "PPTX", "PPT", "ODP" -> "slides"
    "PDF" -> "pdf"
    else -> "docs"
}
