package ua.hramota.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.speech.StressDictionary
import java.io.File
import java.nio.ByteBuffer

/** Ukrainian spelling with the dictionaries built into Hramota. */
class SpellingTest {
    private fun dictionary(name: String) = File("src/main/assets/$name").takeIf { it.exists() }?.let { StressDictionary(ByteBuffer.wrap(it.readBytes())) }
    private val speller = Speller(listOfNotNull(dictionary("voice/stress.bin"), dictionary("spell/uk_extra.bin")))

    private fun marked(text: String) = speller.mistakes(text).map { text.substring(it.first, it.last + 1) }

    @Test fun wordsAreFound() {
        val t = "Пам’ять, будь-хто — 5-й iPhone"
        assertEquals(listOf("Пам’ять", "будь-хто", "й", "iPhone"), Speller.words(t).map { t.substring(it.first, it.last + 1) })
    }

    @Test fun rightTextHasNoMistakes() {
        assertEquals(emptyList<String>(), marked("Я люблю молоко, а мама любить чай. Пам'ять про Київ — будь-коли."))
    }

    @Test fun misspeltWordsAreMarked() {
        assertEquals(listOf("любдю", "малоко"), marked("Я любдю малоко."))
    }

    @Test fun whatIsNotCheckedIsLeft() {
        // Latin, digits, single letters, short capitals (abbreviations), abbreviations with a full stop
        assertEquals(emptyList<String>(), marked("Відкрийте iPhone, 5-й поверх, ЗСУ і ООН, 200 грн., с. 5, стор. 7"))
    }

    @Test fun onlyTheUnknownPartOfAHyphenedWordIsMarked() {
        assertEquals(listOf("хтосб"), marked("будь-хтосб"))
    }

    @Test fun likelyWordsAreSuggested() {
        assertTrue(speller.suggestions("любдю").contains("люблю"))
        assertEquals("пам'ять", speller.suggestions("память").first())
        assertTrue(speller.suggestions("сонячни").contains("сонячний"))
        // In the case written
        assertTrue(speller.suggestions("Малоко").contains("Молоко"))
        assertTrue(speller.suggestions("МАЛОКО").contains("МОЛОКО"))
    }

    @Test fun ownWordsAreKnown() {
        val mine = Speller(speller.let { listOfNotNull(dictionary("voice/stress.bin")) }, setOf("грамота-лаб"))
        assertFalse(mine.mistakes("Грамота-лаб").isNotEmpty())
    }
}
