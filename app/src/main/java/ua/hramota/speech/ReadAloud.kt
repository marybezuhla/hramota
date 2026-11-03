package ua.hramota.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reading a document aloud, sentence by sentence, while the next sentence is already being made. It goes on with
 * the screen off (the service keeps Hramota running and shows the controls); it stops when the document closes.
 *
 * Every control runs on the main thread, one at a time; the work of one reading (making sentences, playing them)
 * is one job with its own sound output, so stopping or skipping never touches sound another job is using.
 */
object ReadAloud {
    /** What is read: units (pages, paragraphs, a book's blocks) and their text. */
    class Document(
        val id: String,
        val title: String,
        val count: () -> Int,
        val text: suspend (Int) -> String,
        /** A unit that starts a chapter (a heading): «until the end of the chapter» stops before the next one. */
        val isHeading: (Int) -> Boolean = { false },
    )

    data class State(
        val document: String? = null,
        val title: String = "",
        val playing: Boolean = false,
        /** Getting the voice ready (the first time it takes a moment). */
        val preparing: Boolean = false,
        val unit: Int = 0,
        val start: Int = 0,
        val end: Int = 0,
        val speed: Float = 1f,
        val voice: String = HRAMOTA,
        /** When the sleep timer stops reading (ms, uptime clock), or [SLEEP_CHAPTER], or null. */
        val sleepAt: Long? = null,
        /** Said when something could not be read (no voice for the language). */
        val notice: Int? = null,
    ) { val active get() = document != null }

    const val HRAMOTA = "hramota"
    const val PHONE = "phone"
    const val SLEEP_CHAPTER = -1L
    const val NOTICE_NO_VOICE = 1
    const val NOTICE_NO_LANGUAGE = 2
    const val NOTICE_NO_SOUND = 3
    /** Paused this long, reading ends (the controls go and the voice's memory is freed). */
    private const val PAUSED_LIMIT = 30 * 60_000L

    // A failure in the sound system must never close the app: reading just stops, with a word why
    private val guard = CoroutineExceptionHandler { _, _ -> _state.update { it.copy(playing = false, preparing = false, notice = NOTICE_NO_SOUND) } }
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Touched on the main thread only
    @Volatile private var doc: Document? = null
    private var job: Job? = null
    private var sleepJob: Job? = null
    private var idleJob: Job? = null
    private lateinit var app: Context

    // The voices: opened once, used by one sentence at a time, closed only when nothing reads
    private val voices = Mutex()
    private var tetiana: TetianaVoice? = null
    private var tetianaFailed = false
    private var phone: PhoneVoice? = null
    private var phoneFailed = false

    /** A sentence and where it is. */
    private class Spot(val unit: Int, val start: Int, val end: Int, val text: String, val last: Boolean)

    /** Starts reading [document] from [unit] (and the character [from] in it). */
    fun start(context: Context, document: Document, unit: Int, from: Int = 0, speed: Float, voice: String) = onMain {
        app = context.applicationContext
        job?.cancel(); job = null
        doc = document
        _state.value = State(document.id, document.title, playing = true, unit = unit, start = from, end = from, speed = speed, voice = voice, sleepAt = _state.value.sleepAt)
        ReadAloudService.show(app)
        run(unit, from, 0)
    }

    /** The same document seen anew (the screen turned): its text comes from the new view from now on. */
    fun rebind(document: Document) { if (_state.value.document == document.id) doc = document }

    fun pause() = onMain {
        job?.cancel(); job = null
        _state.update { it.copy(playing = false, preparing = false) }
        idleJob?.cancel(); idleJob = main.launch { delay(PAUSED_LIMIT); stopNow() }
    }

    fun resume() = onMain {
        val s = _state.value
        if (!s.active || s.playing) return@onMain
        idleJob?.cancel()
        _state.update { it.copy(playing = true, notice = null) }
        if (::app.isInitialized) ReadAloudService.show(app)
        run(s.unit, s.start, 0)
    }

    fun toggle() = onMain { if (_state.value.playing) pause() else resume() }

    /** To the next or the previous sentence (while paused, only the place moves). */
    fun skip(by: Int) = onMain {
        val s = _state.value
        if (!s.active) return@onMain
        run(s.unit, s.start, by)
    }

    fun setSpeed(speed: Float) = onMain { _state.update { it.copy(speed = speed) }; again() }

    fun setVoice(voice: String) = onMain { _state.update { it.copy(voice = voice) }; again() }

    private fun again() { val s = _state.value; if (s.playing) run(s.unit, s.start, 0) }

    /** Stops reading after [minutes]; 0 turns the timer off; [SLEEP_CHAPTER] stops at the next chapter. */
    fun sleep(minutes: Long) = onMain {
        sleepJob?.cancel(); sleepJob = null
        when (minutes) {
            0L -> _state.update { it.copy(sleepAt = null) }
            SLEEP_CHAPTER -> _state.update { it.copy(sleepAt = SLEEP_CHAPTER) }
            else -> {
                _state.update { it.copy(sleepAt = android.os.SystemClock.uptimeMillis() + minutes * 60_000) }
                sleepJob = main.launch { delay(minutes * 60_000); _state.update { it.copy(sleepAt = null) }; pause() }
            }
        }
    }

    /** Ends reading (the document closed, or ✕): the controls go. With [documentId], only if that one is read. */
    fun stop(documentId: String? = null) = onMain {
        if (documentId != null && _state.value.document != documentId) return@onMain
        stopNow()
    }

    private fun stopNow() {
        job?.cancel(); job = null
        sleepJob?.cancel(); sleepJob = null
        idleJob?.cancel(); idleJob = null
        doc = null
        _state.value = State(speed = _state.value.speed, voice = _state.value.voice)
        if (::app.isInitialized) ReadAloudService.hide(app)
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun onMain(block: () -> Unit) { main.launch { block() } }

    /** The sentences of [unit] in the document read now (it may have been seen anew). */
    private suspend fun sentences(unit: Int): Pair<String, List<Sentences.Span>> {
        val d = doc ?: return "" to emptyList()
        val text = try { d.text(unit) } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
        return text to Sentences.split(text)
    }

    /** The sentence [by] steps away from the one at ([unit], [from]). */
    private suspend fun neighbour(unit: Int, from: Int, by: Int): Spot? {
        val count = doc?.count?.invoke() ?: return null
        var u = unit
        var (text, spans) = sentences(u)
        var k = spans.indexOfLast { it.start <= from }.coerceAtLeast(0) + by
        var guardSteps = 0
        while (k < 0 || k >= spans.size) {
            if (++guardSteps > 2000) return null
            if (k < 0) { u--; if (u < 0) return null; val s = sentences(u); text = s.first; spans = s.second; k += spans.size }
            else { k -= spans.size; u++; if (u >= count) return null; val s = sentences(u); text = s.first; spans = s.second }
        }
        val sp = spans[k]
        return Spot(u, sp.start, sp.end, text.substring(sp.start, sp.end), k == spans.size - 1)
    }

    /** The sentences from ([unit], [from]) on, one after another. */
    private fun sentencesFrom(unit: Int, from: Int) = flow {
        var u = unit; var first = true
        while (u < (doc?.count?.invoke() ?: 0)) {
            val (text, spans) = sentences(u)
            for ((k, sp) in spans.withIndex()) {
                if (first && sp.end <= from) continue
                emit(Spot(u, sp.start, sp.end, text.substring(sp.start, sp.end), k == spans.size - 1))
            }
            first = false
            u++
        }
    }

    /** One reading: moves [by] sentences first, then (when playing) reads on from there. */
    private fun run(unit: Int, from: Int, by: Int) {
        job?.cancel()
        val previous = job
        job = main.launch(Dispatchers.Default) {
            previous?.join()
            var u = unit; var f = from
            if (by != 0) {
                val spot = neighbour(unit, from, by) ?: return@launch
                u = spot.unit; f = spot.start
                _state.update { it.copy(unit = spot.unit, start = spot.start, end = spot.end) }
            }
            if (!_state.value.playing) return@launch
            read(u, f)
        }
    }

    private suspend fun read(unit: Int, from: Int) = kotlinx.coroutines.coroutineScope {
        val made = Channel<Pair<Spot, Sound?>>(capacity = 2)
        // Made ahead: the next sentence is ready when this one ends
        val maker = launch {
            sentencesFrom(unit, from)
                // With «until the end of the chapter», reading stops before the next chapter's heading
                .takeWhile { !(_state.value.sleepAt == SLEEP_CHAPTER && it.unit > unit && doc?.isHeading?.invoke(it.unit) == true) }
                .collect { spot -> made.send(spot to make(spot.text)) }
            made.close()
        }
        val out = Output()
        try {
            while (isActive) {
                // While the next sentence is still being made, quiet sound keeps playing: the phone stays awake
                // with the screen off (no wake lock needed) and the pause is heard as a pause
                var next = made.tryReceive()
                while (next.isFailure && !next.isClosed && isActive) { out.silence(0.1f); next = made.tryReceive() }
                val (spot, sound) = next.getOrNull() ?: break
                _state.update { it.copy(unit = spot.unit, start = spot.start, end = spot.end, preparing = false) }
                if (sound != null) { out.play(sound); out.silence(if (spot.last) 0.55f else 0.28f) }
            }
            out.drain()
            // The end (of the document, or of the chapter the timer waits for)
            if (isActive) {
                _state.update { it.copy(playing = false, sleepAt = if (it.sleepAt == SLEEP_CHAPTER) null else it.sleepAt) }
                if (_state.value.sleepAt == null) withContext(Dispatchers.Main) { pause() }
            }
        } finally {
            maker.cancel()
            out.release()
        }
    }

    /** A sentence made into sound by the voice for its language (the person's choice first). */
    private suspend fun make(text: String): Sound? {
        val lang = Sentences.language(text) ?: return null
        val speed = _state.value.speed
        if (lang == "uk" && _state.value.voice != PHONE) {
            val sound = voices.withLock {
                if (tetiana == null && !tetianaFailed) {
                    _state.update { it.copy(preparing = true) }
                    tetiana = try { TetianaVoice.open(app, TetianaVoice.dictionary(app)) } catch (e: Exception) { tetianaFailed = true; null } catch (e: OutOfMemoryError) { tetianaFailed = true; null }
                }
                // The model runs under the lock: it is never closed while a sentence is being made
                tetiana?.let { v -> try { v.read(text, speed) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
            }
            if (sound != null) return sound
        }
        val sound = voices.withLock {
            if (phone == null && !phoneFailed) {
                phone = withContext(Dispatchers.Main) { withTimeoutOrNull(8000) { PhoneVoice.open(app) } }
                if (phone == null) phoneFailed = true
            }
            phone?.let { p -> try { p.read(text, speed) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
        }
        if (sound == null) _state.update { it.copy(notice = if (lang == "uk") NOTICE_NO_VOICE else NOTICE_NO_LANGUAGE) }
        return sound
    }

    /** Frees the voices (memory); they open again when needed. Waits for a sentence being made to finish. */
    fun release() {
        main.launch(Dispatchers.Default) {
            if (_state.value.active) return@launch
            voices.withLock { tetiana?.close(); tetiana = null; tetianaFailed = false; phone?.close(); phone = null; phoneFailed = false }
        }
    }

    /** Sound output of one reading: its own track, made for the voice's rate, released when the reading ends. */
    private class Output {
        private var track: AudioTrack? = null
        private var rate = TetianaVoice.RATE
        private var written = 0L

        private suspend fun track(r: Int): AudioTrack {
            track?.let { if (r == rate) return it; drain(); it.release() }
            rate = r; written = 0
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            return AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, r))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it; it.play() }
        }

        suspend fun play(sound: Sound) {
            val s = sound.samples
            val all = FloatArray(s.size)
            // A gentle edge: no click where the sound starts and ends
            val fade = minOf(220, s.size / 4)
            for (i in s.indices) {
                val g = when { i < fade -> i / fade.toFloat(); i >= s.size - fade -> (s.size - i) / fade.toFloat(); else -> 1f }
                all[i] = (s[i] * g * 0.95f).coerceIn(-1f, 1f)
            }
            write(all, sound.rate)
            // Shown as it is heard: wait until this sentence has been played, not just handed over
            val t = track ?: return
            val target = written - (rate / 10)
            while (kotlinx.coroutines.currentCoroutineContext().isActive && t.playbackHeadPosition.toLong() and 0xFFFFFFFFL < target) delay(15)
        }

        suspend fun silence(seconds: Float) = write(FloatArray((rate * seconds).toInt()), rate)

        private suspend fun write(data: FloatArray, r: Int) = withContext(Dispatchers.IO) {
            val t = track(r)
            var off = 0
            while (off < data.size && isActive) {
                val n = t.write(data, off, minOf(2048, data.size - off), AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                off += n
            }
            written += off
        }

        /** Waits until what was written has been heard (the end of a reading). */
        suspend fun drain() {
            val t = track ?: return
            while (kotlinx.coroutines.currentCoroutineContext().isActive && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) delay(20)
        }

        fun release() { track?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }; track = null }
    }
}
