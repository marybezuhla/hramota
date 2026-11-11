package ua.hramota.ui.theme

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.R

/*
 * The look shared by all the apps (the same as Komora's): Material colours from a theme, the type a size larger than
 * Android's, the embroidered motif, and the theme picker with small pictures.
 */

/** Material colours from one of the themes; the phone's own (Material You) for the system ones. */
fun colorScheme(theme: Theme, context: Context): ColorScheme {
    val p = theme.palette
        ?: return if (theme.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return paletteScheme(p, theme.dark)
}

/** Material colours from a palette. */
fun paletteScheme(p: Palette, dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(p.primary),
        onPrimary = c(p.onPrimary),
        primaryContainer = c(mix(p.surface, p.primary, 0.2)),
        onPrimaryContainer = c(p.onSurface),
        secondary = c(p.secondary),
        onSecondary = c(p.onPrimary),
        secondaryContainer = c(mix(p.surface, p.secondary, 0.25)),
        onSecondaryContainer = c(p.onSurface),
        tertiary = c(p.secondary),
        onTertiary = c(p.onPrimary),
        background = c(p.background),
        onBackground = c(p.onSurface),
        // Cards are the lighter (or, at night, raised) surface; the top bar sits on the background
        surface = c(p.surface),
        onSurface = c(p.onSurface),
        surfaceVariant = c(p.surfaceVariant),
        onSurfaceVariant = c(p.onSurfaceVariant),
        surfaceContainerLowest = c(p.surface),
        surfaceContainerLow = c(p.surface),
        surfaceContainer = c(p.surface),
        surfaceContainerHigh = c(p.surface),
        surfaceContainerHighest = c(p.surface),
        surfaceBright = c(p.surface),
        surfaceDim = c(p.background),
        outline = c(p.onSurfaceVariant),
        outlineVariant = c(mix(p.surfaceVariant, p.onSurfaceVariant, 0.3)),
        error = c(p.error),
    )
}

/** The scheme as the app shows it: descriptions and small print darker (lighter at night) than Android's grey. */
fun shownScheme(theme: Theme, context: Context): ColorScheme =
    colorScheme(theme, context).let { it.copy(onSurfaceVariant = lerp(it.onSurfaceVariant, it.onSurface, 0.45f)) }

/**
 * Text a size larger than Android's defaults, so nothing is tiny; Android's own font size setting still scales all of
 * it. Body text left-aligned, whole words (no hyphens), lines broken evenly over the paragraph.
 */
val SharedType = androidx.compose.material3.Typography().run {
    fun body(t: TextStyle, size: Int, line: Int) = t.copy(
        fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp,
        textAlign = TextAlign.Start, lineBreak = LineBreak.Paragraph, hyphens = Hyphens.None,
    )
    // Labels and titles: the letters at their natural spacing (Material spreads them apart)
    fun label(t: TextStyle, size: Int, line: Int) = t.copy(fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp)
    copy(
        bodyLarge = body(bodyLarge, 18, 26),
        bodyMedium = body(bodyMedium, 17, 24),
        bodySmall = body(bodySmall, 15, 21),
        labelLarge = label(labelLarge, 16, 22),
        labelMedium = label(labelMedium, 15, 20),
        labelSmall = label(labelSmall, 14, 18),
        titleSmall = label(titleSmall, 16, 22),
        titleMedium = label(titleMedium, 19, 26),
        titleLarge = label(titleLarge, 24, 30),
    )
}

/**
 * A small embroidered motif under a title: three rhombuses in the middle (the big one in the theme's accent, the small
 * ones in its second colour) between two thin lines that fade out to the sides. Decoration only: hidden from TalkBack.
 */
@Composable
fun Ornament(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.secondary
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { }) {
        val mid = size.height / 2f
        val cx = size.width / 2f
        val big = size.height * 0.5f
        val small = big * 0.62f
        val gap = big * 2.6f
        fun rhombus(x: Float, r: Float, color: Color) = drawPath(
            Path().apply { moveTo(x, mid - r); lineTo(x + r, mid); lineTo(x, mid + r); lineTo(x - r, mid); close() },
            color,
        )
        rhombus(cx - gap, small, second)
        rhombus(cx, big, accent)
        rhombus(cx + gap, small, second)
        val inner = gap + small + big * 1.4f
        val reach = minOf(size.width / 2f - inner, 140.dp.toPx())
        if (reach > 0f) {
            val stroke = 1.dp.toPx()
            drawLine(Brush.horizontalGradient(listOf(line.copy(alpha = 0f), line), cx - inner - reach, cx - inner), Offset(cx - inner - reach, mid), Offset(cx - inner, mid), stroke)
            drawLine(Brush.horizontalGradient(listOf(line, line.copy(alpha = 0f)), cx + inner, cx + inner + reach), Offset(cx + inner, mid), Offset(cx + inner + reach, mid), stroke)
        }
    }
}

@Composable
fun themeName(theme: Theme): String = stringResource(
    when (theme) {
        Theme.LINEN -> R.string.theme_linen
        Theme.VISK -> R.string.theme_visk
        Theme.NEBO -> R.string.theme_nebo
        Theme.GLYNA -> R.string.theme_glyna
        Theme.KALYNA -> R.string.theme_kalyna
        Theme.ZORIANA_NICH -> R.string.theme_zoriana_nich
        Theme.SYSTEM -> R.string.theme_phone
        Theme.MAK -> R.string.theme_mak
        Theme.SMEREKA -> R.string.theme_smereka
        Theme.CHORNOZEM -> R.string.theme_chornozem
        Theme.ZHAR -> R.string.theme_zhar
        Theme.SYSTEM_DARK -> R.string.theme_phone
    },
)

/** A pill split into choices; the chosen one is raised in the card colour (and framed). Read as radio buttons. */
@Composable
fun <T> Segments(options: List<Pair<T, String>>, chosen: T, modifier: Modifier = Modifier, onChoose: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(50)).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val selected = value == chosen
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (selected) scheme.surface else Color.Transparent, RoundedCornerShape(50))
                    .border(1.dp, if (selected) scheme.outline else Color.Transparent, RoundedCornerShape(50))
                    .selectable(selected, role = Role.RadioButton) { onChoose(value) }
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** All themes at once, in rows across the whole width: none hides behind the edge of the screen. */
@Composable
fun ThemeRow(themes: List<Theme>, chosen: Theme, onChoose: (Theme) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        // Rows of exactly three, each across the whole width: a flowing row could push the third onto the next line
        Column(
            Modifier.fillMaxWidth().selectableGroup().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in themes.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (theme in row) {
                        val selected = theme == chosen
                        Column(Modifier.weight(1f).selectable(selected, role = Role.RadioButton) { onChoose(theme) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Sample(theme, selected)
                            ThemeName(themeName(theme), selected)
                        }
                    }
                    // A short last row keeps the same sizes
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A theme's name under its picture, as in Komora: never broken inside a word («Viburnu / m» on a narrow phone with
 * large text). One word per line at most; a word that does not fit gets a smaller size instead.
 */
@Composable
private fun ThemeName(name: String, selected: Boolean) {
    val style = MaterialTheme.typography.bodyMedium.merge(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    // One word per line: any other break overflows, so the text shrinks instead
    androidx.compose.foundation.text.BasicText(
        name.replace(' ', '\n'),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { contentDescription = name },
        style = style,
        maxLines = name.split(' ').size,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** A tiny app: the background, one card with two lines of text and a button, in the theme's colours. */
@Composable
private fun Sample(theme: Theme, selected: Boolean) {
    val context = LocalContext.current
    val s = remember(theme) { colorScheme(theme, context) }
    val frame = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    // A picture only: a screen reader reads the theme's name below it
    Surface(shape = RoundedCornerShape(12.dp), border = frame, color = s.background, modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Box(Modifier.padding(8.dp)) {
            Column(
                Modifier.fillMaxWidth().background(s.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Bar(s.onSurface, 0.7f, 6)
                Bar(s.onSurfaceVariant, 0.9f, 4)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(36.dp).height(14.dp).background(s.primary, RoundedCornerShape(7.dp)))
                    Box(Modifier.width(14.dp).height(14.dp).background(s.secondary, RoundedCornerShape(7.dp)))
                }
            }
        }
    }
}

@Composable
private fun Bar(color: Color, fraction: Float, height: Int) {
    Box(Modifier.fillMaxWidth(fraction).height(height.dp).background(color, RoundedCornerShape(height.dp / 2)))
}
