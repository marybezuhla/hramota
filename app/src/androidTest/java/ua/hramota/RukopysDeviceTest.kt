package ua.hramota

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.hramota.ink.Rukopys

/** The handwriting model Р2 on the phone reads as it did where it was made (onnxruntime in Python). */
@RunWith(AndroidJUnit4::class)
class RukopysDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theModelIsBuiltIn() = assertTrue(Rukopys.available(context))

    @Test fun readsAsInTraining(): Unit = runBlocking {
        val o = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("ink-sample.json").bufferedReader().readText())
        val ss = o.getJSONArray("strokes")
        val strokes = (0 until ss.length()).map { i -> val s = ss.getJSONArray(i); (0 until s.length()).map { j -> s.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } } }
        // What rukopys/train/features.py and onnxruntime 1.24 give for these strokes with this model
        assertEquals("іжак, 7", Rukopys.readPiece(context, strokes))
    }
}
