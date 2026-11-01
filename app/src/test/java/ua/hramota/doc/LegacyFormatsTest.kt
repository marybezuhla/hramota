package ua.hramota.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        val rows = CsvReader.rows("Назва;Сума;Примітка\r\n\"Папір; А4\";1 450,00;\"він сказав \"\"так\"\"\"\n\nРучки;640,00;\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("Папір; А4", "1 450,00", "він сказав \"так\""), rows[1])
        assertEquals(listOf("Ручки", "640,00", ""), rows[2])
        assertEquals(',', CsvReader.guessSeparator("a,b,c\n1,2,3"))
        assertEquals('\t', CsvReader.guessSeparator("a\tb\n1\t2"))
    }

    @Test fun windows1251Csv() {
        val bytes = "Прізвище;Місто\nІваненко;Київ\n".toByteArray(charset("windows-1251"))
        val rows = CsvReader.read(ByteArrayInputStream(bytes)).rows
        assertEquals(listOf("Іваненко", "Київ"), rows[1])
        assertEquals("Ґанок", TextDecoding.decode("Ґанок".toByteArray()))
    }

    // A file built to make one enormous paragraph or millions of empty cells stays usable
    @Test fun hugeInputsAreBounded() {
        val one = TextExtractor.split(listOf(Paragraph("слово ".repeat(200_000))))
        assertTrue(one.size > 100)
        assertTrue(one.all { it.text.length <= TextExtractor.MAX_PARAGRAPH })
        assertEquals("слово ".repeat(200_000), one.joinToString("") { it.text })

        val table = CsvReader.parse(",".repeat(255) + "\n" + (",".repeat(255) + "\n").repeat(20_000))
        assertTrue(table.truncated)
        assertTrue(table.rows.sumOf { it.size } <= CsvReader.MAX_CELLS)
        assertEquals(256, table.widths.size)
    }

    @Test fun widthsFollowContent() {
        val t = CsvReader.parse("a;довга назва колонки\n1;2")
        assertEquals(1, t.widths[0])
        assertEquals("довга назва колонки".length, t.widths[1])
        assertFalse(t.truncated)
    }
}
