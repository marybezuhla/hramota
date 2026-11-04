package ua.hramota.speech

import ua.hramota.speech.StressDictionary.Companion as T

/**
 * Ukrainian text made ready for the voice: abbreviations and numbers written out, every word given its stress
 * («+» before the stressed vowel, as the voice was trained), and only the letters and marks the voice knows.
 */
class UkrainianText(private val dictionary: StressDictionary?) {
    private val VOWELS = "аеєиіїоуюя"

    // Short forms read in full (only where the meaning is sure)
    private val ABBREVIATIONS = listOf(
        // (these end sentences: the full stop stays, so the voice ends the sentence)
        Regex("""\bт\.\s?д\.""") to "так далі.", Regex("""\bт\.\s?п\.""") to "тому подібне.", Regex("""\bт\.\s?зв\.""") to "так званий",
        Regex("""\bі\s?т\.\s?ін\.""") to "і таке інше.", Regex("""\bта\s?ін\.""") to "та інші.", Regex("""\bнапр\.""") to "наприклад",
        Regex("""\bзокр\.""") to "зокрема", Regex("""\bдив\.""") to "дивіться", Regex("""\bім\.""") to "імені",
        Regex("""\bвул\.""") to "вулиця", Regex("""\bпросп\.""") to "проспект", Regex("""\bобл\.""") to "область",
        Regex("""\bст\.(?=\s*\d)""") to "стаття", Regex("""\bс\.(?=\s*\d)""") to "сторінка", Regex("""\bрис\.(?=\s*\d)""") to "рисунок",
        Regex("""\bтабл\.(?=\s*\d)""") to "таблиця", Regex("""\bп\.(?=\s*\d)""") to "пункт", Regex("""\bч\.(?=\s*\d)""") to "частина",
        Regex("""\bтел\.""") to "телефон", Regex("""\bпроф\.""") to "професор", Regex("""\bд-р\b""") to "доктор",
        // (not after a number: «1991 р. Україна» is a year, «5 м. Далі» metres; numbers come after these)
        Regex("""(?<!\d\s?)\bм\.(?=\s*\p{Lu})""") to "місто", Regex("""(?<!\d\s?)\bс\.(?=\s*\p{Lu})""") to "село", Regex("""(?<!\d\s?)\bр\.(?=\s*\p{Lu})""") to "річка",
        Regex("""(?<=\d\s?)\bст\.""") to "століття",
    )

    // Short forms said letter by letter (stress on the last letter)
    private val LETTERS = mapOf('а' to "а", 'б' to "бе", 'в' to "ве", 'г' to "ге", 'ґ' to "ґе", 'д' to "де", 'е' to "е", 'є' to "є", 'ж' to "же",
        'з' to "зе", 'и' to "и", 'і' to "і", 'ї' to "ї", 'й' to "йот", 'к' to "ка", 'л' to "ел", 'м' to "ем", 'н' to "ен", 'о' to "о", 'п' to "пе",
        'р' to "ер", 'с' to "ес", 'т' to "те", 'у' to "у", 'ф' to "еф", 'х' to "ха", 'ц' to "це", 'ч' to "че", 'ш' to "ша", 'щ' to "ща", 'ю' to "ю", 'я' to "я")
    private val SPELLED = setOf("США", "ЗСУ", "ЄС", "ЗМІ", "ЗНО", "НМТ", "ВВП", "ПДВ", "ФОП", "ЄДРПОУ", "РНОКПП", "ДСНС", "СБУ", "МВС", "МОЗ", "КМУ",
        "ВРУ", "ПК", "ТОВ", "ПП", "ДП", "ЗУ", "ОСББ", "АЗС", "ДТП", "ІТ", "ЄІСУБ", "ЦНАП", "ПФУ", "ДПС", "МОН", "ЗВО", "ОТГ", "ЕЦП", "КЕП")

    private fun spell(w: String): String {
        val parts = w.lowercase().map { LETTERS[it] ?: it.toString() }
        val joined = parts.joinToString("")
        // The stress on the last letter's vowel: «есше+а»
        val last = joined.indexOfLast { it in VOWELS }
        return if (last < 0) joined else joined.substring(0, last) + "+" + joined.substring(last)
    }

    // Roman numerals of centuries: «XX ст.», «у XIX столітті»
    private val ROMAN_CENTURY = Regex("""(?:\b(у|в)\s+)?\b([IVXL]{1,6})\s*(ст\.|століття|столітті)""")

    private fun roman(r: String): Int? {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50)
        var total = 0
        for (i in r.indices) { val a = v[r[i]] ?: return null; val b = r.getOrNull(i + 1)?.let { v[it] } ?: 0; total += if (a < b) -a else a }
        return total.takeIf { it in 1..30 }
    }

    // The cases prepositions ask for, the likelier first (choose() tries them in this order)
    private val PREP_CASES: Map<String, List<Int>> = buildMap {
        for (p in listOf("до", "від", "без", "для", "біля", "після", "крім", "окрім", "серед", "навколо", "замість", "щодо", "коло", "проти", "поза", "вздовж",
            "протягом", "упродовж", "поблизу", "посеред", "із-за", "з-під", "з-за", "з-поміж", "внаслідок", "близько", "кінець", "край")) put(p, listOf(T.GEN))
        for (p in listOf("у", "в")) put(p, listOf(T.LOC, T.ACC, T.GEN))
        put("на", listOf(T.LOC, T.ACC)); put("при", listOf(T.LOC)); put("о", listOf(T.LOC, T.ACC)); put("об", listOf(T.LOC, T.ACC))
        put("по", listOf(T.DAT, T.LOC, T.ACC))
        for (p in listOf("з", "із", "зі")) put(p, listOf(T.GEN, T.INS, T.ACC))
        for (p in listOf("над", "під", "перед", "між", "поміж")) put(p, listOf(T.INS, T.ACC))
        put("за", listOf(T.INS, T.ACC)); put("про", listOf(T.ACC)); put("через", listOf(T.ACC)); put("крізь", listOf(T.ACC)); put("повз", listOf(T.ACC))
        put("понад", listOf(T.ACC, T.INS)); put("завдяки", listOf(T.DAT)); put("всупереч", listOf(T.DAT)); put("назустріч", listOf(T.DAT))
    }

    // «один» before a noun takes the noun's gender: «одна книга», «одне місто» (told by the noun's ending, as the
    // dictionary has no genders; masculine nouns in -о/-а are listed)
    private val MASCULINE_O_A = setOf("батько", "дядько", "тато", "дідо", "петро", "дмитро", "павло", "суддя", "староста", "воєвода", "слуга", "голова", "колега", "сирота", "бідолаха", "владика",
        "микола", "лука", "кузьма", "микита", "михайло", "данило", "марко", "дніпро", "ілля", "хома", "фома", "сава", "яким")
    // Feminine although they end like neuter words: «одна стаття»
    private val FEMININE_YA = setOf("стаття", "сім'я", "рілля", "кутя", "попадя")
    private val NEUTER_YA = listOf("ння", "ття", "ддя", "ззя", "лля", "сся", "ччя", "жжя", "шшя", "'я", "цтво", "ство")
    // Counting words: «один два три» (a phone number read out) is not «одна два»
    private val NUMERALS = setOf("нуль", "два", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять", "десять", "сто", "двісті", "тисяча",
        "тисячі", "тисяч", "мільйон", "мільйона", "мільйонів", "кома", "плюс", "мінус", "двадцять", "тридцять", "сорок", "п'ятдесят", "шістдесят",
        "сімдесят", "вісімдесят", "дев'яносто", "триста", "чотириста", "п'ятсот", "шістсот", "сімсот", "вісімсот", "дев'ятсот")
    private val KEEP = '\uE000'
    private val WORD_ONE = Regex("""(?<![\p{L}'])один(?![\p{L}'])""", RegexOption.IGNORE_CASE)
    private val ONE_BEFORE = Regex("""(?<![\p{L}'])(один)(\s+)([\p{L}']{3,})""", RegexOption.IGNORE_CASE)

    private fun agree(text: String): String = ONE_BEFORE.replace(text) { m ->
        val noun = m.groupValues[3].lowercase()
        val one = m.groupValues[1]
        val form = when {
            noun in MASCULINE_O_A || noun in NUMERALS -> null
            noun in FEMININE_YA -> "одна"
            NEUTER_YA.any { noun.endsWith(it) } || noun.endsWith("о") || noun.endsWith("е") -> "одне"
            noun.endsWith("а") || noun.endsWith("я") -> "одна"
            else -> null
        } ?: return@replace m.value
        // (only before a word the dictionary knows as a noun-like word: «один з них» stays)
        if (dictionary != null && dictionary.lookup(noun).isEmpty()) return@replace m.value
        (if (one[0].isUpperCase()) form.replaceFirstChar { it.uppercase() } else form) + m.groupValues[2] + m.groupValues[3]
    }

    /** Words whose stress depends on the next one: «черго́вий лікар» (next in turn, on duty for the owner), «чергови́й» alone (the one on duty). */
    private fun byContext(lower: String, next: String?): String? = when (lower) {
        "черговий" -> if (next != null && next.length > 2 && !next.endsWith("в") && !next.endsWith("ла") && !next.endsWith("ли") && !next.endsWith("ло")) "черг+овий" else "чергов+ий"
        else -> null
    }

    /** Words where the dictionary has one stress but the usual reading is another (heard and corrected). */
    private val CORRECTIONS = mapOf<String, String>()

    /** The text the voice reads, with stresses. */
    fun prepare(sentence: String): String {
        // Marks the text may already have: a stress written in it (kept, as the voice's «+»), soft hyphens and
        // invisible characters (gone, they would cut words), and «+» itself, which the voice takes for a stress
        var t = sentence.replace("\u00AD", "").replace("\u200B", "").replace("\u200C", "").replace("\u200D", "").replace("\u2060", "")
        t = UkrainianNumbers.phones(t)
        t = Regex("""\+(?=\s?\d)""").replace(t, " плюс ").replace("+", " ")
        t = Regex("""([аеєиіїоуюяАЕЄИІЇОУЮЯ])\u0301""").replace(t) { "+" + it.groupValues[1] }.replace("\u0301", "")
        t = ROMAN_CENTURY.replace(t) { m ->
            val n = roman(m.groupValues[2]) ?: return@replace m.value
            val prep = m.groupValues[1]
            if (prep.isNotEmpty() || m.groupValues[3] == "столітті") (if (prep.isNotEmpty()) "$prep " else "") + UkrainianNumbers.ordinal(n.toLong(), UkrainianNumbers.Form.LOC_M) + " столітті"
            else UkrainianNumbers.ordinal(n.toLong(), UkrainianNumbers.Form.GEN_M) + " століття"
        }
        for ((r, w) in ABBREVIATIONS) t = r.replace(t, w)
        t = Regex("""\b\p{Lu}{2,6}\b""").replace(t) { m -> if (m.value in SPELLED) spell(m.value) else m.value }
        // «один» written as a word stays as written («один одного», «один удома»): only a number agrees with its noun
        t = WORD_ONE.replace(t) { it.value.dropLast(1) + KEEP + it.value.last() }
        t = UkrainianNumbers.expand(t)
        t = agree(t).replace(KEEP.toString(), "")
        t = t.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace('‘', '\'')
        val words = WORD.findAll(t).toList()
        val sb = StringBuilder()
        var last = 0
        for ((k, m) in words.withIndex()) {
            sb.append(t, last, m.range.first)
            // The preposition a word follows may be one or two words back («у старому замку»)
            val prev = words.getOrNull(k - 1)?.value?.lowercase()?.replace("+", "")
            val prev2 = words.getOrNull(k - 2)?.value?.lowercase()?.replace("+", "")
            val next = words.getOrNull(k + 1)?.value?.lowercase()?.replace("+", "")
            sb.append(stressed(m.value, if (prev != null && prev !in PREP_CASES && prev2 in PREP_CASES) prev2 else prev, next))
            last = m.range.last + 1
        }
        sb.append(t, last, t.length)
        return sb.toString()
    }

    /** [word] with «+» before its stressed vowel. */
    fun stressed(word: String, prev: String? = null, next: String? = null): String {
        val lower = word.lowercase()
        if (lower.any { it == '+' }) return word
        val vowels = lower.count { it in VOWELS }
        if (vowels == 0) return word
        CORRECTIONS[lower]?.let { return keepCase(word, it) }
        byContext(lower, next)?.let { return keepCase(word, it) }
        // One vowel: marked too, as the voice was trained («д+о», «м+и»)
        if (vowels == 1) return mark(word, intArrayOf(lower.indexOfFirst { it in VOWELS } + 1))
        val options = dictionary?.lookup(lower).orEmpty()
        if (options.isEmpty()) {
            // A word joined by a hyphen: each part on its own
            if ('-' in word) return word.split('-').joinToString("-") { stressed(it, prev, next) }
            return word
        }
        val chosen = if (options.size == 1) options[0] else choose(options, prev, next)
        return mark(word, chosen.positions)
    }

    /** Of a word's readings, the one its neighbours point to: the case a preposition asks for, an adjective before a noun. */
    private fun choose(options: List<StressDictionary.Option>, prev: String?, next: String?): StressDictionary.Option {
        val distinct = options.distinctBy { it.positions.toList() }
        if (distinct.size == 1) return distinct[0]
        fun pick(test: (StressDictionary.Option) -> Boolean) = distinct.firstOrNull(test)
        // Before a noun, an adjective
        if (next != null) {
            val nextIsNoun = dictionary?.lookup(next).orEmpty().any { it.has(T.NOUN) || it.has(T.NOUN2) }
            if (nextIsNoun) pick { it.has(T.ADJ) }?.let { return it }
        }
        // After a preposition, the case it asks for
        PREP_CASES[prev]?.let { cases -> for (c in cases) pick { it.has(c) }?.let { return it } }
        // Otherwise the plain (nominative) one, then the first
        return pick { it.has(T.NOM) } ?: distinct[0]
    }

    private fun mark(word: String, positions: IntArray): String {
        val sb = StringBuilder(word)
        // The source marks the index after the stressed vowel; the voice wants «+» before it
        for (p in positions.sortedDescending()) {
            val v = p - 1
            if (v in word.indices && word[v].lowercaseChar() in VOWELS) sb.insert(v, '+')
        }
        return sb.toString()
    }

    private fun keepCase(original: String, corrected: String) =
        if (original.firstOrNull()?.isUpperCase() == true) corrected.replaceFirstChar { it.uppercase() } else corrected

    companion object {
        private val WORD = Regex("""[\p{L}'+]+(?:-[\p{L}'+]+)*""")
    }
}
