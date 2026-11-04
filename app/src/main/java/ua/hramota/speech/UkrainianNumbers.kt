package ua.hramota.speech

/**
 * Numbers in Ukrainian words, the way they are read: «14 кілометрів» — «чотирнадцять кілометрів», «23 листопада» —
 * «двадцять третього листопада», «у 2026 році» — «у дві тисячі двадцять шостому році», «о 9:30» — «о дев'ятій
 * тридцять», «15%» — «п'ятнадцять відсотків». Only what can be said surely; other numbers are read as plain counts.
 */
object UkrainianNumbers {
    private val ONES_M = arrayOf("нуль", "один", "два", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val ONES_F = arrayOf("нуль", "одна", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val TEENS = arrayOf("десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять", "п'ятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "дев'ятнадцять")
    private val TENS = arrayOf("", "", "двадцять", "тридцять", "сорок", "п'ятдесят", "шістдесят", "сімдесят", "вісімдесят", "дев'яносто")
    private val HUNDREDS = arrayOf("", "сто", "двісті", "триста", "чотириста", "п'ятсот", "шістсот", "сімсот", "вісімсот", "дев'ятсот")

    // Ordinal stems; «трет» is the one soft stem
    private val ORD_ONES = arrayOf("нульов", "перш", "друг", "трет", "четверт", "п'ят", "шост", "сьом", "восьм", "дев'ят")
    private val ORD_TEENS = arrayOf("десят", "одинадцят", "дванадцят", "тринадцят", "чотирнадцят", "п'ятнадцят", "шістнадцят", "сімнадцят", "вісімнадцят", "дев'ятнадцят")
    private val ORD_TENS = arrayOf("", "", "двадцят", "тридцят", "сороков", "п'ятдесят", "шістдесят", "сімдесят", "вісімдесят", "дев'яност")
    private val ORD_HUNDREDS = arrayOf("", "сот", "двохсот", "трьохсот", "чотирьохсот", "п'ятисот", "шестисот", "семисот", "восьмисот", "дев'ятисот")
    private val THOUSAND_PREFIX = arrayOf("", "", "двох", "трьох", "чотирьох", "п'яти", "шести", "семи", "восьми", "дев'яти")

    /** Grammatical form of an ordinal: masculine/neuter or feminine, and the case. */
    enum class Form(val hard: String, val soft: String) {
        NOM_M("ий", "ій"), GEN_M("ого", "ього"), LOC_M("ому", "ьому"),
        NOM_F("а", "я"), GEN_F("ої", "ьої"), LOC_F("ій", "ій"), ACC_F("у", "ю"),
        NOM_N("е", "є"), PLURAL("их", "іх"), PLURAL_NOM("і", "і"),
    }

    private fun below1000(n: Int, feminine: Boolean, out: MutableList<String>) {
        val h = n / 100; val r = n % 100
        if (h > 0) out += HUNDREDS[h]
        when {
            r in 10..19 -> out += TEENS[r - 10]
            else -> {
                if (r / 10 > 0) out += TENS[r / 10]
                if (r % 10 > 0) out += (if (feminine) ONES_F else ONES_M)[r % 10]
            }
        }
    }

    /** The plural form for [n]: 1 — [one], 2–4 — [few], else [many] (as in «кілометр/кілометри/кілометрів»). */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val a = n % 100; val b = n % 10
        return when { a in 11..14 -> many; b == 1L -> one; b in 2..4 -> few; else -> many }
    }

    /** A count in words; [feminine] for «одна, дві» (тисяча, гривня, година). */
    fun cardinal(n: Long, feminine: Boolean = false): String {
        if (n == 0L) return "нуль"
        if (n < 0) return "мінус " + cardinal(-n, feminine)
        val out = ArrayList<String>()
        var rest = n
        val billions = rest / 1_000_000_000; rest %= 1_000_000_000
        val millions = rest / 1_000_000; rest %= 1_000_000
        val thousands = rest / 1000; rest %= 1000
        if (billions > 0) { out += cardinal(billions); out += plural(billions, "мільярд", "мільярди", "мільярдів") }
        if (millions > 0) { below1000(millions.toInt(), false, out); out += plural(millions, "мільйон", "мільйони", "мільйонів") }
        if (thousands == 1L && out.isEmpty()) out += "тисяча"
        else if (thousands > 0) { below1000(thousands.toInt(), true, out); out += plural(thousands, "тисяча", "тисячі", "тисяч") }
        if (rest > 0) below1000(rest.toInt(), feminine, out)
        return out.joinToString(" ")
    }

    // The genitive of counts: «до десяти», «від п'яти», «близько тридцяти»
    private val ONES_GEN = arrayOf("нуля", "одного", "двох", "трьох", "чотирьох", "п'яти", "шести", "семи", "восьми", "дев'яти")
    private val ONES_GEN_F = arrayOf("нуля", "однієї", "двох", "трьох", "чотирьох", "п'яти", "шести", "семи", "восьми", "дев'яти")
    private val TEENS_GEN = arrayOf("десяти", "одинадцяти", "дванадцяти", "тринадцяти", "чотирнадцяти", "п'ятнадцяти", "шістнадцяти", "сімнадцяти", "вісімнадцяти", "дев'ятнадцяти")
    private val TENS_GEN = arrayOf("", "", "двадцяти", "тридцяти", "сорока", "п'ятдесяти", "шістдесяти", "сімдесяти", "вісімдесяти", "дев'яноста")
    private val HUNDREDS_GEN = arrayOf("", "ста", "двохсот", "трьохсот", "чотирьохсот", "п'ятисот", "шестисот", "семисот", "восьмисот", "дев'ятисот")

    private fun below1000Gen(n: Int, feminine: Boolean, out: MutableList<String>) {
        val h = n / 100; val r = n % 100
        if (h > 0) out += HUNDREDS_GEN[h]
        when {
            r in 10..19 -> out += TEENS_GEN[r - 10]
            else -> {
                if (r / 10 > 0) out += TENS_GEN[r / 10]
                if (r % 10 > 0) out += (if (feminine) ONES_GEN_F else ONES_GEN)[r % 10]
            }
        }
    }

    /** A count in the genitive («двох тисяч п'ятисот»), as after «до», «від», «близько». */
    fun cardinalGen(n: Long, feminine: Boolean = false): String {
        if (n == 0L) return "нуля"
        if (n < 0 || n >= 1_000_000_000) return cardinal(n, feminine)
        val out = ArrayList<String>()
        var rest = n
        val millions = rest / 1_000_000; rest %= 1_000_000
        val thousands = rest / 1000; rest %= 1000
        if (millions > 0) { below1000Gen(millions.toInt(), false, out); out += if (millions % 10 == 1L && millions % 100 != 11L) "мільйона" else "мільйонів" }
        if (thousands == 1L && out.isEmpty()) out += "тисячі"
        else if (thousands > 0) { below1000Gen(thousands.toInt(), true, out); out += if (thousands % 10 == 1L && thousands % 100 != 11L) "тисячі" else "тисяч" }
        if (rest > 0) below1000Gen(rest.toInt(), feminine, out)
        return out.joinToString(" ")
    }

    /** An ordinal in words («двадцять третього», «дві тисячі двадцять шостому»); only the last word changes. */
    fun ordinal(n: Long, form: Form): String {
        if (n <= 0 || n >= 1_000_000) return cardinal(n)
        fun end(stem: String) = stem + if (stem == "трет") form.soft else form.hard
        val thousands = n / 1000; val rest = (n % 1000).toInt()
        if (rest == 0) {
            // «двохтисячний», «тисячний»
            if (thousands >= 10) return cardinal(n)
            return end((if (thousands == 1L) "" else THOUSAND_PREFIX[thousands.toInt()]) + "тисячн")
        }
        val words = ArrayList<String>()
        if (thousands > 0) words += if (thousands == 1L) "тисяча" else cardinal(thousands * 1000)
        val h = rest / 100; val r = rest % 100
        if (r == 0) { words += end(ORD_HUNDREDS[h]); return words.joinToString(" ") }
        if (h > 0) words += HUNDREDS[h]
        when {
            r in 10..19 -> words += end(ORD_TEENS[r - 10])
            r % 10 == 0 -> words += end(ORD_TENS[r / 10])
            else -> { if (r / 10 > 0) words += TENS[r / 10]; words += end(ORD_ONES[r % 10]) }
        }
        return words.joinToString(" ")
    }

    private val MONTHS = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")

    private val DATE = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val DAY_MONTH = Regex("""\b(\d{1,2})\s+(${MONTHS.joinToString("|")})""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?:\b(у|в)\s+)?\b(\d{3,4})\s*(?:-?(?:го|му|й))?\s+(році|року|рік|р\.)""", RegexOption.IGNORE_CASE)
    private val YEARS = Regex("""\b(\d{3,4})\s*[–—-]\s*(\d{3,4})\s*(рр\.|роки|років|роках)""", RegexOption.IGNORE_CASE)
    private val DECADE = Regex("""\b(\d*0)-(х|их|ті|тих)\b""")
    private val TIME = Regex("""(?:\b(о|об|до|після|з|від)\s+)?\b([01]?\d|2[0-3])([:.])([0-5]\d)\b""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""(?<![\d+])(?:\+?\s?38\s?)?\(?0\s?\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}(?!\d)|(?<!\d)\+\d{7,15}(?!\d)""")
    // A minus at the start or after a space (not «10 - 15», a range)
    private val MINUS = Regex("""(^|(?<!\d)\s|\()[-−](\d)""")
    private val MONEY_UAH = Regex("""\b(\d+)[,.](\d{2})\s?(грн|₴)(?![\p{L}])""")
    private val MONEY_BEFORE = Regex("""([$€])\s?(\d+)(?:[,.](\d{2}))?""")
    private val MONEY_AFTER = Regex("""\b(\d+)(?:[,.](\d{2}))?\s?([$€₴])""")
    private val UNIT_NAMES = "%|км/год|км|мм|см|кг|грн|коп|год|хв|сек|тис|млн|млрд|°C|°|м|г|т|л|с"
    // (a unit's full stop goes when the sentence goes on: «2 тис. грн» is not the end of a sentence)
    private val WITH_UNIT = Regex("""(?:\b(\p{L}+)\s+)?\b(\d{1,12})\s?($UNIT_NAMES)(?![\p{L}])(?:\.(?=\s*[\p{Ll}₴$€]))?""")
    private val NUMBERED = Regex("""№\s?(\d+)""")
    private val DECIMAL_UNIT = Regex("""\b(\d+)[,.](\d+)\s?($UNIT_NAMES)(?![\p{L}])(?:\.(?=\s*[\p{Ll}₴$€]))?""")
    private val DECIMAL = Regex("""\b(\d+)[,.](\d+)\b""")
    private val ORDINAL_SUFFIX = Regex("""\b(\d+)-(й|ий|ій|го|ого|му|ому|та|тя|ту|тю|ої|те|тє|ше|ге|ме|ке|ша|га|ма|ка|х)\b""")
    private val NUMBER = Regex("""(?:\b(\p{L}+)\s+)?\b(\d+)\b""")
    private val SPACED = Regex("""\b(\d{1,3})(?:[  ](\d{3}))+\b""")
    /** Words after which a count is in the genitive. */
    private val GEN_WORDS = setOf("до", "від", "з", "із", "зі", "близько", "біля", "після", "без", "для", "кілька", "декілька", "більше", "менше", "протягом", "упродовж", "навколо", "щонайменше", "щонайбільше", "замість", "крім", "окрім", "серед")

    private class UnitName(val one: String, val few: String, val many: String, val feminine: Boolean = false, val gen: String = few)
    private val UNIT_FULL = mapOf(
        "%" to UnitName("відсоток", "відсотки", "відсотків", gen = "відсотка"),
        "км/год" to UnitName("кілометр на годину", "кілометри на годину", "кілометрів на годину", gen = "кілометра на годину"),
        "км" to UnitName("кілометр", "кілометри", "кілометрів", gen = "кілометра"), "м" to UnitName("метр", "метри", "метрів", gen = "метра"),
        "см" to UnitName("сантиметр", "сантиметри", "сантиметрів", gen = "сантиметра"), "мм" to UnitName("міліметр", "міліметри", "міліметрів", gen = "міліметра"),
        "кг" to UnitName("кілограм", "кілограми", "кілограмів", gen = "кілограма"), "г" to UnitName("грам", "грами", "грамів", gen = "грама"),
        "т" to UnitName("тонна", "тонни", "тонн", true), "л" to UnitName("літр", "літри", "літрів", gen = "літра"),
        "грн" to UnitName("гривня", "гривні", "гривень", true), "₴" to UnitName("гривня", "гривні", "гривень", true),
        "коп" to UnitName("копійка", "копійки", "копійок", true),
        "год" to UnitName("година", "години", "годин", true), "хв" to UnitName("хвилина", "хвилини", "хвилин", true),
        "с" to UnitName("секунда", "секунди", "секунд", true), "сек" to UnitName("секунда", "секунди", "секунд", true),
        "$" to UnitName("долар", "долари", "доларів", gen = "долара"), "€" to UnitName("євро", "євро", "євро", gen = "євро"),
        "°C" to UnitName("градус Цельсія", "градуси Цельсія", "градусів Цельсія", gen = "градуса Цельсія"), "°" to UnitName("градус", "градуси", "градусів", gen = "градуса"),
        "тис" to UnitName("тисяча", "тисячі", "тисяч", true), "млн" to UnitName("мільйон", "мільйони", "мільйонів", gen = "мільйона"), "млрд" to UnitName("мільярд", "мільярди", "мільярдів", gen = "мільярда"),
    )
    private val CENTS = mapOf("$" to UnitName("цент", "центи", "центів"), "€" to UnitName("цент", "центи", "центів"), "₴" to UnitName("копійка", "копійки", "копійок", true), "грн" to UnitName("копійка", "копійки", "копійок", true))

    /** [n] with its unit, in the right form; after «до», «від»… in the genitive. */
    private fun counted(n: Long, u: UnitName, genitive: Boolean): String =
        if (genitive) cardinalGen(n, u.feminine) + " " + (if (n % 10 == 1L && n % 100 != 11L) u.gen else u.many)
        else cardinal(n, u.feminine) + " " + plural(n, u.one, u.few, u.many)

    /** The digits of a fraction: «нуль нуль п'ять», «п'ятдесят». */
    private fun fraction(f: String, feminine: Boolean): String {
        val zeros = f.takeWhile { it == '0' }.length
        val rest = f.drop(zeros)
        return (List(zeros.coerceAtMost(f.length - if (rest.isEmpty()) 1 else 0)) { "нуль" } + (if (rest.isEmpty()) emptyList() else listOf(cardinal(rest.toLongOrNull() ?: 0, feminine)))).joinToString(" ")
    }

    /** Phone numbers read digit by digit («+» as «плюс»), before anything joins their groups into one big number. */
    fun phones(text: String): String = PHONE.replace(text) { m ->
            m.value.replace("+", " плюс ").split(Regex("""[\s()\-]+""")).filter { it.isNotBlank() }.joinToString(" ") { g ->
                if (g.all { it.isDigit() }) (if (g.length > 2 || g.startsWith("0")) g.map { ONES_M[it - '0'] }.joinToString(" ") else cardinal(g.toLong())) else g
            }.trim()
        }

    /** [text] with its numbers written out. */
    fun expand(text: String): String {
        var t = phones(text)
        t = SPACED.replace(t) { it.value.replace(" ", "").replace(" ", "") }
        t = MINUS.replace(t) { m -> m.groupValues[1] + "мінус " + m.groupValues[2] }
        t = DATE.replace(t) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].toLongOrNull() ?: return@replace m.value
            if (d !in 1..31 || mo !in 1..12) m.value
            else "${ordinal(d.toLong(), Form.GEN_M)} ${MONTHS[mo - 1]} ${ordinal(y, Form.GEN_M)} року"
        }
        t = DAY_MONTH.replace(t) { m -> "${ordinal(m.groupValues[1].toLongOrNull() ?: return@replace m.value, Form.GEN_M)} ${m.groupValues[2]}" }
        t = YEARS.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value; val b = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val noun = m.groupValues[3].lowercase().let { if (it == "рр.") "років" else it }
            val form = if (noun == "роках") Form.LOC_M else Form.GEN_M
            "${ordinal(a, form)} – ${ordinal(b, form)} $noun"
        }
        t = YEAR.replace(t) { m ->
            val prep = m.groupValues[1]; val y = m.groupValues[2].toLongOrNull() ?: return@replace m.value; val word = m.groupValues[3].lowercase()
            val loc = prep.isNotEmpty() || word == "році"
            val noun = when { word == "р." -> if (loc) "році" else "року"; else -> word }
            val form = when (noun) { "рік" -> Form.NOM_M; "році" -> Form.LOC_M; else -> Form.GEN_M }
            (if (prep.isNotEmpty()) "$prep " else "") + ordinal(y, form) + " " + noun
        }
        // «1990-х» — «дев'яностих» (the plural of the ordinal)
        t = DECADE.replace(t) { m -> m.groupValues[1].toLongOrNull()?.takeIf { it >= 10 }?.let { ordinal(it, if (m.groupValues[2] == "ті") Form.PLURAL_NOM else Form.PLURAL) } ?: m.value }
        t = TIME.replace(t) { m ->
            val prep = m.groupValues[1].lowercase(); val h = m.groupValues[2].toLong(); val min = m.groupValues[4].toLong()
            // «9.30» is a time only after «о», «до»…; on its own it may be a number
            if (m.groupValues[3] == "." && prep.isEmpty()) return@replace m.value
            val hourForm = when (prep) { "о", "об" -> Form.LOC_F; "до", "після", "з", "від" -> Form.GEN_F; else -> Form.NOM_F }
            val minutes = if (min == 0L) "" else " " + (if (min < 10) "нуль " else "") + cardinal(min, true)
            (if (prep.isNotEmpty()) m.groupValues[1] + " " else "") + (if (h == 0L) "нуль" else ordinal(h, hourForm)) + minutes
        }
        // Money with kopecks or cents: «12,50 грн» — «дванадцять гривень п'ятдесят копійок»
        t = MONEY_UAH.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value; val k = m.groupValues[2].toLong()
            counted(a, UNIT_FULL["грн"]!!, false) + if (k > 0) " " + counted(k, CENTS["грн"]!!, false) else ""
        }
        t = MONEY_BEFORE.replace(t) { m ->
            val a = m.groupValues[2].toLongOrNull() ?: return@replace m.value; val c = m.groupValues[3].toLongOrNull() ?: 0
            val sign = m.groupValues[1]
            counted(a, UNIT_FULL[sign]!!, false) + if (c > 0) " " + counted(c, CENTS[sign]!!, false) else ""
        }
        t = MONEY_AFTER.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value; val c = m.groupValues[2].toLongOrNull() ?: 0
            val sign = m.groupValues[3]
            counted(a, UNIT_FULL[sign]!!, false) + if (c > 0) " " + counted(c, CENTS[sign]!!, false) else ""
        }
        t = DECIMAL_UNIT.replace(t) { m ->
            val u = UNIT_FULL[m.groupValues[3]] ?: return@replace m.value
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            if (frac.length > 6) m.value else cardinal(a, true) + " кома " + fraction(frac, true) + " " + (if (u.feminine) u.few else u.gen)
        }
        t = DECIMAL.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            if (frac.length > 6) m.value else cardinal(a, true) + " кома " + fraction(frac, true)
        }
        t = WITH_UNIT.replace(t) { m ->
            val before = m.groupValues[1]
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value; val u = UNIT_FULL[m.groupValues[3]] ?: return@replace m.value
            (if (before.isNotEmpty()) "$before " else "") + counted(n, u, before.lowercase() in GEN_WORDS)
        }
        // «5 млн грн», «2 тисячі грн»: the money after a big number is in the genitive plural
        t = t.replace(Regex("""(тисяч\p{L}*|мільйон\p{L}*|мільярд\p{L}*)\s+(грн|₴|\$|€)(?![\p{L}])(?:\.(?=\s*\p{Ll}))?""")) { m -> m.groupValues[1] + " " + UNIT_FULL[m.groupValues[2]]!!.many }
        t = NUMBERED.replace(t) { m -> m.groupValues[1].toLongOrNull()?.let { "номер " + cardinal(it) } ?: m.value }
        t = ORDINAL_SUFFIX.replace(t) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            when (val s = m.groupValues[2]) {
                // «у 2-х томах», «з 3-ма»: a count, not an ordinal
                "х" -> cardinalGen(n)
                else -> ordinal(n, when (s) {
                    "й", "ий" -> Form.NOM_M; "го", "ого" -> Form.GEN_M; "му", "ому" -> Form.LOC_M
                    "та", "тя", "ша", "га", "ма", "ка" -> Form.NOM_F; "ої" -> Form.GEN_F; "ту", "тю" -> Form.ACC_F
                    "те", "тє", "ше", "ге", "ме", "ке" -> Form.NOM_N
                    else -> Form.LOC_F
                })
            }
        }
        t = NUMBER.replace(t) { m ->
            val before = m.groupValues[1]; val digits = m.groupValues[2]
            val n = digits.toLongOrNull() ?: return@replace m.value
            val words = when {
                digits.length > 12 -> digits.map { ONES_M[it - '0'] }.joinToString(" ")
                before.lowercase() in GEN_WORDS -> cardinalGen(n)
                else -> cardinal(n)
            }
            (if (before.isNotEmpty()) "$before " else "") + words
        }
        return t
    }

}
