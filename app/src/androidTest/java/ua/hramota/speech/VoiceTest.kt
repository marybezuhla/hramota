package ua.hramota.speech

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Tetiana's voice on a real Android system: the model opens from the APK, reads a sentence, and how fast. */
@RunWith(AndroidJUnit4::class)
class VoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun tetianaReadsASentence() = runBlocking {
        val voice = TetianaVoice.open(context, TetianaVoice.dictionary(context))
        try {
            // The letters given to the voice, read back through its own token list: stresses in place, one space
            val tokens = context.assets.open("voice/uk-tetiana.tokens.txt").bufferedReader().readLines()
            val back = voice.ids(voice.text.prepare("Стежка звернула")).joinToString("") { tokens[it.toInt()].replace("<space>", " ") }
            assertEquals("ст+ежка зверн+ула", back)
            val text = "Увечері туман піднявся з долини, а мати засвітила лампу."
            val t0 = System.nanoTime()
            val sound = voice.read(text, 1f)
            val took = (System.nanoTime() - t0) / 1e9
            assertNotNull(sound)
            val seconds = sound!!.samples.size / sound.rate.toDouble()
            Log.i("HramotaVoice", "sentence %.1f s of sound in %.2f s (RTF %.2f)".format(seconds, took, took / seconds))
            // A real sentence: a few seconds of sound, and not silence
            assertTrue("length $seconds", seconds in 2.0..12.0)
            assertTrue("loudness", sound.samples.map { abs(it) }.average() > 0.01)
        } finally { voice.close() }
    }

    @Test fun coriReadsEnglish() = runBlocking {
        val voice = EnglishVoice.open(context)
        try {
            // eSpeak NG gives the phonemes the voice was trained on
            val ph = voice.phonemes("The path turned towards the stream.")
            assertTrue(ph, ph.contains("ðə pˈ") && ph.contains("stɹˈiːm") && ph.endsWith("."))
            val t0 = System.nanoTime()
            val sound = voice.read("In the evening, mist rose from the valley, and mother lit the lamp.", 1f)
            val took = (System.nanoTime() - t0) / 1e9
            assertNotNull(sound)
            val seconds = sound!!.samples.size / sound.rate.toDouble()
            Log.i("HramotaVoice", "English sentence %.1f s of sound in %.2f s (RTF %.2f)".format(seconds, took, took / seconds))
            assertTrue("length $seconds", seconds in 2.0..12.0)
            assertTrue("loudness", sound.samples.map { abs(it) }.average() > 0.01)
        } finally { voice.close() }
    }
}
