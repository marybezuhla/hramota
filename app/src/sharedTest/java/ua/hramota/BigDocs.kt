package ua.hramota

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Large made-up documents for speed and memory tests: a comparison table of about a thousand
 * pages in two versions, long prose, a big CSV. Same seed, same document.
 */
object BigDocs {
    private val words = ("договір сторона постачальник покупець товар оплата строк відповідальність умова пункт " +
        "редакція зміна закон стаття частина порядок вимога право обов'язок документ рішення " +
        "комісія засідання протокол додаток специфікація кількість вартість гривня день місяць " +
        "рік підписання виконання припинення продовження повідомлення письмово електронна форма").split(' ')

    /** A word that appears exactly once, in the last row of the table, to test searching to the end. */
    const val NEEDLE = "Ґрунтовність"

    fun sentence(r: Random, n: Int): String {
        val sb = StringBuilder()
        repeat(n) { i ->
            val w = words[r.nextInt(words.size)]
            sb.append(if (i == 0) w.replaceFirstChar { it.uppercase() } else w)
            sb.append(if (i == n - 1) "." else if (r.nextInt(9) == 0) ", " else " ")
        }
        return sb.toString()
    }

    /** Rows of the comparison table for [version] 1 or 2; version 2 edits, inserts and removes a few rows. */
    fun tableRows(rows: Int, version: Int): List<List<String>> {
        val r = Random(42)
        val out = ArrayList<List<String>>(rows + 1)
        out += listOf("№", "Чинна редакція", "Запропонована редакція", "Примітка")
        for (i in 1..rows) {
            val old = sentence(r, 12 + r.nextInt(18))
            var new = sentence(r, 12 + r.nextInt(18))
            val note = sentence(r, 3 + r.nextInt(6))
            if (version == 2 && i % 97 == 0) new = new.replaceFirst(" ", " змінено ")
            if (version == 2 && i % 499 == 0) continue
            val last = if (i == rows) " $NEEDLE" else ""
            out += listOf("$i", old, new + last, note)
            if (version == 2 && i % 701 == 0) out += listOf("$i-а", "—", sentence(r, 10), "Новий пункт")
        }
        return out
    }

    /** Changes the comparison should find (it reads one paragraph per cell): edited cells, plus four per removed or inserted row. */
    fun changesExpected(rows: Int): Int = (1..rows).count { it % 97 == 0 && it % 499 != 0 } + 4 * (rows / 499) + 4 * (rows / 701)

    fun docxTable(rows: Int, version: Int): ByteArray {
        val body = StringBuilder()
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Порівняльна таблиця</w:t></w:r></w:p>")
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$b w:val=\"single\" w:sz=\"4\"/>")
        body.append("</w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"700\"/><w:gridCol w:w=\"3600\"/><w:gridCol w:w=\"3600\"/><w:gridCol w:w=\"1700\"/></w:tblGrid>")
        for ((k, row) in tableRows(rows, version).withIndex()) {
            body.append("<w:tr>")
            if (k == 0) body.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (cell in row) body.append("<w:tc><w:p><w:r><w:t xml:space=\"preserve\">").append(esc(cell)).append("</w:t></w:r></w:p></w:tc>")
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
        return docx(body)
    }

    fun docxProse(paragraphs: Int): ByteArray {
        val r = Random(7)
        val body = StringBuilder()
        for (i in 1..paragraphs) {
            if (i % 40 == 1) body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>Розділ ${i / 40 + 1}</w:t></w:r></w:p>")
            val text = sentence(r, 20 + r.nextInt(60)) + if (i == paragraphs) " $NEEDLE" else ""
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r></w:p>")
        }
        return docx(body)
    }

    fun csv(rows: Int): String {
        val r = Random(3)
        val sb = StringBuilder("Дата;Стаття;Сума;Примітка\n")
        for (i in 1..rows) sb.append("2026-${1 + i % 12}-${1 + i % 28};").append(words[r.nextInt(words.size)]).append(';')
            .append(r.nextInt(100_000)).append(';').append(if (i == rows) NEEDLE else sentence(r, 4)).append('\n')
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun docx(body: CharSequence): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: CharSequence) { z.putNextEntry(ZipEntry(name)); z.write(text.toString().toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            put("word/document.xml", StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                .append(body).append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701"/></w:sectPr></w:body></w:document>"""))
        }
        return out.toByteArray()
    }
}
