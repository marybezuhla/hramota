package ua.hramota.text

import android.content.Context
import ua.hramota.data.Vault
import ua.hramota.speech.StressDictionary
import ua.hramota.speech.mapAsset

/**
 * Ukrainian spelling, on the phone only. A word is right when one of the dictionaries knows it: the stress
 * dictionary (2.8 million word forms, voice/stress.bin), the words dict_uk's Hunspell dictionary adds to it
 * (spell/uk_extra.bin, when built in), a few abbreviations, and the person's own words. Words with Latin letters
 * or digits, single letters and short capitals (abbreviations) are not checked.
 */
class Speller(private val dictionaries: List<StressDictionary>, private val own: Set<String> = emptySet()) {

    fun known(word: String): Boolean {
        val w = plain(word)
        if (w.isEmpty() || w in ABBREVIATIONS || w in own) return true
        return dictionaries.any { it.lookup(w).isNotEmpty() }
    }

    /** The places in [text] that look misspelt (start until end), in order; at most [max]. */
    fun mistakes(text: String, max: Int = Int.MAX_VALUE): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (t in words(text)) {
            if (out.size >= max) break
            if (!checked(text, t)) continue
            val word = text.substring(t.first, t.last + 1)
            if (known(word)) continue
            // A word with hyphens: only the parts not known are marked (пів-Європи, будь-хто)
            if ('-' in word) {
                var at = t.first
                for (part in word.split('-')) {
                    val r = at until at + part.length
                    if (part.count { it.isLetter() } > 1 && !known(part)) out += r
                    at += part.length + 1
                }
            } else out += t
        }
        return out
    }

    /** Words most likely meant by [word], best first: one letter changed, added, dropped or swapped (two for the usual slips). */
    fun suggestions(word: String, max: Int = 5): List<String> {
        val w = plain(word)
        if (w.length < 2 || w.length > 40) return emptyList()
        val cost = HashMap<String, Float>()
        fun offer(c: String, k: Float) { if (c != w && c.isNotEmpty() && (cost[c] ?: Float.MAX_VALUE) > k) cost[c] = k }
        val first = edits(w)
        for ((c, k) in first) offer(c, k)
        // Two of the usual slips (и/і, е/є, апостроф, м'який знак, a letter doubled) together
        for ((c, k) in first) if (k < 0.6f) for ((c2, k2) in edits(c, cheapOnly = true)) offer(c2, k + k2)
        val found = cost.entries.filter { known(it.key) }
            .sortedWith(compareBy<Map.Entry<String, Float>> { it.value }.thenBy { if (it.key[0] == w[0]) 0 else 1 }.thenBy { it.key })
            .map { it.key }
        return found.take(max).map { like(word, it) }
    }

    /** Candidate words one change away from [w], each with how unlikely the slip is. */
    private fun edits(w: String, cheapOnly: Boolean = false): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for (i in w.indices) {
            val c = w[i]
            // Dropped
            val dropCost = when { c == '\'' || c == 'ь' -> 0.4f; i > 0 && w[i - 1] == c -> 0.5f; else -> 1f }
            if (!cheapOnly || dropCost < 1f) out += (w.removeRange(i, i + 1)) to dropCost
            // Changed
            for (r in LETTERS) if (r != c) {
                val k = if (CONFUSED.any { c in it && r in it }) 0.4f else 1f
                if (!cheapOnly || k < 1f) out += (w.substring(0, i) + r + w.substring(i + 1)) to k
            }
            // Swapped with the next
            if (!cheapOnly && i + 1 < w.length && w[i + 1] != c) out += (w.substring(0, i) + w[i + 1] + c + w.substring(i + 2)) to 0.7f
        }
        // Added
        for (i in 0..w.length) for (r in LETTERS) {
            val k = when { r == '\'' || r == 'ь' -> 0.4f; (i > 0 && w[i - 1] == r) || (i < w.length && w[i] == r) -> 0.5f; else -> 1f }
            if (!cheapOnly || k < 1f) out += (w.substring(0, i) + r + w.substring(i)) to k
        }
        return out
    }

    companion object {
        private const val LETTERS = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя'"
        /** Letters often written one for another. */
        private val CONFUSED = listOf("иіїй", "еєи", "гґх", "ую", "ая", "ао", "шщ", "зс", "дт", "бп", "ву")
        /** Abbreviations written with a full stop, and the like, that the dictionaries do not have. */
        private val ABBREVIATIONS = setOf(
            "грн", "коп", "тис", "млн", "млрд", "трлн", "км", "кг", "см", "мм", "дм", "мл", "хв", "год", "сек", "стор", "табл",
            "рис", "див", "вул", "просп", "пров", "обл", "буд", "кв", "напр", "ім", "проф", "доц", "акад", "тел", "ст", "пп",
            "тощо", "рр", "тт", "зб", "вид", "вип", "розд", "абз",
        )

        /** Characters joining letters within one word. */
        private fun joiner(c: Char) = c == '\'' || c == '’' || c == 'ʼ' || c == '`' || c == '-' || c == '­'

        /** The word as the dictionaries keep it: lower case, one kind of apostrophe, no soft hyphens. */
        fun plain(word: String) = buildString(word.length) {
            for (c in word) when (c) { '’', 'ʼ', '`' -> append('\''); '­' -> {}; else -> append(c.lowercaseChar()) }
        }

        /** The words of [text]: runs of letters, joined by apostrophes and hyphens between letters. */
        fun words(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var i = 0
            while (i < text.length) {
                if (!text[i].isLetter()) { i++; continue }
                val s = i
                while (i < text.length && (text[i].isLetter() || joiner(text[i]) && i + 1 < text.length && text[i + 1].isLetter() && text[i - 1].isLetter())) i++
                out += s until i
            }
            return out
        }

        /** Whether a word is checked at all: Cyrillic only, not by a digit, more than one letter, not a short abbreviation. */
        private fun checked(text: String, r: IntRange): Boolean {
            val word = text.substring(r.first, r.last + 1)
            val letters = word.filter { it.isLetter() }
            if (letters.length < 2) return false
            if (letters.any { it !in 'Ѐ'..'ӿ' }) return false
            if (r.first > 0 && text[r.first - 1].isDigit() || r.last + 1 < text.length && text[r.last + 1].isDigit()) return false
            if (r.first > 0 && (text[r.first - 1] == '-' || text[r.first - 1] == '_' || text[r.first - 1] == '@' || text[r.first - 1] == '/')) return false
            if (letters.length <= 6 && letters.all { it.isUpperCase() }) return false
            return true
        }

        /** [s] in the case [like] was written in: all capitals, a capital first, or as it is. */
        fun like(like: String, s: String): String = when {
            like.length > 1 && like.filter { it.isLetter() }.all { it.isUpperCase() } -> s.uppercase()
            like.firstOrNull()?.isUpperCase() == true -> s.replaceFirstChar { it.uppercaseChar() }
            else -> s
        }
    }
}

/** The speller of this phone: the dictionaries are read once; the person's own words are kept beside them. */
object Spelling {
    const val OWN = "my-words.txt"
    private const val MAX_OWN = 20_000
    @Volatile private var dictionaries: List<StressDictionary>? = null
    @Volatile private var ownWords: Set<String>? = null

    private fun dictionaries(context: Context): List<StressDictionary> = dictionaries ?: synchronized(this) {
        dictionaries ?: listOfNotNull(
            try { StressDictionary(mapAsset(context, "voice/stress.bin")) } catch (e: Exception) { null },
            try { StressDictionary(mapAsset(context, "spell/uk_extra.bin")) } catch (e: Exception) { null },
        ).also { dictionaries = it }
    }

    /** The person's own words; none while the storage is locked. */
    fun own(context: Context): Set<String> {
        if (Vault.locked(context)) { ownWords = null; return emptySet() }
        ownWords?.let { return it }
        val words = try { Vault.readSmall(context, OWN)?.let { String(it, Charsets.UTF_8).split('\n').map { w -> w.trim() }.filter { w -> w.isNotEmpty() }.toSet() } } catch (e: Exception) { null } ?: emptySet()
        return words.also { ownWords = it }
    }

    /** Adds [word] to the person's own words (kept on the phone, sealed with locked storage on). */
    fun addOwn(context: Context, word: String) = synchronized(ua.hramota.ink.MyInk) {
        val w = Speller.plain(word).trim()
        if (w.isEmpty() || w.length > 60) return@synchronized
        val now = (own(context) + w).let { if (it.size > MAX_OWN) it.drop(it.size - MAX_OWN).toSet() else it }
        Vault.writeSmall(context, OWN, now.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        ownWords = now
    }

    /** Removes [word] from the person's own words. */
    fun removeOwn(context: Context, word: String) = synchronized(ua.hramota.ink.MyInk) {
        val now = own(context) - Speller.plain(word)
        Vault.writeSmall(context, OWN, now.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        ownWords = now
    }

    /** Locking or unlocking changed how the file is kept: read again next time. */
    fun reset() { ownWords = null }

    /** A speller with the dictionaries and the person's words; null when the dictionaries cannot be read. */
    fun speller(context: Context): Speller? {
        val d = dictionaries(context)
        if (d.isEmpty()) return null
        return Speller(d, own(context))
    }
}
