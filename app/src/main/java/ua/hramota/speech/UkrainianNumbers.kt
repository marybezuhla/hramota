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
        NOM_F("а", "я"), GEN_F("ої", "ьої"), LOC_F("ій", "ій"),
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
        if (thousands > 0) { below1000(thousands.toInt(), true, out); out += plural(thousands, "тисяча", "тисячі", "тисяч") }
        if (rest > 0) below1000(rest.toInt(), feminine, out)
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
        if (thousands > 0) words += cardinal(thousands * 1000)
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
    private val LOC_PREP = setOf("у", "в", "на", "при", "о", "об", "по")

    // A unit after a number, with its forms (one, few, many) and gender
    private class Unit(val one: String, val few: String, val many: String, val feminine: Boolean = false)
    private val UNITS = mapOf(
        "%" to Unit("відсоток", "відсотки", "відсотків"),
        "км" to Unit("кілометр", "кілометри", "кілометрів"), "м" to Unit("метр", "метри", "метрів"),
        "см" to Unit("сантиметр", "сантиметри", "сантиметрів"), "мм" to Unit("міліметр", "міліметри", "міліметрів"),
        "кг" to Unit("кілограм", "кілограми", "кілограмів"), "г" to Unit("грам", "грами", "грамів"),
        "т" to Unit("тонна", "тонни", "тонн", true), "л" to Unit("літр", "літри", "літрів"),
        "грн" to Unit("гривня", "гривні", "гривень", true), "коп" to Unit("копійка", "копійки", "копійок", true),
        "год" to Unit("година", "години", "годин", true), "хв" to Unit("хвилина", "хвилини", "хвилин", true),
        "с" to Unit("секунда", "секунди", "секунд", true), "сек" to Unit("секунда", "секунди", "секунд", true),
        "$" to Unit("долар", "долари", "доларів"), "€" to Unit("євро", "євро", "євро"),
        "тис" to Unit("тисяча", "тисячі", "тисяч", true), "млн" to Unit("мільйон", "мільйони", "мільйонів"), "млрд" to Unit("мільярд", "мільярди", "мільярдів"),
    )

    private val DATE = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val DAY_MONTH = Regex("""\b(\d{1,2})\s+(${MONTHS.joinToString("|")})""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?:\b(у|в)\s+)?\b(\d{3,4})\s*(?:-?(?:го|му|й))?\s+(році|року|рік|р\.)""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""(?:\b(о|об|до|після|з|від)\s+)?\b([01]?\d|2[0-3])([:.])([0-5]\d)\b""", RegexOption.IGNORE_CASE)
    private val CURRENCY_BEFORE = Regex("""([$€])\s?(\d+)""")
    private val WITH_UNIT = Regex("""\b(\d{1,12})\s?(%|км|мм|см|кг|грн|коп|год|хв|сек|тис|млн|млрд|м|г|т|л|с)(?![\p{L}])\.?""")
    private val NUMBERED = Regex("""№\s?(\d+)""")
    private val DECIMAL = Regex("""\b(\d+)[,.](\d+)\b""")
    private val ORDINAL_SUFFIX = Regex("""\b(\d+)-(?:й|ий|ій|го|ого|му|ому|та|тя|ту|ої|ій)\b""")
    private val NUMBER = Regex("""\b\d+\b""")
    private val SPACED = Regex("""\b(\d{1,3})(?:[  ](\d{3}))+\b""")

    /** [text] with its numbers written out. */
    fun expand(text: String): String {
        var t = SPACED.replace(text) { it.value.replace(" ", "").replace(" ", "") }
        t = DATE.replace(t) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].toLong()
            if (d !in 1..31 || mo !in 1..12) m.value
            else "${ordinal(d.toLong(), Form.GEN_M)} ${MONTHS[mo - 1]} ${ordinal(y, Form.GEN_M)} року"
        }
        t = DAY_MONTH.replace(t) { m -> "${ordinal(m.groupValues[1].toLong(), Form.GEN_M)} ${m.groupValues[2]}" }
        t = YEAR.replace(t) { m ->
            val prep = m.groupValues[1]; val y = m.groupValues[2].toLong(); val word = m.groupValues[3].lowercase()
            val loc = prep.isNotEmpty() || word == "році"
            val noun = when { word == "р." -> if (loc) "році" else "року"; else -> word }
            val form = when (noun) { "рік" -> Form.NOM_M; "році" -> Form.LOC_M; else -> Form.GEN_M }
            (if (prep.isNotEmpty()) "$prep " else "") + ordinal(y, form) + " " + noun
        }
        t = TIME.replace(t) { m ->
            val prep = m.groupValues[1].lowercase(); val h = m.groupValues[2].toLong(); val min = m.groupValues[4].toLong()
            // «9.30» is a time only after «о», «до»…; on its own it may be a number
            if (m.groupValues[3] == "." && prep.isEmpty()) return@replace m.value
            val hourForm = when (prep) { "о", "об" -> Form.LOC_F; "до", "після", "з", "від" -> Form.GEN_F; else -> Form.NOM_F }
            val minutes = if (min == 0L) "" else " " + (if (min < 10) "нуль " else "") + cardinal(min, true)
            (if (prep.isNotEmpty()) m.groupValues[1] + " " else "") + ordinal(if (h == 0L) 24 else h, hourForm) + minutes
        }
        t = CURRENCY_BEFORE.replace(t) { m -> m.groupValues[2] + " " + m.groupValues[1] }
        t = WITH_UNIT.replace(t) { m ->
            val n = m.groupValues[1].toLong(); val u = UNITS[m.groupValues[2]] ?: return@replace m.value
            cardinal(n, u.feminine) + " " + plural(n, u.one, u.few, u.many)
        }
        t = t.replace(Regex("""(\d+)\s?([$€])""")) { m ->
            val n = m.groupValues[1].toLong(); val u = UNITS[m.groupValues[2]]!!
            cardinal(n, u.feminine) + " " + plural(n, u.one, u.few, u.many)
        }
        t = NUMBERED.replace(t) { m -> "номер " + cardinal(m.groupValues[1].toLong()) }
        t = ORDINAL_SUFFIX.replace(t) { m ->
            val n = m.groupValues[1].toLong(); val s = m.value.substringAfter('-')
            val form = when (s) { "й", "ий", "ій" -> Form.NOM_M; "го", "ого" -> Form.GEN_M; "му", "ому" -> Form.LOC_M; "та", "тя" -> Form.NOM_F; "ої" -> Form.GEN_F; else -> Form.LOC_F }
            ordinal(n, form)
        }
        t = DECIMAL.replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            if (frac.length > 6) m.value else cardinal(a, true) + " кома " + frac.trimStart('0').let { f -> (if (frac.startsWith("0")) "нуль " else "") + cardinal(f.ifEmpty { "0" }.toLong()) }
        }
        t = NUMBER.replace(t) { m -> m.value.toLongOrNull()?.let { if (m.value.length > 12) m.value.map { ONES_M[it - '0'] }.joinToString(" ") else cardinal(it) } ?: m.value }
        return t
    }

    internal fun isLocPrep(w: String) = w.lowercase() in LOC_PREP
}
