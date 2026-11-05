package ua.hramota.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's settings, kept only on this phone. */
data class AppSettings(
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Open DOCX in the reading view instead of pages. */
    val docxReading: Boolean = false,
    /** Show what a document carries (macros, links, author…). */
    val inspector: Boolean = true,
    /** Keep a list of recently opened files. */
    val rememberRecent: Boolean = true,
    /** Hide the app's content from screenshots and the recent-apps preview. */
    val secureScreen: Boolean = false,
    /** The themes by day and at night (ui/theme/Palettes.kt): the phone's own colours unless one is chosen. */
    val dayScheme: String = "system",
    val nightScheme: String = "system_dark",
    /** The old wooden bookshelf instead of the modern one. */
    val woodShelf: Boolean = false,
    /** Books: paper colour ("auto" follows the app's theme: paper by day, night in the dark). */
    val readPaper: String = "auto",
    /** Books: "serif" (Literata) or "sans". */
    val readFont: String = "serif",
    /** Books: text size in sp, line height (times the size), side margin in dp. */
    val readSize: Float = 19f,
    val readSpacing: Float = 1.5f,
    val readMargin: Float = 24f,
    /** Books: justified with hyphens, or ragged right. */
    val readJustify: Boolean = true,
    /** Books: pages to turn, or one long scroll. */
    val readPaged: Boolean = true,
    /** Books: how a page turns: "slide", "curl" or "none". */
    val readTurn: String = "slide",
    /** Books: the volume keys turn pages. */
    val readVolumeKeys: Boolean = true,
    /** Size of text in the reading view and plain text (times the usual). */
    val textScale: Float = 1f,
    /** Reading aloud: Hramota's own voice ("hramota") or the phone's ("phone"), and the speed (times the usual). */
    val readVoice: String = "hramota",
    val readSpeed: Float = 1f,
    /** Each document in its own window (a card in Recents); otherwise inside Hramota's window. */
    val separateWindows: Boolean = false,
    /** The name put on Word comments written here (empty: none). */
    val commentAuthor: String = "",
)

/** One instance per process; screens observe [state] and change it with [update]. */
class Settings private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = flow.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val before = flow.value
        val s = change(before)
        if (s.separateWindows != before.separateWindows) ua.hramota.ViewerActivity.syncEntry(appContext, s.separateWindows)
        prefs.edit()
            .putString("theme", s.theme)
            .putBoolean("docxReading", s.docxReading)
            .putBoolean("inspector", s.inspector)
            .putBoolean("rememberRecent", s.rememberRecent)
            .putBoolean("secureScreen", s.secureScreen)
            .remove("dynamicColor")
            .putString("dayScheme", s.dayScheme).putString("nightScheme", s.nightScheme)
            .putBoolean("woodShelf", s.woodShelf)
            .putString("readPaper", s.readPaper).putString("readFont", s.readFont)
            .putFloat("readSize", s.readSize).putFloat("readSpacing", s.readSpacing).putFloat("readMargin", s.readMargin)
            .putBoolean("readJustify", s.readJustify).putBoolean("readPaged", s.readPaged)
            .putString("readTurn", s.readTurn).putBoolean("readVolumeKeys", s.readVolumeKeys)
            .putString("commentAuthor", s.commentAuthor)
            .putBoolean("separateWindows", s.separateWindows)
            .putFloat("textScale", s.textScale)
            .putString("readVoice", s.readVoice).putFloat("readSpeed", s.readSpeed)
            .apply()
        flow.value = s
    }

    /** The settings as JSON, for a backup. */
    fun toJson(): org.json.JSONObject {
        val s = flow.value
        return org.json.JSONObject().apply {
            put("theme", s.theme); put("docxReading", s.docxReading); put("inspector", s.inspector); put("rememberRecent", s.rememberRecent)
            put("secureScreen", s.secureScreen)
            put("dayScheme", s.dayScheme); put("nightScheme", s.nightScheme)
            put("readPaper", s.readPaper); put("readFont", s.readFont); put("readSize", s.readSize.toDouble()); put("readSpacing", s.readSpacing.toDouble())
            put("readMargin", s.readMargin.toDouble()); put("readJustify", s.readJustify); put("readPaged", s.readPaged); put("readTurn", s.readTurn); put("readVolumeKeys", s.readVolumeKeys)
            put("commentAuthor", s.commentAuthor)
            put("separateWindows", s.separateWindows)
            put("textScale", s.textScale.toDouble())
            put("readVoice", s.readVoice); put("readSpeed", s.readSpeed.toDouble())
        }
    }

    /** Settings from a backup, each checked as when read from this phone. */
    fun restore(o: org.json.JSONObject) = update { s ->
        fun f(k: String, d: Float, r: ClosedFloatingPointRange<Float>) = o.optDouble(k, d.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(r) ?: d
        s.copy(
            theme = o.optString("theme", s.theme).takeIf { it in setOf("system", "light", "dark") } ?: s.theme,
            docxReading = o.optBoolean("docxReading", s.docxReading), inspector = o.optBoolean("inspector", s.inspector),
            rememberRecent = o.optBoolean("rememberRecent", s.rememberRecent), secureScreen = o.optBoolean("secureScreen", s.secureScreen),
            dayScheme = dayTheme(o.optString("dayScheme", s.dayScheme), o.optBoolean("dynamicColor", false)),
            nightScheme = nightTheme(o.optString("nightScheme", s.nightScheme), o.optBoolean("dynamicColor", false)),
            readPaper = o.optString("readPaper", s.readPaper).takeIf { it in PAPERS } ?: s.readPaper,
            readFont = if (o.optString("readFont", s.readFont) == "sans") "sans" else "serif",
            readSize = f("readSize", s.readSize, 12f..40f), readSpacing = f("readSpacing", s.readSpacing, 1.1f..2.2f), readMargin = f("readMargin", s.readMargin, 8f..64f),
            readJustify = o.optBoolean("readJustify", s.readJustify), readPaged = o.optBoolean("readPaged", s.readPaged),
            readTurn = o.optString("readTurn", s.readTurn).takeIf { it in setOf("slide", "curl", "none") } ?: s.readTurn,
            readVolumeKeys = o.optBoolean("readVolumeKeys", s.readVolumeKeys),
            commentAuthor = o.optString("commentAuthor", s.commentAuthor).take(100),
            separateWindows = o.optBoolean("separateWindows", s.separateWindows),
            textScale = f("textScale", s.textScale, 0.8f..2.2f),
            readVoice = o.optString("readVoice", s.readVoice).takeIf { it == "hramota" || it == "phone" } ?: s.readVoice,
            readSpeed = f("readSpeed", s.readSpeed, 0.5f..2f),
        )
    }

    private fun load() = AppSettings(
        theme = prefs.getString("theme", "system").takeIf { it in setOf("system", "light", "dark") } ?: "system",
        docxReading = prefs.getBoolean("docxReading", false),
        inspector = prefs.getBoolean("inspector", true),
        rememberRecent = prefs.getBoolean("rememberRecent", true),
        secureScreen = prefs.getBoolean("secureScreen", false),
        dayScheme = dayTheme(prefs.getString("dayScheme", null), prefs.getBoolean("dynamicColor", false)),
        nightScheme = nightTheme(prefs.getString("nightScheme", null), prefs.getBoolean("dynamicColor", false)),
        woodShelf = prefs.getBoolean("woodShelf", false),
        readPaper = prefs.getString("readPaper", "auto").takeIf { it in PAPERS } ?: "auto",
        readFont = if (prefs.getString("readFont", "serif") == "sans") "sans" else "serif",
        readSize = prefs.getFloat("readSize", 19f).coerceIn(12f, 40f),
        readSpacing = prefs.getFloat("readSpacing", 1.5f).coerceIn(1.1f, 2.2f),
        readMargin = prefs.getFloat("readMargin", 24f).coerceIn(8f, 64f),
        readJustify = prefs.getBoolean("readJustify", true),
        readPaged = prefs.getBoolean("readPaged", true),
        readTurn = prefs.getString("readTurn", "slide").takeIf { it in setOf("slide", "curl", "none") } ?: "slide",
        readVolumeKeys = prefs.getBoolean("readVolumeKeys", true),
        commentAuthor = prefs.getString("commentAuthor", "").orEmpty().take(100),
        separateWindows = prefs.getBoolean("separateWindows", false),
        textScale = prefs.getFloat("textScale", 1f).takeIf { it.isFinite() }?.coerceIn(0.8f, 2.2f) ?: 1f,
        readVoice = prefs.getString("readVoice", "hramota").takeIf { it == "hramota" || it == "phone" } ?: "hramota",
        readSpeed = prefs.getFloat("readSpeed", 1f).takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
    )

    companion object {
        val PAPERS = setOf("auto", "paper", "sepia", "gray", "night")
        val DAY_SCHEMES: Set<String> = ua.hramota.ui.theme.Theme.DAY.map { it.key }.toSet()
        val NIGHT_SCHEMES: Set<String> = ua.hramota.ui.theme.Theme.NIGHT.map { it.key }.toSet()

        /**
         * The day theme from what was saved. Before 0.10 there was Hramota's own «Льон» (the default) and a separate
         * switch for the wallpaper's colours: both become the phone's colours, the default of all the apps now.
         */
        fun dayTheme(saved: String?, wallpaper: Boolean = false): String =
            if (wallpaper) "system" else saved?.takeIf { it in DAY_SCHEMES } ?: "system"
        fun nightTheme(saved: String?, wallpaper: Boolean = false): String =
            if (wallpaper) "system_dark" else saved?.takeIf { it in NIGHT_SCHEMES } ?: "system_dark"
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) { instance ?: Settings(context).also { instance = it } }
    }
}
