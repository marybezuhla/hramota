package ua.hramota.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.data.Settings
import ua.hramota.ui.theme.Theme
import ua.hramota.ui.theme.paletteScheme
import ua.hramota.ui.theme.contrast
import ua.hramota.ui.theme.mix

/** The themes shared by all the apps read well (WCAG AA), and a book on a theme's paper reads at 7:1 (AAA). */
class SchemesTest {
    private val themes = Theme.entries.filter { it.palette != null }

    @Test fun textIsReadable() {
        val errors = ArrayList<String>()
        for (t in themes) {
            val p = t.palette!!
            val pairs = mapOf(
                "onSurface/surface" to (p.onSurface to p.surface),
                "onSurface/background" to (p.onSurface to p.background),
                "onSurfaceVariant/surface" to (p.onSurfaceVariant to p.surface),
                "onSurfaceVariant/background" to (p.onSurfaceVariant to p.background),
                "onSurfaceVariant/surfaceVariant" to (p.onSurfaceVariant to p.surfaceVariant),
                "onPrimary/primary" to (p.onPrimary to p.primary),
                "primary/surface" to (p.primary to p.surface),
                "primary/background" to (p.primary to p.background),
                "error/surface" to (p.error to p.surface),
                "onSurface/primaryContainer" to (p.onSurface to mix(p.surface, p.primary, 0.2)),
                "onSurface/secondaryContainer" to (p.onSurface to mix(p.surface, p.secondary, 0.25)),
            )
            for ((name, c) in pairs) contrast(c.first, c.second).let { if (it < 4.5) errors += "${t.key} $name %.2f".format(it) }
            if (contrast(p.secondary, p.background) < 3.0) errors += "${t.key} secondary/background"
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun booksReadWellOnEveryThemesPaper() {
        val errors = ArrayList<String>()
        for (t in themes) {
            val paper = themePaper(paletteScheme(t.palette!!, t.dark))
            val body = Schemes.contrast(paper.text, paper.background)
            val quiet = Schemes.contrast(paper.muted, paper.background)
            if (body < 7.0) errors += "${t.key} body %.2f".format(body)
            if (quiet < 4.5) errors += "${t.key} quiet %.2f".format(quiet)
            if (paper.dark != t.dark) errors += "${t.key} light or dark the wrong way"
            if (paper.background == Color.Black) errors += "${t.key} pure black paper"
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun daysAndNightsInPairs() {
        assertTrue(Theme.DAY.none { it.dark })
        assertTrue(Theme.NIGHT.all { it.dark })
        assertEquals(Theme.DAY.size, Theme.NIGHT.size)
        assertEquals(Theme.ZORIANA_NICH, Theme.pairOf(Theme.NEBO))
        assertEquals(Theme.SYSTEM, Theme.DAY.first())
        assertEquals(Theme.SYSTEM_DARK, Theme.NIGHT.first())
    }

    @Test fun oldSettingsBecomeNewThemes() {
        // Hramota's own «Льон» and the wallpaper switch were before 0.10: both are the phone's colours now
        assertEquals("system", Settings.dayTheme("lon"))
        assertEquals("system_dark", Settings.nightTheme("lon"))
        assertEquals("system", Settings.dayTheme("kreyda", wallpaper = true))
        assertEquals("kreyda", Settings.dayTheme("kreyda"))
        assertEquals("podillia", Settings.nightTheme("podillia"))
        assertEquals("system", Settings.dayTheme(null))
        // A night theme is never used by day
        assertEquals("system", Settings.dayTheme("zhar"))
    }
}
