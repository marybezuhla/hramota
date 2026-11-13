package ua.hramota.ink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Hramota turns strokes into the model's input exactly as the training did (rukopys/train/features.py). */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class RukopysTest {
    @Test fun featuresAreThoseOfTraining() {
        val o = JSONObject(File(javaClass.classLoader!!.getResource("ink/features.json")!!.toURI()).readText())
        val ss = o.getJSONArray("strokes")
        val strokes = (0 until ss.length()).map { i -> val s = ss.getJSONArray(i); (0 until s.length()).map { j -> s.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } } }
        val want = o.getJSONArray("features")
        val got = Rukopys.features(strokes)
        assertEquals(want.length(), got.size / 3)
        for (i in 0 until want.length()) for (k in 0 until 3)
            assertEquals("point $i, value $k", want.getJSONArray(i).getDouble(k), got[i * 3 + k].toDouble(), 2e-3)
    }

    @Test fun linesAreFoundTopToBottom() {
        fun stroke(x: Float, y: Float, h: Float) = Stroke(listOf(InkPoint(x, y, 0, 0.5f), InkPoint(x + 5, y + h, 10, 0.5f)))
        val a = stroke(0f, 0f, 20f); val b = stroke(10f, 2f, 18f); val dot = stroke(12f, -8f, 2f)
        val c = stroke(0f, 60f, 20f); val d = stroke(12f, 61f, 19f)
        val lines = Rukopys.lines(listOf(c, a, dot, b, d))
        assertEquals(2, lines.size)
        assertEquals(listOf(a, dot, b), lines[0])
        assertEquals(listOf(c, d), lines[1])
    }

    @Test fun slantIsTakenOut() {
        // Stems leaning right by 0.3 (x per y going up)
        val strokes = (0 until 12).map { k -> Stroke((0..10).map { i -> InkPoint(k * 20f - i * 0.3f * 3f, i * 3f, i.toLong(), 0.5f) }) }
        assertEquals(0.3f, InkTuning.slantOf(strokes), 0.01f)
        val up = InkTuning.upright(strokes, 0.3f)
        assertEquals(up[0].points.first().x, up[0].points.last().x, 0.01f)
    }
}
