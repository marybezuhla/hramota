package ua.hramota.notebook

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.ink.InkCanvas
import ua.hramota.ink.InkState
import ua.hramota.ink.InkTool
import ua.hramota.ink.drawStroke
import ua.hramota.ui.Tool

/** Before each paragraph's text: deleting it (Backspace at the very start) joins the paragraph to the one before. */
private const val ZW = '​'

/** Ink colours to choose from: dark blue, black, red, green, violet; and marker colours. */
val PEN_COLORS = intArrayOf(0xFF1B2A4A.toInt(), 0xFF111111.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())
val MARKER_COLORS = intArrayOf(0xFFFFD600.toInt(), 0xFF76FF03.toInt(), 0xFF40C4FF.toInt(), 0xFFFF80AB.toInt())

/** The lines, squares or dots of the paper, drawn behind handwriting. */
fun DrawScope.paper(kind: String, density: Float, line: Color) {
    val step = 28f * density
    when (kind) {
        "lines" -> { var y = step; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), density); y += step } }
        "grid" -> {
            val g = step * 0.75f
            var y = g; while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), density * 0.7f); y += g }
            var x = g; while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), density * 0.7f); x += g }
        }
        "dots" -> {
            val g = step * 0.75f
            var y = g; while (y < size.height) { var x = g; while (x < size.width) { drawCircle(line, density * 1.3f, Offset(x, y)); x += g }; y += g }
        }
    }
}

@Composable
fun NoteEditor(state: NoteState, modifier: Modifier = Modifier, onPickPicture: () -> Unit) {
    val list = rememberLazyListState()
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp)) {
            // The title, as a heading above the text
            item(key = "title") {
                BasicTextField(
                    state.meta.title, { t -> state.metaChanged(state.meta.copy(title = t.replace("\n", " "))) },
                    textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box { if (state.meta.title.isEmpty()) Text(stringResource(R.string.note_title_hint), fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)); inner() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
            val numbers = numbering(state.blocks)
            itemsIndexed(state.blocks, key = { _, b -> b.id }) { i, b ->
                when (b) {
                    is NBlock.Text -> TextBlock(state, b, numbers[i])
                    is NBlock.Ink -> InkBlock(state, b)
                    is NBlock.Picture -> PictureBlock(state, b)
                }
            }
        }
        Toolbar(state, onPickPicture)
    }
}

/** The number of each item of a numbered list (0 elsewhere). */
private fun numbering(blocks: List<NBlock>): IntArray {
    val out = IntArray(blocks.size)
    var n = 0
    for ((i, b) in blocks.withIndex()) { n = if (b is NBlock.Text && b.style == "number") n + 1 else 0; out[i] = n }
    return out
}

private fun styleFor(style: String, color: Color) = TextStyle(
    color = color,
    fontSize = when (style) { "h1" -> 23.sp; "h2" -> 20.sp; "h3" -> 18.sp; else -> 17.sp },
    lineHeight = when (style) { "h1" -> 30.sp; "h2" -> 27.sp; "h3" -> 25.sp; else -> 25.sp },
    fontWeight = if (style in setOf("h1", "h2", "h3")) FontWeight.SemiBold else null,
    fontStyle = if (style == "quote") FontStyle.Italic else null,
)

/** The marks of a paragraph shown on its text (the hidden mark at the start included). */
private class Marks(val spans: List<Span>, val highlight: Color, val done: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        val n = text.text.length
        if (done) b.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), 1, n)
        for (s in spans) {
            val st = (s.start + 1).coerceIn(1, n); val en = (s.end + 1).coerceIn(st, n)
            if (st == en) continue
            b.addStyle(when (s.kind) {
                "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                "hl" -> SpanStyle(background = highlight)
                else -> continue
            }, st, en)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is Marks && other.spans == spans && other.done == done
    override fun hashCode() = spans.hashCode() * 31 + done.hashCode()
}

@Composable
private fun TextBlock(state: NoteState, b: NBlock.Text, number: Int) {
    val colors = MaterialTheme.colorScheme
    val done = b.style == "check" && b.checked
    val style = styleFor(b.style, if (done) colors.onSurfaceVariant else if (b.style == "quote") colors.onSurfaceVariant else colors.onSurface)
    val top = when (b.style) { "h1" -> 14.dp; "h2" -> 10.dp; "h3" -> 8.dp; "bullet", "number", "check" -> 0.dp; else -> 4.dp }
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = 2.dp), verticalAlignment = Alignment.Top) {
        when (b.style) {
            "bullet" -> Text("•", style = style, modifier = Modifier.width(24.dp))
            "number" -> Text("$number.", style = style, modifier = Modifier.width(30.dp))
            "check" -> Checkbox(b.checked, { state.toggleChecked(b.id) }, modifier = Modifier.size(32.dp).padding(end = 6.dp))
            "quote" -> Box(Modifier.padding(end = 10.dp).width(3.dp).height(24.dp).background(colors.primary.copy(alpha = 0.5f)))
        }
        var value by remember(b.id) { mutableStateOf(TextFieldValue(ZW + b.text, TextRange(1))) }
        val current = if (value.text == ZW + b.text) value else TextFieldValue(ZW + b.text, TextRange(value.selection.start.coerceIn(1, b.text.length + 1), value.selection.end.coerceIn(1, b.text.length + 1)))
        val focus = remember { FocusRequester() }
        // Set while what the keyboard sends is based on text this field no longer shows
        val stale = remember(b.id) { BooleanArray(1) }
        stale[0] = false
        LaunchedEffect(state.moveTo) {
            val (key, at) = state.moveTo ?: return@LaunchedEffect
            if (key != b.id) return@LaunchedEffect
            val p = at.coerceIn(0, b.text.length)
            value = TextFieldValue(ZW + b.text, TextRange(p + 1))
            state.focused = b.id; state.selection = TextRange(p); state.moveTo = null
            try { focus.requestFocus() } catch (_: Exception) {}
        }
        BasicTextField(
            value = current,
            onValueChange = { v ->
                if (stale[0]) return@BasicTextField
                if (!v.text.startsWith(ZW)) {
                    val before = current.text
                    val text = v.text
                    var pre = 0
                    while (pre < before.length && pre < text.length && before[pre] == text[pre]) pre++
                    var suf = 0
                    while (suf < before.length - pre && suf < text.length - pre && before[before.length - 1 - suf] == text[text.length - 1 - suf]) suf++
                    // Only the hidden mark deleted (and nothing typed): Backspace at the very start
                    if (before.length - pre - suf == 1 && text.length == before.length - 1 && pre == 0) {
                        // The hidden mark was deleted: Backspace at the very start
                        if (state.backspaceAtStart(b.id)) stale[0] = true else value = TextFieldValue(ZW + b.text, TextRange(1))
                        return@BasicTextField
                    }
                    // Typed before the mark (or the whole text replaced): the mark goes back to the start
                    val t = v.text.replace(ZW.toString(), "")
                    value = TextFieldValue(ZW + t, TextRange((v.selection.end + 1).coerceIn(1, t.length + 1)))
                    state.selection = TextRange(value.selection.end - 1)
                    if ('\n' in t || '\r' in t) stale[0] = true
                    state.typed(b.id, t, value.selection.end - 1)
                    return@BasicTextField
                }
                val plain = v.text.substring(1)
                val sel = TextRange(v.selection.start.coerceIn(1, v.text.length), v.selection.end.coerceIn(1, v.text.length))
                value = TextFieldValue(v.text, sel, v.composition)
                state.selection = TextRange(sel.start - 1, sel.end - 1)
                if (plain != b.text) {
                    if ('\n' in plain || '\r' in plain) stale[0] = true
                    state.typed(b.id, plain, sel.end - 1)
                }
            },
            textStyle = style,
            readOnly = state.pen,
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = Marks(b.spans, Color(NoteExport.HIGHLIGHT).copy(alpha = 0.55f), done),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { f ->
                if (f.isFocused && state.focused != b.id) { state.focused = b.id; state.chosen = null; state.selection = TextRange(current.selection.start - 1, current.selection.end - 1) }
            },
        )
    }
}

@Composable
private fun InkBlock(state: NoteState, b: NBlock.Ink) {
    val density = LocalDensity.current.density
    val colors = MaterialTheme.colorScheme
    val ink = remember(b.id) { InkState() }
    ink.show(b.strokes)
    ink.tool = state.tool; ink.color = state.color; ink.width = state.width
    ink.stylusSeen = state.stylusSeen
    ink.onChange = { if (ink.stylusSeen) state.stylusSeen = true; state.setInk(b.id, ink.strokes.toList()) }
    val chosen = state.chosen == b.id
    val line = colors.outlineVariant.copy(alpha = 0.7f)
    val paperKind = state.meta.paper
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(b.height.dp).clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.25f))
                .border(1.dp, if (chosen) colors.primary else colors.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .then(if (!state.pen) Modifier.clickable { state.chosen = if (chosen) null else b.id } else Modifier),
        ) {
            if (state.pen) InkCanvas(ink, Modifier.fillMaxSize()) { paper(paperKind, density, line) }
            else Canvas(Modifier.fillMaxSize()) { paper(paperKind, density, line); for (s in b.strokes) drawStroke(s, density) }
        }
        if (chosen || state.pen) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.grow(b.id, 240f) }) { Text(stringResource(R.string.note_more_paper)) }
            if (b.recognized.isNotBlank()) TextButton(onClick = { state.inkToText(b.id) }) { Text(stringResource(R.string.note_ink_to_text)) }
            if (!state.pen) TextButton(onClick = { state.remove(b.id) }) { Text(stringResource(R.string.note_remove), color = colors.error) }
        }
        if (b.recognized.isNotBlank() && chosen) Text(
            stringResource(R.string.note_ink_read, b.recognized.replace('\n', ' ')),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PictureBlock(state: NoteState, b: NBlock.Picture) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, b.file) {
        value = withContext(Dispatchers.IO) {
            val bytes = Notebook.picture(context, b.file) ?: return@withContext null
            var s = 1
            while (b.w / (s * 2) >= 1600) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }
    }
    val chosen = state.chosen == b.id
    var alt by remember { mutableStateOf<String?>(null) }
    alt?.let { a ->
        AlertDialog(
            onDismissRequest = { alt = null },
            title = { Text(stringResource(R.string.note_alt)) },
            text = { OutlinedTextField(a, { alt = it }, label = { Text(stringResource(R.string.note_alt_hint)) }) },
            confirmButton = { TextButton(onClick = { state.setAlt(b.id, a.trim()); alt = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { alt = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val ratio = if (b.w > 0 && b.h > 0) b.w.toFloat() / b.h else 1.5f
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(if (chosen) 2.dp else 0.dp, if (chosen) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { state.chosen = if (chosen) null else b.id }) {
            bitmap?.let { Image(it, contentDescription = b.alt.ifBlank { null }, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                ?: Box(Modifier.fillMaxWidth().height((300 / ratio).coerceIn(80f, 400f).dp).background(colors.surfaceVariant))
        }
        if (chosen) Row {
            TextButton(onClick = { alt = b.alt }) { Text(stringResource(R.string.note_alt)) }
            TextButton(onClick = { state.remove(b.id) }) { Text(stringResource(R.string.note_remove), color = colors.error) }
        }
    }
}

@Composable
private fun Toolbar(state: NoteState, onPickPicture: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var styles by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(if (state.pen) "Aa" else "✎", stringResource(if (state.pen) R.string.note_mode_text else R.string.note_mode_pen), on = state.pen) {
                    state.pen = !state.pen
                    // A place to write: the handwriting with the cursor, or new paper after it
                    if (state.pen && state.blocks.none { it is NBlock.Ink }) state.insert(NBlock.Ink(state.newId(), emptyList()))
                }
                Tool("↶", stringResource(R.string.edit_undo), enabled = state.canUndo) { state.undo() }
                Tool("↷", stringResource(R.string.edit_redo), enabled = state.canRedo) { state.redo() }
                if (state.pen) {
                    Tool("✒", stringResource(R.string.note_pen), on = state.tool == InkTool.PEN) { state.tool = InkTool.PEN; if (state.color in MARKER_COLORS) state.color = PEN_COLORS[0] }
                    Tool("🖍", stringResource(R.string.note_marker), on = state.tool == InkTool.MARKER) { state.tool = InkTool.MARKER; if (state.color !in MARKER_COLORS) state.color = MARKER_COLORS[0] }
                    Tool("⌫", stringResource(R.string.note_eraser), on = state.tool == InkTool.ERASER) { state.tool = InkTool.ERASER }
                    for (c in if (state.tool == InkTool.MARKER) MARKER_COLORS else PEN_COLORS) Box(
                        Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (state.color == c) 3.dp else 1.dp, if (state.color == c) colors.primary else colors.outlineVariant, CircleShape)
                            .clickable { state.color = c; if (state.tool == InkTool.ERASER) state.tool = InkTool.PEN },
                    )
                    for ((w, label) in listOf(1.4f to "·", 2.2f to "•", 3.6f to "●")) Tool(label, stringResource(R.string.note_width), on = state.width == w) { state.width = w }
                    Tool("+", stringResource(R.string.note_add_paper)) { state.insert(NBlock.Ink(state.newId(), emptyList())) }
                } else {
                    val enabled = state.focused != null && state.indexOf(state.focused) >= 0 && state.blocks[state.indexOf(state.focused)] is NBlock.Text
                    Box {
                        Tool("¶", stringResource(R.string.note_styles), enabled = enabled) { styles = true }
                        androidx.compose.material3.DropdownMenu(styles, onDismissRequest = { styles = false }) {
                            for ((key, label) in listOf("body" to R.string.note_style_body, "h1" to R.string.note_style_h1, "h2" to R.string.note_style_h2, "h3" to R.string.note_style_h3, "quote" to R.string.note_style_quote)) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { styles = false; state.setStyle(key) })
                            }
                        }
                    }
                    Tool("☑", stringResource(R.string.note_checklist), enabled, (state.blocks.getOrNull(state.indexOf(state.focused)) as? NBlock.Text)?.style == "check", toggles = true) { state.setStyle("check") }
                    Tool("•", stringResource(R.string.edit_bullets), enabled, (state.blocks.getOrNull(state.indexOf(state.focused)) as? NBlock.Text)?.style == "bullet", toggles = true) { state.setStyle("bullet") }
                    Tool("1.", stringResource(R.string.edit_numbers), enabled, (state.blocks.getOrNull(state.indexOf(state.focused)) as? NBlock.Text)?.style == "number", toggles = true) { state.setStyle("number") }
                    Tool("B", stringResource(R.string.edit_bold), enabled, state.hasMark("b"), TextStyle(fontWeight = FontWeight.Bold), toggles = true) { state.toggleMark("b") }
                    Tool("I", stringResource(R.string.edit_italic), enabled, state.hasMark("i"), TextStyle(fontStyle = FontStyle.Italic), toggles = true) { state.toggleMark("i") }
                    Tool("U", stringResource(R.string.edit_underline), enabled, state.hasMark("u"), TextStyle(textDecoration = TextDecoration.Underline), toggles = true) { state.toggleMark("u") }
                    Tool("S", stringResource(R.string.edit_strike), enabled, state.hasMark("s"), TextStyle(textDecoration = TextDecoration.LineThrough), toggles = true) { state.toggleMark("s") }
                    Tool("🖍", stringResource(R.string.note_highlight), enabled, state.hasMark("hl"), toggles = true) { state.toggleMark("hl") }
                    Tool("✍", stringResource(R.string.note_add_paper)) { state.insert(NBlock.Ink(state.newId(), emptyList())); state.pen = true }
                    Tool("🖼", stringResource(R.string.note_add_picture)) { onPickPicture() }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
