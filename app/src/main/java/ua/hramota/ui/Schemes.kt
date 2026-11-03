package ua.hramota.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import ua.hramota.R

/**
 * The colour schemes: Hramota's own «Льон» (flax: pale blue flowers on linen, as the icon's blue) and the colours of
 * pysanky, the same as the Pysanka keyboard's themes: three for the day, three for the night.
 */
class Scheme(val id: String, val name: Int, val dark: Boolean, val colors: ColorScheme)

object Schemes {
    const val DEFAULT = "lon"

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

    /** The one of [a] and [b] that reads better on [bg]. */
    private fun better(bg: Color, a: Color, b: Color) = if (contrast(a, bg) >= contrast(b, bg)) a else b

    /**
     * A whole scheme from a stylesheet's few colours, as the keyboard draws them: [page] the screen,
     * [card] raised things, [band] bars and chips, [edge] lines; text in [ink] and [muted].
     */
    private fun make(
        dark: Boolean, primary: Long, onPrimary: Long, secondary: Long,
        page: Long, card: Long, band: Long, edge: Long, ink: Long, muted: Long,
    ): ColorScheme {
        val bg = Color(page); val text = Color(ink)
        val light = Color.White; val black = Color.Black
        // Accents are also text (buttons, links): kept readable on the page
        val p = readable(Color(primary), bg, if (dark) text else black, 4.8)
        val s = readable(Color(secondary), bg, if (dark) text else black, 4.8)
        val onP = readable(better(p, Color(onPrimary), if (dark) Color(page) else light), p, if (p.luminance() > 0.4f) black else light)
        val onS = readable(better(s, Color(onPrimary), if (dark) Color(page) else light), s, if (s.luminance() > 0.4f) black else light)
        val pc = lerp(Color(primary), bg, if (dark) 0.62f else 0.84f)
        val sc = Color(band)
        val v = Color(card)
        val mutedText = readable(Color(muted), v, text)
        val common = { c: ColorScheme ->
            c.copy(
                primary = p, onPrimary = onP,
                primaryContainer = pc, onPrimaryContainer = readable(lerp(Color(primary), text, 0.55f), pc, text, 7.0),
                secondary = s, onSecondary = onS,
                secondaryContainer = sc, onSecondaryContainer = readable(text, sc, if (dark) light else black, 7.0),
                tertiary = s, onTertiary = onS,
                background = bg, onBackground = text,
                surface = bg, onSurface = text,
                surfaceVariant = v, onSurfaceVariant = mutedText,
                surfaceContainerLowest = bg, surfaceContainerLow = lerp(bg, v, 0.5f), surfaceContainer = lerp(bg, v, 0.6f),
                surfaceContainerHigh = v, surfaceContainerHighest = lerp(v, Color(edge), 0.5f),
                inverseSurface = text, inverseOnSurface = bg, inversePrimary = lerp(Color(primary), if (dark) black else light, 0.4f),
                outline = readable(lerp(Color(edge), text, 0.3f), bg, text, 3.0), outlineVariant = Color(edge),
                surfaceTint = p,
            )
        }
        return common(if (dark) darkColorScheme() else lightColorScheme())
    }

    // Hramota's own: flax-flower blue on linen by day (ink blue for what is read), night blue in the dark
    private val LON_DAY = lightColorScheme(
        primary = Color(0xFF24467A), onPrimary = Color.White,
        primaryContainer = Color(0xFFDDE2F6), onPrimaryContainer = Color(0xFF0E2547),
        secondary = Color(0xFFB4472E), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE6E0D3), onSecondaryContainer = Color(0xFF2B2A26),
        background = Color(0xFFFBF8F2), onBackground = Color(0xFF1C1B19),
        surface = Color(0xFFFBF8F2), onSurface = Color(0xFF1C1B19),
        surfaceVariant = Color(0xFFF0EBE1), onSurfaceVariant = Color(0xFF55524B),
        surfaceContainer = Color(0xFFF4EFE6), outline = Color(0xFF8C8577), outlineVariant = Color(0xFFE2DCD0),
    )
    private val LON_NIGHT = darkColorScheme(
        primary = Color(0xFFAEBFEE), onPrimary = Color(0xFF0E2547),
        primaryContainer = Color(0xFF2C4170), onPrimaryContainer = Color(0xFFDDE2F6),
        secondary = Color(0xFFF0A48F), onSecondary = Color(0xFF4A1607),
        secondaryContainer = Color(0xFF353A43), onSecondaryContainer = Color(0xFFE6E2DA),
        background = Color(0xFF15181D), onBackground = Color(0xFFE6E2DA),
        surface = Color(0xFF15181D), onSurface = Color(0xFFE6E2DA),
        surfaceVariant = Color(0xFF242830), onSurfaceVariant = Color(0xFFB9B4AA),
        surfaceContainer = Color(0xFF1D2026), outline = Color(0xFF6B717B), outlineVariant = Color(0xFF30343B),
    )

    // The colours of the Pysanka keyboard's stylesheets: primary, on-primary, secondary; the page, cards, bars, lines; text and quieter text
    // (by day the keys' light colour is the page, at night the keyboard's dark background is)
    val day: List<Scheme> = listOf(
        Scheme(DEFAULT, R.string.scheme_lon, false, LON_DAY),
        Scheme("kreyda", R.string.scheme_kreyda, false, make(false, 0xFFA3392C, 0xFFFFF8F0, 0xFF7D5A12, 0xFFF8F3EB, 0xFFEBE3D8, 0xFFE6DFD5, 0xFFDAD1C5, 0xFF2B2420, 0xFF5A5048)),
        Scheme("visk", R.string.scheme_visk, false, make(false, 0xFF8A4A2A, 0xFFFFF8EC, 0xFF765010, 0xFFF2E8D4, 0xFFE5D9C0, 0xFFE2D5BC, 0xFFD6C7AA, 0xFF2E2116, 0xFF64503C)),
        Scheme("nebo", R.string.scheme_nebo, false, make(false, 0xFF3D5F94, 0xFFFFFFFF, 0xFFA3392C, 0xFFF1F4F7, 0xFFE1E7EE, 0xFFD9E0E7, 0xFFCCD5DE, 0xFF1E2A3C, 0xFF4A596D)),
    )
    val night: List<Scheme> = listOf(
        Scheme(DEFAULT, R.string.scheme_lon, true, LON_NIGHT),
        Scheme("podillia", R.string.scheme_podillia, true, make(true, 0xFFB03D31, 0xFFFFF6EE, 0xFFC99A3A, 0xFF15110F, 0xFF2A2320, 0xFF3A312C, 0xFF3A312C, 0xFFF1E6D2, 0xFFC2B5A3)),
        Scheme("hutsulshchyna", R.string.scheme_hutsulshchyna, true, make(true, 0xFFC98F3A, 0xFF1A1208, 0xFFD6A64E, 0xFF101A15, 0xFF1E2E25, 0xFF2A3D32, 0xFF2A3D32, 0xFFECE6D6, 0xFFB9BBA8)),
        Scheme("chornozem", R.string.scheme_chornozem, true, make(true, 0xFFB98E3E, 0xFF1A1206, 0xFFB98E3E, 0xFF000000, 0xFF151210, 0xFF241F1B, 0xFF241F1B, 0xFFDED3C2, 0xFFA89C8B)),
    )

    fun dayScheme(id: String) = day.firstOrNull { it.id == id } ?: day.first()
    fun nightScheme(id: String) = night.firstOrNull { it.id == id } ?: night.first()
    val dayIds: Set<String> get() = day.map { it.id }.toSet()
    val nightIds: Set<String> get() = night.map { it.id }.toSet()
}
