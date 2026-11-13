package ua.hramota.ink

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class InkTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun samplesKeepTheirStrokes() {
        val strokes = listOf(
            Stroke(listOf(InkPoint(1.04f, 2f, 0, 0.5f), InkPoint(3f, 4.26f, 16, 0.734f)), stylus = true),
            Stroke(listOf(InkPoint(10f, 2f, 400, 0.5f))),
        )
        val json = JSONObject(InkJson.sample("Їжак", strokes, 360f, 500f).toString())
        assertEquals("Їжак", json.getString("text"))
        assertEquals("stylus", json.getString("tool"))
        val back = InkJson.readStrokes(json.getJSONArray("strokes"))
        assertEquals(2, back.size)
        assertEquals(1.0f, back[0].points[0].x, 0.001f)
        assertEquals(4.3f, back[0].points[1].y, 0.001f)
        assertEquals(0.73f, back[0].points[1].p, 0.001f)
        assertEquals(400L, back[1].points[0].t)
    }

    @Test fun sentencesCoverTheWholeAlphabet() {
        val text = MyInk.prompts(context).joinToString(" ").lowercase()
        for (c in "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя'0123456789") assertTrue("$c", c in text)
        assertTrue(MyInk.prompts(context).size >= MyInk.ENOUGH)
    }

    @Test fun theNextSentenceIsOneNotYetWritten() {
        val p = listOf("а", "б", "в")
        assertEquals(0, MyInk.next(p, emptyList()))
        assertEquals(2, MyInk.next(p, listOf("а", "б")))
        assertEquals(1, MyInk.next(p, listOf("а", "б", "в", "а")))
        assertEquals(-1, MyInk.next(emptyList(), emptyList()))
    }

    @Test fun samplesAreAddedTakenOutAndDeleted() {
        MyInk.deleteAll(context)
        MyInk.add(context, JSONObject().put("text", "один"))
        MyInk.add(context, JSONObject().put("text", "два"))
        assertEquals(listOf("один", "два"), MyInk.samples(context).map { it.getString("text") })
        MyInk.dropLast(context)
        assertEquals(listOf("один"), MyInk.samples(context).map { it.getString("text") })
        MyInk.deleteAll(context)
        assertTrue(MyInk.samples(context).isEmpty())
    }
}
