package ua.hramota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ua.hramota.R
import ua.hramota.data.AppSettings
import ua.hramota.doc.SearchHit
import ua.hramota.doc.book.Book
import ua.hramota.doc.book.BookBlock

/**
 * The colours of the page. Never pure black on pure white (glare over hours of reading) nor white
 * on black (light text spreads in the dark, worse with astigmatism); body text at 7:1 or more.
 */
data class Paper(val name: String, val background: Color, val text: Color, val muted: Color, val dark: Boolean)

val PAPERS = listOf(
    Paper("paper", Color(0xFFFBF8F1), Color(0xFF2A2926), Color(0xFF6B675E), false),
    Paper("sepia", Color(0xFFF4EAD5), Color(0xFF3A2E21), Color(0xFF6E5C45), false),
    Paper("gray", Color(0xFF3B3C3E), Color(0xFFD9D6CF), Color(0xFFB3AFA6), true),
    Paper("night", Color(0xFF1B1A19), Color(0xFFCCC6BA), Color(0xFF8F897E), true),
)

/**
 * The paper of the theme («Тема», the default): the theme's card colour, so a book looks like the rest of the app in
 * every theme, by day and at night. Its text is pushed to 7:1 and its quieter text to 4.5:1, so every theme reads well
 * for hours; at night the raised card colour is used, never pure black.
 */
fun themePaper(scheme: androidx.compose.material3.ColorScheme): Paper {
    val bg = scheme.surface
    val dark = bg.luminance() < 0.5f
    val text = Schemes.readable(scheme.onSurface, bg, if (dark) Color.White else Color.Black, 7.0)
    val muted = Schemes.readable(scheme.onSurfaceVariant, bg, text, 4.5)
    return Paper("auto", bg, text, muted, dark)
}

/** The theme's paper, as the app shows it outside the book (set where the book's own colours begin). */
val LocalThemePaper = androidx.compose.runtime.staticCompositionLocalOf<Paper?> { null }

@Composable
fun paperFor(name: String): Paper = PAPERS.firstOrNull { it.name == name }
    ?: LocalThemePaper.current ?: themePaper(MaterialTheme.colorScheme)

/** The window in the colours of the book's paper, so bars and lists match the page. */
@Composable
fun BookTheme(on: Boolean, settings: AppSettings, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    val themed = themePaper(MaterialTheme.colorScheme)
    val p = PAPERS.firstOrNull { it.name == settings.readPaper } ?: themed
    // Status bar icons readable on the paper
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(p.dark) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = !p.dark
        bars?.isAppearanceLightNavigationBars = !p.dark
        onDispose { if (before != null) { bars.isAppearanceLightStatusBars = before; bars.isAppearanceLightNavigationBars = before } }
    }
    HramotaTheme(dark = p.dark, day = settings.dayScheme, night = settings.nightScheme) {
        val cs = MaterialTheme.colorScheme.copy(
            background = p.background, surface = p.background, onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.muted,
            surfaceContainer = p.background, surfaceContainerLow = p.background, surfaceContainerHigh = p.background,
        )
        MaterialTheme(colorScheme = cs) { androidx.compose.runtime.CompositionLocalProvider(LocalThemePaper provides themed) {
            androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = p.background, contentColor = p.text) { content() }
        } }
    }
}

/** Literata, the typeface made for long reading on screens (as in Google Play Books), with full Ukrainian. */
val Literata = FontFamily(
    Font(R.font.literata_regular),
    Font(R.font.literata_italic, style = FontStyle.Italic),
    Font(R.font.literata_bold, weight = FontWeight.Bold),
    Font(R.font.literata_bold_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

/** How each kind of block is set: type, spacing above and below. Pages and the scroll use the same. */
class BookLook(val settings: AppSettings, val paper: Paper, private val language: String) {
    val family: FontFamily = if (settings.readFont == "sans") FontFamily.SansSerif else Literata
    val margin: Dp = settings.readMargin.dp
    private val bookLang = language.ifEmpty { "uk" }
    val body = TextStyle(
        fontFamily = family, fontSize = settings.readSize.sp, lineHeight = settings.readSpacing.em, color = paper.text,
        textAlign = if (settings.readJustify) TextAlign.Justify else TextAlign.Start,
        hyphens = if (settings.readJustify) Hyphens.Auto else Hyphens.None,
        lineBreak = LineBreak.Paragraph, localeList = LocaleList(bookLang),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /**
     * The block's own style: its language picks the hyphenation patterns, and justified text is used
     * only where the language can be hyphenated (else gaps open between words).
     */
    fun style(block: BookBlock.Text, flush: Boolean): TextStyle {
        val lang = Typography.languageOf(block.text, bookLang)
        val base = kindStyle(block.kind, flush)
        val canHyphenate = Typography.hyphenates(lang)
        return base.copy(
            localeList = LocaleList(lang),
            textAlign = if (base.textAlign == TextAlign.Justify && !canHyphenate) TextAlign.Start else base.textAlign,
            hyphens = if (base.hyphens == Hyphens.Auto && !canHyphenate) Hyphens.None else base.hyphens,
        )
    }

    /** The text as set on the page: the language's rules on where a line may not break. */
    fun text(block: BookBlock.Text, hits: List<SearchHit>?, current: SearchHit?, marks: List<Triple<Int, Int, Color>> = emptyList()): AnnotatedString =
        styledBlock(block, Typography.typeset(block.text, Typography.languageOf(block.text, bookLang)), hits, current, marks)

    private fun kindStyle(kind: BookBlock.Kind, flush: Boolean): TextStyle = when (kind) {
        BookBlock.Kind.HEADING1 -> body.copy(fontSize = body.fontSize * 1.45f, lineHeight = 1.25.em, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, hyphens = Hyphens.None)
        BookBlock.Kind.HEADING2 -> body.copy(fontSize = body.fontSize * 1.22f, lineHeight = 1.3.em, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, hyphens = Hyphens.None)
        BookBlock.Kind.HEADING3 -> body.copy(fontSize = body.fontSize * 1.08f, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start, hyphens = Hyphens.None)
        BookBlock.Kind.SUBTITLE -> body.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        BookBlock.Kind.QUOTE -> body.copy(fontSize = body.fontSize * 0.92f, fontStyle = FontStyle.Italic, color = paper.muted.copy(alpha = 1f).let { lerpColor(paper.text, it, 0.35f) })
        BookBlock.Kind.POEM -> body.copy(textAlign = TextAlign.Start, hyphens = Hyphens.None)
        BookBlock.Kind.CONTINUED -> body
        // A paragraph after a heading, a picture or a quote starts flush, as in print
        else -> if (flush) body else body.copy(textIndent = TextIndent(firstLine = 1.5.em))
    }

    /** Space above and below, and the indent from the left. */
    fun space(kind: BookBlock.Kind): Triple<Dp, Dp, Dp> {
        val line = (settings.readSize * settings.readSpacing).dp
        return when (kind) {
            BookBlock.Kind.HEADING1 -> Triple(line * 2, line, 0.dp)
            BookBlock.Kind.HEADING2 -> Triple(line * 1.2f, line * 0.6f, 0.dp)
            BookBlock.Kind.HEADING3 -> Triple(line * 0.8f, line * 0.3f, 0.dp)
            BookBlock.Kind.SUBTITLE -> Triple(line * 0.5f, line * 0.5f, 0.dp)
            BookBlock.Kind.QUOTE -> Triple(2.dp, 2.dp, 28.dp)
            BookBlock.Kind.POEM -> Triple(0.dp, 0.dp, 32.dp)
            BookBlock.Kind.BREAK -> Triple(line * 0.5f, 0.dp, 0.dp)
            else -> Triple(0.dp, 0.dp, 0.dp)
        }
    }

    companion object {
        /** The language a book is written in, or a guess from its letters (і, ї, є, ґ are Ukrainian). */
        fun languageOf(book: Book): String {
            book.language.trim().takeIf { it.isNotEmpty() && it.length <= 20 }?.let { return it.replace('_', '-') }
            var cyr = 0; var uk = 0; var ru = 0; var n = 0
            for (b in book.blocks) {
                val t = (b as? BookBlock.Text)?.text ?: continue
                for (c in t) {
                    if (c in 'а'..'я' || c in 'А'..'Я') cyr++
                    when (c) { 'і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ' -> uk++; 'ы', 'э', 'ъ', 'ё' -> ru++ }
                }
                if (++n > 200) break
            }
            return when { cyr == 0 -> "en"; uk >= ru -> "uk"; else -> "ru" }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** The block's text with its bold, italic and raised parts, and found matches marked. */
internal fun styledBlock(b: BookBlock.Text, shown: String, hits: List<SearchHit>?, current: SearchHit?, marks: List<Triple<Int, Int, Color>> = emptyList()): AnnotatedString {
    if (b.spans.isEmpty() && hits.isNullOrEmpty() && marks.isEmpty()) return AnnotatedString(shown)
    return buildAnnotatedString {
        append(shown)
        for ((s0, e0, c) in marks) {
            val a = s0.coerceIn(0, shown.length); val e = e0.coerceIn(a, shown.length)
            if (a < e) addStyle(SpanStyle(background = c), a, e)
        }
        for (s in b.spans) {
            val a = s.start.coerceIn(0, b.text.length); val e = s.end.coerceIn(a, b.text.length)
            if (a < e) addStyle(SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                baselineShift = if (s.raised) BaselineShift.Superscript else null,
                fontSize = if (s.raised) 0.7.em else TextUnit.Unspecified,
            ), a, e)
        }
        hits?.forEach { h ->
            val a = h.start.coerceIn(0, b.text.length); val e = h.end.coerceIn(a, b.text.length)
            if (a < e) addStyle(SpanStyle(background = if (h == current) Color(0xCCFF9800) else Color(0x80FFD54F)), a, e)
        }
    }
}

/**
 * Typesetting rules by language. Only spaces become no-break spaces, so the text keeps its length
 * and found matches, styles and selection stay where they were.
 */
object Typography {
    // Languages whose hyphenation patterns Android carries
    private val HYPHENATED = setOf(
        "uk", "en", "de", "fr", "es", "it", "pt", "nl", "da", "sv", "nb", "nn", "no", "cs", "sk", "sl", "hr", "hu",
        "lt", "lv", "et", "be", "bg", "ru", "el", "hy", "ka", "la", "eu", "cy", "ga", "sq", "mn", "af", "tk",
    )
    fun hyphenates(lang: String) = lang.substringBefore('-').lowercase() in HYPHENATED

    /** The paragraph's language: the book's, unless the paragraph is plainly in another script. */
    fun languageOf(text: String, book: String): String {
        var cyr = 0; var lat = 0; var uk = 0; var ru = 0
        val n = minOf(text.length, 400)
        for (i in 0 until n) {
            val c = text[i]
            when {
                c in '\u0400'..'\u04FF' -> { cyr++; when (c) { 'і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ' -> uk++; 'ы', 'э', 'ъ', 'ё', 'Ы', 'Э', 'Ъ', 'Ё' -> ru++ } }
                c in 'a'..'z' || c in 'A'..'Z' -> lat++
            }
        }
        val bookCyrillic = book.substringBefore('-').lowercase() in setOf("uk", "ru", "be", "bg", "sr", "mk")
        return when {
            cyr + lat < 12 -> book
            bookCyrillic && lat > cyr * 3 -> "en"
            !bookCyrillic && cyr > lat * 3 -> if (ru > uk) "ru" else "uk"
            else -> book
        }
    }

    private val UK_SHORT = setOf("і", "й", "в", "у", "з", "а", "о", "та", "не", "на", "до", "за", "по", "від", "із", "зі", "чи", "ні", "же", "ж", "б", "би", "к")

    fun typeset(text: String, lang: String): String {
        val l = lang.substringBefore('-').lowercase()
        val out = text.toCharArray()
        fun wordBefore(i: Int): String { var a = i; while (a > 0 && out[a - 1].isLetter()) a--; return String(out, a, i - a) }
        fun startsWord(i: Int) = i == 0 || !out[i - 1].isLetterOrDigit()
        for (i in out.indices) {
            if (out[i] != ' ') continue
            val next = out.getOrNull(i + 1) ?: continue
            val before = wordBefore(i)
            val keep = when {
                // A dash never begins a line
                next == '—' || next == '–' -> true
                // A number stays with what it counts
                i > 0 && out[i - 1].isDigit() && next.isLowerCase() -> true
                // An initial stays with the name: "J. R. Smith", "Т. Шевченко"
                i >= 2 && out[i - 1] == '.' && out[i - 2].isUpperCase() && (i < 3 || !out[i - 3].isLetter()) -> true
                // Ukrainian (and Russian, Belarusian): short words go with the next word
                (l == "uk" || l == "ru" || l == "be") && before.isNotEmpty() && startsWord(i - before.length) && before.lowercase() in UK_SHORT -> true
                else -> false
            }
            if (keep) out[i] = '\u00A0'
        }
        return String(out)
    }
}
