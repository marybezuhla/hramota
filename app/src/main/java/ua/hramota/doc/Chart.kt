package ua.hramota.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import ua.hramota.doc.docx.XmlNode
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Charts as Word, Excel and PowerPoint keep them (DrawingML chart parts): columns and bars, lines,
 * areas, pies and doughnuts, from the values saved with the chart. Drawn as a picture where the
 * chart stands; nothing is recalculated.
 */
class Chart(
    val title: String,
    /** "column", "bar", "line", "area", "pie", "doughnut" or "scatter". */
    val kind: String,
    val stacked: Boolean,
    val categories: List<String>,
    val series: List<Series>,
) {
    class Series(val name: String, val values: List<Double?>, val color: Int?, val xs: List<Double?> = emptyList())

    companion object {
        private val PALETTE = intArrayOf(0xFF3B5B92.toInt(), 0xFFC8963E.toInt(), 0xFF2F7D5B.toInt(), 0xFFA33B3B.toInt(), 0xFF6B4E9B.toInt(), 0xFF2C8C99.toInt(), 0xFF8C6D3F.toInt(), 0xFF5E7A1F.toInt())
        private const val MAX_POINTS = 2000
        private const val MAX_SERIES = 40

        /** A chart part's tree ([chartSpace]); null if it holds no chart drawn here. */
        fun parse(chartSpace: XmlNode, theme: Map<String, Int> = emptyMap(), resolve: ((String) -> List<String>)? = null): Chart? {
            this.resolve = resolve
            try { return parseHere(chartSpace, theme) } finally { this.resolve = null }
        }

        /** Reads cells a reference names, for charts saved without their values (made by some programs). */
        private var resolve: ((String) -> List<String>)? = null

        private fun parseHere(chartSpace: XmlNode, theme: Map<String, Int>): Chart? {
            val chart = chartSpace.child("chart") ?: return null
            val title = chart.child("title")?.let { t -> texts(t).joinToString("") }.orEmpty()
            val plot = chart.child("plotArea") ?: return null
            val group = plot.children.firstOrNull { it.name in setOf("barChart", "bar3DChart", "lineChart", "line3DChart", "areaChart", "pieChart", "pie3DChart", "doughnutChart", "scatterChart") } ?: return null
            val kind = when (group.name) {
                "barChart", "bar3DChart" -> if (group.child("barDir")?.attr("val") == "bar") "bar" else "column"
                "lineChart", "line3DChart" -> "line"
                "areaChart" -> "area"
                "pieChart", "pie3DChart" -> "pie"
                "doughnutChart" -> "doughnut"
                else -> "scatter"
            }
            val stacked = group.child("grouping")?.attr("val")?.let { it == "stacked" || it == "percentStacked" } == true
            var cats: List<String> = emptyList()
            val series = group.all("ser").take(MAX_SERIES).mapIndexed { i, s ->
                val name = s.child("tx")?.let { tx -> tx.find("v")?.text ?: texts(tx).joinToString("") }.orEmpty()
                val valNode = s.child("val") ?: s.child("yVal")
                val values = numbers(valNode)
                val catNode = s.child("cat") ?: s.child("xVal")
                if (cats.isEmpty()) cats = labels(catNode)
                val xs = if (kind == "scatter") numbers(s.child("xVal")) else emptyList()
                val color = s.child("spPr")?.let { color(it.child("solidFill"), theme) } ?: s.child("spPr")?.child("ln")?.let { color(it.child("solidFill"), theme) }
                Series(name.ifBlank { "${i + 1}" }, values, color, xs)
            }
            if (series.isEmpty() || series.all { s -> s.values.all { it == null } }) return null
            return Chart(title, kind, stacked, cats, series)
        }

        private fun texts(n: XmlNode): List<String> {
            val out = ArrayList<String>()
            val stack = ArrayDeque<XmlNode>().apply { add(n) }
            while (stack.isNotEmpty()) { val x = stack.removeFirst(); if (x.name == "t") out += x.text; stack.addAll(x.children) }
            return out
        }

        /** The saved values of a reference or a literal list, by their point index. */
        private fun points(n: XmlNode?): Map<Int, String> {
            n ?: return emptyMap()
            val cache = n.find("numCache") ?: n.find("strCache") ?: n.find("numLit") ?: n.find("strLit") ?: n.find("multiLvlStrCache")
                ?: return n.find("f")?.text?.let { f -> resolve?.invoke(f) }?.take(MAX_POINTS)?.withIndex()?.associate { it.index to it.value } ?: emptyMap()
            val count = cache.child("ptCount")?.attr("val")?.toIntOrNull()?.coerceIn(0, MAX_POINTS) ?: MAX_POINTS
            val lvl = cache.child("lvl") ?: cache
            return lvl.all("pt").mapNotNull { pt -> pt.attr("idx")?.toIntOrNull()?.takeIf { it in 0 until count }?.let { it to (pt.child("v")?.text ?: "") } }.toMap()
        }

        private fun numbers(n: XmlNode?): List<Double?> {
            val p = points(n); if (p.isEmpty()) return emptyList()
            return (0..p.keys.max()).map { p[it]?.toDoubleOrNull()?.takeIf { v -> v.isFinite() } }
        }

        private fun labels(n: XmlNode?): List<String> {
            val p = points(n); if (p.isEmpty()) return emptyList()
            return (0..p.keys.max()).map { p[it] ?: "" }
        }

        private fun color(fill: XmlNode?, theme: Map<String, Int>): Int? {
            fill ?: return null
            fill.child("srgbClr")?.attr("val")?.let { v -> return v.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } }
            fill.child("schemeClr")?.attr("val")?.let { return theme[it] }
            return null
        }
    }

    /** The chart drawn on white, [w] × [h] pixels. */
    fun draw(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceIn(80, 2400), h.coerceIn(60, 2400), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
        val unit = min(W, H) / 30f
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = unit * 1.05f }
        var top = unit
        if (title.isNotBlank()) {
            val tp = TextPaint(text).apply { textSize = unit * 1.5f; isFakeBoldText = true }
            val t = TextUtils.ellipsize(title, tp, W - 2 * unit, TextUtils.TruncateAt.END).toString()
            c.drawText(t, (W - tp.measureText(t)) / 2, top + tp.textSize, tp)
            top += tp.textSize * 1.6f
        }
        fun colorOf(i: Int, s: Series?) = s?.color ?: PALETTE[i % PALETTE.size]
        // The legend at the bottom: series (or, for a pie, its slices)
        val legend = if (kind == "pie" || kind == "doughnut") categories.indices.map { (categories.getOrElse(it) { "${it + 1}" }) to PALETTE[it % PALETTE.size] }
            else series.mapIndexed { i, s -> s.name to colorOf(i, s) }
        val legendH = if (legend.size > 1 || kind == "pie" || kind == "doughnut") unit * 2.2f else 0f
        if (legendH > 0) {
            var x = unit; val y = H - unit * 1.2f
            for ((name, col) in legend.take(12)) {
                val label = TextUtils.ellipsize(name, text, W / 4, TextUtils.TruncateAt.END).toString()
                val need = unit * 1.6f + text.measureText(label) + unit
                if (x + need > W) break
                c.drawRect(x, y - unit * 0.9f, x + unit, y + unit * 0.1f, Paint().apply { color = col })
                c.drawText(label, x + unit * 1.4f, y, text)
                x += need
            }
        }
        val area = RectF(unit, top, W - unit, H - legendH - unit)
        when (kind) {
            "pie", "doughnut" -> drawPie(c, area, unit, text)
            else -> drawAxes(c, area, unit, text, ::colorOf)
        }
        return bmp
    }

    private fun drawPie(c: Canvas, area: RectF, unit: Float, text: TextPaint) {
        val values = series.first().values.map { (it ?: 0.0).coerceAtLeast(0.0) }
        val total = values.sum().takeIf { it > 0 } ?: return
        val r = min(area.width(), area.height()) / 2
        val oval = RectF(area.centerX() - r, area.centerY() - r, area.centerX() + r, area.centerY() + r)
        var start = -90f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((i, v) in values.withIndex()) {
            val sweep = (v / total * 360).toFloat()
            p.color = PALETTE[i % PALETTE.size]
            c.drawArc(oval, start, sweep, true, p)
            // The share written on the slice when there is room
            if (sweep > 18) {
                val mid = Math.toRadians((start + sweep / 2).toDouble())
                val label = "${Math.round(v / total * 100)}%"
                val tp = TextPaint(text).apply { color = Color.WHITE; isFakeBoldText = true }
                val rr = if (kind == "doughnut") r * 0.78f else r * 0.62f
                c.drawText(label, (area.centerX() + rr * kotlin.math.cos(mid)).toFloat() - tp.measureText(label) / 2, (area.centerY() + rr * kotlin.math.sin(mid)).toFloat() + tp.textSize / 3, tp)
            }
            start += sweep
        }
        if (kind == "doughnut") c.drawCircle(area.centerX(), area.centerY(), r * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun niceStep(range: Double): Double {
        if (range <= 0) return 1.0
        val raw = range / 5
        val mag = 10.0.pow(floor(log10(raw)))
        val f = raw / mag
        return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    }

    private fun label(v: Double): String = if (abs(v) >= 1e6) "%.1fM".format(v / 1e6) else if (abs(v) >= 1e4) "%.0fk".format(v / 1e3) else if (v == floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')

    private fun drawAxes(c: Canvas, area: RectF, unit: Float, text: TextPaint, colorOf: (Int, Series?) -> Int) {
        val n = max(categories.size, series.maxOf { it.values.size }).coerceAtLeast(1)
        // The range of values (sums when stacked), always including zero
        var lo = 0.0; var hi = 0.0
        if (stacked) for (i in 0 until n) {
            var pos = 0.0; var neg = 0.0
            for (s in series) { val v = s.values.getOrNull(i) ?: 0.0; if (v >= 0) pos += v else neg += v }
            hi = max(hi, pos); lo = min(lo, neg)
        } else for (s in series) for (v in s.values) if (v != null) { hi = max(hi, v); lo = min(lo, v) }
        if (hi == lo) hi = lo + 1
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step; hi = ceil(hi / step) * step
        val horizontal = kind == "bar"
        val labelW = listOf(label(lo), label(hi)).maxOf { text.measureText(it) } + unit * 0.6f
        val catH = unit * 1.6f
        val plot = if (horizontal) RectF(area.left + area.width() * 0.22f, area.top, area.right - unit, area.bottom - catH)
            else RectF(area.left + labelW, area.top, area.right, area.bottom - catH)
        val grid = Paint().apply { color = 0xFFDDDDDD.toInt(); strokeWidth = max(1f, unit / 12) }
        val axis = Paint().apply { color = 0xFF888888.toInt(); strokeWidth = max(1f, unit / 10) }
        fun pos(v: Double): Float = if (horizontal) (plot.left + (v - lo) / (hi - lo) * plot.width()).toFloat() else (plot.bottom - (v - lo) / (hi - lo) * plot.height()).toFloat()
        // Value lines and their numbers
        var v = lo
        var guard = 0
        while (v <= hi + step / 2 && guard++ < 50) {
            val p = pos(v); val l = label(v)
            if (horizontal) { c.drawLine(p, plot.top, p, plot.bottom, grid); c.drawText(l, p - text.measureText(l) / 2, plot.bottom + text.textSize * 1.2f, text) }
            else { c.drawLine(plot.left, p, plot.right, p, grid); c.drawText(l, plot.left - unit * 0.3f - text.measureText(l), p + text.textSize / 3, text) }
            v += step
        }
        val zero = pos(0.0)
        if (horizontal) c.drawLine(zero, plot.top, zero, plot.bottom, axis) else c.drawLine(plot.left, zero, plot.right, zero, axis)
        val band = (if (horizontal) plot.height() else plot.width()) / n
        // Category names under (or beside) their place
        val small = TextPaint(text).apply { textSize = unit * 0.9f }
        for (i in 0 until n) {
            val name = categories.getOrNull(i) ?: continue
            val l = TextUtils.ellipsize(name, small, if (horizontal) plot.left - area.left - unit * 0.3f else band * 0.95f, TextUtils.TruncateAt.END).toString()
            if (horizontal) c.drawText(l, plot.left - unit * 0.3f - small.measureText(l), plot.top + band * (i + 0.5f) + small.textSize / 3, small)
            else c.drawText(l, plot.left + band * (i + 0.5f) - small.measureText(l) / 2, plot.bottom + small.textSize * 1.3f, small)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            "column", "bar" -> {
                val groups = if (stacked) 1 else series.size
                val barW = band * 0.75f / groups
                val base = DoubleArray(n); val baseNeg = DoubleArray(n)
                for ((si, s) in series.withIndex()) {
                    paint.color = colorOf(si, s)
                    for (i in 0 until n) {
                        val value = s.values.getOrNull(i) ?: continue
                        val from = if (stacked) (if (value >= 0) base[i] else baseNeg[i]) else 0.0
                        val to = from + value
                        if (stacked) { if (value >= 0) base[i] = to else baseNeg[i] = to }
                        val off = band * i + band * 0.125f + (if (stacked) 0f else barW * si)
                        val a = pos(from); val b = pos(to)
                        if (horizontal) c.drawRect(min(a, b), plot.top + off, max(a, b), plot.top + off + barW, paint)
                        else c.drawRect(plot.left + off, min(a, b), plot.left + off + barW, max(a, b), paint)
                    }
                }
            }
            "line", "area", "scatter" -> {
                val base = DoubleArray(n)
                for ((si, s) in series.withIndex()) {
                    paint.color = colorOf(si, s)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = unit * 0.25f; paint.strokeJoin = Paint.Join.ROUND
                    val path = Path(); var started = false
                    val pts = ArrayList<Pair<Float, Float>>()
                    for (i in 0 until n) {
                        val value = s.values.getOrNull(i) ?: continue
                        val y = if (stacked) { base[i] += value; base[i] } else value
                        val x = plot.left + band * (i + 0.5f)
                        val yy = pos(y)
                        pts += x to yy
                        if (!started) { path.moveTo(x, yy); started = true } else path.lineTo(x, yy)
                    }
                    if (kind == "area" && pts.size > 1) {
                        val fill = Path(path).apply { lineTo(pts.last().first, zero); lineTo(pts.first().first, zero); close() }
                        c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOf(si, s); alpha = 110 })
                    }
                    if (kind != "scatter") c.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                    for ((x, y) in pts) c.drawCircle(x, y, unit * (if (kind == "scatter") 0.45f else 0.3f), paint)
                }
            }
        }
    }

    /** The chart as a PNG. */
    fun png(w: Int, h: Int): ByteArray {
        val b = draw(w, h)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { b.recycle() }
    }
}
