package ua.hramota.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.data.Settings

/** Every colour scheme reads well: text, accents, buttons and highlighted things (WCAG AA, body text AAA). */
class SchemesTest {
    private fun check(errors: MutableList<String>, scheme: String, what: String, fg: Color, bg: Color, min: Double) {
        val c = Schemes.contrast(fg, bg)
        if (c < min) errors += "$scheme: $what %.2f < $min".format(c)
    }

    @Test fun everySchemeReadsWell() {
        val errors = ArrayList<String>()
        for (s in Schemes.day + Schemes.night) {
            val c = s.colors
            val n = s.id + if (s.dark) " (night)" else " (day)"
            check(errors, n, "text", c.onBackground, c.background, 7.0)
            check(errors, n, "text on surface", c.onSurface, c.surface, 7.0)
            check(errors, n, "quiet text", c.onSurfaceVariant, c.surfaceVariant, 4.5)
            check(errors, n, "quiet text on the page", c.onSurfaceVariant, c.background, 4.5)
            check(errors, n, "accent text", c.primary, c.background, 4.5)
            check(errors, n, "accent on cards", c.primary, c.surfaceVariant, 3.0)
            check(errors, n, "button text", c.onPrimary, c.primary, 4.5)
            check(errors, n, "second accent", c.secondary, c.background, 4.5)
            check(errors, n, "on second accent", c.onSecondary, c.secondary, 4.5)
            check(errors, n, "highlighted", c.onPrimaryContainer, c.primaryContainer, 4.5)
            check(errors, n, "chips", c.onSecondaryContainer, c.secondaryContainer, 4.5)
            check(errors, n, "lines", c.outline, c.background, 3.0)
            check(errors, n, "errors", c.error, c.background, 4.5)
            // Day schemes are light, night ones dark (the status bar icons follow)
            if (s.dark != (c.background.red + c.background.green + c.background.blue < 1.5f)) errors += "$n: light or dark the wrong way"
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun settingsKnowTheSameSchemes() {
        assertEquals(Schemes.dayIds, Settings.DAY_SCHEMES)
        assertEquals(Schemes.nightIds, Settings.NIGHT_SCHEMES)
        assertEquals(Schemes.DEFAULT, Schemes.day.first().id)
        assertEquals(Schemes.DEFAULT, Schemes.night.first().id)
    }
}
