package ua.hramota.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class ReflowTest {
    private fun line(text: String, left: Int, top: Int, right: Int) = Reflow.Line(text, Rect(left, top, right, top + 20))

    @Test fun linesOfAPageBecomeParagraphs() {
        val lines = listOf(
            line("Перший абзац тягнеться через увесь", 100, 100, 900),
            line("рядок і закінчується тут.", 100, 130, 500),
            line("Другий абзац має відступ і слово, яке розірва-", 160, 160, 905),
            line("но переносом, а далі текст.", 100, 190, 560),
        )
        assertEquals(
            listOf("Перший абзац тягнеться через увесь рядок і закінчується тут.", "Другий абзац має відступ і слово, яке розірвано переносом, а далі текст."),
            Reflow.paragraphs(lines),
        )
    }

    @Test fun aWiderGapAndAHeadingEndAParagraph() {
        val lines = listOf(
            line("Заголовок", 100, 100, 300),
            line("Текст під заголовком, довгий рядок на всю ширину", 100, 130, 900),
            line("сторінки і ще трохи.", 100, 160, 900),
            line("Після проміжку новий абзац, хоч і без відступу", 100, 230, 900),
        )
        assertEquals(3, Reflow.paragraphs(lines).size)
    }
}
