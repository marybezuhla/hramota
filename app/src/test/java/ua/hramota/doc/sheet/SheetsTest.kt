package ua.hramota.doc.sheet

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/** Spreadsheets: the same made-up workbook as XLSX and as XLS must read the same; number formats as Excel shows them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SheetsTest {
    @Before fun locale() { Locale.setDefault(Locale.US) }

    private fun file(name: String) = File(javaClass.classLoader!!.getResource("sheets/$name")!!.toURI())

    private fun cell(s: Sheet, ref: String): String {
        val row = s.row(rowOf(ref)) ?: return ""
        val k = row.find(columnOf(ref))
        return if (k < 0) "" else s.text(row, k)
    }

    private fun checkBudget(w: Workbook, frozen: Boolean = true) {
        assertEquals(listOf("Бюджет", "Звіт"), w.sheets.map { it.name })
        val s = w.sheets[0]
        assertEquals("Сімейний бюджет 2026", cell(s, "A1"))
        assertEquals("6,200.00", cell(s, "B4"))
        assertEquals("1,100.50", cell(s, "C4"))
        assertEquals("10,700.50", cell(s, "E4"))
        assertEquals("41.2%", cell(s, "B11"))
        assertEquals("01.07.2026", cell(s, "B12"))
        assertTrue(s.merges.toString(), s.merges.contains(Merge(0, 0, 0, 4)))
        // LibreOffice does not keep frozen panes when it saves .xls
        if (frozen) { assertEquals(3, s.frozenRows); assertEquals(1, s.frozenCols) }
        val head = s.style(s.row(2)!!, 0)
        assertTrue(head.bold)
        assertEquals(0xFF2F5597.toInt(), head.fill)
        assertEquals("center", head.align)
        assertEquals(15, head.borders)
        assertTrue(s.style(s.row(13)!!, 0).wrap)
        assertTrue("column A wider than B", s.width(0) > s.width(1))
        val r = w.sheets[1]
        assertEquals("Завдання номер 40", cell(r, "B43"))
        assertTrue(r.merges.contains(Merge(0, 0, 1, 3)))
    }

    @Test fun xlsx() = checkBudget(file("01-budget.xlsx").inputStream().use { XlsxReader.read(it) })
    @Test fun xls() = checkBudget(file("01-budget.xls").inputStream().use { XlsReader.read(it) }, frozen = false)

    @Test fun numberFormats() {
        val us = Locale.US
        val uk = Locale.forLanguageTag("uk-UA")
        assertEquals("1234.5", NumberFormat.format(1234.5, "General", locale = us))
        assertEquals("42", NumberFormat.format(42.0, "General", locale = us))
        assertEquals("0.333333333", NumberFormat.format(1.0 / 3, "General", locale = us))
        assertEquals("1,234.50", NumberFormat.format(1234.5, "#,##0.00", locale = us))
        assertEquals("-1,234", NumberFormat.format(-1234.4, "#,##0", locale = us))
        assertEquals("(1,234.00)", NumberFormat.format(-1234.0, "#,##0.00;(#,##0.00)", locale = us))
        assertEquals("12.5%", NumberFormat.format(0.125, "0.0%", locale = us))
        assertEquals("1 234,50 ₴", NumberFormat.format(1234.5, "#,##0.00 [\$₴-422]", locale = uk).replace(' ', ' ').replace(' ', ' '))
        assertEquals("1.23E+04", NumberFormat.format(12345.0, "0.00E+00", locale = us))
        assertEquals("007", NumberFormat.format(7.0, "000", locale = us))
        assertEquals("15.03.2026", NumberFormat.format(46096.0, "dd.mm.yyyy", locale = us))
        assertEquals("15 березня 2026", NumberFormat.format(46096.0, "d mmmm yyyy", locale = uk))
        assertEquals("березень 26", NumberFormat.format(46096.0, "mmmm yy", locale = uk))
        assertEquals("14:30", NumberFormat.format(46096.0 + 14.5 / 24, "hh:mm", locale = us))
        assertEquals("2:30 PM", NumberFormat.format(46096.0 + 14.5 / 24, "h:mm AM/PM", locale = us))
        assertEquals("27:00:00", NumberFormat.format(27.0 / 24, "[h]:mm:ss", locale = us))
        assertEquals("Разом: 5", NumberFormat.format(5.0, "\"Разом: \"0", locale = us))
    }

    @Test fun columnsAndReferences() {
        assertEquals("A", columnName(0)); assertEquals("Z", columnName(25)); assertEquals("AA", columnName(26)); assertEquals("XFD", columnName(16383))
        assertEquals(27, columnOf("AB12")); assertEquals(11, rowOf("AB12"))
    }

    // Damaged files fail cleanly (an IOException) or keep what could be read
    @Test fun damagedFilesFailCleanly() {
        val xls = file("01-budget.xls").readBytes()
        for (cut in listOf(600, 4096, xls.size / 2)) {
            try { XlsReader.read(ByteArrayInputStream(xls.copyOf(cut))) } catch (e: IOException) {}
        }
        val noisy = xls.copyOf().also { b -> for (i in 2048 until b.size step 97) b[i] = (b[i] + 31).toByte() }
        try { XlsReader.read(ByteArrayInputStream(noisy)) } catch (e: IOException) {}
        try { XlsxReader.read(ByteArrayInputStream(ByteArray(100))) } catch (e: IOException) {}
    }
}
