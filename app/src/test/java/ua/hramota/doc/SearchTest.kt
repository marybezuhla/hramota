package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test fun ignoresCaseAndApostrophes() {
        val text = "Новий пункт з’явиться завтра. ЗʼЯВИТЬСЯ знову, з'явиться втретє."
        val hits = Search.find(text, "з'явиться")
        assertEquals(3, hits.size)
        assertEquals("з’явиться", text.substring(hits[0].first, hits[0].last + 1))
        assertEquals("ЗʼЯВИТЬСЯ", text.substring(hits[1].first, hits[1].last + 1))
    }

    @Test fun matchesAcrossLineBreaksAndDashes() {
        val text = "строк дії\r\nдоговору — 12 місяців"
        assertEquals(1, Search.find(text, "дії договору - 12").size)
        val hit = Search.find(text, "дії договору").single()
        assertEquals("дії\r\nдоговору", text.substring(hit.first, hit.last + 1))
    }

    @Test fun emptyAndMissing() {
        assertTrue(Search.find("текст", "   ").isEmpty())
        assertTrue(Search.find("текст", "інше").isEmpty())
        assertTrue(Search.find("", "а").isEmpty())
    }

    @Test fun nonOverlapping() {
        assertEquals(2, Search.find("аааа", "аа").size)
    }

    @Test fun wordAround() {
        val t = "Підписати акт приймання-передачі (з’ясувати деталі)."
        fun w(i: Int) = Search.wordAt(t, i).let { t.substring(it.first, it.last + 1) }
        assertEquals("акт", w(11))
        assertEquals("приймання-передачі", w(16))
        assertEquals("з’ясувати", w(36))
        assertEquals("деталі", w(t.indexOf("деталі") + 2))
        assertEquals("(", w(t.indexOf('(')))
    }
}
