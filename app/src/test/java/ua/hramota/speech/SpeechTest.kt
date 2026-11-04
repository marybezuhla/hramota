package ua.hramota.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class SpeechTest {
    private val dictionary by lazy {
        val f = File("src/main/assets/voice/stress.bin")
        StressDictionary(RandomAccessFile(f, "r").channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()))
    }
    private val text by lazy { UkrainianText(dictionary) }

    @Test fun stressesComeFromTheDictionary() {
        // The words heard wrong before, and their right stresses
        val right = mapOf(
            "шумить" to "шум+ить", "туман" to "тум+ан", "кілометрів" to "кілом+етрів", "пошкодували" to "пошкодув+али",
            "виставка" to "в+иставка", "листопада" to "листоп+ада", "бібліотеки" to "бібліот+еки", "увечері" to "ув+ечері",
            "засвітила" to "засвіт+ила", "понеділок" to "понед+ілок", "сказав" to "сказ+ав", "мама" to "м+ама",
        )
        for ((w, s) in right) assertEquals(w, s, text.stressed(w))
        // Capitals and the apostrophe keep their place
        assertEquals("Бібліот+ека", text.stressed("Бібліотека"))
        assertEquals("п'+ять", text.stressed("п’ять").replace('’', '\''))
        // Unknown words are left for the voice
        assertEquals("хрямзик", text.stressed("хрямзик"))
    }

    @Test fun neighboursChooseBetweenReadings() {
        // «за́мок / замо́к»: the dictionary has both; something is always chosen and marked once
        val z = text.stressed("замок")
        assertEquals(1, z.count { it == '+' })
    }

    @Test fun numbersAreWrittenOut() {
        val n = UkrainianNumbers
        assertEquals("чотирнадцять", n.cardinal(14))
        assertEquals("дві тисячі двадцять шість", n.cardinal(2026))
        assertEquals("тисяча", n.cardinal(1000))
        assertEquals("до десяти років", n.expand("до 10 років"))
        assertEquals("від п'яти до десяти кілометрів", n.expand("від 5 до 10 км"))
        assertEquals("дванадцять гривень п'ятдесят копійок", n.expand("12,50 грн"))
        assertEquals("п'ять доларів дев'яносто дев'ять центів", n.expand("$5.99"))
        assertEquals("мінус п'ять градусів Цельсія", n.expand("-5 °C"))
        assertEquals("п'яте місце", n.expand("5-те місце"))
        assertEquals("у двох томах", n.expand("у 2-х томах"))
        assertEquals("у тисяча дев'ятсот дев'яностих роках", n.expand("у 1990-х роках"))
        assertEquals("нуль шість сім один два три чотири п'ять шість сім", n.expand("0671234567"))
        assertEquals("нуль кома нуль нуль п'ять", n.expand("0,005"))
        assertEquals("сторінки десять – п'ятнадцять", n.expand("сторінки 10 – 15"))
        assertEquals("до двох тисяч гривень", n.expand("до 2 тис. грн"))
        assertEquals("понад сто кілометрів на годину", n.expand("понад 100 км/год"))
        assertEquals("дев'яності роки", n.expand("90-ті роки"))
        assertEquals("двадцять третього", n.ordinal(23, UkrainianNumbers.Form.GEN_M))
        assertEquals("дві тисячі двадцять шостому", n.ordinal(2026, UkrainianNumbers.Form.LOC_M))
        assertEquals("двохтисячного", n.ordinal(2000, UkrainianNumbers.Form.GEN_M))
        assertEquals("дев'ятій", n.ordinal(9, UkrainianNumbers.Form.LOC_F))
        assertEquals("двадцять третього листопада", n.expand("23 листопада"))
        assertEquals("у дві тисячі двадцять шостому році", n.expand("у 2026 році"))
        assertEquals("о дев'ятій тридцять", n.expand("о 9:30"))
        assertEquals("п'ятнадцять відсотків", n.expand("15%"))
        assertEquals("чотирнадцять кілометрів", n.expand("14 км"))
        assertEquals("двадцять одна гривня", n.expand("21 грн"))
        assertEquals("три кілограми", n.expand("3 кг"))
        assertEquals("двадцять третього листопада дві тисячі двадцять шостого року", n.expand("23.11.2026"))
        assertEquals("номер дванадцять", n.expand("№ 12"))
        assertEquals("одна кома п'ять", n.expand("1,5"))
        assertEquals("у тисяча дев'ятсот дев'яносто першому році", n.expand("у 1991 році"))
        assertEquals("одна кома п'ять кілометра", n.expand("1,5 км"))
        assertEquals("дві кома п'ять відсотка", n.expand("2,5%"))
        assertEquals("п'яту", n.expand("5-ту"))
    }

    @Test fun sentencesAreCutWhereTheyEnd() {
        fun cut(t: String) = Sentences.split(t).map { t.substring(it.start, it.end) }
        assertEquals(listOf("Ми пішли.", "Вони лишились!", "Чому?"), cut("Ми пішли. Вони лишились! Чому?"))
        // Abbreviations, initials and numbers do not end a sentence
        assertEquals(1, cut("Т. Г. Шевченко народився 1814 р. у селі.").size)
        assertEquals(1, cut("Книги, зошити, ручки т. д. лежали на столі.").size)
        assertEquals(1, cut("Ціна 1.5 тис. гривень за все.").size)
        // A long sentence is cut further, never mid-word
        val long = (1..60).joinToString(", ") { "слово$it" } + "."
        val parts = cut(long)
        assertTrue(parts.size > 1 && parts.all { it.length <= Sentences.LONG + 1 })
        assertEquals("uk", Sentences.language("Привіт, світе"))
        assertEquals("en", Sentences.language("Hello, world"))
    }

    @Test fun abbreviationsAfterNumbersStayTheirs() {
        // «р.» after a year is «року», not «річка»; «м.» before a name is «місто»
        val p = text.prepare("У 1991 р. Україна стала незалежною.").replace("+", "")
        assertTrue(p, p.contains("тисяча дев'ятсот дев'яносто першому році Україна"))
        assertTrue(text.prepare("Він жив у м. Києві.").replace("+", "").contains("місто Києві"))
    }

    @Test fun marksAndShortFormsAreHandled() {
        // A stress already written in the text is kept; «+» is not taken for one
        assertTrue(text.prepare("за́мок").contains("з+амок"))
        assertTrue(text.prepare("+380 67 123 45 67").replace("+", "").contains("плюс три вісім нуль шістдесят сім один два три"))
        assertTrue(text.prepare("США").contains("есша+а"))
        assertTrue(text.prepare("у XX ст.").replace("+", "").contains("у двадцятому столітті"))
        // A soft hyphen does not cut a word
        assertEquals(text.prepare("бібліотеки"), text.prepare("біблі\u00ADотеки"))
    }

    @Test fun numbersAgreeWithNouns() {
        fun said(t: String) = text.prepare(t).replace("+", "")
        assertTrue(said("1 книга"), said("1 книга").startsWith("одна книга"))
        assertTrue(said("21 дитина"), said("21 дитина").startsWith("двадцять одна дитина"))
        assertTrue(said("1 місто"), said("1 місто").startsWith("одне місто"))
        assertTrue(said("1 стіл"), said("1 стіл").startsWith("один стіл"))
        assertTrue(said("1 батько"), said("1 батько").startsWith("один батько"))
        assertTrue(said("1 стаття"), said("1 стаття").startsWith("одна стаття"))
        assertTrue(said("1 Микола"), said("1 Микола").startsWith("один Микола"))
        assertTrue(said("один одного"), said("один одного").startsWith("один одного"))
        assertTrue(said("Один удома"), said("Один удома").startsWith("Один удома"))
        // «черговий»: before a noun, as the owner hears it; on its own, the one on duty
        assertTrue(text.prepare("Черговий лікар прийшов.").contains("Черг+овий"))
        assertTrue(text.prepare("Покликали, і черговий прийшов.").contains("чергов+ий"))
    }

    @Test fun dialogueAndListsStayTogether() {
        fun cut(t: String) = Sentences.split(t).map { t.substring(it.start, it.end) }
        assertEquals(1, cut("— Ти прийдеш? — спитала мама.").size)
        assertEquals(1, cut("1. Вступ до курсу").size)
        assertEquals(2, cut("Купили хліб, молоко тощо. Потім пішли.").size)
    }

    @Test fun preparedTextHasStressesAndNumbers() {
        val p = text.prepare("Двадцять третього листопада ми пройшли 14 км.")
        assertTrue(p, p.contains("листоп+ада"))
        assertTrue(p, p.contains("чотирн+адцять кілом+етрів"))
    }

    @Test fun englishClauses() {
        fun c(t: String) = EnglishVoice.clauses(t)
        assertEquals(listOf("Well" to ',', "it costs 3.5 or 1,000" to '.'), c("Well, it costs 3.5 or 1,000."))
        assertEquals(listOf("Ask Mr. Smith" to '?'), c("Ask Mr. Smith?"))
        assertEquals(listOf("a" to '—', "b" to null), c("a — b"))
        assertEquals(listOf("c" to '—', "d" to null), c("c—d"))
        assertEquals(listOf("pages 1–2" to null), c("pages 1–2"))
        assertEquals(listOf("\"Hi" to '.', "Then go" to null), c("\"Hi.\" Then go"))
        assertEquals(listOf("Name Value" to null), c("Name\tValue"))
    }
}
