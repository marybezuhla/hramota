package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text decoding that also opens files saved by older Windows programs (Excel, Notepad). */
object TextDecoding {
    /** UTF-8 if the bytes are valid UTF-8 (BOM removed), otherwise Windows-1251 (Cyrillic). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("File larger than $limit bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/**
 * CSV and TSV files as rows of cells: quotes as in RFC 4180, the separator guessed from the first
 * lines (comma, semicolon, tab or bar), and limits so a huge file stays usable.
 */
/** A table read from CSV: rows, whether it was cut at a limit, and each column's widest text (in characters, capped). */
class CsvTable(val rows: List<List<String>>, val truncated: Boolean, val widths: IntArray)

object CsvReader {
    const val MAX_BYTES = 32L * 1024 * 1024
    const val MAX_ROWS = 100_000
    const val MAX_COLUMNS = 256
    const val MAX_CELL = 10_000
    /** All cells together; a file of nothing but separators stops here. */
    const val MAX_CELLS = 2_000_000
    /** Widths are measured on the first rows only, and never beyond what a column can show. */
    private const val WIDTH_ROWS = 300
    private const val WIDTH_CAP = 40

    fun read(input: InputStream): CsvTable = parse(TextDecoding.decode(TextDecoding.readLimited(input, MAX_BYTES)))

    fun parse(text: String): CsvTable {
        var truncated = false
        val rows = rows(text) { truncated = true }
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val widths = IntArray(columns)
        for (r in rows.asSequence().take(WIDTH_ROWS)) for ((c, cell) in r.withIndex()) {
            var line = 0
            for (i in 0 until minOf(cell.length, WIDTH_CAP * 4)) {
                if (cell[i] == '\n') line = 0 else if (++line > widths[c]) widths[c] = minOf(line, WIDTH_CAP)
            }
        }
        return CsvTable(rows, truncated, widths)
    }

    /** Rows of [text]; [onCut] is called if a limit stopped reading before the end. */
    fun rows(text: String, onCut: () -> Unit = {}): List<List<String>> {
        val sep = guessSeparator(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var cells = 0
        fun endCell() {
            if (row.size < MAX_COLUMNS) { row += if (cell.isEmpty()) "" else cell.toString(); cells++ }
            cell.clear()
        }
        fun endRow() {
            endCell()
            // Lines with nothing at all are skipped
            if (!(row.size == 1 && row[0].isEmpty())) rows += row
            row = ArrayList()
        }
        while (i < text.length && rows.size < MAX_ROWS && cells < MAX_CELLS) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { if (cell.length < MAX_CELL) cell.append('"'); i++ } else quoted = false
                } else if (cell.length < MAX_CELL) cell.append(c)
            } else when (c) {
                '"' -> if (cell.isEmpty()) quoted = true else if (cell.length < MAX_CELL) cell.append(c)
                sep -> endCell()
                '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                '\n' -> endRow()
                else -> if (cell.length < MAX_CELL) cell.append(c)
            }
            i++
        }
        // The last line, unless reading stopped at a limit mid-row
        if (i < text.length) onCut() else if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** The separator that splits the first lines most consistently. */
    fun guessSeparator(text: String): Char {
        val lines = text.take(65_536).lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (lines.isEmpty()) return ','
        var best = ','
        var bestScore = -1
        for (sep in charArrayOf(',', ';', '\t', '|')) {
            val counts = lines.map { line -> var n = 0; var q = false; for (c in line) { if (c == '"') q = !q else if (c == sep && !q) n++ }; n }
            val min = counts.minOrNull() ?: 0
            // Every line split the same number of times scores highest
            val score = if (min == 0) 0 else min * 10 + if (counts.all { it == counts[0] }) 5 else 0
            if (score > bestScore) { best = sep; bestScore = score }
        }
        return best
    }
}
