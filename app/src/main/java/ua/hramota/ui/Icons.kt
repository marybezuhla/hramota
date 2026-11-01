package ua.hramota.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The few icons the app draws itself (the bundled icon set has no grid or bookmark). */
object AppIcons {
    /** Four pages laid out: the overview. */
    val Overview: ImageVector by lazy {
        ImageVector.Builder("overview", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                for ((x, y) in listOf(4f to 3f, 13f to 3f, 4f to 13f, 13f to 13f)) {
                    moveTo(x + 1f, y); lineTo(x + 6f, y)
                    quadTo(x + 7f, y, x + 7f, y + 1f); lineTo(x + 7f, y + 7f)
                    quadTo(x + 7f, y + 8f, x + 6f, y + 8f); lineTo(x + 1f, y + 8f)
                    quadTo(x, y + 8f, x, y + 7f); lineTo(x, y + 1f)
                    quadTo(x, y, x + 1f, y); close()
                }
            }
        }.build()
    }

    /** A bookmark ribbon, filled. */
    val Bookmark: ImageVector by lazy { ribbon(filled = true) }

    /** A bookmark ribbon, outline only. */
    val BookmarkOutline: ImageVector by lazy { ribbon(filled = false) }

    private fun ribbon(filled: Boolean) = ImageVector.Builder(if (filled) "bookmark" else "bookmark_outline", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 3.5f); lineTo(17f, 3.5f); lineTo(17f, 20.5f); lineTo(12f, 16.5f); lineTo(7f, 20.5f); close()
        }
    }.build()
}
