package ua.hramota.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.hramota.data.AppSettings
import ua.hramota.data.Settings

/** Hides the window from screenshots and the recent-apps preview when the setting is on. */
fun ComponentActivity.applySecureScreen(on: Boolean = Settings.get(this).state.value.secureScreen) {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/** The theme chosen in settings, applied to a whole window; changes take effect at once. */
@Composable
fun ComponentActivity.AppTheme(content: @Composable (AppSettings) -> Unit) {
    val settings by Settings.get(this).state.collectAsStateWithLifecycle()
    SideEffect { applySecureScreen(settings.secureScreen) }
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    HramotaTheme(dark = dark) { content(settings) }
}
