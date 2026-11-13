package ua.hramota.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import ua.hramota.ocr.Reflow
import ua.hramota.ocr.TextRecognizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Handwriting read as text, the first way (Р1): the strokes are drawn black on white and read by the
 * same recognition as scanned pages. Printed letters are read best; joined-up writing waits for the
 * handwriting model of its own (rukopys/).
 */
object InkText {
    /** Pixels per dp the strokes are drawn at: letters a finger writes come out 40–120 px high. */
    private const val SCALE = 2f
    private const val MARGIN = 24f
    private const val MAX_SIDE = 4000

    /** The strokes drawn for reading: only the part written on, with a margin. Null if nothing is written. */
    fun render(strokes: List<Stroke>): Bitmap? {
        val pts = strokes.filter { it.tool == InkTool.PEN }.flatMap { it.points }
        if (pts.isEmpty()) return null
        val x0 = pts.minOf { it.x }; val y0 = pts.minOf { it.y }
        val x1 = pts.maxOf { it.x }; val y1 = pts.maxOf { it.y }
        var k = SCALE
        val w0 = (x1 - x0) * k + 2 * MARGIN; val h0 = (y1 - y0) * k + 2 * MARGIN
        if (max(w0, h0) > MAX_SIDE) k *= MAX_SIDE / max(w0, h0)
        val w = ceil((x1 - x0) * k + 2 * MARGIN).toInt().coerceAtLeast(32)
        val h = ceil((y1 - y0) * k + 2 * MARGIN).toInt().coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            // As a pen on paper: about as thick whatever the stroke's own width
            strokeWidth = min(6f, max(2.5f, 2.2f * k))
        }
        for (s in strokes) {
            if (s.tool != InkTool.PEN || s.points.isEmpty()) continue
            val p = Path()
            s.points.forEachIndexed { i, pt ->
                val x = (pt.x - x0) * k + MARGIN; val y = (pt.y - y0) * k + MARGIN
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (s.points.size == 1) c.drawPoint((s.points[0].x - x0) * k + MARGIN, (s.points[0].y - y0) * k + MARGIN, paint) else c.drawPath(p, paint)
        }
        return bmp
    }

    /**
     * The text written, in paragraphs (empty if none was found). Runs off the main thread. Writing in
     * a Cyrillic language ([cyrillic]), a word of letters that look alike in both scripts is taken as
     * Cyrillic: a hand-written «ТАК» is not "TAK".
     */
    suspend fun read(context: Context, strokes: List<Stroke>, cyrillic: Boolean = java.util.Locale.getDefault().language in CYRILLIC_LANGUAGES): String {
        val bmp = render(strokes) ?: return ""
        return try {
            val r = TextRecognizer.get(context)
            r.hold()
            val text = try { Reflow.paragraphs(r.recognize(bmp)).joinToString("\n") } finally { r.letGo() }
            if (cyrillic) ua.hramota.ocr.Lookalikes.toCyrillic(text) else text
        } finally { bmp.recycle() }
    }

    private val CYRILLIC_LANGUAGES = setOf("uk", "be", "bg", "ru", "sr", "mk")
}
