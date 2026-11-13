package ua.hramota.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class LookalikesTest {
    @Test fun aLatinLookingWordInACyrillicLineBecomesCyrillic() {
        assertEquals("Людвіг фон Мізес", Lookalikes.fix("Людвіг фон Mizec"))
    }
    @Test fun wordsWithOtherLatinLettersAndLatinLinesStay() {
        assertEquals("Видавництво WELL", Lookalikes.fix("Видавництво WELL"))
        assertEquals("Pixel 10 Pro", Lookalikes.fix("Pixel 10 Pro"))
        assertEquals("Hello Mac", Lookalikes.fix("Hello Mac"))
    }
    @Test fun aLineOfOneWordIsLeftAlone() { assertEquals("Mizec", Lookalikes.fix("Mizec")) }
    @Test fun aWordSplitAtALineEndIsJoined() {
        assertEquals("медичної\nсвідомості –\nдалі", joinOcrLines(listOf("меди-", "чної", "свідомості –", "далі")))
    }
}
