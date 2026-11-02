package ua.hramota.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ua.hramota.R
import ua.hramota.data.Note
import ua.hramota.doc.book.Book
import ua.hramota.doc.book.BookBlock

/** Marker colours: yellow, green, blue, pink; drawn lighter on dark paper. */
val NOTE_COLORS = listOf(Color(0xFFF6D84B), Color(0xFF8ED081), Color(0xFF7FB8F0), Color(0xFFF2A0C0))

fun noteColor(index: Int, dark: Boolean): Color = NOTE_COLORS[index.coerceIn(0, NOTE_COLORS.size - 1)].copy(alpha = if (dark) 0.38f else 0.45f)

/** The marked parts of one block: start, end and colour. */
internal fun marksIn(notes: List<Note>, block: Int, length: Int, dark: Boolean): List<Triple<Int, Int, Color>> =
    notes.filter { block in it.block..it.endBlock }.map {
        Triple(if (it.block == block) it.start else 0, if (it.endBlock == block) it.end else length, noteColor(it.color, dark))
    }

/**
 * The menu over selected text in a book: copy, the markers, a note, share, and what other apps on the
 * phone offer for text (a translator or dictionary that works offline, for example). Text reaches
 * another app only when the person taps it there.
 */
internal class BookSelection : TextToolbar {
    var rect by mutableStateOf<Rect?>(null)
    private var copy: (() -> Unit)? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set
    /** Changes to clear the selection after an action. */
    var reset by mutableIntStateOf(0)

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect; copy = onCopyRequested; status = TextToolbarStatus.Shown
    }

    override fun hide() { rect = null; status = TextToolbarStatus.Hidden }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The selected text, handed to [use] instead of the clipboard. */
    fun take(clipboard: TakingClipboard, use: (String) -> Unit) {
        val c = copy
        hide()
        if (c == null) return
        clipboard.taking = use
        c()
        // Never left waiting: a later real copy must reach the clipboard, not this action
        main.postDelayed({ clipboard.taking = null; reset++ }, 600)
    }

    fun copyToClipboard(clipboard: TakingClipboard) {
        clipboard.taking = null
        copy?.invoke(); hide()
        main.postDelayed({ reset++ }, 600)
    }
}

/** The clipboard, except when the book takes the selected text for a marker, note or another app. */
internal class TakingClipboard(private val real: Clipboard) : Clipboard {
    var taking: ((String) -> Unit)? = null
    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val use = taking
        if (use == null) { real.setClipEntry(clipEntry); return }
        taking = null
        val data = clipEntry?.clipData
        use(if (data != null && data.itemCount > 0) data.getItemAt(0).text?.toString().orEmpty() else "")
    }
    override val nativeClipboard get() = real.nativeClipboard
}

/** Apps that offer something for selected text (translate, define…), by label. */
internal fun textApps(context: Context): List<Pair<String, android.content.ComponentName>> = try {
    val pm = context.packageManager
    pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { it.loadLabel(pm).toString() to android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        .distinctBy { it.first }.take(6)
} catch (e: Exception) { emptyList() }

/**
 * Finds selected text among the blocks on screen: the place it starts and ends. The text is matched
 * as shown (no-break spaces count as spaces), which keeps the same length as the book's own text.
 */
internal fun locate(book: Book, parts: List<Visible.Part>, selected: String): Note? {
    // Line ends are left out on both sides: paragraphs may be joined with or without one
    fun norm(c: Char) = if (c == ' ') ' ' else c
    val wanted = selected.filter { it != '\n' && it != '\r' }.map(::norm).joinToString("").trim()
    if (wanted.isEmpty()) return null
    val sb = StringBuilder()
    var blocks = IntArray(1024); var offsets = IntArray(1024); var n = 0
    for (p in parts) {
        val t = (book.blocks.getOrNull(p.block) as? BookBlock.Text)?.text ?: continue
        // Only what is shown: the part of a paragraph on this page
        for (k in p.from.coerceAtLeast(0) until minOf(t.length, p.to)) {
            val c = t[k]
            if (c == '\n' || c == '\r') continue
            if (n == blocks.size) { blocks = blocks.copyOf(n * 2); offsets = offsets.copyOf(n * 2) }
            sb.append(norm(c)); blocks[n] = p.block; offsets[n] = k; n++
        }
    }
    val at = sb.indexOf(wanted).takeIf { it >= 0 } ?: return null
    val last = at + wanted.length - 1
    return Note(blocks[at], offsets[at], blocks[last], offsets[last] + 1, 0, at = System.currentTimeMillis())
}

@Composable
internal fun SelectionMenu(
    selection: BookSelection, clipboard: TakingClipboard, origin: androidx.compose.ui.geometry.Offset, onMark: (String, Int) -> Unit, onNote: (String) -> Unit,
    onDictionary: (String) -> Unit = {},
) {
    val rect = selection.rect ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember { textApps(context) }
    val above = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.roundToPx() }
    // The selection comes in window coordinates; the menu sits just above it (below, if no room)
    val top = (rect.top - origin.y).toInt() - above
    Popup(
        offset = IntOffset((rect.left - origin.x).toInt().coerceAtLeast(0), if (top >= 0) top else (rect.bottom - origin.y).toInt() + 8),
        properties = PopupProperties(focusable = false),
        onDismissRequest = { selection.hide() },
    ) {
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((i, c) in NOTE_COLORS.withIndex()) Box(
                    Modifier.padding(horizontal = 4.dp).size(28.dp).clip(CircleShape).background(c).border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                        .clickable { selection.take(clipboard) { onMark(it, i) } },
                )
                Item(stringResource(R.string.note_add)) { selection.take(clipboard) { onNote(it) } }
                Item(stringResource(R.string.dict_open)) { selection.take(clipboard) { onDictionary(it) } }
                Item(stringResource(R.string.copy)) { selection.copyToClipboard(clipboard) }
                Item(stringResource(R.string.share)) {
                    selection.take(clipboard) { t ->
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.take(100_000)), null)) } catch (_: Exception) {}
                    }
                }
                for ((label, component) in apps) Item(label) {
                    selection.take(clipboard) { t ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT, t.take(5000)).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun Item(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, maxLines = 1) }
}

/** A note: its colour and words; or deleting it. */
@Composable
internal fun NoteDialog(excerpt: String, note: Note, onSave: (Note) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(note.text) }
    var color by remember { mutableIntStateOf(note.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("«${excerpt.take(300)}»", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Literata), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, c) in NOTE_COLORS.withIndex()) Box(
                        Modifier.size(32.dp).clip(CircleShape).background(c)
                            .border(if (i == color) 3.dp else 1.dp, if (i == color) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.15f), CircleShape)
                            .clickable { color = i },
                    )
                }
                OutlinedTextField(text, { text = it.take(ua.hramota.data.Library.MAX_NOTE_TEXT) }, Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text(stringResource(R.string.note_hint)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note.copy(text = text.trim(), color = color)) }) { Text(stringResource(R.string.note_save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.note_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The words a note marks, for lists and export. */
internal fun excerpt(book: Book, n: Note): String {
    val sb = StringBuilder()
    for (b in n.block..minOf(n.endBlock, n.block + 50)) {
        val t = (book.blocks.getOrNull(b) as? BookBlock.Text)?.text ?: continue
        val s = if (b == n.block) n.start.coerceIn(0, t.length) else 0
        val e = if (b == n.endBlock) n.end.coerceIn(s, t.length) else t.length
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(t, s, e)
    }
    return sb.toString()
}

/** All notes of a book as plain text (Markdown), to send or keep elsewhere. */
fun notesText(book: Book, name: String, notes: List<Note>): String = buildString {
    append("# ").append(book.title.ifEmpty { name }).append('\n')
    if (book.author.isNotEmpty()) append(book.author).append('\n')
    for (n in notes) {
        append('\n').append("> ").append(excerpt(book, n).replace("\n", "\n> ")).append('\n')
        if (n.text.isNotEmpty()) append('\n').append(n.text).append('\n')
    }
}
