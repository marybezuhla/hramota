package ua.hramota.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.doc.Paragraph

/*
 * Covers drawn for documents with no picture of their own. The colour says the kind of file (Word blue, tables green,
 * PDF red, presentations orange, books violet); the ornament, in the spirit of pysanky, comes from the title: the same
 * title always gets the same pattern. The title is set as on a book: in the middle, never broken inside a word.
 */

/** The cover colour of a kind of file. */
fun coverColor(format: String): Color = when (format.uppercase()) {
    "PDF" -> Color(0xFF9C2F2F)
    "DOCX", "DOC", "ODT", "RTF", "TXT", "MD" -> Color(0xFF1F4E79)
    "XLSX", "XLS", "ODS", "CSV" -> Color(0xFF2E6B3A)
    "PPTX", "PPT", "ODP" -> Color(0xFFB2541A)
    "EPUB", "FB2" -> Color(0xFF5B3F8C)
    else -> Color(0xFF4A4A4A)
}

private val CREAM = Color(0xFFF6EBD6)
private val GILT = Color(0xFFE2C27A)

/** The pattern of a title: which motif, how many in a row, and which of the two light colours leads. */
internal data class Pattern(val motif: Int, val count: Int, val giltFirst: Boolean)

internal fun patternOf(title: String): Pattern {
    // A steady number from the title (String.hashCode is the same everywhere and in every version)
    val h = title.lowercase().hashCode() and 0x7fffffff
    return Pattern(motif = h % MOTIFS, count = 5 + (h / MOTIFS) % 3, giltFirst = (h / (MOTIFS * 3)) % 2 == 0)
}

private const val MOTIFS = 4

/** A drawn cover: the format's colour, an ornament band above and below, the title in the middle. */
@Composable
fun DrawnCover(title: String, format: String, modifier: Modifier = Modifier) {
    val base = coverColor(format)
    val pattern = remember(title) { patternOf(title) }
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.08f), base, lerp(base, Color.Black, 0.22f))))) {
        val width = maxWidth
        val small = width < 72.dp
        // The ornament: a picture only (the title is what is read aloud)
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val band = size.width / (pattern.count + 1)
            ornamentRow(pattern, Offset(0f, size.height * 0.06f), size.width, band, base)
            if (!small) ornamentRow(pattern.copy(giltFirst = !pattern.giltFirst), Offset(0f, size.height * 0.94f - band * 0.62f), size.width, band * 0.62f, base)
        }
        if (small) {
            Text(format, color = CREAM, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        CoverTitle(title, width, Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp))
        Text(format, color = GILT, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (width.value * 0.17f).dp))
    }
}

/** The title: as large as the longest word allows on one line, in the book face, centred. */
@Composable
private fun CoverTitle(title: String, width: Dp, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val room = with(density) { (width - 24.dp).toPx() }
    val scale = density.fontScale
    // Measured once per title and size, not again on each scroll (kept for the shelf's lifetime)
    val size = remember(title, room, scale) {
        fitted.get("$title|$room|$scale") ?: run {
            val word = title.split(' ', '-', '_', '/').maxByOrNull { it.length } ?: ""
            var s = 17f
            while (s > 9f && measurer.measure(word, TextStyle(fontSize = s.sp, fontFamily = Literata, fontWeight = FontWeight.Bold)).size.width > room) s -= 0.5f
            s.also { fitted.put("$title|$room|$scale", it) }
        }
    }
    Text(
        title, color = CREAM, fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 3).sp,
        textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
        style = TextStyle(hyphens = Hyphens.None, lineBreak = LineBreak.Heading), modifier = modifier,
    )
}

/** Title sizes already worked out (title, room, font scale). */
private val fitted = android.util.LruCache<String, Float>(1024)

/** A row of motifs between two thin lines. */
private fun DrawScope.ornamentRow(p: Pattern, at: Offset, width: Float, cell: Float, base: Color) {
    val a = if (p.giltFirst) GILT else CREAM
    val b = if (p.giltFirst) CREAM else GILT
    val line = 1.dp.toPx()
    drawLine(a.copy(alpha = 0.7f), Offset(width * 0.06f, at.y), Offset(width * 0.94f, at.y), line)
    drawLine(a.copy(alpha = 0.7f), Offset(width * 0.06f, at.y + cell), Offset(width * 0.94f, at.y + cell), line)
    val start = (width - cell * p.count) / 2f
    for (i in 0 until p.count) {
        val cx = start + cell * (i + 0.5f)
        val cy = at.y + cell / 2f
        val r = cell * 0.36f
        val main = if (i % 2 == 0) a else b
        when (p.motif) {
            0 -> { rhombus(cx, cy, r, main); rhombus(cx, cy, r * 0.45f, base) }
            1 -> star(cx, cy, r, main, base)
            2 -> wave(cx, cy, cell, r, main)
            else -> { cross(cx, cy, r, main); for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) drawCircle(b, r * 0.12f, Offset(cx + dx * r * 0.7f, cy + dy * r * 0.7f)) }
        }
    }
}

private fun DrawScope.rhombus(cx: Float, cy: Float, r: Float, color: Color) =
    drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }, color)

/** An eight-pointed star, as on pysanky: two squares turned to each other. */
private fun DrawScope.star(cx: Float, cy: Float, r: Float, color: Color, base: Color) {
    val s = r * 0.72f
    drawPath(Path().apply { moveTo(cx - s, cy - s); lineTo(cx + s, cy - s); lineTo(cx + s, cy + s); lineTo(cx - s, cy + s); close() }, color)
    rhombus(cx, cy, r, color)
    drawCircle(base, r * 0.22f, Offset(cx, cy))
}

/** The endless line («безконечник»): one wave per cell, joining its neighbours. */
private fun DrawScope.wave(cx: Float, cy: Float, cell: Float, r: Float, color: Color) {
    val half = cell / 2f
    drawPath(Path().apply {
        moveTo(cx - half, cy)
        cubicTo(cx - half * 0.5f, cy - r * 1.2f, cx, cy - r * 1.2f, cx, cy)
        cubicTo(cx, cy + r * 1.2f, cx + half * 0.5f, cy + r * 1.2f, cx + half, cy)
    }, color, style = Stroke(width = r * 0.28f))
}

private fun DrawScope.cross(cx: Float, cy: Float, r: Float, color: Color) {
    val t = r * 0.3f
    drawRect(color, Offset(cx - t, cy - r), androidx.compose.ui.geometry.Size(t * 2, r * 2))
    drawRect(color, Offset(cx - r, cy - t), androidx.compose.ui.geometry.Size(r * 2, t * 2))
}

/**
 * Titles a cover can carry, best first: the book's or the file's own title, the first heading, the first line that
 * says something, the file's name. Hramota cannot tell what a text is about without the internet or a model, so it only
 * offers what the document itself says; the person can also write their own.
 */
fun coverTitles(fileName: String, bookTitle: String? = null, propertiesTitle: String? = null, paragraphs: List<Paragraph>? = null): List<String> {
    val out = LinkedHashSet<String>()
    fun add(s: String?) {
        val t = ua.hramota.data.Library.cleanTitle(s ?: return).take(ua.hramota.data.Library.MAX_COVER_TITLE).trim()
        // Something to read: a few letters, not a page number or a date alone
        if (t.count { it.isLetter() } >= 3 && t.length <= ua.hramota.data.Library.MAX_COVER_TITLE) out += t
    }
    add(bookTitle)
    add(propertiesTitle)
    paragraphs?.let { ps ->
        ps.take(400).firstOrNull { it.heading > 0 && it.text.isNotBlank() }?.let { add(it.text) }
        ps.take(400).firstOrNull { p -> p.text.trim().let { it.length in 3..120 && it.count(Char::isLetter) >= 3 } }?.let { add(it.text) }
    }
    add(fileName.substringBeforeLast('.', fileName).replace('_', ' '))
    return out.toList().take(5)
}

/** What was chosen for a document's cover. */
data class CoverChoice(val title: String?, val cover: String, val rename: Boolean)

/**
 * The cover of a document on the shelf: its title (one the document offers, or one's own) and its look (drawn, the
 * page being read, or a book's own picture). The file itself can be renamed too.
 */
@Composable
fun CoverDialog(
    fileName: String,
    format: String,
    titles: List<String>,
    current: ua.hramota.data.LibraryEntry?,
    page: Int?,
    bookPicture: Boolean,
    onApply: (CoverChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val fileTitle = fileName.substringBeforeLast('.', fileName)
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current?.coverTitle ?: titles.firstOrNull() ?: fileTitle) }
    var own by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current?.coverTitle?.takeIf { it !in titles } ?: "") }
    var ownChosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(own.isNotEmpty()) }
    var look by androidx.compose.runtime.saveable.rememberSaveable {
        // A picture already chosen for a document with pages is its page
        androidx.compose.runtime.mutableStateOf(when (current?.cover) { "picture" -> if (bookPicture) "picture" else if (page != null) "page" else "drawn"; "drawn" -> "drawn"; else -> if (bookPicture) "picture" else "drawn" })
    }
    var rename by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val title = (if (ownChosen) own else chosen).let { ua.hramota.data.Library.cleanTitle(it) }.take(ua.hramota.data.Library.MAX_COVER_TITLE)
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                // What it will look like
                if (look == "drawn") Box(Modifier.align(Alignment.CenterHorizontally).width(112.dp).aspectRatio(0.72f)) {
                    DrawnCover(title.ifBlank { fileTitle }, format)
                }
                Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_name), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
                    for (t in titles) ChoiceRow(t, !ownChosen && chosen == t) { chosen = t; ownChosen = false }
                    ChoiceRow(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_own), ownChosen) { ownChosen = true }
                }
                if (ownChosen) androidx.compose.material3.OutlinedTextField(
                    own, { own = it.take(ua.hramota.data.Library.MAX_COVER_TITLE) }, singleLine = false, maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_look), style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
                    ChoiceRow(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_drawn), look == "drawn") { look = "drawn" }
                    if (page != null) ChoiceRow(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_page, page + 1), look == "page") { look = "page" }
                    if (bookPicture) ChoiceRow(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_book), look == "picture") { look = "picture" }
                }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().toggleable(value = rename, role = Role.Checkbox, onValueChange = { rename = it }).padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(rename, onCheckedChange = null)
                    androidx.compose.foundation.layout.Column(Modifier.padding(start = 10.dp)) {
                        Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_rename), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_rename_hint), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = title.isNotBlank(), onClick = {
                val t = title.takeIf { it != fileTitle }
                onApply(CoverChoice(t, if (look == "page") "picture-page" else look, rename))
            }) { Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cover_apply)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected, onClick = null)
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}
