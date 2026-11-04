package ua.hramota.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.runtime.LaunchedEffect
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
    // A kind chosen before its last document went (or with one kind left, so no chips): all are shown again
    val kind = KINDS.firstOrNull { "kind:" + it.first == selected }?.takeIf { k -> kinds.size > 1 && kinds.any { it.first == k } }
    LaunchedEffect(kind == null && selected.startsWith("kind:")) { if (kind == null && selected.startsWith("kind:")) selected = ShelfDef.ALL }
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
                    Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(18.dp))
                        .background(if (on) shelfColor(d).copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, if (on) shelfColor(d) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .semantics { this.selected = on }
                        .combinedClickable(role = Role.Tab, onClick = { if (on) editing = d else selected = d.id }, onLongClick = { editing = d }, onLongClickLabel = stringResource(R.string.shelf_edit))
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
                    modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(18.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .selectable(on, role = Role.Tab) { selected = if (on) ShelfDef.ALL else "kind:" + k.first }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            item(key = "+") {
                Text(
                    "+ " + stringResource(R.string.shelf_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { editing = ShelfDef("s" + System.currentTimeMillis(), "", hue = 140f, created = System.currentTimeMillis()) }
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

/** Wood in its natural colour, or stained in the shelf's hue: light, mid, dark, deepest. */
private class Wood(val light: Color, val mid: Color, val dark: Color, val deep: Color)

private fun woodOf(hue: Float, night: Boolean): Wood {
    val w = if (hue < 0f) Wood(Color(0xFFC79563), Color(0xFFA9743F), Color(0xFF7C4E27), Color(0xFF4A2C14))
    else Wood(Color.hsl(hue, 0.34f, 0.52f), Color.hsl(hue, 0.36f, 0.40f), Color.hsl(hue, 0.38f, 0.28f), Color.hsl(hue, 0.40f, 0.16f))
    // At night the wood is in lamplight: darker, the same grain
    if (!night) return w
    fun d(c: Color) = lerp(c, Color.Black, 0.38f)
    return Wood(d(w.light), d(w.mid), d(w.dark), d(w.deep))
}

/**
 * Wood grain for one board: long, gently waving fibres, a few lighter ones, now and then a knot.
 * Seeded, so the same board always looks the same; built once per size, then only drawn.
 */
private fun CacheDrawScope.grain(seed: Int, w: Wood, knots: Boolean): DrawScope.() -> Unit {
    val r = kotlin.random.Random(seed)
    val lines = ArrayList<Triple<Path, Color, Float>>()
    var y = r.nextFloat() * 4f
    val step = density * 2.2f
    while (y < size.height) {
        val p = Path()
        val amp = density * (0.4f + r.nextFloat() * 1.6f)
        val wave = size.width * (0.35f + r.nextFloat() * 0.9f)
        val phase = r.nextFloat() * 6.28f
        var x = 0f
        p.moveTo(0f, y + amp * kotlin.math.sin(phase))
        while (x < size.width) { x += 8f * density; p.lineTo(x, y + amp * kotlin.math.sin(phase + x / wave * 6.28f)) }
        val light = r.nextFloat() < 0.18f
        val alpha = if (light) 0.05f + r.nextFloat() * 0.06f else 0.04f + r.nextFloat() * 0.12f
        lines += Triple(p, (if (light) Color(0xFFFFE9CC) else w.deep).copy(alpha = alpha), density * (0.5f + r.nextFloat() * 1.1f))
        y += step * (0.6f + r.nextFloat() * 1.8f)
    }
    val knot = if (knots && r.nextFloat() < 0.45f) Offset(size.width * (0.15f + r.nextFloat() * 0.7f), size.height * (0.3f + r.nextFloat() * 0.5f)) else null
    val base = Brush.verticalGradient(listOf(w.mid, lerp(w.mid, w.light, 0.35f), w.mid))
    return {
        drawRect(base)
        for ((p, c, width) in lines) drawPath(p, c, style = Stroke(width))
        knot?.let { k ->
            for (i in 0 until 5) drawOval(w.deep.copy(alpha = 0.10f + 0.04f * i), topLeft = Offset(k.x - (26 - i * 5) * density, k.y - (7 - i) * density),
                size = Size((52 - i * 10) * density, (14 - i * 2) * density), style = Stroke(density * 0.9f))
            drawOval(w.deep.copy(alpha = 0.35f), topLeft = Offset(k.x - 4 * density, k.y - 2 * density), size = Size(8 * density, 4 * density))
        }
    }
}

/**
 * The wooden bookcase: a back wall of grained boards between two side posts, books standing on planks
 * that are seen a little from above, each casting a shadow on the wall. Empty planks fill the rest of the
 * screen, as in a real bookcase.
 */
@Composable
private fun WoodShelf(entries: List<LibraryEntry>, hue: Float, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit) {
    val night = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val w = remember(hue, night) { woodOf(hue, night) }
    BoxWithConstraints(Modifier.fillMaxSize().background(w.dark)) {
        val perRow = ((maxWidth - 40.dp) / 108.dp).toInt().coerceIn(2, 8)
        val gap = 14.dp
        val bookW = (maxWidth - 40.dp - gap * (perRow - 1)) / perRow
        val rowH = bookW / 0.72f + 30.dp + 22.dp
        val rows = entries.chunked(perRow)
        val count = maxOf(rows.size, (maxHeight / rowH).toInt() + 1)
        LazyColumn(Modifier.fillMaxSize()) {
            items(count) { i -> WoodRow(i, rows.getOrNull(i).orEmpty(), perRow, gap, w, onOpen, menu) }
        }
    }
}

@Composable
private fun WoodRow(index: Int, row: List<LibraryEntry>, perRow: Int, gap: androidx.compose.ui.unit.Dp, w: Wood, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit) {
    val post = 12.dp
    Column(
        Modifier.fillMaxWidth()
            .drawWithCache {
                val wall = grain(index * 7919 + 13, w, knots = true)
                val shade = Brush.verticalGradient(0f to Color(0x99000000), 0.10f to Color(0x40000000), 0.32f to Color.Transparent)
                val p = post.toPx()
                val sideShade = Brush.horizontalGradient(0f to Color(0x66000000), 1f to Color.Transparent, startX = p, endX = p + 18.dp.toPx())
                val sideShadeR = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0x55000000), startX = size.width - p - 18.dp.toPx(), endX = size.width - p)
                val postBrush = Brush.horizontalGradient(listOf(w.dark, w.mid, w.dark))
                onDrawWithContent {
                    // The back wall, a little darker than the planks: it is further from the light
                    wall()
                    drawRect(Color(0x30000000))
                    // Shadow under the plank (or the top of the case) above, and in the inner corners
                    drawRect(shade)
                    drawRect(sideShade); drawRect(sideShadeR)
                    drawContent()
                    // The side posts, in front of everything
                    drawRect(postBrush, size = Size(p, size.height))
                    drawRect(postBrush, topLeft = Offset(size.width - p, 0f), size = Size(p, size.height))
                    drawLine(Color(0x33FFE9CC), Offset(p - 1f, 0f), Offset(p - 1f, size.height), 1.5f)
                    drawLine(Color(0x33FFE9CC), Offset(size.width - p + 1f, 0f), Offset(size.width - p + 1f, size.height), 1.5f)
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = post + 8.dp, end = post + 8.dp, top = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom,
        ) {
            for (e in row) StandingBook(e, onOpen, menu, Modifier.weight(1f))
            repeat(perRow - row.size) { Box(Modifier.weight(1f).aspectRatio(0.72f)) }
        }
        Plank(index, w)
    }
}

/** A plank: its top seen from above (darker at the back, where the books stand), then the lit front edge. */
@Composable
private fun Plank(index: Int, w: Wood) {
    // (at night the edge only glints)
    val edge = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0x55FFEBD2) else Color(0xAAFFEBD2)
    Box(
        Modifier.fillMaxWidth().height(22.dp).drawWithCache {
            val top = 7.dp.toPx()
            val topBrush = Brush.verticalGradient(0f to lerp(w.dark, w.deep, 0.3f), 1f to w.light, endY = top)
            val front = grain(index * 104729 + 7, Wood(w.light, lerp(w.mid, w.light, 0.45f), w.dark, w.deep), knots = false)
            val frontShade = Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0x66000000), startY = top, endY = size.height)
            onDrawBehind {
                drawRect(topBrush, size = Size(size.width, top))
                clipRect(top = top) { front() }
                drawRect(frontShade, topLeft = Offset(0f, top))
                // The sharp edge where top meets front catches the light
                drawLine(edge, Offset(0f, top), Offset(size.width, top), 1.6f * density)
                drawLine(Color(0x88000000), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
            }
        },
    )
}

/** A book on the plank: its shadow falls on the wall behind, to the right and a little down. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StandingBook(e: LibraryEntry, onOpen: (LibraryEntry) -> Unit, menu: @Composable (LibraryEntry, () -> Unit) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 3.dp)
    Box(
        modifier
            .drawBehind {
                // A soft shadow, as from a lamp up and to the left: the book's shape moved right and down,
                // drawn in a few widening, fainter layers (a blur that looks the same on every phone)
                val dx = 5.dp.toPx(); val top = 7.dp.toPx(); val step = 1.6.dp.toPx()
                for (i in 0 until 7) {
                    val g = i * step
                    drawRoundRect(Color.Black.copy(alpha = 0.075f), topLeft = Offset(dx - g * 0.4f, top - g * 0.6f),
                        size = Size(size.width + g, size.height - top + g * 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(g + 2f))
                }
            }
            .clip(shape)
            .combinedClickable(onClick = { onOpen(e) }, onLongClick = { open = true }),
    ) {
        Cover(e, Modifier.fillMaxWidth(), bound = true)
        // The binding: light catches the spine's ridge, the hinge just after it is in shadow; a faint gloss on top
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(Brush.horizontalGradient(0f to Color(0x30000000), 0.015f to Color(0x55FFFFFF), 0.04f to Color(0x10FFFFFF), 0.065f to Color(0x45000000), 0.095f to Color.Transparent, 0.93f to Color.Transparent, 1f to Color(0x30000000)))
            drawRect(Brush.verticalGradient(0f to Color(0x1FFFFFFF), 0.25f to Color.Transparent, 0.85f to Color.Transparent, 1f to Color(0x26000000)))
        })
        if (e.bookmarks.isNotEmpty()) Icon(AppIcons.Bookmark, contentDescription = null, tint = Color(0xFFE5484D), modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).width(16.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu(e) { open = false } }
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

/** The title a document shows on the shelf: the one chosen for its cover, else its name without the extension. */
internal fun shownTitle(e: LibraryEntry): String = e.coverTitle ?: e.title.substringBeforeLast('.', e.title)

/** Whether a picture is its cover: one chosen, or as usual a book's own and a presentation's first slide. */
internal fun usesPicture(e: LibraryEntry): Boolean = when (e.cover) {
    "picture" -> true
    "drawn" -> false
    else -> e.format.uppercase() in setOf("EPUB", "FB2", "PPTX", "PPT", "ODP")
}

@Composable
private fun Cover(e: LibraryEntry, modifier: Modifier, bound: Boolean = false) {
    val context = LocalContext.current
    val picture = usesPicture(e)
    val cover by produceState<ImageBitmap?>(null, e.id, picture, e.cover) {
        value = if (!picture) null else withContext(Dispatchers.IO) {
            ua.hramota.data.Shelf.loadCover(context, e.id)?.asImageBitmap()
        }
    }
    BoxWithConstraints(modifier.aspectRatio(0.72f)) {
        val width = maxWidth
        val c = cover
        val title = shownTitle(e)
        if (c != null) Image(c, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color.White))
        else if (bound && width >= 72.dp) BoundCover(e, title, width)
        else DrawnCover(title, e.format)
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

/** Book cloth, for a cover with no picture: one of a few classic bindings, always the same for a title. */
private val CLOTH = listOf(
    Color(0xFF7A1F1F), Color(0xFF1E3A5F), Color(0xFF2F4F3A), Color(0xFF6B4A1A), Color(0xFF4A2F55),
    Color(0xFF1F4A4F), Color(0xFF5A2A1C), Color(0xFF2B2F3A), Color(0xFF8A5A1E),
)
private val GILT = Color(0xFFE2C27A)

/** A cloth-bound cover: gilt rules, the title in the book face, the format in small letters at the foot. */
@Composable
private fun BoundCover(e: LibraryEntry, title: String, width: androidx.compose.ui.unit.Dp) {
    val cloth = CLOTH[(title.hashCode() and 0x7fffffff) % CLOTH.size]
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { (width - 30.dp).toPx() }
    val fs = remember(title, room) {
        val word = title.split(' ', '-', '_').maxByOrNull { it.length } ?: ""
        var s = 14f
        while (s > 9f && measurer.measure(word, TextStyle(fontSize = s.sp, fontFamily = Literata, fontWeight = FontWeight.Bold)).size.width > room) s -= 0.5f
        s
    }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(cloth, Color.White, 0.10f), cloth, lerp(cloth, Color.Black, 0.25f))))
            .drawBehind {
                // Gilt rules near head and foot, as stamped on cloth
                val inset = 10.dp.toPx()
                for (y in listOf(9.dp.toPx(), 12.dp.toPx(), size.height - 12.dp.toPx(), size.height - 9.dp.toPx()))
                    drawLine(GILT.copy(alpha = 0.75f), Offset(inset, y), Offset(size.width - inset * 0.7f, y), 0.8.dp.toPx())
            }
            .padding(start = 12.dp, end = 8.dp, top = 20.dp, bottom = 18.dp),
    ) {
        Text(title, color = Color(0xFFF6EBD6), fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = fs.sp, lineHeight = (fs + 3).sp,
            maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopStart))
        Text(e.format, color = GILT, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
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
                    if (style == "wood") m.background(woodOf(preview.hue, false).let { Brush.verticalGradient(listOf(it.light, it.mid, it.dark)) }) else m.background(shelfColor(preview).copy(alpha = 0.25f))
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
