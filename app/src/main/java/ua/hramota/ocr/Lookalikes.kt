package ua.hramota.ocr

/**
 * a word read in Latin letters inside a Cyrillic line is usually a Cyrillic word whose
 * letters merely look Latin («Mizec» for «Мізес», «Pim» for «Рім»). Such a word is turned into
 * Cyrillic when every one of its letters has a Cyrillic look-alike and the rest of the line is
 * clearly Cyrillic. Words with other Latin letters («WELL», «Pixel») stay as they are.
 */
internal object Lookalikes {
    private val map = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'I' to 'І', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'i' to 'і', 'k' to 'к', 'm' to 'м', 'o' to 'о', 'p' to 'р',
        'x' to 'х', 'y' to 'у',
        // Not look-alikes on paper, but the recognizer confuses them with з
        'z' to 'з', 'Z' to 'З',
    )

    private val word = Regex("\\p{L}+")

    private fun isCyrillic(c: Char) = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC

    /** Words written only in letters that look the same in both scripts, read as Cyrillic ("TAK" → "ТАК"). */
    fun toCyrillic(text: String): String = word.replace(text) { m ->
        val w = m.value
        if (w.any { it !in map }) w else w.map { map.getValue(it) }.joinToString("")
    }

    fun fix(line: String): String {
        if (word.findAll(line).count() < 2) return line
        return word.replace(line) { m ->
            val w = m.value
            if (w.length < 2 || w.any { it !in map }) return@replace w
            // The rest of the line: its letters must be mostly Cyrillic
            val rest = line.removeRange(m.range)
            val cyr = rest.count(::isCyrillic)
            val lat = rest.count { it in 'A'..'Z' || it in 'a'..'z' }
            if (cyr >= 3 && cyr >= 2 * lat) w.map { map.getValue(it) }.joinToString("") else w
        }
    }
}
