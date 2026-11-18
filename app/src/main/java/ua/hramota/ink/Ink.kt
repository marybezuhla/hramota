package ua.hramota.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import org.json.JSONArray
import org.json.JSONObject

/**
 * One point of handwriting: where (in dp from the top left of the page), when (ms from the first
 * point put on the page, so pauses between strokes stay) and how hard (0..1; a finger gives a fixed value).
 */
data class InkPoint(val x: Float, val y: Float, val t: Long, val p: Float)

enum class InkTool { PEN, MARKER, ERASER }

/** One stroke: from touching the page to lifting off. [width] is in dp, [color] ARGB. */
data class Stroke(val points: List<InkPoint>, val color: Int = INK, val width: Float = 2.2f, val tool: InkTool = InkTool.PEN, val stylus: Boolean = false)

const val INK = 0xFF1B2A4A.toInt()

/** The page being written on: strokes kept, the one being drawn, undo and redo. */
class InkState {
    val strokes = mutableStateListOf<Stroke>()
    private val undone = ArrayList<Stroke>()
    /** The stroke being drawn (not yet in [strokes]); [drawing] changes with each point. */
    internal var live: MutableList<InkPoint>? = null
    internal var liveStylus = false
    internal var drawing by mutableIntStateOf(0)
    /** A stylus has touched this page: from then on, fingers and the palm do not write. */
    var stylusSeen by mutableStateOf(false)
    /** Called after each change by hand (a stroke written or erased). */
    var onChange: (() -> Unit)? = null
    var tool by mutableStateOf(InkTool.PEN)
    var color by mutableIntStateOf(INK)
    var width by mutableStateOf(2.2f)
    /** When the first point of all was put down (times in a sample count from here). */
    internal var started = 0L

    val isEmpty get() = strokes.isEmpty()
    fun clear() { strokes.clear(); undone.clear(); started = 0L }
    fun undo() { if (strokes.isNotEmpty()) undone += strokes.removeAt(strokes.size - 1) }
    fun redo() { if (undone.isNotEmpty()) strokes += undone.removeAt(undone.size - 1) }
    val canRedo get() = undone.isNotEmpty()

    internal fun finish(points: List<InkPoint>, stylus: Boolean) {
        if (points.isEmpty()) return
        if (tool == InkTool.ERASER) { erase(points); return }
        undone.clear()
        strokes += Stroke(points, color, if (tool == InkTool.MARKER) width * 5 else width, tool, stylus)
        onChange?.invoke()
    }

    /** Shows [list] (after an undo in the editor, say), without counting as a change by hand. */
    fun show(list: List<Stroke>) { if (strokes.toList() != list) { strokes.clear(); strokes.addAll(list); undone.clear() } }

    /** Takes out whole strokes the eraser went over (only those near its path are looked at closely). */
    private fun erase(path: List<InkPoint>) {
        val r = 10f
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (b in path) { left = minOf(left, b.x); top = minOf(top, b.y); right = maxOf(right, b.x); bottom = maxOf(bottom, b.y) }
        left -= r; top -= r; right += r; bottom += r
        val hit = strokes.filter { s ->
            s.points.any { a -> a.x in left..right && a.y in top..bottom } &&
                s.points.any { a -> a.x in left..right && a.y in top..bottom && path.any { b -> (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) < r * r } }
        }
        if (hit.isNotEmpty()) { strokes.removeAll(hit.toSet()); undone.clear(); onChange?.invoke() }
    }
}

/**
 * A page to write on with a stylus or a finger. Pressure changes the width; once a stylus is seen,
 * touches by the hand are ignored (the palm resting on the screen); two fingers do not write.
 */
@Composable
fun InkCanvas(state: InkState, modifier: Modifier = Modifier, background: DrawScope.() -> Unit = {}) {
    val density = LocalDensity.current.density
    Box(modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
            if (stylus) state.stylusSeen = true
            // The hand resting on the page while a stylus writes
            if (state.stylusSeen && !stylus) return@awaitEachGesture
            val erasing = down.type == PointerType.Eraser
            val toolBefore = state.tool
            if (erasing) state.tool = InkTool.ERASER
            if (state.started == 0L) state.started = down.uptimeMillis
            val t0 = state.started
            val points = ArrayList<InkPoint>()
            fun add(pos: Offset, t: Long, p: Float) {
                points += InkPoint(pos.x / density, pos.y / density, t - t0, if (stylus) p.coerceIn(0f, 1f) else FINGER)
            }
            add(down.position, down.uptimeMillis, down.pressure)
            state.live = points; state.liveStylus = stylus; state.drawing++
            down.consume()
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent()
                // A second finger: zooming or scrolling, not writing
                if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                for (h in c.historical) add(h.position, h.uptimeMillis, c.pressure)
                if (c.pressed) add(c.position, c.uptimeMillis, c.pressure)
                c.consume()
                state.drawing++
                if (!c.pressed || event.type == PointerEventType.Release) break
            }
            state.live = null
            if (!cancelled) state.finish(points, stylus)
            if (erasing) state.tool = toolBefore
            state.drawing++
        }
    }) {
        // Three layers: the page's lines; the strokes written, kept as a picture (only a new stroke is
        // drawn onto it, so thousands of them cost nothing while writing); the stroke being written
        Canvas(Modifier.matchParentSize()) { background() }
        val cache = remember(state) { InkPicture() }
        Spacer(Modifier.matchParentSize().drawWithCache {
            val list = state.strokes.toList()
            val image = cache.update(list, size, density, layoutDirection)
            onDrawBehind { if (image != null) drawImage(image) else for (s in list) drawStroke(s, density) }
        })
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") state.drawing
            state.live?.let { if (state.tool != InkTool.ERASER) drawStroke(Stroke(it.toList(), state.color, if (state.tool == InkTool.MARKER) state.width * 5 else state.width, state.tool, state.liveStylus), density) }
        }
    }
}

private const val FINGER = 0.5f

/**
 * The strokes written, drawn once into a picture as big as the page: a stroke added is drawn onto
 * it alone; anything else (undo, erasing, another page's strokes, a new size) draws them all again.
 * Null for a page too big to keep so (its strokes are drawn as they are then).
 */
private class InkPicture {
    private var image: ImageBitmap? = null
    private var drawn: List<Stroke> = emptyList()
    private val scope = CanvasDrawScope()

    fun update(list: List<Stroke>, size: Size, density: Float, dir: LayoutDirection): ImageBitmap? {
        val w = size.width.toInt(); val h = size.height.toInt()
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) { image = null; drawn = emptyList(); return null }
        var img = image
        val extends = img != null && img.width == w && img.height == h && list.size >= drawn.size && drawn.indices.all { list[it] === drawn[it] }
        if (img == null || img.width != w || img.height != h) img = try { ImageBitmap(w, h) } catch (e: OutOfMemoryError) { image = null; drawn = emptyList(); return null }
        val from = if (extends) drawn.size else 0
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        scope.draw(Density(density), dir, canvas, size) {
            if (from == 0) drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            for (i in from until list.size) drawStroke(list[i], density)
        }
        image = img; drawn = list
        return img
    }

    companion object { const val MAX_PIXELS = 16L * 1024 * 1024 }
}

/** Draws a stroke: as one path for a finger or a marker, piece by piece (width by pressure) for a stylus pen. */
fun DrawScope.drawStroke(s: Stroke, density: Float) {
    val pts = s.points
    if (pts.isEmpty()) return
    val color = if (s.tool == InkTool.MARKER) Color(s.color).copy(alpha = 0.35f) else Color(s.color)
    val base = s.width * density
    if (pts.size == 1) { drawCircle(color, base / 2, Offset(pts[0].x * density, pts[0].y * density)); return }
    if (!s.stylus || s.tool == InkTool.MARKER) {
        val path = Path()
        path.moveTo(pts[0].x * density, pts[0].y * density)
        // Smoothed through the midpoints
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            path.quadraticTo(a.x * density, a.y * density, (a.x + b.x) / 2 * density, (a.y + b.y) / 2 * density)
        }
        path.lineTo(pts.last().x * density, pts.last().y * density)
        drawPath(path, color, style = DrawStroke(base, cap = if (s.tool == InkTool.MARKER) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round))
        return
    }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        val w = base * (0.45f + 1.1f * (a.p + b.p) / 2)
        drawLine(color, Offset(a.x * density, a.y * density), Offset(b.x * density, b.y * density), w, StrokeCap.Round)
    }
}

/** Handwriting as JSON: each stroke a list of [x, y, t, p] (dp, ms from the first point of all, 0..1). */
object InkJson {
    fun strokes(strokes: List<Stroke>): JSONArray = JSONArray().apply {
        for (s in strokes) put(JSONArray().apply {
            for (p in s.points) put(JSONArray().put(round1(p.x)).put(round1(p.y)).put(p.t).put(round2(p.p)))
        })
    }

    fun readStrokes(a: JSONArray): List<Stroke> = (0 until a.length()).map { i ->
        val s = a.getJSONArray(i)
        Stroke((0 until s.length()).map { j ->
            val p = s.getJSONArray(j)
            InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getLong(2), p.optDouble(3, FINGER.toDouble()).toFloat())
        })
    }

    private fun round1(v: Float) = Math.round(v * 10) / 10.0
    private fun round2(v: Float) = Math.round(v * 100) / 100.0

    /** One sample for the handwriting model: the text asked for and how it was written. */
    fun sample(text: String, strokes: List<Stroke>, widthDp: Float, heightDp: Float): JSONObject = JSONObject().apply {
        put("text", text)
        put("w", Math.round(widthDp)); put("h", Math.round(heightDp))
        put("tool", if (strokes.any { it.stylus }) "stylus" else "finger")
        put("strokes", strokes(strokes))
    }
}
