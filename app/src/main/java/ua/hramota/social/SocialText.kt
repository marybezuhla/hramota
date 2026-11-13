package ua.hramota.social

import java.text.BreakIterator

/** Where a post goes: its name and how many characters one post may have. */
data class Platform(val key: String, val name: String, val limit: Int, val countsLinksAs: Int = -1)

/**
 * Counting characters as the networks do, and cutting a long text into a thread of posts at the ends
 * of sentences (or words), each numbered «1/5».
 */
object SocialText {
    val PLATFORMS = listOf(
        // X counts every link as 23 characters
        Platform("x", "X", 280, countsLinksAs = 23),
        Platform("threads", "Threads", 500),
        Platform("bluesky", "Bluesky", 300),
        Platform("mastodon", "Mastodon", 500, countsLinksAs = 23),
        Platform("facebook", "Facebook", 63_206),
        Platform("instagram", "Instagram", 2_200),
        Platform("linkedin", "LinkedIn", 3_000),
        Platform("telegram", "Telegram", 4_096),
    )

    private val LINK = Regex("https?://\\S+")

    /** Characters as a person sees them (an emoji or a letter with an accent is one). */
    fun graphemes(s: String): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        var n = 0
        while (it.next() != BreakIterator.DONE) n++
        return n
    }

    /** How long [text] is for [p]. */
    fun length(text: String, p: Platform): Int {
        if (p.countsLinksAs < 0) return graphemes(text)
        var n = 0; var last = 0
        for (m in LINK.findAll(text)) { n += graphemes(text.substring(last, m.range.first)) + p.countsLinksAs; last = m.range.last + 1 }
        return n + graphemes(text.substring(last))
    }

    /**
     * [text] as posts no longer than [p] allows, numbered «1/N» at the end when there are more than
     * one ([numbered]). Paragraphs are kept together where they fit; a long one is cut between
     * sentences, a long sentence between words, a very long word wherever it must.
     */
    fun thread(text: String, p: Platform, numbered: Boolean = true): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (length(clean, p) <= p.limit) return listOf(clean)
        // Room for « 12/12» at the end, worked out for the number of posts it comes to
        var guess = 2
        while (true) {
            val suffix = if (numbered) " ${guess}/${guess}".length else 0
            val parts = pack(clean, p, p.limit - suffix)
            if (parts.size <= guess || guess > 999) {
                return if (numbered) parts.mapIndexed { i, s -> "$s ${i + 1}/${parts.size}" } else parts
            }
            guess = parts.size
        }
    }

    private fun pieces(text: String, kind: Int): List<String> {
        val it = if (kind == 0) BreakIterator.getSentenceInstance() else BreakIterator.getWordInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) { out += text.substring(start, end); start = end; end = it.next() }
        return out
    }

    private fun pack(text: String, p: Platform, room: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { val s = cur.toString().trim(); if (s.isNotEmpty()) out += s; cur.setLength(0) }
        fun fits(extra: String) = length((cur.toString() + extra).trim(), p) <= room
        fun add(piece: String, level: Int) {
            if (fits(piece)) { cur.append(piece); return }
            // Does not fit with what is there: a new post, unless it does not fit on its own either
            if (cur.isNotBlank() && length(piece.trim(), p) <= room) { flush(); cur.append(piece.trimStart()); return }
            when (level) {
                0 -> for (s in pieces(piece, 0)) add(s, 1)
                1 -> for (w in pieces(piece, 1)) add(w, 2)
                else -> {
                    // A word longer than a post: cut by characters
                    flush()
                    val chars = BreakIterator.getCharacterInstance().apply { setText(piece) }
                    var start = chars.first(); var end = chars.next()
                    while (end != BreakIterator.DONE) {
                        val g = piece.substring(start, end)
                        if (!fits(g)) flush()
                        cur.append(g); start = end; end = chars.next()
                    }
                }
            }
        }
        val paragraphs = text.split(Regex("\n\\s*\n"))
        for ((i, para) in paragraphs.withIndex()) add((if (i > 0) "\n\n" else "") + para.trim(), 0)
        flush()
        return out
    }
}
