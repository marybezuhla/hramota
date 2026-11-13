package ua.hramota.ink

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Handwriting read the second way (Р2): the model of rukopys/ reads the pen's movement itself, so
 * joined-up writing is read too. Built into Hramota only when it read better than Р1 on writing it
 * never saw; on each phone «Мій почерк» checks again which way reads that person's hand better.
 * The steps turning strokes into the model's input are those of rukopys/train/features.py.
 */
object Rukopys {
    private const val MODEL = "ink/rukopys.onnx"
    private const val ALPHABET = "ink/rukopys_alphabet.txt"
    const val STEP = 0.05f
    const val CLIP = 5f
    const val MAX_POINTS = 3000

    private val lock = Mutex()
    private var session: OrtSession? = null
    private var chars: List<String> = emptyList()

    /** Whether this Hramota has the model. */
    fun available(context: Context): Boolean = try { context.assets.open(MODEL).close(); context.assets.open(ALPHABET).close(); true } catch (e: Exception) { false }

    /** The p-th percentile (0..100) of [v], as numpy computes it (linear between neighbours). */
    internal fun percentile(v: FloatArray, p: Float): Float {
        val s = v.sortedArray()
        if (s.isEmpty()) return 0f
        val at = (s.size - 1) * p / 100f
        val lo = floor(at).toInt(); val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (at - lo)
    }

    /** Points every [step] along a stroke's path (its first point kept; a short stroke is one point). */
    internal fun resample(xs: FloatArray, ys: FloatArray, step: Float): List<Pair<Float, Float>> {
        if (xs.size < 2) return listOf(xs[0] to ys[0])
        val at = FloatArray(xs.size)
        for (i in 1 until xs.size) at[i] = at[i - 1] + sqrt((xs[i] - xs[i - 1]) * (xs[i] - xs[i - 1]) + (ys[i] - ys[i - 1]) * (ys[i] - ys[i - 1]))
        val total = at.last()
        if (total < step) return listOf(xs[0] to ys[0])
        val n = (total / step).toInt() + 1
        val out = ArrayList<Pair<Float, Float>>(n)
        var j = 0
        for (k in 0 until n) {
            val want = k * step
            while (j < at.size - 2 && at[j + 1] < want) j++
            val span = at[j + 1] - at[j]
            val f = if (span > 0f) ((want - at[j]) / span).coerceIn(0f, 1f) else 0f
            out += (xs[j] + (xs[j + 1] - xs[j]) * f) to (ys[j] + (ys[j + 1] - ys[j]) * f)
        }
        return out
    }

    /** The model's input for one line: [n, 3] (dx, dy, pen put down), row after row. */
    fun features(strokes: List<List<Pair<Float, Float>>>): FloatArray {
        val ss = strokes.filter { it.isNotEmpty() }
        if (ss.isEmpty()) return FloatArray(0)
        val allY = ss.flatMap { s -> s.map { it.second } }.toFloatArray()
        val h = max(percentile(allY, 95f) - percentile(allY, 5f), 1e-3f)
        val x0 = ss.minOf { s -> s.minOf { it.first } }
        val ym = percentile(allY, 50f)
        val out = ArrayList<Float>()
        var px = 0f; var py = 0f; var first = true
        for (s in ss) {
            val xs = FloatArray(s.size) { (s[it].first - x0) / h }
            val ys = FloatArray(s.size) { (s[it].second - ym) / h }
            for ((i, p) in resample(xs, ys, STEP).withIndex()) {
                val dx = if (first) 0f else p.first - px
                val dy = if (first) 0f else p.second - py
                out += dx.coerceIn(-CLIP, CLIP); out += dy.coerceIn(-CLIP, CLIP); out += if (i == 0) 1f else 0f
                px = p.first; py = p.second; first = false
                if (out.size / 3 >= MAX_POINTS) return out.toFloatArray()
            }
        }
        return out.toFloatArray()
    }

    /**
     * The strokes in lines, top to bottom, each in the order written. A small stroke (a dot, an
     * accent) joins the line nearest to it.
     */
    fun lines(strokes: List<Stroke>): List<List<Stroke>> {
        val pen = strokes.filter { it.tool == InkTool.PEN && it.points.isNotEmpty() }
        if (pen.isEmpty()) return emptyList()
        class S(val s: Stroke, val i: Int) {
            val top = s.points.minOf { it.y }; val bottom = s.points.maxOf { it.y }
            val h = bottom - top; val mid = (top + bottom) / 2
        }
        val all = pen.mapIndexed { i, s -> S(s, i) }
        val typical = all.map { it.h }.sorted().let { it[it.size * 3 / 4] }.coerceAtLeast(1f)
        val big = all.filter { it.h >= typical * 0.3f }.sortedBy { it.mid }
        val groups = ArrayList<MutableList<S>>()
        var bottom = Float.NEGATIVE_INFINITY
        for (s in big) {
            // A new line: this stroke starts below most of the line so far
            if (groups.isEmpty() || s.top > bottom - 0.35f * typical) { groups += mutableListOf(s); bottom = s.bottom }
            else { groups.last() += s; bottom = max(bottom, s.bottom) }
        }
        if (groups.isEmpty()) groups += mutableListOf<S>()
        for (s in all) if (s.h < typical * 0.3f) {
            val g = groups.filter { it.isNotEmpty() }.minByOrNull { g -> val c = g.map { it.mid }.average().toFloat(); kotlin.math.abs(c - s.mid) } ?: groups[0]
            g += s
        }
        return groups.filter { it.isNotEmpty() }.map { g -> g.sortedBy { it.i }.map { it.s } }
    }

    /** A line in pieces short enough to read (under [MAX_POINTS] once resampled), cut between strokes at the widest gaps. */
    internal fun pieces(line: List<Stroke>): List<List<Stroke>> {
        val f = features(line.map { st -> st.points.map { it.x to it.y } })
        if (f.size / 3 < MAX_POINTS * 9 / 10 || line.size < 2) return listOf(line)
        // The gap after each stroke: how far right the next one starts beyond everything before
        var right = Float.NEGATIVE_INFINITY
        val gaps = ArrayList<Pair<Int, Float>>()
        for (i in 0 until line.size - 1) {
            right = max(right, line[i].points.maxOf { it.x })
            gaps += i to (line[i + 1].points.minOf { it.x } - right)
        }
        val cut = gaps.maxBy { it.second }.first + 1
        return pieces(line.subList(0, cut)) + pieces(line.subList(cut, line.size))
    }

    private suspend fun ready(context: Context): OrtSession = lock.withLock {
        session ?: withContext(Dispatchers.IO) {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            chars = context.assets.open(ALPHABET).bufferedReader(Charsets.UTF_8).readText().split('\n').dropLast(1)
            env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
        }.also { session = it }
    }

    /** One line read: the most likely letter at each step, repeats and blanks dropped. */
    private fun decode(o: OnnxTensor): String {
        val shape = o.info.shape
        val steps = shape[1].toInt(); val classes = shape[2].toInt()
        val fb = o.floatBuffer
        val sb = StringBuilder()
        var prev = 0
        for (t in 0 until steps) {
            var bi = 0; var bv = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) { val v = fb.get(t * classes + c); if (v > bv) { bv = v; bi = c } }
            if (bi != prev && bi != 0) sb.append(chars.getOrElse(bi - 1) { "" })
            prev = bi
        }
        return sb.toString().trim().replace(Regex(" {2,}"), " ")
    }

    /** The text written, line by line. Off the main thread; empty when nothing is written. */
    suspend fun read(context: Context, strokes: List<Stroke>): String = withContext(Dispatchers.Default) {
        val s = ready(context)
        val env = OrtEnvironment.getEnvironment()
        lines(strokes).map { line ->
            // A line longer than the model was taught on: read in pieces, cut where the pen jumps furthest
            pieces(line).map { piece ->
                val f = features(piece.map { st -> st.points.map { it.x to it.y } })
                if (f.isEmpty()) "" else OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, (f.size / 3).toLong(), 3)).use { t ->
                    lock.withLock { s.run(mapOf("points" to t)).use { out -> decode(out[0] as OnnxTensor) } }
                }
            }.filter { it.isNotEmpty() }.joinToString(" ")
        }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
