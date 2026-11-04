package ua.hramota.speech

import android.content.Context
import java.io.File

/**
 * eSpeak NG, used only to turn English text into phonemes for the English voice (it was trained on them). Its
 * language data comes with the app and is put in place once, next to Hramota's other files.
 */
internal object Espeak {
    private const val DATA = "voice/espeak-ng-data"
    private const val VERSION = "1.52.0-en-1"
    @Volatile private var started = false

    @Synchronized
    fun start(context: Context): Boolean {
        if (started) return true
        System.loadLibrary("hramota_speech")
        val root = File(context.filesDir, "espeak")
        val mark = File(root, "version")
        if (!mark.exists() || mark.readText() != VERSION) {
            // Copied beside, written to the disk, then put in place whole: a copy cut short is never used
            val fresh = File(context.filesDir, "espeak.new")
            fresh.deleteRecursively()
            copy(context, DATA, File(fresh, "espeak-ng-data"))
            java.io.FileOutputStream(File(fresh, "version")).use { it.write(VERSION.toByteArray()); it.fd.sync() }
            root.deleteRecursively()
            if (!fresh.renameTo(root)) return false
        }
        started = nativeInit(root.absolutePath) > 0
        return started
    }

    private fun copy(context: Context, from: String, to: File) {
        val names = context.assets.list(from).orEmpty()
        if (names.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(from).use { i -> java.io.FileOutputStream(to).use { o -> i.copyTo(o); o.fd.sync() } }
            return
        }
        to.mkdirs()
        for (n in names) copy(context, "$from/$n", File(to, n))
    }

    /** The IPA phonemes of [text] in eSpeak's [voice] (one call at a time: eSpeak keeps state). */
    @Synchronized
    fun phonemes(text: String, voice: String): String? = if (!started) null else nativePhonemes(text, voice)

    @JvmStatic private external fun nativeInit(path: String): Int
    @JvmStatic private external fun nativePhonemes(text: String, voice: String): String?
}
