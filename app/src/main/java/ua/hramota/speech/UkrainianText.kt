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
        Regex("""\bт\.\s?д\.""") to "так далі", Regex("""\bт\.\s?п\.""") to "тому подібне", Regex("""\bт\.\s?зв\.""") to "так званий",
        Regex("""\bі\s?т\.\s?ін\.""") to "і таке інше", Regex("""\bта\s?ін\.""") to "та інші", Regex("""\bнапр\.""") to "наприклад",
        Regex("""\bзокр\.""") to "зокрема", Regex("""\bдив\.""") to "дивіться", Regex("""\bім\.""") to "імені",
        Regex("""\bвул\.""") to "вулиця", Regex("""\bпросп\.""") to "проспект", Regex("""\bобл\.""") to "область",
        Regex("""\bст\.(?=\s*\d)""") to "стаття", Regex("""\bс\.(?=\s*\d)""") to "сторінка", Regex("""\bрис\.(?=\s*\d)""") to "рисунок",
        Regex("""\bтабл\.(?=\s*\d)""") to "таблиця", Regex("""\bп\.(?=\s*\d)""") to "пункт", Regex("""\bч\.(?=\s*\d)""") to "частина",
        Regex("""\bтел\.""") to "телефон", Regex("""\bпроф\.""") to "професор", Regex("""\bд-р\b""") to "доктор",
        // (not after a number: «1991 р. Україна» is a year, «5 м. Далі» metres; numbers come after these)
        Regex("""(?<!\d\s?)\bм\.(?=\s*\p{Lu})""") to "місто", Regex("""(?<!\d\s?)\bс\.(?=\s*\p{Lu})""") to "село", Regex("""(?<!\d\s?)\bр\.(?=\s*\p{Lu})""") to "річка",
        Regex("""(?<=[\dIVXLC]\s?)\bст\.""") to "століття",
    )

    private val GEN_PREP = setOf("до", "з", "із", "зі", "від", "без", "для", "біля", "після", "крім", "окрім", "серед", "навколо", "замість", "щодо", "коло", "проти", "від", "у", "в")
    private val LOC_PREP = setOf("у", "в", "на", "при", "о", "об", "по")
    private val INS_PREP = setOf("з", "із", "зі", "над", "під", "перед", "за", "між", "поміж")
    private val ACC_PREP = setOf("на", "в", "у", "за", "про", "через", "під", "над", "крізь")

    /** Words where the dictionary has one stress but the usual reading is another (heard and corrected). */
    private val CORRECTIONS = mapOf<String, String>()

    /** The text the voice reads, with stresses. */
    fun prepare(sentence: String): String {
        var t = sentence
        for ((r, w) in ABBREVIATIONS) t = r.replace(t, w)
        t = UkrainianNumbers.expand(t)
        t = t.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace('‘', '\'')
        val words = WORD.findAll(t).toList()
        val sb = StringBuilder()
        var last = 0
        for ((k, m) in words.withIndex()) {
            sb.append(t, last, m.range.first)
            val prev = words.getOrNull(k - 1)?.value?.lowercase()
            val next = words.getOrNull(k + 1)?.value?.lowercase()
            sb.append(stressed(m.value, prev, next))
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
        if (prev != null) {
            val cases = buildList {
                if (prev in GEN_PREP) add(T.GEN)
                if (prev in LOC_PREP) add(T.LOC)
                if (prev in INS_PREP) add(T.INS)
                if (prev in ACC_PREP) add(T.ACC)
            }
            for (c in cases) pick { it.has(c) }?.let { return it }
        }
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
