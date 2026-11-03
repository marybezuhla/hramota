package ua.hramota.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.coroutines.resume

/** Sound for one sentence: samples between -1 and 1 at [rate]. */
class Sound(val samples: FloatArray, val rate: Int)

/** A voice that reads a sentence into sound. */
interface Voice {
    suspend fun read(text: String, speed: Float): Sound?
    fun close() {}
}

/** Files that come with the app (not compressed in the APK, so they are mapped, not copied). */
internal fun mapAsset(context: Context, name: String): ByteBuffer {
    val fd = context.assets.openFd(name)
    return FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
}

/**
 * Hramota's Ukrainian voice: Tetiana (robinhad/ukrainian-tts v5, MIT; recordings Apache-2.0), a VITS model run on
 * the phone. Text gets its stresses from the dictionary first ([UkrainianText]); the voice was trained on stressed text.
 */
class TetianaVoice private constructor(private val session: OrtSession, private val env: OrtEnvironment, private val tokens: Map<Char, Long>, private val speaker: FloatArray, val text: UkrainianText) : Voice {
    private val space = tokens[' '] ?: 3L

    /** The letters and marks the voice knows, as its token numbers; anything else is left out. */
    fun ids(prepared: String): LongArray {
        val out = ArrayList<Long>(prepared.length)
        var lastSpace = true
        for (raw in prepared.lowercase()) {
            val c = when (raw) {
                '«', '»', '“', '”' -> '"'; '—', '–', '−' -> '-'; '…' -> '.'; ';' -> ','; '’', 'ʼ', '`' -> '\''
                '\n', '\t', ' ' -> ' '
                else -> raw
            }
            if (c == ' ') { if (!lastSpace) out += space; lastSpace = true; continue }
            val id = tokens[c] ?: continue
            out += id; lastSpace = false
        }
        while (out.isNotEmpty() && out.last() == space) out.removeAt(out.size - 1)
        return out.toLongArray()
    }

    override suspend fun read(text: String, speed: Float): Sound? {
        val ids = ids(this.text.prepare(text))
        if (ids.isEmpty()) return null
        // Longer sounds are slower: the voice's own pace times the person's speed
        val scales = floatArrayOf(0.667f, BASE_LENGTH / speed.coerceIn(0.5f, 2.5f), 0.8f)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(speaker), longArrayOf(speaker.size.toLong())).use { s ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3)).use { sc ->
                    // The sentence's length too, when the model asks for it
                    val lengths = if ("text_lengths" in session.inputNames) OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)) else null
                    val inputs = buildMap { put("text", t); put("spembs", s); put("scales", sc); lengths?.let { put("text_lengths", it) } }
                    try { session.run(inputs).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val wav = (r[0].value as Array<FloatArray>)[0]
                        return Sound(wav, RATE)
                    } } finally { lengths?.close() }
                }
            }
        }
    }

    override fun close() { try { session.close() } catch (_: Exception) {} }

    companion object {
        const val RATE = 22050
        /** The voice's own pace stretched to a calm narrator's, about four syllables a second. */
        const val BASE_LENGTH = 1.0f
        const val MODEL = "voice/uk-tetiana.onnx"

        fun open(context: Context, dictionary: StressDictionary?): TetianaVoice {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // The big cores of a phone; the rest of the app stays quick
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(mapAsset(context, MODEL), options)
            val lines = context.assets.open("voice/uk-tetiana.tokens.txt").bufferedReader().readLines()
            val tokens = HashMap<Char, Long>()
            lines.forEachIndexed { i, t -> when { t == "<space>" -> tokens[' '] = i.toLong(); t.length == 1 -> tokens[t[0]] = i.toLong() } }
            val bytes = context.assets.open("voice/uk-tetiana.xvector.bin").use { it.readBytes() }
            val speaker = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            return TetianaVoice(session, env, tokens, speaker, UkrainianText(dictionary))
        }

        fun dictionary(context: Context): StressDictionary? = try { StressDictionary(mapAsset(context, "voice/stress.bin")) } catch (e: Exception) { null }
    }
}

/**
 * The phone's own voice (Android's text to speech, as set up on the phone): for English and other languages, and
 * for anyone who prefers it. It writes each sentence to a file in the cache, read back and played like the others.
 */
class PhoneVoice private constructor(private val tts: TextToSpeech, private val dir: File) : Voice {
    @Volatile var language: Locale = Locale.getDefault()
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    init {
        // One listener for all sentences: each finishes (done, error or stopped) by its own id
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(true) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
        })
    }

    override suspend fun read(text: String, speed: Float): Sound? {
        val lang = Sentences.language(text)
        val locale = when (lang) { "uk" -> Locale.forLanguageTag("uk-UA"); "en" -> Locale.UK; else -> language }
        if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return null
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) return null
        tts.language = locale
        tts.setSpeechRate(speed)
        val id = "s" + System.nanoTime()
        // Each sentence its own file: a sentence stopped late can never delete the next one's
        val file = File(dir, "$id.wav")
        // On the phone, not over the network, where the engine allows choosing
        val params = android.os.Bundle().apply { putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true") }
        try {
            val ok = suspendCancellableCoroutine { c ->
                waiting[id] = { done -> if (c.isActive) c.resume(done) }
                if (tts.synthesizeToFile(text, params, file, id) != TextToSpeech.SUCCESS) waiting.remove(id)?.invoke(false)
                c.invokeOnCancellation { waiting.remove(id) }
            }
            return if (ok) Wav.read(file) else null
        } finally { file.delete() }
    }

    override fun close() { try { tts.shutdown() } catch (_: Exception) {} }

    companion object {
        /** Starts the phone's voice; null when the phone has none. */
        suspend fun open(context: Context): PhoneVoice? = suspendCancellableCoroutine { c ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                if (!c.isActive) { tts?.shutdown(); return@TextToSpeech }
                if (status == TextToSpeech.SUCCESS) c.resume(PhoneVoice(tts!!, File(context.cacheDir, "speech").apply { mkdirs(); listFiles()?.forEach { it.delete() } }))
                else { tts?.shutdown(); c.resume(null) }
            }
        }
    }
}

/** A plain WAV file (16-bit, as Android's voices write) read into samples. */
internal object Wav {
    fun read(f: File): Sound? {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 44) return null
        b.position(12)
        var rate = 22050; var bits = 16; var channels = 1
        while (b.remaining() >= 8) {
            val id = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
            val size = b.int
            if (id == "fmt ") {
                val at = b.position()
                b.short; channels = b.short.toInt(); rate = b.int; b.int; b.short; bits = b.short.toInt()
                b.position(at + size)
            } else if (id == "data") {
                // Only what an engine on a phone writes; anything else is not played
                if (bits != 16 || rate !in 8000..48000 || channels !in 1..2) return null
                val n = minOf(size, b.remaining()) / 2 / channels
                val out = FloatArray(n)
                for (i in 0 until n) { out[i] = b.short / 32768f; repeat(channels - 1) { b.short } }
                return Sound(out, rate)
            } else b.position(minOf(b.limit(), b.position() + size + (size and 1)))
        }
        return null
    }
}
