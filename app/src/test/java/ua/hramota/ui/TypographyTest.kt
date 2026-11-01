package ua.hramota.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a line may not break, by language; the text keeps its length. */
class TypographyTest {
    private val nb = ' '

    @Test fun ukrainianShortWordsStayWithTheNext() {
        val t = "Він прийшов і сказав, а вона в хаті з книжкою — так 5 км до 2026 р. Т. Шевченко"
        val out = Typography.typeset(t, "uk")
        assertEquals(t.length, out.length)
        assertTrue(out.contains("і${nb}сказав"))
        assertTrue(out.contains("а${nb}вона"))
        assertTrue(out.contains("в${nb}хаті"))
        assertTrue(out.contains("з${nb}книжкою"))
        assertTrue(out.contains("книжкою$nb—"))
        assertTrue(out.contains("5${nb}км"))
        assertTrue(out.contains("Т.${nb}Шевченко"))
        // Long words still break normally
        assertTrue(out.contains("Він прийшов"))
    }

    @Test fun englishKeepsShortWordsFree() {
        val out = Typography.typeset("I saw a cat in J. R. Smith's garden — at 5 pm", "en")
        assertTrue(out.contains("a cat"))
        assertTrue(out.contains("in J."))
        assertTrue(out.contains("J.${nb}R.${nb}Smith"))
        assertTrue(out.contains("garden$nb—"))
        assertTrue(out.contains("5${nb}pm"))
    }

    @Test fun paragraphLanguage() {
        assertEquals("en", Typography.languageOf("To be, or not to be, that is the question.", "uk"))
        assertEquals("uk", Typography.languageOf("Садок вишневий коло хати, хрущі над вишнями гудуть.", "en"))
        assertEquals("uk", Typography.languageOf("Ok", "uk"))
        assertTrue(Typography.hyphenates("en-GB"))
        assertTrue(Typography.hyphenates("uk"))
        assertFalse(Typography.hyphenates("ja"))
    }
}
