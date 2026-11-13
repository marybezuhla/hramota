package ua.hramota.social

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** A card's shape: square for a feed, 4:5 for Instagram's feed, 9:16 for stories and reels. */
enum class CardShape(val w: Int, val h: Int, val label: String) { SQUARE(1080, 1080, "1:1"), PORTRAIT(1080, 1350, "4:5"), STORY(1080, 1920, "9:16") }

/** A card's look: background (two colours for a soft gradient), text and accent colours, serif or not. */
data class CardLook(val key: String, val top: Int, val bottom: Int, val text: Int, val accent: Int, val serif: Boolean)

/**
 * Quotation cards: a passage from a book or a note on a calm background, with where it is from,
 * sized for social networks. Drawn on the phone; nothing is fetched.
 */
object Cards {
    val LOOKS = listOf(
        CardLook("paper", 0xFFFBF7EE.toInt(), 0xFFF1E8D6.toInt(), 0xFF2B2522.toInt(), 0xFFB4472E.toInt(), true),
        CardLook("night", 0xFF14213D.toInt(), 0xFF0B1426.toInt(), 0xFFF4F1EA.toInt(), 0xFFF6C453.toInt(), true),
        CardLook("sky", 0xFF2F5597.toInt(), 0xFF1E3A6E.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD500.toInt(), false),
        CardLook("meadow", 0xFFEAF4E4.toInt(), 0xFFD5E8CB.toInt(), 0xFF1F3B1F.toInt(), 0xFF2E7D4F.toInt(), false),
        CardLook("rose", 0xFFFCE4EC.toInt(), 0xFFF8C9D8.toInt(), 0xFF3D1A25.toInt(), 0xFFC2185B.toInt(), true),
    )

    /** The text on a card is never longer than this (a card is for a passage, not a page). */
    const val MAX_CHARS = 700

    fun render(quote: String, source: String, shape: CardShape, look: CardLook): Bitmap {
        val w = shape.w; val h = shape.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), look.top, look.bottom, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val margin = w * 0.1f
        val width = (w - 2 * margin).toInt()
        val face = if (look.serif) Typeface.SERIF else Typeface.SANS_SERIF
        // The big quotation mark
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent; textSize = w * 0.22f; typeface = Typeface.create(face, Typeface.BOLD) }
        val text = quote.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS).trimEnd() + "…" else it }
        // The largest size at which the text fits the room left for it
        val room = h - 2 * margin - w * 0.18f - (if (source.isNotBlank()) w * 0.12f else 0f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.text; typeface = face }
        var size = w * 0.075f
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
            if (layout.height <= room || size <= w * 0.03f) break
            size *= 0.92f
        }
        val block = w * 0.16f + layout.height + (if (source.isNotBlank()) w * 0.1f else 0f)
        var y = max(margin, (h - block) / 2)
        c.drawText("«", margin - w * 0.01f, y + w * 0.16f, mark)
        y += w * 0.18f
        c.save(); c.translate(margin, y); layout.draw(c); c.restore()
        y += layout.height
        if (source.isNotBlank()) {
            val line = Paint().apply { color = look.accent; strokeWidth = w * 0.004f }
            y += w * 0.05f
            c.drawLine(margin, y, margin + w * 0.08f, y, line)
            val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.text; alpha = 200; textSize = min(w * 0.036f, size * 0.8f); typeface = Typeface.create(face, Typeface.ITALIC) }
            val s = StaticLayout.Builder.obtain(source.trim(), 0, source.trim().length, sp, width).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(margin, y + w * 0.025f); s.draw(c); c.restore()
        }
        return bmp
    }

    /** A page of a document as a picture for a feed: on white (or the page's own colour), fitted into [shape]. */
    fun fit(page: Bitmap, shape: CardShape?, background: Int = Color.WHITE): Bitmap {
        if (shape == null) return page
        val out = Bitmap.createBitmap(shape.w, shape.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(background)
        val pad = shape.w * 0.04f
        val k = min((shape.w - 2 * pad) / page.width, (shape.h - 2 * pad) / page.height)
        val dw = page.width * k; val dh = page.height * k
        c.drawBitmap(page, null, android.graphics.RectF((shape.w - dw) / 2, (shape.h - dh) / 2, (shape.w + dw) / 2, (shape.h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** What a screen reader says for a card: the quotation and its source. */
    fun alt(quote: String, source: String, quoteWord: String) =
        "$quoteWord: «${quote.trim()}»" + if (source.isNotBlank()) " — ${source.trim()}" else ""
}

/** Pages as pictures: which ones (at most [MAX_PAGES], a carousel) and in what shape. */
@androidx.compose.runtime.Composable
fun PagesAsPicturesDialog(count: Int, current: Int, onDismiss: () -> Unit, onGo: (from: Int, to: Int, shape: CardShape?) -> Unit) {
    var from by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf((current + 1).toString()) }
    var to by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf((current + 1).toString()) }
    var shape by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CardShape?>(null) }
    val a = from.toIntOrNull()?.minus(1); val b = to.toIntOrNull()?.minus(1)
    val ok = a != null && b != null && a in 0 until count && b in a until count && b - a < MAX_PAGES
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_pages)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_pages_hint, MAX_PAGES))
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_from)) }, modifier = androidx.compose.ui.Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    androidx.compose.material3.OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_to)) }, modifier = androidx.compose.ui.Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(shape == null, { shape = null }, label = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_as_is)) })
                    for (s in CardShape.entries) androidx.compose.material3.FilterChip(shape == s, { shape = s }, label = { androidx.compose.material3.Text(s.label) })
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onGo(a!!, b!!, shape) }, enabled = ok) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.social_next)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(ua.hramota.R.string.cancel)) } },
    )
}

const val MAX_PAGES = 10
