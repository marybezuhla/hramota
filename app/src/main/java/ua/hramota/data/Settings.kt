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
    /** Colours from the wallpaper (Material You) instead of Hramota's own. */
    val dynamicColor: Boolean = false,
)

/** One instance per process; screens observe [state] and change it with [update]. */
class Settings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = flow.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(flow.value)
        prefs.edit()
            .putString("theme", s.theme)
            .putBoolean("docxReading", s.docxReading)
            .putBoolean("inspector", s.inspector)
            .putBoolean("rememberRecent", s.rememberRecent)
            .putBoolean("secureScreen", s.secureScreen)
            .putBoolean("dynamicColor", s.dynamicColor)
            .apply()
        flow.value = s
    }

    private fun load() = AppSettings(
        theme = prefs.getString("theme", "system").takeIf { it in setOf("system", "light", "dark") } ?: "system",
        docxReading = prefs.getBoolean("docxReading", false),
        inspector = prefs.getBoolean("inspector", true),
        rememberRecent = prefs.getBoolean("rememberRecent", true),
        secureScreen = prefs.getBoolean("secureScreen", false),
        dynamicColor = prefs.getBoolean("dynamicColor", false),
    )

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) { instance ?: Settings(context).also { instance = it } }
    }
}
