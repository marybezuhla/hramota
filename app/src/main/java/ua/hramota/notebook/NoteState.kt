package ua.hramota.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import ua.hramota.ink.INK
import ua.hramota.ink.InkTool
import ua.hramota.ink.Stroke

/** Spans moved along with an edit of [old] into [new] (found by what both share at the start and the end). */
fun adjustSpans(spans: List<Span>, old: String, new: String): List<Span> {
    if (spans.isEmpty() || old == new) return spans
    var p = 0
    while (p < old.length && p < new.length && old[p] == new[p]) p++
    var s = 0
    while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val removedEnd = old.length - s
    val inserted = new.length - p - s
    fun map(x: Int, end: Boolean, start: Int): Int = when {
        // Typing right after a bold word goes on in bold
        x == p && end && x > start && removedEnd == p -> x + inserted
        x <= p -> x
        x >= removedEnd -> x - (removedEnd - p) + inserted
        else -> if (end) p + inserted else p
    }
    return spans.map { Span(map(it.start, false, it.start), map(it.end, true, it.start), it.kind) }.filter { it.end > it.start }
}

/** [spans] with [kind] on [start]..[end], or off there if all of it already has it. */
fun toggleSpan(spans: List<Span>, start: Int, end: Int, kind: String): List<Span> {
    if (end <= start) return spans
    val same = spans.filter { it.kind == kind }
    val covered = (start until end).all { i -> same.any { it.start <= i && it.end > i } }
    val others = spans.filter { it.kind != kind }
    val cut = same.flatMap { sp ->
        if (sp.end <= start || sp.start >= end) listOf(sp)
        else listOfNotNull(Span(sp.start, start, kind).takeIf { start > sp.start }, Span(end, sp.end, kind).takeIf { sp.end > end })
    }
    val result = if (covered) cut else cut + Span(start, end, kind)
    // Pieces of one look that touch are one piece
    val merged = result.sortedBy { it.start }.fold(ArrayList<Span>()) { acc, sp ->
        val last = acc.lastOrNull()
        if (last != null && sp.start <= last.end) acc[acc.size - 1] = Span(last.start, maxOf(last.end, sp.end), kind) else acc += sp
        acc
    }
    return others + merged
}

/**
 * A note being edited: its blocks, what was done (to undo and redo), the pen and where the cursor is.
 * Typing in one paragraph is undone in pauses, not letter by letter.
 */
class NoteState(meta: NoteMeta, blocks: List<NBlock>) {
    var meta by mutableStateOf(meta)
    var blocks by mutableStateOf(blocks.ifEmpty { listOf(NBlock.Text(1, "")) })
        private set
    /** Grows with each change: saving follows it. */
    var version by mutableIntStateOf(0)
        private set
    private val undoStack = ArrayList<List<NBlock>>()
    private val redoStack = ArrayList<List<NBlock>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var lastKey = -1L
    private var lastAt = 0L

    /** Writing with the pen (else typing). */
    var pen by mutableStateOf(false)
    var tool by mutableStateOf(InkTool.PEN)
    var color by mutableIntStateOf(INK)
    var width by mutableStateOf(2.2f)
    /** A stylus has written in this note: fingers then scroll instead of writing. */
    var stylusSeen by mutableStateOf(false)

    var focused by mutableStateOf<Long?>(null)
    var selection by mutableStateOf(TextRange.Zero)
    /** Where the cursor goes next (block, position), after a split or a join. */
    var moveTo by mutableStateOf<Pair<Long, Int>?>(null)
    /** An ink or picture block tapped: its actions are shown. */
    var chosen by mutableStateOf<Long?>(null)

    private var nextId = (blocks.maxOfOrNull { it.id } ?: 0L) + 1
    fun newId() = nextId++

    /** Replaces the blocks; [typingIn]: typing in that block (merged with typing just before into one undo step). */
    fun set(list: List<NBlock>, typingIn: Long? = null) {
        val now = System.currentTimeMillis()
        val merge = typingIn != null && typingIn == lastKey && now - lastAt < 1500
        if (!merge) { undoStack += blocks; if (undoStack.size > 200) undoStack.removeAt(0) }
        lastKey = typingIn ?: -1L; lastAt = now
        redoStack.clear()
        blocks = list
        canUndo = undoStack.isNotEmpty(); canRedo = false
        version++
    }

    /** A change that is not undone (the text read from handwriting). */
    fun quietly(list: List<NBlock>) { blocks = list; version++ }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack += blocks
        blocks = undoStack.removeAt(undoStack.size - 1)
        lastKey = -1L
        canUndo = undoStack.isNotEmpty(); canRedo = true
        version++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack += blocks
        blocks = redoStack.removeAt(redoStack.size - 1)
        lastKey = -1L
        canUndo = true; canRedo = redoStack.isNotEmpty()
        version++
    }

    fun metaChanged(m: NoteMeta) { meta = m; version++ }

    fun indexOf(id: Long?) = blocks.indexOfFirst { it.id == id }

    // ---------------------------------------------------------------- text

    /** What was typed in a paragraph: its new text; a line break splits it. */
    fun typed(id: Long, text: String, cursor: Int) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Text ?: return
        if ('\n' !in text && '\r' !in text) {
            if (text == b.text) return
            set(blocks.toMutableList().also { it[i] = b.copy(text = text, spans = adjustSpans(b.spans, b.text, text)) }, typingIn = id)
            return
        }
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val parts = clean.split('\n')
        // Enter on an empty list item ends the list (the paragraph becomes ordinary)
        if (parts.size == 2 && parts.all { it.isEmpty() } && b.style in setOf("bullet", "number", "check", "quote")) {
            set(blocks.toMutableList().also { it[i] = b.copy(text = "", style = "body", checked = false) })
            moveTo = id to 0
            return
        }
        val spans = adjustSpans(b.spans, b.text, clean)
        val out = ArrayList<NBlock.Text>()
        var at = 0
        parts.forEachIndexed { n, part ->
            val from = at; val to = at + part.length
            val own = spans.filter { it.end > from && it.start < to }.map { Span(maxOf(it.start, from) - from, minOf(it.end, to) - from, it.kind) }
            out += if (n == 0) b.copy(text = part, spans = own)
            else NBlock.Text(newId(), part, if (b.style in setOf("bullet", "number", "check")) b.style else "body", false, own)
            at = to + 1
        }
        set(blocks.toMutableList().also { it.removeAt(i); it.addAll(i, out) })
        // The cursor in the last new paragraph, just after what was typed or pasted
        val last = out.last()
        moveTo = last.id to (cursor - (clean.lastIndexOf('\n') + 1)).coerceIn(0, last.text.length)
    }

    /**
     * Backspace at the very start of a paragraph: a list item or heading becomes ordinary first; an
     * ordinary one joins the one before. Returns false if nothing changed.
     */
    fun backspaceAtStart(id: Long): Boolean {
        val i = indexOf(id); if (i < 0) return false
        val b = blocks[i] as? NBlock.Text ?: return false
        if (b.style != "body") { set(blocks.toMutableList().also { it[i] = b.copy(style = "body", checked = false) }); moveTo = id to 0; return true }
        val prev = blocks.getOrNull(i - 1)
        when {
            prev is NBlock.Text -> {
                val joined = prev.copy(text = prev.text + b.text, spans = prev.spans + b.spans.map { Span(it.start + prev.text.length, it.end + prev.text.length, it.kind) })
                set(blocks.toMutableList().also { it[i - 1] = joined; it.removeAt(i) })
                moveTo = prev.id to prev.text.length
                return true
            }
            // After handwriting or a picture: an empty paragraph goes, else nothing happens
            prev != null && b.text.isEmpty() -> { set(blocks.toMutableList().also { it.removeAt(i) }); chosen = prev.id; return true }
            else -> return false
        }
    }

    fun setStyle(style: String) {
        val i = indexOf(focused); if (i < 0) return
        val b = blocks[i] as? NBlock.Text ?: return
        val now = if (b.style == style) "body" else style
        set(blocks.toMutableList().also { it[i] = b.copy(style = now, checked = if (now == "check") b.checked else false) })
        moveTo = b.id to selection.end.coerceIn(0, b.text.length)
    }

    fun toggleChecked(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Text ?: return
        set(blocks.toMutableList().also { it[i] = b.copy(checked = !b.checked) })
    }

    /** Bold, italic and so on, on the selection, or on the word with the cursor if nothing is selected. */
    fun toggleMark(kind: String) {
        val i = indexOf(focused); if (i < 0) return
        val b = blocks[i] as? NBlock.Text ?: return
        var s = minOf(selection.start, selection.end).coerceIn(0, b.text.length)
        var e = maxOf(selection.start, selection.end).coerceIn(0, b.text.length)
        if (s == e) {
            while (s > 0 && b.text[s - 1].isLetterOrDigit()) s--
            while (e < b.text.length && b.text[e].isLetterOrDigit()) e++
        }
        if (s == e) return
        set(blocks.toMutableList().also { it[i] = b.copy(spans = toggleSpan(b.spans, s, e, kind)) })
        moveTo = b.id to selection.end.coerceIn(0, b.text.length)
    }

    fun hasMark(kind: String): Boolean {
        val b = blocks.getOrNull(indexOf(focused)) as? NBlock.Text ?: return false
        val s = minOf(selection.start, selection.end); val e = maxOf(selection.start, selection.end)
        return if (s == e) b.spans.any { it.kind == kind && it.start < s && it.end >= s }
        else (s until e).all { x -> b.spans.any { it.kind == kind && it.start <= x && it.end > x } }
    }

    // ---------------------------------------------------------------- handwriting and pictures

    /** Puts [b] after the paragraph with the cursor (or at the end), with an empty paragraph after it to type in. */
    fun insert(b: NBlock) {
        val i = indexOf(focused).let { if (it < 0) blocks.size - 1 else it }
        val list = blocks.toMutableList()
        val cur = list.getOrNull(i)
        // An empty paragraph is replaced
        val at = if (cur is NBlock.Text && cur.text.isEmpty() && cur.style == "body") { list.removeAt(i); i } else i + 1
        list.add(at, b)
        val after = NBlock.Text(newId(), "")
        if (list.getOrNull(at + 1) !is NBlock.Text) list.add(at + 1, after)
        set(list)
        chosen = b.id
    }

    fun setInk(id: Long, strokes: List<Stroke>) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Ink ?: return
        if (b.strokes == strokes) return
        set(blocks.toMutableList().also { it[i] = b.copy(strokes = strokes) })
    }

    fun grow(id: Long, by: Float) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Ink ?: return
        set(blocks.toMutableList().also { it[i] = b.copy(height = (b.height + by).coerceIn(120f, 6000f)) })
    }

    fun remove(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val list = blocks.toMutableList().also { it.removeAt(i) }
        if (list.isEmpty()) list += NBlock.Text(newId(), "")
        set(list)
        if (chosen == id) chosen = null
    }

    /** The text read from handwriting put in as a paragraph after it (to correct and keep). */
    fun inkToText(id: Long) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Ink ?: return
        if (b.recognized.isBlank()) return
        val paras = b.recognized.split('\n').filter { it.isNotBlank() }.map { NBlock.Text(newId(), it) }
        set(blocks.toMutableList().also { it.addAll(i + 1, paras) })
        moveTo = paras.last().id to paras.last().text.length
    }

    fun setAlt(id: Long, alt: String) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Picture ?: return
        set(blocks.toMutableList().also { it[i] = b.copy(alt = alt) })
    }

    /** What handwriting was read as (kept without an undo step). */
    fun recognized(id: Long, strokesHash: Int, text: String) {
        val i = indexOf(id); if (i < 0) return
        val b = blocks[i] as? NBlock.Ink ?: return
        if (b.strokes.hashCode() != strokesHash) return
        quietly(blocks.toMutableList().also { it[i] = b.copy(recognized = text, readFor = strokesHash) })
    }

    fun note() = Note(meta, blocks)
}
