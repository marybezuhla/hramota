package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** Old Word documents (.doc) and CSV tables. The .doc samples come from Apache POI's test data. */
class LegacyFormatsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("legacy/$name")!!

    @Test fun readsWord97Text() {
        val p = res("SampleDoc.doc").use { DocReader.read(it) }.map { it.text }
        assertTrue(p.contains("I am a test document"))
        assertTrue(p.any { it.startsWith("I am Calibri (Body) in font size 11") })
    }

    @Test fun readsUnicodeText() {
        val text = res("HeaderFooterUnicode.doc").use { DocReader.read(it) }.joinToString("\n") { it.text }
        assertTrue(text, text.contains("€"))
        assertTrue(text, text.contains("Molière"))
    }

    @Test fun damagedFilesFailCleanly() {
        for (name in listOf("fuzzed-1.doc", "fuzzed-2.doc")) {
            try { res(name).use { DocReader.read(it) } } catch (e: IOException) { /* expected or fine */ }
        }
        try { DocReader.read(ByteArrayInputStream(ByteArray(600) { 7 })); fail("not a Word file") } catch (e: IOException) {}
    }

    @Test fun csvQuotesAndSeparators() {
        val rows = CsvReader.parse("Назва;Сума;Примітка\r\n\"Папір; А4\";1 450,00;\"він сказав \"\"так\"\"\"\n\nРучки;640,00;\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("Папір; А4", "1 450,00", "він сказав \"так\""), rows[1])
        assertEquals(listOf("Ручки", "640,00", ""), rows[2])
        assertEquals(',', CsvReader.guessSeparator("a,b,c\n1,2,3"))
        assertEquals('\t', CsvReader.guessSeparator("a\tb\n1\t2"))
    }

    @Test fun windows1251Csv() {
        val bytes = "Прізвище;Місто\nІваненко;Київ\n".toByteArray(charset("windows-1251"))
        val rows = CsvReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Іваненко", "Київ"), rows[1])
        assertEquals("Ґанок", TextDecoding.decode("Ґанок".toByteArray()))
    }
}
