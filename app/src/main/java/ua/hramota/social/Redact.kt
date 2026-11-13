package ua.hramota.social

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ua.hramota.ocr.OcrLine

/**
 * Hiding parts of a picture before it is shared: the pixels under a box are painted over in the
 * picture itself and the picture is written anew (no layers, no hidden original, nothing of the
 * camera's metadata). Phone numbers, e-mail addresses, card and account numbers found in its text
 * are offered to hide.
 */
object Redact {
    private val EMAIL = Regex("[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[\\p{L}]{2,}")
    private val PHONE = Regex("(\\+?\\d[\\d ()\\-]{7,}\\d)")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[ ]?(?:[A-Z0-9]{4}[ ]?){3,7}[A-Z0-9]{1,4}\\b")
    private val CARD = Regex("\\b(?:\\d[ -]?){13,19}\\b")

    enum class Kind { PHONE, EMAIL, CARD, IBAN }

    data class Found(val kind: Kind, val text: String, val box: Rect)

    /** Places in recognised text that are better hidden: whole words or runs of words that match. */
    fun find(lines: List<OcrLine>): List<Found> {
        val out = ArrayList<Found>()
        for (line in lines) {
            val words = line.words
            if (words.isEmpty()) continue
            // The line as its words joined with spaces, and where each word starts in it
            val starts = IntArray(words.size)
            val sb = StringBuilder()
            words.forEachIndexed { i, w -> if (i > 0) sb.append(' '); starts[i] = sb.length; sb.append(w.text) }
            val text = sb.toString()
            fun add(kind: Kind, r: IntRange) {
                val first = starts.indexOfLast { it <= r.first }.coerceAtLeast(0)
                val last = starts.indexOfLast { it <= r.last }.coerceAtLeast(first)
                val box = Rect(words[first].box)
                for (i in first + 1..last) box.union(words[i].box)
                if (out.none { it.box == box && it.kind == kind }) out += Found(kind, text.substring(r.first, r.last + 1), box)
            }
            for (m in EMAIL.findAll(text)) add(Kind.EMAIL, m.range)
            for (m in IBAN.findAll(text)) add(Kind.IBAN, m.range)
            for (m in CARD.findAll(text)) if (luhn(m.value.filter(Char::isDigit))) add(Kind.CARD, m.range)
            for (m in PHONE.findAll(text)) {
                val digits = m.value.count(Char::isDigit)
                // Not a date or an amount: 9 to 15 digits
                if (digits in 9..15 && out.none { it.text.contains(m.value.trim()) }) add(Kind.PHONE, m.range)
            }
        }
        return out
    }

    /** The Luhn check card numbers pass (so a long ordinary number is not taken for a card). */
    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    /** A new picture with [boxes] painted over in [color], a little larger than asked so no edge of a letter shows. */
    fun apply(src: Bitmap, boxes: List<Rect>, color: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = false }
        for (b in boxes) {
            val pad = maxOf(2, b.height() / 8)
            c.drawRect(Rect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad), paint)
        }
        return out
    }
}
