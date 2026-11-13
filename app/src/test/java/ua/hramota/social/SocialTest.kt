package ua.hramota.social

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.ocr.OcrLine
import ua.hramota.ocr.OcrWord

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class SocialTest {
    private val x = SocialText.PLATFORMS.first { it.key == "x" }

    @Test fun countingAsTheNetworksDo() {
        assertEquals(5, SocialText.graphemes("Їжак!"))
        // A flag or a family emoji is one character to a person
        assertEquals(2, SocialText.graphemes("🇺🇦👨‍👩‍👧"))
        // On X a link is 23
        assertEquals(6 + 23, SocialText.length("Ось: https://example.com/very/long/address/indeed", x))
    }

    @Test fun aLongTextBecomesAThread() {
        val sentence = "Це речення про українську мову і письмо. "
        val text = sentence.repeat(30).trim()
        val parts = SocialText.thread(text, x)
        assertTrue(parts.size > 1)
        for ((i, p) in parts.withIndex()) {
            assertTrue(p, SocialText.length(p, x) <= 280)
            assertTrue(p, p.endsWith(" ${i + 1}/${parts.size}"))
            // Cut between sentences
            assertTrue(p, p.substringBeforeLast(' ').endsWith("."))
        }
        // Nothing lost
        assertEquals(text.replace(" ", ""), parts.joinToString("") { it.substringBeforeLast(' ') }.replace(" ", ""))
        assertEquals(listOf("Коротко."), SocialText.thread("Коротко.", x))
    }

    @Test fun aWordLongerThanAPostIsCut() {
        val parts = SocialText.thread("а".repeat(700), x, numbered = false)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= 280 })
    }

    private var y = 0
    private fun line(vararg words: String): OcrLine {
        var left = 0
        val top = y; y += 30
        val ws = words.map { w -> OcrWord(w, Rect(left, top, left + w.length * 10, top + 20)).also { left += w.length * 10 + 10 } }
        return OcrLine(words.joinToString(" "), Rect(0, top, left, top + 20), 0.9f, ws)
    }

    @Test fun privateThingsAreFound() {
        val found = Redact.find(listOf(
            line("Тел.:", "+380", "67", "123", "45", "67"),
            line("Пошта:", "olena.k@example.com"),
            line("Картка", "4111", "1111", "1111", "1111"),
            line("Дата", "13.11.2026,", "сума", "1250"),
        ))
        val kinds = found.map { it.kind }.toSet()
        assertTrue(found.toString(), Redact.Kind.PHONE in kinds && Redact.Kind.EMAIL in kinds && Redact.Kind.CARD in kinds)
        assertTrue(found.none { it.text.contains("2026") || it.text.contains("1250") })
        // The phone's box covers all its words, not the label
        val phone = found.first { it.kind == Redact.Kind.PHONE }
        assertEquals(60, phone.box.left)
        assertTrue(Redact.luhn("4111111111111111"))
        assertFalse(Redact.luhn("4111111111111112"))
    }

    @Test fun hiddenPixelsAreGone() {
        val b = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) }
        val out = Redact.apply(b, listOf(Rect(10, 10, 40, 30)), 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), out.getPixel(20, 20))
        assertEquals(0xFFFF0000.toInt(), out.getPixel(80, 40))
    }

    @Test fun cardsHaveTheirShape() {
        for (s in CardShape.entries) {
            val b = Cards.render("Хто рано встає, тому Бог дає.", "Народна мудрість", s, Cards.LOOKS[1])
            assertEquals(s.w, b.width); assertEquals(s.h, b.height)
        }
        val long = Cards.render("Слово ".repeat(400), "", CardShape.SQUARE, Cards.LOOKS[0])
        assertEquals(1080, long.height)
    }
}
