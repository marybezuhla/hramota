package ua.hramota.ink

import android.content.Context
import org.json.JSONObject
import ua.hramota.data.Vault
import kotlin.math.abs

/**
 * Recognition fitted to one person's hand, from their «Мій почерк» samples, on the phone only:
 * which way reads them better (Р1 or Р2) and whether straightening their slant first helps. Kept
 * beside the samples (sealed with locked storage on), made again whenever they ask.
 */
object InkTuning {
    const val FILE = "ink-tuning.json"

    /** How the person's writing is read: [engine] "r1" or "r2", and the slant taken out first (0: none). */
    data class Tuning(val engine: String = "r1", val slant: Float = 0f, val scores: Map<String, Float> = emptyMap(), val samples: Int = 0)

    @Volatile private var cached: Tuning? = null

    fun get(context: Context): Tuning = cached ?: (try {
        Vault.readSmall(context, FILE)?.let { b ->
            val o = JSONObject(String(b))
            val sc = o.optJSONObject("scores")
            Tuning(o.optString("engine", "r1"), o.optDouble("slant", 0.0).toFloat().coerceIn(-1f, 1f),
                sc?.keys()?.asSequence()?.associateWith { sc.optDouble(it).toFloat() }.orEmpty(), o.optInt("samples"))
        }
    } catch (e: Exception) { null } ?: Tuning()).also { cached = it }

    fun save(context: Context, t: Tuning) {
        Vault.writeSmall(context, FILE, JSONObject().apply {
            put("engine", t.engine); put("slant", t.slant.toDouble()); put("samples", t.samples)
            put("scores", JSONObject().apply { for ((k, v) in t.scores) put(k, v.toDouble()) })
        }.toString().toByteArray())
        cached = t
    }

    fun forget(context: Context) {
        java.io.File(context.filesDir, FILE).delete()
        java.io.File(context.filesDir, FILE + Vault.SUFFIX).delete()
        cached = null
    }

    /** Locking or unlocking changed how the file is kept: read again next time. */
    fun reset() { cached = null }

    /**
     * The writing's slant: of the pen's mostly upright moves, the middle sideways lean (x per y,
     * positive leaning right). Upright moves are those of letters' stems.
     */
    fun slantOf(strokes: List<Stroke>): Float {
        val leans = ArrayList<Float>()
        for (s in strokes) for (i in 1 until s.points.size) {
            val dx = s.points[i].x - s.points[i - 1].x
            val dy = s.points[i].y - s.points[i - 1].y
            if (abs(dy) > 0.5f && abs(dy) > 1.5f * abs(dx)) leans += -dx / dy
        }
        if (leans.size < 10) return 0f
        leans.sort()
        return leans[leans.size / 2].coerceIn(-1f, 1f)
    }

    /** The strokes with [slant] taken out (letters stood upright). */
    fun upright(strokes: List<Stroke>, slant: Float): List<Stroke> =
        if (slant == 0f) strokes else strokes.map { s -> s.copy(points = s.points.map { p -> p.copy(x = p.x + slant * p.y) }) }

    /** Letters to change to turn [a] into [b] (Levenshtein). */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Text compared as written: lines and repeated spaces as one space, apostrophes alike. */
    fun plain(s: String) = s.replace('\n', ' ').replace('’', '\'').replace('ʼ', '\'').trim().replace(Regex("\\s+"), " ")
}
