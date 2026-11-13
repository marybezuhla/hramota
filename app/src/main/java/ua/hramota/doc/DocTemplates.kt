package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ready-made documents to start from: a letter, an application, minutes of a meeting (Word); a family
 * budget and a to-do list (Excel). Places to fill in are in square brackets. Ukrainian or English, as
 * the phone's language.
 */
object DocTemplates {
    val WORD = listOf("letter", "application", "minutes")
    val EXCEL = listOf("budget", "todo")

    private fun uk(lang: String) = lang.startsWith("uk")

    fun docx(kind: String, lang: String): ByteArray {
        val u = uk(lang)
        fun p(t: String, style: String? = null, align: String? = null) = WBlock.Para(listOf(WRun(t)), style, align = align)
        fun b(t: String) = WBlock.Para(listOf(WRun(t, bold = true)))
        val blocks: List<WBlock> = when (kind) {
            "letter" -> if (u) listOf(
                p("[Ваше ім'я та прізвище]", align = "right"), p("[Адреса, телефон, e-mail]", align = "right"), p("[Дата]", align = "right"), p(""),
                p("[Кому: ім'я, посада, установа]"), p("[Адреса]"), p(""),
                b("Шановна пані / Шановний пане [Прізвище]!"), p(""),
                p("[Чому ви пишете: одне-два речення.]"),
                p("[Подробиці: що сталося, що вже зроблено, що потрібно від адресата.]"),
                p("[Чого ви чекаєте і до якого строку.]"), p(""),
                p("З повагою"), p("[Ім'я та прізвище]"),
            ) else listOf(
                p("[Your name]", align = "right"), p("[Address, phone, e-mail]", align = "right"), p("[Date]", align = "right"), p(""),
                p("[To: name, position, organisation]"), p("[Address]"), p(""),
                b("Dear [Name],"), p(""),
                p("[Why you are writing: a sentence or two.]"),
                p("[Details: what happened, what has been done, what you need from them.]"),
                p("[What you expect, and by when.]"), p(""),
                p("Yours sincerely,"), p("[Your name]"),
            )
            "application" -> if (u) listOf(
                p("[Посада, ім'я керівника]", align = "right"), p("[Назва установи]", align = "right"),
                p("[Ваше ім'я та прізвище]", align = "right"), p("[Адреса, телефон]", align = "right"), p(""),
                p("ЗАЯВА", "Title", align = "center"),
                p("Прошу [що саме: надати відпустку, видати довідку…] [з якої дати / на який строк]."),
                p("[Підстава або пояснення, якщо потрібно.]"), p(""),
                p("Додатки: [перелік, якщо є]"), p(""),
                p("[Дата]\t\t\t\t\t\t[Підпис]"),
            ) else listOf(
                p("[Position, name of the head]", align = "right"), p("[Organisation]", align = "right"),
                p("[Your name]", align = "right"), p("[Address, phone]", align = "right"), p(""),
                p("APPLICATION", "Title", align = "center"),
                p("I kindly request [what: leave, a certificate…] [from what date / for how long]."),
                p("[The reason or explanation, if needed.]"), p(""),
                p("Enclosures: [list, if any]"), p(""),
                p("[Date]\t\t\t\t\t\t[Signature]"),
            )
            else -> if (u) listOf(
                p("ПРОТОКОЛ № [__]", "Title", align = "center"), p("[назва зборів / наради]", align = "center"),
                p("[Місто]\t\t\t\t\t\t[Дата]"), p(""),
                b("Присутні:"), WBlock.Para(listOf(WRun("[Ім'я, посада]")), list = "bullet"), WBlock.Para(listOf(WRun("[Ім'я, посада]")), list = "bullet"),
                b("Головує: [ім'я]. Секретар: [ім'я]."),
                p("Порядок денний", "Heading1"), WBlock.Para(listOf(WRun("[Питання 1]")), list = "number"), WBlock.Para(listOf(WRun("[Питання 2]")), list = "number"),
                p("1. [Питання 1]", "Heading2"), p("Слухали: [хто, коротко що]"), p("Виступили: [хто, коротко що]"), p("Вирішили: [рішення]"),
                p("Голосували: «за» — [ ], «проти» — [ ], «утрималися» — [ ]."),
                p("2. [Питання 2]", "Heading2"), p("Слухали: "), p("Вирішили: "), p(""),
                p("Головуючий\t\t\t[Підпис]\t\t[Ім'я]"), p("Секретар\t\t\t[Підпис]\t\t[Ім'я]"),
            ) else listOf(
                p("MINUTES No. [__]", "Title", align = "center"), p("[meeting]", align = "center"),
                p("[City]\t\t\t\t\t\t[Date]"), p(""),
                b("Present:"), WBlock.Para(listOf(WRun("[Name, position]")), list = "bullet"), WBlock.Para(listOf(WRun("[Name, position]")), list = "bullet"),
                b("Chair: [name]. Secretary: [name]."),
                p("Agenda", "Heading1"), WBlock.Para(listOf(WRun("[Item 1]")), list = "number"), WBlock.Para(listOf(WRun("[Item 2]")), list = "number"),
                p("1. [Item 1]", "Heading2"), p("Heard: [who, briefly]"), p("Discussed: [who, briefly]"), p("Decided: [decision]"),
                p("Votes: for — [ ], against — [ ], abstained — [ ]."),
                p("2. [Item 2]", "Heading2"), p("Heard: "), p("Decided: "), p(""),
                p("Chair\t\t\t[Signature]\t\t[Name]"), p("Secretary\t\t\t[Signature]\t\t[Name]"),
            )
        }
        return DocxWriter.write(blocks, lang)
    }

    // ---------------------------------------------------------------- Excel

    /** A cell of a template sheet: text, a number or a formula (without "="), with a style (see [styles]). */
    private sealed class C(val style: Int) {
        class T(val v: String, style: Int = 0) : C(style)
        class N(val v: Double, style: Int = 0) : C(style)
        class F(val v: String, style: Int = 0) : C(style)
    }

    /** 0 ordinary, 1 heading (bold, filled), 2 money, 3 money bold, 4 date, 5 bold, 6 title (large bold). */
    private const val STYLES = "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/></numFmts>" +
        "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font><font><b/><sz val=\"16\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEBF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top style=\"thin\"><color auto=\"1\"/></top><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"7\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"

    fun xlsx(kind: String, lang: String): ByteArray {
        val u = uk(lang)
        val rows = ArrayList<List<C?>>()
        val widths: List<Double>
        val name: String
        if (kind == "budget") {
            name = if (u) "Бюджет" else "Budget"
            widths = listOf(28.0, 14.0, 14.0, 14.0)
            rows.add(listOf(C.T(if (u) "Бюджет на місяць" else "Monthly budget", 6)))
            rows.add(emptyList())
            rows.add(listOf(C.T(if (u) "Доходи" else "Income", 1), C.T(if (u) "План" else "Planned", 1), C.T(if (u) "Факт" else "Actual", 1), C.T(if (u) "Різниця" else "Difference", 1)))
            val income = if (u) listOf("Зарплата" to 30000.0, "Інше" to 2000.0) else listOf("Salary" to 3000.0, "Other" to 200.0)
            val start = rows.size + 1
            for ((t, v) in income) { val r = rows.size + 1; rows.add(listOf(C.T(t), C.N(v, 2), C.N(0.0, 2), C.F("C$r-B$r", 2))) }
            val end = rows.size
            val incomeTotal = rows.size + 1
            rows.add(listOf(C.T(if (u) "Разом доходи" else "Total income", 5), C.F("SUM(B$start:B$end)", 3), C.F("SUM(C$start:C$end)", 3), C.F("C$incomeTotal-B$incomeTotal", 3)))
            rows.add(emptyList())
            rows.add(listOf(C.T(if (u) "Витрати" else "Expenses", 1), C.T(if (u) "План" else "Planned", 1), C.T(if (u) "Факт" else "Actual", 1), C.T(if (u) "Різниця" else "Difference", 1)))
            val spend = if (u) listOf("Житло й комунальні" to 8000.0, "Їжа" to 9000.0, "Транспорт" to 1500.0, "Зв'язок та інтернет" to 500.0, "Здоров'я" to 1000.0, "Діти" to 3000.0, "Відпочинок" to 2000.0, "Заощадження" to 3000.0, "Інше" to 1000.0)
                else listOf("Housing and bills" to 800.0, "Food" to 900.0, "Transport" to 150.0, "Phone and internet" to 50.0, "Health" to 100.0, "Children" to 300.0, "Leisure" to 200.0, "Savings" to 300.0, "Other" to 100.0)
            val s2 = rows.size + 1
            for ((t, v) in spend) { val r = rows.size + 1; rows.add(listOf(C.T(t), C.N(v, 2), C.N(0.0, 2), C.F("B$r-C$r", 2))) }
            val e2 = rows.size
            val spendTotal = rows.size + 1
            rows.add(listOf(C.T(if (u) "Разом витрати" else "Total expenses", 5), C.F("SUM(B$s2:B$e2)", 3), C.F("SUM(C$s2:C$e2)", 3), C.F("B$spendTotal-C$spendTotal", 3)))
            rows.add(emptyList())
            rows.add(listOf(C.T(if (u) "Залишок" else "Left over", 5), C.F("B$incomeTotal-B$spendTotal", 3), C.F("C$incomeTotal-C$spendTotal", 3)))
        } else {
            name = if (u) "Справи" else "To do"
            widths = listOf(40.0, 14.0, 18.0, 12.0, 30.0)
            rows.add(listOf(C.T(if (u) "Список справ" else "To-do list", 6)))
            rows.add(emptyList())
            rows.add(listOf(C.T(if (u) "Справа" else "Task", 1), C.T(if (u) "Термін" else "Due", 1), C.T(if (u) "Хто" else "Who", 1), C.T(if (u) "Зроблено" else "Done", 1), C.T(if (u) "Примітки" else "Notes", 1)))
            val today = (java.time.LocalDate.now().toEpochDay() - java.time.LocalDate.of(1899, 12, 30).toEpochDay()).toDouble()
            val tasks = if (u) listOf("Оплатити рахунки", "Записатися до лікаря", "Купити подарунок") else listOf("Pay the bills", "Book a doctor", "Buy a present")
            for ((i, t) in tasks.withIndex()) rows.add(listOf(C.T(t), C.N(today + (i + 1) * 2, 4), C.T(""), C.T(""), C.T("")))
            repeat(10) { rows.add(listOf(null, null, null, null, null)) }
            val first = 4; val last = rows.size
            rows.add(emptyList())
            rows.add(listOf(C.T(if (u) "Зроблено справ" else "Tasks done", 5), C.F("COUNTA(D$first:D$last)&\" / \"&COUNTA(A$first:A$last)", 5)))
        }
        return workbook(name, rows, widths)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun workbook(sheetName: String, rows: List<List<C?>>, widths: List<Double>): ByteArray {
        val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }; return sb.toString() }
        // Formulas worked out here, so the totals show before anything is typed
        val memo = HashMap<Pair<Int, Int>, ua.hramota.doc.sheet.edit.Value>()
        val cells = object : ua.hramota.doc.sheet.edit.Formula.Cells {
            override fun value(sheet: String?, row: Int, col: Int): ua.hramota.doc.sheet.edit.Value {
                memo[row to col]?.let { return it }
                val v = when (val c = rows.getOrNull(row)?.getOrNull(col)) {
                    is C.N -> ua.hramota.doc.sheet.edit.Value.Num(c.v)
                    is C.T -> if (c.v.isEmpty()) ua.hramota.doc.sheet.edit.Value.Empty else ua.hramota.doc.sheet.edit.Value.Text(c.v)
                    is C.F -> ua.hramota.doc.sheet.edit.Formula.parse(c.v).node?.let { ua.hramota.doc.sheet.edit.Formula.eval(it, this) } ?: ua.hramota.doc.sheet.edit.Value.Empty
                    null -> ua.hramota.doc.sheet.edit.Value.Empty
                }
                memo[row to col] = v
                return v
            }
        }
        val data = StringBuilder()
        for ((ri, row) in rows.withIndex()) {
            val r = ri + 1
            data.append("<row r=\"$r\">")
            for ((ci, c) in row.withIndex()) {
                c ?: continue
                val ref = col(ci) + r
                val s = if (c.style != 0) " s=\"${c.style}\"" else ""
                when (c) {
                    is C.T -> if (c.v.isNotEmpty()) data.append("<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c.v)}</t></is></c>") else if (s.isNotEmpty()) data.append("<c r=\"$ref\"$s/>")
                    is C.N -> data.append("<c r=\"$ref\"$s><v>${if (c.v == Math.floor(c.v)) c.v.toLong().toString() else c.v.toString()}</v></c>")
                    is C.F -> when (val v = cells.value(null, ri, ci)) {
                        is ua.hramota.doc.sheet.edit.Value.Num -> data.append("<c r=\"$ref\"$s><f>${esc(c.v)}</f><v>${if (v.v == Math.floor(v.v) && Math.abs(v.v) < 1e15) v.v.toLong().toString() else v.v.toString()}</v></c>")
                        is ua.hramota.doc.sheet.edit.Value.Text -> data.append("<c r=\"$ref\"$s t=\"str\"><f>${esc(c.v)}</f><v>${esc(v.v)}</v></c>")
                        else -> data.append("<c r=\"$ref\"$s><f>${esc(c.v)}</f></c>")
                    }
                }
            }
            data.append("</row>")
        }
        val cols = widths.withIndex().joinToString("") { (i, w) -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }
        val parts = listOf(
            "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/_rels/workbook.xml.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$R/styles\" Target=\"styles.xml\"/></Relationships>",
            // Formulas are worked out when the file is opened (Hramota, Excel and LibreOffice alike)
            "xl/workbook.xml" to HEAD + "<workbook xmlns=\"$S\" xmlns:r=\"$R\"><sheets><sheet name=\"${esc(sheetName.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>",
            "xl/styles.xml" to HEAD + "<styleSheet xmlns=\"$S\">$STYLES</styleSheet>",
            "xl/worksheets/sheet1.xml" to HEAD + "<worksheet xmlns=\"$S\" xmlns:r=\"$R\"><sheetFormatPr defaultRowHeight=\"15\"/><cols>$cols</cols><sheetData>$data</sheetData></worksheet>",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in parts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }
}
