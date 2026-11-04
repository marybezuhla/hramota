package ua.hramota.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Contrast helpers for colours drawn on top of a theme (the themes themselves are in ui/theme). */
object Schemes {
    /** Contrast of two colours, 1 to 21 (WCAG). */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return if (la > lb) la / lb else lb / la
    }

    /** [c] moved towards [toward] just enough to read on [bg] (at least [min]:1); the hue stays. */
    internal fun readable(c: Color, bg: Color, toward: Color, min: Double = 4.5): Color {
        var t = 0f
        var r = c
        while (contrast(r, bg) < min && t < 1f) { t += 0.04f; r = lerp(c, toward, t) }
        return r
    }
}
