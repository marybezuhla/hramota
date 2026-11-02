package ua.hramota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.EditSaving
import ua.hramota.R
import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.slides.edit.PptxEdit
import ua.hramota.doc.slides.edit.SlidePara
import java.io.InputStream
import java.io.OutputStream

/** Before each paragraph's text: its deletion (Backspace at the very start) joins it to the one before. */
private const val MARK = '​'

/** A presentation's text being edited: paragraphs of every text box, and what was done, to undo and redo. */
class SlideEditorState(val edit: PptxEdit) {
    var paras by mutableStateOf(edit.paragraphs)
        private set
    var dirty by mutableStateOf(false)
        private set
    private val undoStack = ArrayList<List<SlidePara>>()
    private val redoStack = ArrayList<List<SlidePara>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var lastTyped = 0L
    private var lastKey = -1L
    var focused by mutableStateOf<Long?>(null)
    var selection by mutableStateOf(TextRange.Zero)
    var pending by mutableStateOf<RunStyle?>(null)
    var moveTo by mutableStateOf<Pair<Long, Int>?>(null)

    fun indexOf(key: Long?) = if (key == null) -1 else paras.indexOfFirst { it.key == key }

    private fun commit(list: List<SlidePara>, typingIn: Long? = null) {
        val now = System.currentTimeMillis()
        if (!(typingIn != null && typingIn == lastKey && now - lastTyped < 1500)) {
            undoStack += paras; if (undoStack.size > 200) undoStack.removeAt(0)
        }
        lastTyped = now; lastKey = typingIn ?: -1L
        redoStack.clear(); paras = list; dirty = true
        canUndo = true; canRedo = false
    }

    fun undo() { val p = undoStack.removeLastOrNull() ?: return; redoStack += paras; paras = p; lastKey = -1; canUndo = undoStack.isNotEmpty(); canRedo = true; dirty = true }
    fun redo() { val n = redoStack.removeLastOrNull() ?: return; undoStack += paras; paras = n; lastKey = -1; canUndo = true; canRedo = redoStack.isNotEmpty(); dirty = true }

    /** New text of a paragraph; Enter splits it within its text box. */
    fun typed(key: Long, text: String, cursor: Int) {
        val i = indexOf(key); if (i < 0) return
        val p = paras[i]
        if (text == p.text) return
        val old = p.text
        var pre = 0
        while (pre < old.length && pre < text.length && old[pre] == text[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < text.length - pre && old[old.length - 1 - suf] == text[text.length - 1 - suf]) suf++
        if (pre > 0 && pre < old.length && old[pre - 1].isHighSurrogate()) pre--
        if (suf > 0 && old.length - suf > pre && old[old.length - suf].isLowSurrogate()) suf--
        val style = pending ?: p.marks.getOrNull(pre - 1) ?: p.marks.getOrNull(pre) ?: p.typing
        val marks = ArrayList<RunStyle>(text.length)
        marks.addAll(p.marks.subList(0, pre)); repeat(text.length - pre - suf) { marks += style }; marks.addAll(p.marks.subList(old.length - suf, old.length))
        var cur = p.changed(text = text, marks = marks)
        val parts = ArrayList<SlidePara>()
        while (true) {
            val nl = cur.text.indexOf('\n'); if (nl < 0) break
            val cut = cur.changed(text = cur.text.removeRange(nl, nl + 1), marks = cur.marks.toMutableList().apply { removeAt(nl) })
            val (a, b) = edit.split(cut, nl)
            parts += a; cur = b
        }
        parts += cur
        val list = paras.toMutableList(); list.removeAt(i); list.addAll(i, parts)
        commit(list, typingIn = if (parts.size == 1) p.key else null)
        if (parts.size > 1) moveTo = parts.last().key to (cursor - text.lastIndexOf('\n', cursor - 1) - 1).coerceIn(0, parts.last().text.length)
    }

    fun joinWithPrevious(key: Long): Boolean {
        val i = indexOf(key); if (i <= 0) return false
        val a = paras[i - 1]; val b = paras[i]
        if (!edit.canJoin(a, b)) return false
        val list = paras.toMutableList(); list[i - 1] = edit.join(a, b); list.removeAt(i)
        commit(list)
        moveTo = a.key to a.text.length
        return true
    }

    enum class Mark { BOLD, ITALIC, UNDERLINE, STRIKE }

    private fun on(m: RunStyle, mark: Mark) = when (mark) { Mark.BOLD -> edit.bold(m); Mark.ITALIC -> edit.italic(m); Mark.UNDERLINE -> edit.underline(m); Mark.STRIKE -> edit.strike(m) }

    /** Whether the selection (or the place typed at) has [mark]. */
    fun has(mark: Mark): Boolean {
        val i = indexOf(focused); if (i < 0) return false
        val p = paras[i]
        pending?.let { return on(it, mark) }
        val s = selection
        if (s.collapsed) return on(p.marks.getOrNull(s.start - 1) ?: p.marks.getOrNull(s.start) ?: p.typing, mark)
        val ms = p.marks.subList(s.min.coerceIn(0, p.marks.size), s.max.coerceIn(0, p.marks.size)).distinct()
        return ms.isNotEmpty() && ms.all { on(it, mark) }
    }

    fun toggle(mark: Mark) {
        val i = indexOf(focused); if (i < 0 || !paras[i].editable) return
        val p = paras[i]
        val now = !has(mark)
        fun apply(m: RunStyle) = when (mark) {
            Mark.BOLD -> edit.restyle(m, bold = now); Mark.ITALIC -> edit.restyle(m, italic = now)
            Mark.UNDERLINE -> edit.restyle(m, underline = now); Mark.STRIKE -> edit.restyle(m, strike = now)
        }
        val s = selection
        if (s.collapsed) { pending = apply(pending ?: p.marks.getOrNull(s.start - 1) ?: p.marks.getOrNull(s.start) ?: p.typing); return }
        val list = paras.toMutableList()
        list[i] = p.changed(marks = p.marks.mapIndexed { k, m -> if (k in s.min until s.max) apply(m) else m })
        commit(list)
    }

    /** What saving needs: the paragraphs as they are now, written into the presentation read again. */
    fun maker(): EditSaving.Maker {
        val list = paras
        return object : EditSaving.Maker {
            override val ext = "pptx"
            override fun write(source: InputStream, out: OutputStream) = edit.save(list, source, out)
            override fun check(made: InputStream) { ua.hramota.doc.slides.PptxReader.read(made, withMedia = false) }
        }
    }
}

private class SlideFormatting(private val p: SlidePara, private val edit: PptxEdit) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        val n = minOf(p.marks.size, text.length - 1)
        var i = 0
        while (i < n) {
            val m = p.marks[i]
            var j = i
            while (j < n && p.marks[j] == m) j++
            val deco = listOfNotNull(TextDecoration.Underline.takeIf { edit.underline(m) }, TextDecoration.LineThrough.takeIf { edit.strike(m) })
            b.addStyle(SpanStyle(fontWeight = if (edit.bold(m)) FontWeight.Bold else null, fontStyle = if (edit.italic(m)) FontStyle.Italic else null,
                textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco)), i + 1, j + 1)
            i = j
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is SlideFormatting && other.p === p
    override fun hashCode() = System.identityHashCode(p)
}

/**
 * Editing a presentation's text, slide by slide: each slide's picture (as it was opened) and its text
 * boxes below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideEditorScreen(
    name: String,
    state: SlideEditorState,
    saving: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
    /** A slide's picture at a width in pixels (the presentation as opened), or null. */
    thumbnail: (suspend (Int, Int) -> ImageBitmap?)? = null,
    aspect: Float = 16f / 9f,
) {
    var askClose by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    BackHandler { if (state.dirty) askClose = true else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringBeforeLast('.', name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { if (state.dirty) askClose = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_close)) } },
                actions = { TextButton(onClick = onSave, enabled = state.dirty && !saving) { Text(stringResource(if (saving) R.string.edit_saving else R.string.edit_save)) } },
            )
        },
        bottomBar = {
            val i = state.indexOf(state.focused)
            val active = !saving && i >= 0 && state.paras.getOrNull(i)?.editable == true
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider()
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Tool("↶", stringResource(R.string.edit_undo), enabled = !saving && state.canUndo) { state.undo() }
                        Tool("↷", stringResource(R.string.edit_redo), enabled = !saving && state.canRedo) { state.redo() }
                        Tool("B", stringResource(R.string.edit_bold), active, active && state.has(SlideEditorState.Mark.BOLD), TextStyle(fontWeight = FontWeight.Bold)) { state.toggle(SlideEditorState.Mark.BOLD) }
                        Tool("I", stringResource(R.string.edit_italic), active, active && state.has(SlideEditorState.Mark.ITALIC), TextStyle(fontStyle = FontStyle.Italic)) { state.toggle(SlideEditorState.Mark.ITALIC) }
                        Tool("U", stringResource(R.string.edit_underline), active, active && state.has(SlideEditorState.Mark.UNDERLINE), TextStyle(textDecoration = TextDecoration.Underline)) { state.toggle(SlideEditorState.Mark.UNDERLINE) }
                        Tool("S", stringResource(R.string.edit_strike), active, active && state.has(SlideEditorState.Mark.STRIKE), TextStyle(textDecoration = TextDecoration.LineThrough)) { state.toggle(SlideEditorState.Mark.STRIKE) }
                    }
                }
            }
        },
    ) { padding ->
        val list = state.paras
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            itemsIndexed(list, key = { _, p -> p.key }) { i, p ->
                val box = state.edit.boxes[p.box]
                Column {
                    val firstOfSlide = i == 0 || state.edit.boxes[list[i - 1].box].slide != box.slide
                    if (firstOfSlide) {
                        Text(stringResource(R.string.slide_n, box.slide + 1), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 20.dp, bottom = 6.dp))
                        if (thumbnail != null) {
                            val img by produceState<ImageBitmap?>(null, box.slide) { value = thumbnail(box.slide, 720) }
                            img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(aspect).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) }
                        }
                    }
                    val firstOfBox = i == 0 || list[i - 1].box != p.box
                    if (firstOfBox) Text(
                        stringResource(when (box.kind) { "title" -> R.string.slide_box_title; "subtitle" -> R.string.slide_box_subtitle; "table" -> R.string.slide_box_table; else -> R.string.slide_box_text }),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    SlideParagraph(state, p, title = box.kind == "title", readOnly = saving, onLocked = { locked = true })
                }
            }
        }
    }
    if (askClose) AlertDialog(
        onDismissRequest = { askClose = false },
        text = { Text(stringResource(R.string.edit_unsaved)) },
        confirmButton = { TextButton(onClick = { askClose = false; onSave() }) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = { Row { TextButton(onClick = { askClose = false; onClose() }) { Text(stringResource(R.string.edit_discard), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { askClose = false }) { Text(stringResource(R.string.cancel)) } } },
    )
    if (locked) AlertDialog(onDismissRequest = { locked = false }, text = { Text(stringResource(R.string.slide_locked_paragraph)) },
        confirmButton = { TextButton(onClick = { locked = false }) { Text(stringResource(R.string.ok)) } })
}

@Composable
private fun SlideParagraph(state: SlideEditorState, p: SlidePara, title: Boolean, readOnly: Boolean, onLocked: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = TextStyle(color = if (p.editable) colors.onSurface else colors.onSurface.copy(alpha = 0.6f), fontSize = if (title) 21.sp else 17.sp, lineHeight = if (title) 28.sp else 25.sp,
        fontWeight = if (title) FontWeight.SemiBold else null)
    val boxModifier = Modifier.fillMaxWidth().background(colors.surfaceVariant.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp)
    if (!p.editable) {
        Text(p.text.ifEmpty { "⋯" }, style = style, modifier = boxModifier.clickable(onClick = onLocked))
        return
    }
    var value by remember(p.key) { mutableStateOf(TextFieldValue(MARK + p.text, TextRange(1))) }
    val current = if (value.text == MARK + p.text) value else TextFieldValue(MARK + p.text, TextRange(value.selection.start.coerceIn(1, p.text.length + 1), value.selection.end.coerceIn(1, p.text.length + 1)))
    val focus = remember { FocusRequester() }
    val stale = remember(p.key) { BooleanArray(1) }
    stale[0] = false
    LaunchedEffect(state.moveTo) {
        val (key, at) = state.moveTo ?: return@LaunchedEffect
        if (key != p.key) return@LaunchedEffect
        value = TextFieldValue(MARK + p.text, TextRange(at + 1))
        state.focused = p.key; state.selection = TextRange(at, at); state.moveTo = null
        try { focus.requestFocus() } catch (_: Exception) {}
    }
    BasicTextField(
        value = current,
        onValueChange = { v ->
            if (stale[0]) return@BasicTextField
            val before = current.text
            var text = v.text
            var sel = v.selection
            if (!text.startsWith(MARK)) {
                var pre = 0
                while (pre < before.length && pre < text.length && before[pre] == text[pre]) pre++
                var suf = 0
                while (suf < before.length - pre && suf < text.length - pre && before[before.length - 1 - suf] == text[text.length - 1 - suf]) suf++
                if (before.length - pre - suf > 0) {
                    if (state.joinWithPrevious(p.key)) stale[0] = true else value = TextFieldValue(MARK + p.text, TextRange(1))
                    return@BasicTextField
                }
                val inserted = text.length - before.length
                text = MARK + text.substring(0, inserted) + text.substring(inserted + 1)
                fun shift(x: Int) = if (x <= inserted) x + 1 else x
                sel = TextRange(shift(sel.start), shift(sel.end))
            }
            val plain = text.substring(1)
            sel = TextRange(sel.start.coerceIn(1, text.length), sel.end.coerceIn(1, text.length))
            val changed = plain != p.text
            if (!changed && sel != current.selection) state.pending = null
            value = TextFieldValue(text, sel, if (text == v.text) v.composition else null)
            state.selection = TextRange(sel.start - 1, sel.end - 1)
            if (changed) { if ('\n' in plain) stale[0] = true; state.typed(p.key, plain, sel.end - 1) }
        },
        textStyle = style,
        readOnly = readOnly,
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = SlideFormatting(p, state.edit),
        modifier = boxModifier.focusRequester(focus).onFocusChanged { f ->
            if (f.isFocused && state.focused != p.key) { state.focused = p.key; state.pending = null; state.selection = TextRange(current.selection.start - 1, current.selection.end - 1) }
        },
    )
}
