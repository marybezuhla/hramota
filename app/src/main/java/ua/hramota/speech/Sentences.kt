package ua.hramota.speech

/**
 * Text cut into sentences to be read one by one (and shown as they are read). A full stop after a short word
 * («р.», «ст.», «т. д.», «Mr.»), an initial («Т. Шевченко») or inside a number («1.5») does not end a sentence.
 * A very long sentence is cut further at a semicolon, colon, dash or comma, so the voice never waits long.
 */
object Sentences {
    /** A sentence: [start] to [end] (exclusive) in the text it came from. */
    data class Span(val start: Int, val end: Int)

    private val ABBREVIATIONS = setOf(
        // Ukrainian
        "р", "рр", "ст", "с", "сс", "т", "д", "п", "ін", "інш", "ім", "вул", "просп", "пров", "м", "смт", "обл", "р-н",
        "тис", "млн", "млрд", "грн", "коп", "див", "напр", "зокр", "тощо", "проф", "доц", "акад", "канд", "д-р", "пан", "пані",
        "др", "гр", "тел", "№", "рис", "табл", "розд", "п", "пп", "ч", "кн", "вид", "ред", "упоряд", "пер", "арк", "стор",
        "кв", "буд", "о", "оз", "г", "хв", "сек", "год", "дол", "євр",
        // English
        "mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "e.g", "i.e", "no", "fig", "p", "pp", "jr", "sr", "inc", "ltd", "co",
    )
    const val LONG = 220

    fun split(text: String): List<Span> {
        val out = ArrayList<Span>()
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') {
                var j = i + 1
                while (j < n && (text[j] in ".!?…\"»”’)" )) j++
                if (c == '\n' || ends(text, i, j)) {
                    add(text, start, j, out)
                    start = j
                }
                i = j
                continue
            }
            i++
        }
        add(text, start, n, out)
        return out
    }

    /** Does the stop at [at] (with closing marks up to [after]) end a sentence? */
    private fun ends(text: String, at: Int, after: Int): Boolean {
        val c = text[at]
        // The text goes on without a space: a number, an address, «т.д.»
        if (after < text.length && !text[after].isWhitespace()) return false
        if (c != '.') return true
        // The word before the stop
        var k = at - 1
        while (k >= 0 && (text[k].isLetterOrDigit() || text[k] == '-' || text[k] == '.' || text[k] == '\'')) k--
        val word = text.substring(k + 1, at).lowercase()
        if (word.isEmpty()) return true
        if (word in ABBREVIATIONS) return false
        // An initial: one capital letter («Т. Г. Шевченко»)
        if (word.length == 1 && text[at - 1].isUpperCase()) return false
        // What comes next starts with a small letter: the sentence goes on
        var m = after
        while (m < text.length && text[m].isWhitespace()) m++
        if (m < text.length && text[m].isLowerCase()) return false
        return true
    }

    private fun add(text: String, from: Int, to: Int, out: MutableList<Span>) {
        var s = from; var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e || text.substring(s, e).none { it.isLetterOrDigit() }) return
        if (e - s <= LONG) { out += Span(s, e); return }
        // A long one: cut at the last pause mark before the limit, else at a space
        var p = s
        while (e - p > LONG) {
            val window = text.substring(p, p + LONG)
            val cut = listOf(";", ":", " — ", " – ", ",").map { window.lastIndexOf(it) }.firstOrNull { it > LONG / 3 }
                ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: LONG
            val q = p + cut + 1
            out += Span(p, q).trimmed(text)
            p = q
            while (p < e && text[p].isWhitespace()) p++
        }
        if (p < e) out += Span(p, e)
    }

    private fun Span.trimmed(text: String): Span { var e = end; while (e > start && text[e - 1].isWhitespace()) e--; return Span(start, e) }

    /** "uk" when the sentence is mostly Cyrillic, "en" when mostly Latin, else null (nothing to read). */
    fun language(s: CharSequence): String? {
        var cyr = 0; var lat = 0
        for (c in s) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c in "іїєґІЇЄҐ") cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return when { cyr == 0 && lat == 0 -> if (s.any { it.isDigit() }) "uk" else null; cyr >= lat -> "uk"; else -> "en" }
    }
}
