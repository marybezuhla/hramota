package ua.hramota.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.hramota.R
import ua.hramota.doc.Format

private val Light = lightColorScheme(
    primary = Color(0xFF24467A), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5F5), onPrimaryContainer = Color(0xFF0E2547),
    secondary = Color(0xFFB4472E), onSecondary = Color.White,
    background = Color(0xFFFBF8F2), onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFBF8F2), onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFF0EBE1), onSurfaceVariant = Color(0xFF55524B),
    surfaceContainer = Color(0xFFF4EFE6), outline = Color(0xFFCBC4B6), outlineVariant = Color(0xFFE2DCD0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C2EC), onPrimary = Color(0xFF0E2547),
    primaryContainer = Color(0xFF2A426A), onPrimaryContainer = Color(0xFFDCE5F5),
    secondary = Color(0xFFF0A48F), onSecondary = Color(0xFF4A1607),
    background = Color(0xFF15181D), onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF15181D), onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF242830), onSurfaceVariant = Color(0xFFB9B4AA),
    surfaceContainer = Color(0xFF1D2026), outline = Color(0xFF4A4F58), outlineVariant = Color(0xFF30343B),
)

/** Colours of the comparison: always shown with a word or a strike, never by colour alone. */
@Immutable
data class DiffColors(val addedBg: Color, val addedText: Color, val removedBg: Color, val removedText: Color)

val LocalDiffColors = staticCompositionLocalOf {
    DiffColors(Color(0xFFD7F0DC), Color(0xFF14532D), Color(0xFFFADBD6), Color(0xFF8A1C10))
}

@Composable
fun HramotaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val diff = if (dark) DiffColors(Color(0xFF173A24), Color(0xFFA6E3B5), Color(0xFF4A1E18), Color(0xFFF5B1A6))
    else DiffColors(Color(0xFFD7F0DC), Color(0xFF14532D), Color(0xFFFADBD6), Color(0xFF8A1C10))
    androidx.compose.runtime.CompositionLocalProvider(LocalDiffColors provides diff) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** A plain top bar: back arrow, title and optional actions. */
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        } else Box(Modifier.size(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.End) { actions() }
    }
}

/** A small coloured label with the file's format, e.g. "PDF". */
@Composable
fun FormatBadge(format: Format, modifier: Modifier = Modifier) {
    val (bg, fg) = when (format) {
        Format.PDF -> Color(0xFFB4472E) to Color.White
        Format.DOCX, Format.DOC, Format.ODT, Format.RTF -> Color(0xFF24467A) to Color.White
        Format.XLSX, Format.XLS, Format.ODS, Format.CSV -> Color(0xFF2E6B45) to Color.White
        Format.PPTX, Format.PPT, Format.ODP -> Color(0xFFA0632A) to Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier.size(width = 44.dp, height = 52.dp).clip(RoundedCornerShape(8.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(format.label.ifEmpty { "?" }.take(4), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
