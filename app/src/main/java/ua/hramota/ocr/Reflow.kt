package ua.hramota.ocr

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Recognised lines put back into paragraphs, as the text was written: a page's lines end where the
 * paper ended, not where the sentence did. A paragraph ends after a short line, before an indented
 * one, at a wider gap between lines, or where the text moves to another column. A word split at a
 * line end ("меди-" + "чної") is joined back.
 */
object Reflow {
    class Line(val text: String, val box: Rect)

    /** The lines of [ocr] in reading order: pieces of one line joined. */
    fun lines(ocr: List<OcrLine>): List<Line> = ocr.groupBy { it.group }.values.map { g ->
        val box = Rect(g.minOf { it.box.left }, g.minOf { it.box.top }, g.maxOf { it.box.right }, g.maxOf { it.box.bottom })
        Line(Lookalikes.fix(g.joinToString(" ") { it.text.trim() }.trim()), box)
    }.filter { it.text.isNotEmpty() }

    fun paragraphs(ocr: List<OcrLine>): List<String> = paragraphs(lines(ocr))

    fun paragraphs(lines: List<Line>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder(lines[0].text)
        for (i in 1 until lines.size) {
            val b = lines[i]
            if (breaksBetween(lines, i)) { out += sb.toString(); sb.setLength(0); sb.append(b.text); continue }
            val n = sb.length
            if (n >= 2 && sb[n - 1] in "-\u2010\u00AD\u00AC" && sb[n - 2].isLetter() && b.text[0].isLowerCase()) sb.setLength(n - 1)
            else sb.append(' ')
            sb.append(b.text)
        }
        out += sb.toString()
        return out
    }

    /** Whether a paragraph ends before line [i]. */
    private fun breaksBetween(lines: List<Line>, i: Int): Boolean {
        val a = lines[i - 1]; val b = lines[i]
        val h = max(1, min(a.box.height(), b.box.height()))
        // The width of a letter, roughly: lines are mostly as many letters as they are wide
        val charW = max(1f, a.box.width().toFloat() / max(1, a.text.length))
        // Another column, or a line far above (out of order): never the same paragraph
        if (b.box.top < a.box.top || b.box.left > a.box.right || b.box.right < a.box.left) return true
        // A gap wider than between ordinary lines
        val gap = b.box.top - a.box.bottom
        val gaps = (1 until lines.size).map { lines[it].box.top - lines[it - 1].box.bottom }.filter { it >= 0 }.sorted()
        val usual = gaps.getOrNull(gaps.size / 2) ?: 0
        if (gap > max(h * 0.9f, usual * 1.8f)) return true
        // The text block these lines are in: its right edge, from the lines around
        val near = lines.subList(max(0, i - 4), min(lines.size, i + 4)).filter { it.box.left < b.box.right && it.box.right > b.box.left }
        val right = near.maxOf { it.box.right }
        val left = near.minOf { it.box.left }
        // A short line: the sentence ended before the paper did
        val short = a.box.right < right - 6 * charW && a.text.last() in ".!?:;»\"…)"
        if (short) return true
        // A line clearly short whatever it ends with (a heading, a signature)
        if (a.box.right < left + (right - left) * 0.6f && a.text.last() != '-') return true
        // The next line starts indented: a new paragraph
        if (b.box.left > left + 2.5f * charW && b.box.left > a.box.left + 2.5f * charW) return true
        return false
    }
}
