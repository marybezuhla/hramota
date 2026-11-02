package ua.hramota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import ua.hramota.R
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.docx.edit.EditPara
import ua.hramota.doc.docx.edit.Kind
import ua.hramota.doc.docx.edit.ListKind
import ua.hramota.doc.docx.edit.Look
import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.docx.edit.Shown

/** Before each paragraph's text: its deletion (Backspace at the very start) joins it to the one before. */
private const val ZW = '​'

/**
 * A DOCX being edited: the paragraphs, what was done (to undo and redo), and where the cursor is.
 * Typing within one paragraph is undone in pauses, not letter by letter.
 */
class EditorState(val edit: DocxEdit) {
    var paras by mutableStateOf(edit.paragraphs)
        private set
    var shown: List<Shown> by mutableStateOf(edit.shown(edit.paragraphs))
        private set
    var dirty by mutableStateOf(false)
        private set
    private val undoStack = ArrayList<List<EditPara>>()
    private val redoStack = ArrayList<List<EditPara>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var lastTyped = 0L
    private var lastTypedKey = -1L

    /** The paragraph with the cursor, and the selection in its text. */
    var focused by mutableStateOf<Long?>(null)
    var selection by mutableStateOf(TextRange.Zero)
    /** Formatting chosen with nothing selected: for what is typed next. */
    var pending by mutableStateOf<RunStyle?>(null)
    /** Where the cursor goes next (paragraph, position), after a split or a join. */
    var moveTo by mutableStateOf<Pair<Long, Int>?>(null)

    fun indexOf(key: Long?): Int = if (key == null) -1 else paras.indexOfFirst { it.key == key }

    /** Comments marked in the document now (a comment whose mark was deleted with its text is gone). */
    var comments: List<ua.hramota.doc.docx.edit.Comment> by mutableStateOf(liveComments(edit.paragraphs))
        private set

    private fun liveComments(list: List<EditPara>): List<ua.hramota.doc.docx.edit.Comment> {
        val ids = HashSet<String>()
        for (p in list) for (a in p.anchors) if (a.kind == ua.hramota.doc.docx.edit.Anchor.Kind.REF) ids += a.comment
        return edit.comments().filter { it.id in ids }
    }

    /** A comment on the selection (or at the cursor) of the paragraph with the cursor. */
    fun addComment(text: String, author: String): Boolean {
        val i = indexOf(focused); if (i < 0 || !paras[i].editable || text.isBlank()) return false
        val list = paras.toMutableList()
        list[i] = edit.comment(paras[i], selection.min, selection.max, author.trim(), text.trim())
        commit(list, structure = true)
        return true
    }

    /** Removes a comment; false if part of it is in a paragraph that cannot be changed here. */
    fun removeComment(id: String): Boolean {
        val list = edit.uncomment(paras, id) ?: return false
        commit(list, structure = true)
        return true
    }

    /** Puts the cursor where a comment begins. */
    fun goToComment(id: String) {
        val (place, _) = edit.commentPlace(paras, id) ?: return
        paras.getOrNull(place.first)?.takeIf { it.editable }?.let { moveTo = it.key to place.second }
    }

    private fun commit(list: List<EditPara>, typingIn: Long? = null, structure: Boolean = false) {
        val now = System.currentTimeMillis()
        val group = typingIn != null && typingIn == lastTypedKey && now - lastTyped < 1500
        if (!group) {
            undoStack += paras
            // Each step keeps the paragraph list (shared paragraphs, not copies): fewer steps for long documents
            val cap = (2_000_000 / maxOf(paras.size, 1)).coerceIn(20, 200)
            while (undoStack.size > cap) undoStack.removeAt(0)
        }
        lastTyped = now; lastTypedKey = typingIn ?: -1L
        redoStack.clear()
        paras = list
        if (structure) { shown = edit.shown(list); comments = liveComments(list) }
        dirty = true
        canUndo = undoStack.isNotEmpty(); canRedo = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack += paras
        paras = prev; shown = edit.shown(prev); comments = liveComments(prev)
        lastTypedKey = -1L
        canUndo = undoStack.isNotEmpty(); canRedo = true; dirty = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack += paras
        paras = next; shown = edit.shown(next); comments = liveComments(next)
        lastTypedKey = -1L
        canUndo = true; canRedo = redoStack.isNotEmpty(); dirty = true
    }

    /** New text of paragraph [i] as typed: the formatting follows the text it was typed after. */
    fun typed(key: Long, text: String, cursor: Int) {
        val i = indexOf(key); if (i < 0) return
        val p = paras[i]
        if (text == p.text) return
        val old = p.text
        var pre = 0
        while (pre < old.length && pre < text.length && old[pre] == text[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < text.length - pre && old[old.length - 1 - suf] == text[text.length - 1 - suf]) suf++
        // The change's edges never split a character made of two halves (emoji and the like)
        if (pre > 0 && pre < old.length && old[pre - 1].isHighSurrogate()) pre--
        if (suf > 0 && old.length - suf < old.length && old[old.length - suf].isLowSurrogate() && old.length - suf > pre) suf--
        val inserted = text.length - pre - suf
        val style = pending ?: p.marks.getOrNull(pre - 1) ?: p.marks.getOrNull(pre) ?: p.typing
        val marks = ArrayList<RunStyle>(text.length)
        marks.addAll(p.marks.subList(0, pre)); repeat(inserted) { marks += style }; marks.addAll(p.marks.subList(old.length - suf, old.length))
        var changed = edit.retyped(p, text, marks, pre, old.length - suf)
        val list = paras.toMutableList()
        // Enter (or pasted lines): the paragraph is split at each line end
        val parts = ArrayList<EditPara>()
        while (true) {
            val nl = changed.text.indexOf('\n')
            if (nl < 0) break
            val cut = edit.retyped(changed, changed.text.removeRange(nl, nl + 1), changed.marks.toMutableList().apply { removeAt(nl) }, nl, nl + 1)
            val (a, b) = edit.split(cut, nl)
            parts += a
            changed = b
        }
        parts += changed
        list.removeAt(i); list.addAll(i, parts)
        commit(list, typingIn = if (parts.size == 1) p.key else null, structure = parts.size > 1)
        if (parts.size > 1) {
            val lastNl = text.lastIndexOf('\n', cursor - 1)
            moveTo = parts.last().key to (cursor - lastNl - 1).coerceIn(0, parts.last().text.length)
        }
    }

    /** Backspace at the start of paragraph [i]: joined to the one before, if they can be one. */
    fun joinWithPrevious(key: Long): Boolean {
        val i = indexOf(key)
        if (i <= 0) return false
        val a = paras[i - 1]; val b = paras[i]
        if (!edit.canJoin(a, b)) return false
        val list = paras.toMutableList()
        list[i - 1] = edit.join(a, b); list.removeAt(i)
        commit(list, structure = true)
        moveTo = a.key to a.text.length
        return true
    }

    fun look(i: Int, at: Int): Look {
        val p = paras.getOrNull(i) ?: return Look()
        val m = p.marks.getOrNull(at) ?: p.marks.getOrNull(at - 1) ?: p.typing
        return edit.look(p, m)
    }

    /** The look where the cursor is, or of the whole selection (on only if all of it is). */
    fun currentLook(): Look {
        val i = indexOf(focused); if (i < 0) return Look()
        pending?.let { return edit.look(paras[i], it) }
        val s = selection
        val p = paras[i]
        if (s.collapsed) return look(i, s.start - 1)
        // Each distinct formatting looked at once, however long the selection
        val looks = p.marks.subList(s.min.coerceIn(0, p.marks.size), s.max.coerceIn(0, p.marks.size)).distinct().map { edit.look(p, it) }
        if (looks.isEmpty()) return look(i, s.start)
        return Look(looks.all { it.bold }, looks.all { it.italic }, looks.all { it.underline }, looks.all { it.strike })
    }

    enum class Mark { BOLD, ITALIC, UNDERLINE, STRIKE }

    /** Bold, italic, underline or strike-through turned on for the selection (off if all of it has it). */
    fun toggle(mark: Mark) {
        val i = indexOf(focused); if (i < 0) return
        val p = paras[i]
        if (!p.editable) return
        val now = currentLook()
        val on = when (mark) { Mark.BOLD -> !now.bold; Mark.ITALIC -> !now.italic; Mark.UNDERLINE -> !now.underline; Mark.STRIKE -> !now.strike }
        fun apply(m: RunStyle) = when (mark) {
            Mark.BOLD -> edit.restyle(m, bold = on); Mark.ITALIC -> edit.restyle(m, italic = on)
            Mark.UNDERLINE -> edit.restyle(m, underline = on); Mark.STRIKE -> edit.restyle(m, strike = on)
        }
        val s = selection
        if (s.collapsed) {
            pending = apply(pending ?: p.marks.getOrNull(s.start - 1) ?: p.marks.getOrNull(s.start) ?: p.typing)
            return
        }
        val marks = p.marks.mapIndexed { k, m -> if (k in s.min until s.max) apply(m) else m }
        val list = paras.toMutableList(); list[i] = p.changed(marks = marks)
        commit(list)
    }

    fun setKind(kind: Kind) {
        val i = indexOf(focused); if (i < 0 || !paras[i].editable) return
        val list = paras.toMutableList(); list[i] = edit.withKind(paras[i], kind)
        commit(list, structure = true)
    }

    fun toggleList(kind: ListKind) {
        val i = indexOf(focused); if (i < 0 || !paras[i].editable) return
        val list = paras.toMutableList()
        list[i] = edit.withList(paras[i], if (shown.getOrNull(i)?.list == kind) null else kind, paras.getOrNull(i - 1))
        commit(list, structure = true)
    }
}

/** Shows each run of text with its formatting; the text itself is never changed by this. */
private class Formatting(private val p: EditPara, private val edit: DocxEdit, private val open: Set<String>, private val tint: androidx.compose.ui.graphics.Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        // The field's text is the zero-width mark and then the paragraph's text
        val n = minOf(p.marks.size, text.length - 1)
        var i = 0
        while (i < n) {
            val m = p.marks[i]
            var j = i
            while (j < n && p.marks[j] == m) j++
            val look = edit.look(p, m)
            val deco = listOfNotNull(TextDecoration.Underline.takeIf { look.underline }, TextDecoration.LineThrough.takeIf { look.strike })
            b.addStyle(SpanStyle(
                fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (look.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
            ), i + 1, j + 1)
            i = j
        }
        // Text a comment is about, tinted: ranges open from earlier paragraphs, and those begun here
        val inside = HashSet(open)
        var from = 0
        fun mark(to: Int) { if (inside.isNotEmpty() && to > from) b.addStyle(SpanStyle(background = tint), from + 1, minOf(to, n) + 1) }
        for (a in p.anchors.sortedBy { it.at }) {
            val at = a.at.coerceIn(0, n)
            mark(at); from = at
            when (a.kind) { ua.hramota.doc.docx.edit.Anchor.Kind.START -> inside += a.comment; ua.hramota.doc.docx.edit.Anchor.Kind.END -> inside -= a.comment; else -> {} }
        }
        mark(n)
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is Formatting && other.p === p && other.open == open && other.tint == tint
    override fun hashCode() = System.identityHashCode(p) * 31 + open.hashCode()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxEditorScreen(
    name: String,
    state: EditorState,
    saving: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
    /** The name put on new comments (remembered), and how it is changed. */
    author: String = "",
    onAuthor: (String) -> Unit = {},
    startWithComments: Boolean = false,
) {
    var askClose by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(startWithComments) }
    var locked by remember { mutableStateOf(false) }
    BackHandler { if (state.dirty) askClose = true else onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringBeforeLast('.', name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) askClose = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.edit_close))
                    }
                },
                actions = {
                    if (state.comments.isNotEmpty()) TextButton(onClick = { listing = true }) { Text("💬 ${state.comments.size}") }
                    TextButton(onClick = onSave, enabled = state.dirty && !saving) { Text(stringResource(if (saving) R.string.edit_saving else R.string.edit_save)) }
                },
            )
        },
        bottomBar = { EditToolbar(state, enabled = !saving, onComment = { commenting = true }) },
    ) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            itemsIndexed(state.paras, key = { _, p -> p.key }) { i, p ->
                val shown = state.shown.getOrNull(i) ?: return@itemsIndexed
                Column { EditParagraph(state, i, p, shown, readOnly = saving, onLocked = { locked = true }) }
            }
        }
        // After Enter or a join the cursor's paragraph is brought into view
        LaunchedEffect(state.moveTo) {
            val key = state.moveTo?.first ?: return@LaunchedEffect
            val i = state.indexOf(key)
            if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem(i)
        }
    }
    if (askClose) AlertDialog(
        onDismissRequest = { askClose = false },
        text = { Text(stringResource(R.string.edit_unsaved)) },
        confirmButton = { TextButton(onClick = { askClose = false; onSave() }) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { askClose = false; onClose() }) { Text(stringResource(R.string.edit_discard), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { askClose = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    if (commenting) CommentDialog(author, onDismiss = { commenting = false }) { text, who ->
        commenting = false
        if (who != author) onAuthor(who)
        state.addComment(text, who)
    }
    if (listing) CommentsSheet(state, enabled = !saving, onDismiss = { listing = false })
    if (locked) AlertDialog(
        onDismissRequest = { locked = false },
        text = { Text(stringResource(R.string.edit_locked_paragraph)) },
        confirmButton = { TextButton(onClick = { locked = false }) { Text(stringResource(R.string.ok)) } },
    )
}

private fun textStyleFor(kind: Kind, color: androidx.compose.ui.graphics.Color) = TextStyle(
    color = color,
    fontSize = when (kind) { Kind.H1 -> 24.sp; Kind.H2 -> 21.sp; Kind.H3 -> 19.sp; else -> 17.sp },
    lineHeight = when (kind) { Kind.H1 -> 31.sp; Kind.H2 -> 28.sp; Kind.H3 -> 26.sp; else -> 25.sp },
)

@Composable
private fun EditParagraph(state: EditorState, i: Int, p: EditPara, shown: Shown, readOnly: Boolean, onLocked: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = textStyleFor(shown.kind, if (p.editable) color else color.copy(alpha = 0.6f))
    // Table cells: a row begins with a gap and a line, the cells of a row follow closely
    val top = when {
        shown.rowStart -> 10.dp
        shown.cellStart -> 2.dp
        shown.inTable -> 0.dp
        else -> when (shown.kind) { Kind.H1 -> 18.dp; Kind.H2 -> 14.dp; Kind.H3 -> 10.dp; else -> 4.dp }
    }
    val colors = MaterialTheme.colorScheme
    val cell = if (shown.inTable) Modifier.background(colors.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 3.dp) else Modifier
    if (shown.rowStart) HorizontalDivider(Modifier.padding(top = top), color = colors.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(top = if (shown.rowStart) 2.dp else top, bottom = if (shown.inTable) 0.dp else 4.dp).then(cell)) {
        shown.marker?.let { m -> Text(m, style = style, modifier = Modifier.width(28.dp)) }
        if (!p.editable) {
            Text(
                p.text.ifEmpty { "⋯" }, style = style,
                modifier = Modifier.weight(1f).clickable(onClick = onLocked).semantics { contentDescription = p.text },
            )
            return@Row
        }
        var value by remember(p.key) { mutableStateOf(TextFieldValue(ZW + p.text, TextRange(1))) }
        // The text always comes from the paragraph (undo, splits and joins change it from outside)
        val current = if (value.text == ZW + p.text) value else TextFieldValue(ZW + p.text, TextRange(value.selection.start.coerceIn(1, p.text.length + 1), value.selection.end.coerceIn(1, p.text.length + 1)))
        val focus = remember { FocusRequester() }
        // Set while what the keyboard sends is based on text this field no longer shows (see onValueChange)
        val stale = remember(p.key) { BooleanArray(1) }
        stale[0] = false
        LaunchedEffect(state.moveTo) {
            val (key, at) = state.moveTo ?: return@LaunchedEffect
            if (key != p.key) return@LaunchedEffect
            value = TextFieldValue(ZW + p.text, TextRange(at + 1))
            state.focused = p.key
            state.selection = TextRange(at, at)
            state.moveTo = null
            try { focus.requestFocus() } catch (_: Exception) {}
        }
        BasicTextField(
            value = current,
            onValueChange = { v ->
                // After a split, a join or a refusal the field's own copy of the text is out of date until it is
                // shown again: what the keyboard sends meanwhile is based on the old text and is not applied
                if (stale[0]) return@BasicTextField
                val before = current.text
                var text = v.text
                var sel = v.selection
                if (!text.startsWith(ZW)) {
                    // The change touched the very start, where the hidden mark is: where did it go?
                    var pre = 0
                    while (pre < before.length && pre < text.length && before[pre] == text[pre]) pre++
                    var suf = 0
                    while (suf < before.length - pre && suf < text.length - pre && before[before.length - 1 - suf] == text[text.length - 1 - suf]) suf++
                    val removed = before.length - pre - suf
                    if (removed > 0) {
                        // The mark was deleted: Backspace at the start joins this paragraph to the one before
                        // Joined: this field goes away. Refused: nothing changed, the mark is simply kept
                        if (state.joinWithPrevious(p.key)) stale[0] = true else value = TextFieldValue(ZW + p.text, TextRange(1))
                        return@BasicTextField
                    }
                    // Typed before the mark: the mark goes back to the very start
                    val inserted = text.length - before.length
                    text = ZW + text.substring(0, inserted) + text.substring(inserted + 1)
                    fun shift(x: Int) = if (x <= inserted) x + 1 else x
                    sel = TextRange(shift(sel.start), shift(sel.end))
                }
                val plain = text.substring(1)
                sel = TextRange(sel.start.coerceIn(1, text.length), sel.end.coerceIn(1, text.length))
                val textChanged = plain != p.text
                if (!textChanged && sel != current.selection) state.pending = null
                value = TextFieldValue(text, sel, if (text == v.text) v.composition else null)
                state.selection = TextRange(sel.start - 1, sel.end - 1)
                if (textChanged) {
                    // Enter (a line end) splits the paragraph: this field shows its first part next
                    if ('\n' in plain) stale[0] = true
                    state.typed(p.key, plain, sel.end - 1)
                }
            },
            textStyle = style,
            // Nothing changes while the file is being written
            readOnly = readOnly,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = Formatting(p, state.edit, shown.openComments, CommentTint),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { f ->
                if (f.isFocused && state.focused != p.key) {
                    state.focused = p.key; state.pending = null
                    state.selection = TextRange(current.selection.start - 1, current.selection.end - 1)
                }
            },
        )
    }
}

@Composable
private fun EditToolbar(state: EditorState, enabled: Boolean, onComment: () -> Unit) {
    val i = state.indexOf(state.focused)
    val active = enabled && i >= 0 && state.paras.getOrNull(i)?.editable == true
    val look = if (active) state.currentLook() else Look()
    val shown = state.shown.getOrNull(i)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Tool("↶", stringResource(R.string.edit_undo), enabled = enabled && state.canUndo) { state.undo() }
                Tool("↷", stringResource(R.string.edit_redo), enabled = enabled && state.canRedo) { state.redo() }
                Tool("B", stringResource(R.string.edit_bold), active, look.bold, TextStyle(fontWeight = FontWeight.Bold)) { state.toggle(EditorState.Mark.BOLD) }
                Tool("I", stringResource(R.string.edit_italic), active, look.italic, TextStyle(fontStyle = FontStyle.Italic)) { state.toggle(EditorState.Mark.ITALIC) }
                Tool("U", stringResource(R.string.edit_underline), active, look.underline, TextStyle(textDecoration = TextDecoration.Underline)) { state.toggle(EditorState.Mark.UNDERLINE) }
                Tool("S", stringResource(R.string.edit_strike), active, look.strike, TextStyle(textDecoration = TextDecoration.LineThrough)) { state.toggle(EditorState.Mark.STRIKE) }
                Tool("💬", stringResource(R.string.comment_add), active && state.edit.canComment, onClick = onComment)
                KindMenu(state, enabled = active && state.edit.canStyle, kind = shown?.kind)
                Tool("•", stringResource(R.string.edit_bullets), active, shown?.list == ListKind.BULLET) { state.toggleList(ListKind.BULLET) }
                Tool("1.", stringResource(R.string.edit_numbers), active, shown?.list == ListKind.NUMBER) { state.toggleList(ListKind.NUMBER) }
            }
        }
    }
}

@Composable
internal fun Tool(label: String, description: String, enabled: Boolean = true, on: Boolean = false, style: TextStyle = TextStyle(), onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.padding(2.dp)
            .background(if (on) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style.copy(fontSize = 18.sp, color = if (enabled) (if (on) colors.onSecondaryContainer else colors.onSurface) else colors.onSurface.copy(alpha = 0.38f)))
    }
}

@Composable
private fun KindMenu(state: EditorState, enabled: Boolean, kind: Kind?) {
    var open by remember { mutableStateOf(false) }
    val names = listOf(Kind.NORMAL to R.string.edit_kind_normal, Kind.H1 to R.string.edit_kind_h1, Kind.H2 to R.string.edit_kind_h2, Kind.H3 to R.string.edit_kind_h3)
    Box {
        val current = names.firstOrNull { it.first == kind }?.second ?: R.string.edit_kind_normal
        Tool(stringResource(current) + " ▾", stringResource(R.string.edit_kind), enabled) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((k, label) in names) DropdownMenuItem(
                text = { Text(stringResource(label), style = textStyleFor(k, MaterialTheme.colorScheme.onSurface).copy(fontWeight = if (k == Kind.NORMAL) FontWeight.Normal else FontWeight.Bold)) },
                onClick = { open = false; state.setKind(k) },
            )
        }
    }
}

/** Text a comment is about: a soft yellow that reads in both themes. */
private val CommentTint = androidx.compose.ui.graphics.Color(0x66F2C14E)

/** Writing a comment, with the name it is signed with (empty is allowed: nobody's name goes into the file). */
@Composable
private fun CommentDialog(author: String, onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var who by remember { mutableStateOf(author) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comment_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(text, { text = it.take(20_000) }, label = { Text(stringResource(R.string.comment_text)) },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(who, { who = it.take(100) }, label = { Text(stringResource(R.string.comment_author)) },
                    singleLine = true, supportingText = { Text(stringResource(R.string.comment_author_hint)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, who.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.comment_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The document's comments: who, when, what about; tap one to go to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(state: EditorState, enabled: Boolean, onDismiss: () -> Unit) {
    var refused by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.comments_count, state.comments.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (refused) Text(stringResource(R.string.comment_cannot_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.comments, key = { _, c -> c.id }) { _, c ->
                val quoted = remember(c.id, state.paras) { state.edit.commentPlace(state.paras, c.id)?.second.orEmpty() }
                Column(Modifier.fillMaxWidth().clickable { state.goToComment(c.id); onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf(c.author, commentDate(c.date)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { refused = !state.removeComment(c.id) }, enabled = enabled) {
                            Text(stringResource(R.string.comment_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (quoted.isNotBlank()) Text("«$quoted»", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

/** A comment's date (as Word writes it, in UTC) shown as a local date and time; as is if unreadable. */
internal fun commentDate(iso: String): String = try {
    val t = java.time.Instant.parse(iso)
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withZone(java.time.ZoneId.systemDefault()).format(t)
} catch (e: Exception) { iso.take(16).replace('T', ' ') }
