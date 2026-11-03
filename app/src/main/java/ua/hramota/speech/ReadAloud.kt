package ua.hramota.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reading a document aloud, sentence by sentence, while the next sentence is already being made. It goes on with
 * the screen off (the service keeps Hramota running and shows the controls); it stops when the document closes.
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
        /** When the sleep timer stops reading (ms, uptime clock), or -1 for the end of the chapter, or null. */
        val sleepAt: Long? = null,
        /** Said when something could not be read (no voice for the language). */
        val notice: Int? = null,
    ) { val active get() = document != null }

    const val HRAMOTA = "hramota"
    const val PHONE = "phone"
    const val SLEEP_CHAPTER = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var doc: Document? = null
    private var job: Job? = null
    private var sleepJob: Job? = null
    private var track: AudioTrack? = null
    private val voices = Mutex()
    private var tetiana: TetianaVoice? = null
    private var tetianaFailed = false
    private var phone: PhoneVoice? = null
    private var phoneFailed = false
    private lateinit var app: Context

    /** A sentence and where it is. */
    private class Spot(val unit: Int, val start: Int, val end: Int, val text: String, val last: Boolean)

    /** Starts reading [document] from [unit] (and the character [from] in it). */
    fun start(context: Context, document: Document, unit: Int, from: Int = 0, speed: Float, voice: String) {
        app = context.applicationContext
        stopPlayback()
        doc = document
        _state.value = State(document.id, document.title, playing = true, unit = unit, start = from, end = from, speed = speed, voice = voice)
        ReadAloudService.show(app)
        play(unit, from)
    }

    fun pause() {
        job?.cancel(); job = null
        track?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        _state.update { it.copy(playing = false, preparing = false) }
    }

    fun resume() { val s = _state.value; if (!s.active || s.playing) return; _state.update { it.copy(playing = true) }; play(s.unit, s.start) }

    fun toggle() { if (_state.value.playing) pause() else resume() }

    /** To the next or the previous sentence. */
    fun skip(by: Int) {
        val s = _state.value; val d = doc ?: return
        val was = s.playing
        job?.cancel(); job = null
        track?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        scope.launch {
            val spot = neighbour(d, s.unit, s.start, by) ?: return@launch
            _state.update { it.copy(unit = spot.unit, start = spot.start, end = spot.end) }
            if (was) play(spot.unit, spot.start)
        }
    }

    fun setSpeed(speed: Float) { _state.update { it.copy(speed = speed) }; restartIfPlaying() }

    fun setVoice(voice: String) { _state.update { it.copy(voice = voice) }; restartIfPlaying() }

    private fun restartIfPlaying() {
        val s = _state.value
        if (!s.playing) return
        job?.cancel(); track?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        play(s.unit, s.start)
    }

    /** Stops reading after [minutes]; 0 turns the timer off; [SLEEP_CHAPTER] stops at the next chapter. */
    fun sleep(minutes: Long) {
        sleepJob?.cancel(); sleepJob = null
        when {
            minutes == 0L -> _state.update { it.copy(sleepAt = null) }
            minutes == SLEEP_CHAPTER -> _state.update { it.copy(sleepAt = SLEEP_CHAPTER) }
            else -> {
                val at = android.os.SystemClock.uptimeMillis() + minutes * 60_000
                _state.update { it.copy(sleepAt = at) }
                sleepJob = scope.launch { delay(minutes * 60_000); pause(); _state.update { it.copy(sleepAt = null) } }
            }
        }
    }

    /** Ends reading (the document closed, or ✕): the controls go. */
    fun stop(documentId: String? = null) {
        if (documentId != null && _state.value.document != documentId) return
        stopPlayback()
        sleepJob?.cancel(); sleepJob = null
        doc = null
        _state.value = State(speed = _state.value.speed, voice = _state.value.voice)
        if (::app.isInitialized) ReadAloudService.hide(app)
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun stopPlayback() {
        job?.cancel(); job = null
        track?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }
        track = null
    }

    /** The sentences of [unit] (and its text). */
    private suspend fun sentences(d: Document, unit: Int): Pair<String, List<Sentences.Span>> {
        val text = try { d.text(unit) } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
        return text to Sentences.split(text)
    }

    /** The sentence [by] steps away from the one at ([unit], [from]). */
    private suspend fun neighbour(d: Document, unit: Int, from: Int, by: Int): Spot? {
        var u = unit
        var (text, spans) = sentences(d, u)
        var k = spans.indexOfLast { it.start <= from }.coerceAtLeast(0) + by
        while (k < 0 || k >= spans.size) {
            if (k < 0) { u--; if (u < 0) return null; val s = sentences(d, u); text = s.first; spans = s.second; k += spans.size }
            else { k -= spans.size; u++; if (u >= d.count()) return null; val s = sentences(d, u); text = s.first; spans = s.second }
        }
        val sp = spans[k]
        return Spot(u, sp.start, sp.end, text.substring(sp.start, sp.end), k == spans.size - 1)
    }

    /** The sentences from ([unit], [from]) on, one after another. */
    private fun sequence(d: Document, unit: Int, from: Int) = kotlinx.coroutines.flow.flow {
        var u = unit; var first = true
        while (u < d.count()) {
            val (text, spans) = sentences(d, u)
            for ((k, sp) in spans.withIndex()) {
                if (first && sp.end <= from) continue
                emit(Spot(u, sp.start, sp.end, text.substring(sp.start, sp.end), k == spans.size - 1))
            }
            first = false
            u++
        }
    }

    private fun play(unit: Int, from: Int) {
        val d = doc ?: return
        job?.cancel()
        job = scope.launch {
            val iterator = kotlinx.coroutines.channels.Channel<Pair<Spot, Sound?>>(capacity = 2)
            // Made ahead: the next sentence is ready when this one ends
            val maker = launch(Dispatchers.Default) {
                sequence(d, unit, from)
                    // With «until the end of the chapter», reading stops before the next chapter's heading
                    .takeWhile { !(_state.value.sleepAt == SLEEP_CHAPTER && it.unit > unit && d.isHeading(it.unit)) }
                    .collect { spot -> iterator.send(spot to make(spot.text)) }
                iterator.close()
            }
            try {
                for ((spot, sound) in iterator) {
                    _state.update { it.copy(unit = spot.unit, start = spot.start, end = spot.end, preparing = false) }
                    if (sound != null) playSound(sound, if (spot.last) 0.55f else 0.28f)
                }
                // The end (of the document, or of the chapter the timer waits for)
                if (isActive) { _state.update { it.copy(playing = false, sleepAt = if (it.sleepAt == SLEEP_CHAPTER) null else it.sleepAt) } }
            } finally { maker.cancel() }
        }
    }

    /** A sentence made into sound by the voice for its language (the person's choice first). */
    private suspend fun make(text: String): Sound? {
        val lang = Sentences.language(text) ?: return null
        val speed = _state.value.speed
        val wantsPhone = _state.value.voice == PHONE
        if (lang == "uk" && !wantsPhone) {
            val v = voices.withLock {
                if (tetiana == null && !tetianaFailed) {
                    _state.update { it.copy(preparing = true) }
                    tetiana = try { TetianaVoice.open(app, TetianaVoice.dictionary(app)) } catch (e: Exception) { tetianaFailed = true; null } catch (e: OutOfMemoryError) { tetianaFailed = true; null }
                }
                tetiana
            }
            if (v != null) return try { v.read(text, speed) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        val p = voices.withLock {
            if (phone == null && !phoneFailed) phone = withContext(Dispatchers.Main) { PhoneVoice.open(app) }.also { if (it == null) phoneFailed = true }
            phone
        }
        val sound = p?.let { try { it.read(text, speed) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
        if (sound == null) _state.update { it.copy(notice = if (lang == "uk") NOTICE_NO_VOICE else NOTICE_NO_LANGUAGE) }
        return sound
    }

    const val NOTICE_NO_VOICE = 1
    const val NOTICE_NO_LANGUAGE = 2

    /** Plays [sound] and a pause after it; returns when it has been heard. */
    private suspend fun playSound(sound: Sound, pause: Float) = withContext(Dispatchers.IO) {
        val t = trackFor(sound.rate)
        val all = FloatArray(sound.samples.size + (sound.rate * pause).toInt())
        // A gentle edge: no click where the sound starts and ends
        val fade = minOf(220, sound.samples.size / 4)
        for (i in sound.samples.indices) {
            val g = when { i < fade -> i / fade.toFloat(); i >= sound.samples.size - fade -> (sound.samples.size - i) / fade.toFloat(); else -> 1f }
            all[i] = (sound.samples[i] * g * 0.95f).coerceIn(-1f, 1f)
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        val before = t.playbackHeadPosition
        var off = 0
        while (off < all.size && isActive) {
            val n = t.write(all, off, minOf(4096, all.size - off), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        // Wait until it has been heard (the sentence shown is the one being said)
        val target = before + off
        while (isActive && t.playbackHeadPosition < target && t.playState == AudioTrack.PLAYSTATE_PLAYING) delay(20)
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate) return it; try { it.release() } catch (_: Exception) {} }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setBufferSizeInBytes(maxOf(min, rate)).setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it }
    }

    /** Frees the voices (memory) when nothing has been read for a while; they open again when needed. */
    fun release() {
        scope.launch { voices.withLock { tetiana?.close(); tetiana = null; tetianaFailed = false; phone?.close(); phone = null; phoneFailed = false } }
    }
}
