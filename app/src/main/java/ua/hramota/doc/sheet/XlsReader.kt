package ua.hramota.doc.sheet

import ua.hramota.doc.DocReader
import ua.hramota.doc.EncryptedDocumentException
import ua.hramota.doc.Inspection
import ua.hramota.doc.OleFile
import java.io.IOException
import java.io.InputStream

/**
 * Old Excel workbooks (.xls, Excel 97–2003, BIFF8): cells with their saved values, shared strings,
 * fonts, fills, borders, alignment, number formats, column widths, row heights, merged cells and
 * frozen panes. Formulas show their saved result; nothing is calculated or run.
 */
object XlsReader {
    /** [withHidden]: hidden sheets too (for converting the whole workbook); the viewer shows only the visible ones. */
    fun read(input: InputStream, withHidden: Boolean = false): Workbook {
        val file = DocReader.readAll(input)
        val ole = OleFile(file)
        val names = ole.names
        val stream = ole.stream("Workbook") ?: ole.stream("Book")?.let { throw IOException("Excel 5/95 is not supported") }
            ?: throw IOException("Not an Excel workbook")
        return Biff(stream, withHidden).read(Inspection(
            macros = names.any { it == "_VBA_PROJECT_CUR" || it == "VBA" },
            embedded = names.filter { it.startsWith("MBD") }.map { "OLE $it" }.take(100),
        ))
    }
}

private class Biff(private val s: ByteArray, private val withHidden: Boolean = false) {
    private fun u8(at: Int): Int { if (at < 0 || at >= s.size) throw IOException("Read past end"); return s[at].toInt() and 0xFF }
    private fun u16(at: Int) = DocReader.u16(s, at)
    private fun i32(at: Int) = DocReader.i32(s, at)
    private fun f64(at: Int): Double {
        if (at < 0 || at > s.size - 8) throw IOException("Read past end")
        var bits = 0L
        for (k in 7 downTo 0) bits = (bits shl 8) or (s[at + k].toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(bits)
    }

    private class Record(val type: Int, val at: Int, val len: Int)

    /** Records from [start] until the end of the substream. */
    private fun records(start: Int): Sequence<Record> = sequence {
        var p = start
        var count = 0
        while (p + 4 <= s.size && count++ < 20_000_000) {
            val type = u16(p); val len = u16(p + 2)
            if (p + 4 + len > s.size) return@sequence
            yield(Record(type, p + 4, len))
            p += 4 + len
            if (type == 0x000A) return@sequence
        }
    }

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float, val color: Int)
    private val fonts = ArrayList<Font>()
    private val formats = HashMap<Int, String>()
    private val xfs = ArrayList<IntArray>()  // raw XF fields: font, format, alignment, borders, fill
    private var palette: IntArray? = null
    private var date1904 = false
    private val strings = ArrayList<String>()

    fun read(inspection: Inspection): Workbook {
        val sheetsAt = ArrayList<Triple<String, Int, Boolean>>()
        for (r in records(0)) {
            when (r.type) {
                0x002F -> throw EncryptedDocumentException()
                0x0022 -> date1904 = u16(r.at) == 1
                0x0031 -> if (r.len >= 15) {
                    val grbit = u16(r.at + 2)
                    fonts += Font(
                        bold = u16(r.at + 6) >= 700, italic = grbit and 0x02 != 0, underline = u8(r.at + 10) != 0,
                        strike = grbit and 0x08 != 0, size = (u16(r.at) / 20f).coerceIn(1f, 409f), color = u16(r.at + 4),
                    )
                }
                0x041E -> if (r.len >= 5) formats[u16(r.at)] = string(r.at + 2, r.at + r.len, wide = true).first.take(NumberFormat.MAX_CODE)
                0x00E0 -> if (r.len >= 20) xfs += intArrayOf(u16(r.at), u16(r.at + 2), u8(r.at + 6), i32(r.at + 10), i32(r.at + 14), u16(r.at + 18))
                0x0092 -> palette = IntArray(minOf(u16(r.at), 56, (r.len - 2) / 4)) { k -> (u8(r.at + 2 + k * 4) shl 16) or (u8(r.at + 3 + k * 4) shl 8) or u8(r.at + 4 + k * 4) }
                0x0085 -> if (r.len >= 8 && u8(r.at + 5) == 0 && (u8(r.at + 4) == 0 || withHidden)) {
                    sheetsAt += Triple(string(r.at + 6, r.at + r.len, wide = false).first, i32(r.at), u8(r.at + 4) != 0)
                }
                0x00FC -> sst(r)
            }
        }
        val styles = xfs.map(::style).ifEmpty { listOf(CellStyle.DEFAULT) }
        val budget = intArrayOf(SheetLimits.MAX_CELLS)
        var complete = sheetsAt.size <= SheetLimits.MAX_SHEETS
        val sheets = sheetsAt.take(SheetLimits.MAX_SHEETS).mapNotNull { (name, at, hidden) ->
            if (at < 0 || at >= s.size) { complete = false; return@mapNotNull null }
            val b = SheetBuilder(name, budget)
            try { sheet(at, b) } catch (e: IOException) { /* a damaged sheet keeps what was read */ complete = false }
            b.build(styles, date1904, hidden = hidden).also { if (b.cut) complete = false }
        }
        if (sheets.none { !it.hidden }) throw IOException("No sheets")
        return Workbook(sheets, inspection, complete)
    }

    private fun color(index: Int): Int? = when (index) {
        0x7FFF, 64, 65, 0x51, 0x50 -> null
        else -> Palette.color(index, palette)
    }

    private fun style(x: IntArray): CellStyle {
        val fontIndex = x[0].let { if (it >= 4) it - 1 else it }  // there is no font number 4
        val f = fonts.getOrNull(fontIndex)
        val align = x[2]
        val borders = x[3]
        val fillPattern = (x[4] ushr 26) and 0x3F
        var edges = 0
        if (borders and 0xF != 0) edges = edges or 1
        if ((borders ushr 8) and 0xF != 0) edges = edges or 2
        if ((borders ushr 4) and 0xF != 0) edges = edges or 4
        if ((borders ushr 12) and 0xF != 0) edges = edges or 8
        return CellStyle(
            bold = f?.bold == true, italic = f?.italic == true, underline = f?.underline == true, strike = f?.strike == true,
            size = f?.size ?: 10f, color = f?.let { color(it.color) },
            fill = if (fillPattern != 0) color(x[5] and 0x7F) else null,
            align = when (align and 0x7) { 1 -> "left"; 2, 6 -> "center"; 3 -> "right"; else -> null },
            vertical = when ((align ushr 4) and 0x7) { 0 -> "top"; 1 -> "center"; else -> null },
            wrap = align and 0x08 != 0,
            borders = edges,
            format = formats[x[1]] ?: NumberFormat.builtIn(x[1]) ?: "General",
        )
    }

    /**
     * An XLUnicodeString at [at] (a 2-byte length if [wide], else 1 byte); returns the text and
     * where the next thing starts. Stops at [end].
     */
    private fun string(at: Int, end: Int, wide: Boolean): Pair<String, Int> {
        var p = at
        val cch = if (wide) u16(p).also { p += 2 } else u8(p).also { p += 1 }
        val flags = u8(p); p++
        var runs = 0; var ext = 0
        if (flags and 0x08 != 0) { runs = u16(p); p += 2 }
        if (flags and 0x04 != 0) { ext = i32(p); p += 4 }
        val high = flags and 0x01 != 0
        val n = minOf(cch, SheetLimits.MAX_TEXT, ((end - p) / if (high) 2 else 1).coerceAtLeast(0))
        val text = if (high) String(s, p, n * 2, Charsets.UTF_16LE) else String(s, p, n, Charsets.ISO_8859_1)
        p += n * (if (high) 2 else 1) + runs * 4 + ext.coerceIn(0, 1 shl 20)
        return text to p
    }

    /** Shared strings; a string may continue in the CONTINUE records that follow, switching between one and two bytes a character. */
    private fun sst(first: Record) {
        // The SST and its CONTINUE records as pieces of one buffer
        val pieces = ArrayList<IntArray>()
        pieces += intArrayOf(first.at, first.at + first.len)
        var p = first.at + first.len
        while (p + 4 <= s.size && u16(p) == 0x003C) {
            val len = u16(p + 2)
            if (p + 4 + len > s.size) break
            pieces += intArrayOf(p + 4, p + 4 + len)
            p += 4 + len
        }
        var piece = 0
        var pos = first.at + 8
        val total = minOf(i32(first.at + 4), SheetLimits.MAX_CELLS).coerceAtLeast(0)
        fun ensure() { while (piece < pieces.size && pos >= pieces[piece][1]) { piece++; if (piece < pieces.size) pos = pieces[piece][0] } }
        fun byte(): Int { ensure(); if (piece >= pieces.size) throw IOException("SST ended"); return u8(pos++) }
        fun short(): Int = byte() or (byte() shl 8)
        fun int(): Int = short() or (short() shl 16)
        try {
            repeat(total) {
                ensure()
                if (piece >= pieces.size) return
                val cch = short()
                var flags = byte()
                val runs = if (flags and 0x08 != 0) short() else 0
                val ext = if (flags and 0x04 != 0) int() else 0
                val sb = StringBuilder(minOf(cch, 1024))
                var left = cch
                while (left > 0) {
                    if (pos >= pieces[piece][1]) {
                        // Characters go on in the next record, which starts with its own flags byte
                        piece++
                        if (piece >= pieces.size) break
                        pos = pieces[piece][0]
                        flags = u8(pos++)
                    }
                    val high = flags and 0x01 != 0
                    val avail = (pieces[piece][1] - pos) / if (high) 2 else 1
                    val n = minOf(left, avail)
                    if (n <= 0) { pos = pieces[piece][1]; continue }
                    if (sb.length < SheetLimits.MAX_TEXT) sb.append(if (high) String(s, pos, n * 2, Charsets.UTF_16LE) else String(s, pos, n, Charsets.ISO_8859_1))
                    pos += n * if (high) 2 else 1
                    left -= n
                }
                // Formatting runs and phonetic data are skipped (they may also cross records)
                var skip = runs.toLong() * 4 + ext.toLong().coerceIn(0, 1 shl 24)
                while (skip > 0) { ensure(); if (piece >= pieces.size) break; val n = minOf(skip, (pieces[piece][1] - pos).toLong()); pos += n.toInt(); skip -= n }
                strings += sb.toString()
            }
        } catch (e: IOException) { /* keep the strings read so far */ }
    }

    private fun rk(v: Int): Double {
        val d = if (v and 0x02 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 0x01 != 0) d / 100 else d
    }

    private fun sheet(start: Int, b: SheetBuilder) {
        var pendingString: IntArray? = null  // row, col, style of a formula whose text result follows
        var frozen = false
        for (r in records(start)) {
            if (!b.open) return
            val a = r.at
            when (r.type) {
                0x0055 -> b.defaultWidth = ((u16(a) * 7 + 5 + 3) * 0.75f).coerceIn(0f, 1500f)
                0x0225 -> if (r.len >= 4) b.defaultHeight = (u16(a + 2) / 20f).coerceIn(0f, 409f)
                0x007D -> if (r.len >= 10) {
                    val first = u16(a); val last = minOf(u16(a + 2), first + 1024)
                    val hidden = u16(a + 8) and 0x01 != 0
                    val w = if (hidden) 0f else (u16(a + 4) / 256.0 * 7 * 0.75).toFloat().coerceIn(0f, 1500f)
                    for (c in first..last) b.width(c, w)
                }
                0x0208 -> if (r.len >= 16) {
                    val row = u16(a); val height = u16(a + 6); val flags = i32(a + 12)
                    if (flags and 0x20 != 0) b.height(row, 0f)
                    else if (height and 0x8000 == 0 && (flags and 0x40 != 0 || height / 20f > b.defaultHeight)) b.height(row, (height / 20f).coerceIn(0f, 409f))
                }
                0x00FD -> if (r.len >= 10) strings.getOrNull(i32(a + 6))?.let { b.text(u16(a), u16(a + 2), u16(a + 4), it) }
                0x0204 -> if (r.len >= 8) b.text(u16(a), u16(a + 2), u16(a + 4), string(a + 6, a + r.len, wide = true).first)
                0x0203 -> if (r.len >= 14) b.number(u16(a), u16(a + 2), u16(a + 4), f64(a + 6))
                0x027E -> if (r.len >= 10) b.number(u16(a), u16(a + 2), u16(a + 4), rk(i32(a + 6)))
                0x00BD -> if (r.len >= 6) {
                    val row = u16(a); val firstCol = u16(a + 2)
                    val n = (r.len - 6) / 6
                    for (k in 0 until n) b.number(row, firstCol + k, u16(a + 4 + k * 6), rk(i32(a + 6 + k * 6)))
                }
                0x0205 -> if (r.len >= 8) {
                    val v = u8(a + 6); val error = u8(a + 7) != 0
                    b.text(u16(a), u16(a + 2), u16(a + 4), if (error) errorText(v) else if (v != 0) "TRUE" else "FALSE")
                }
                0x0006 -> if (r.len >= 20) {
                    val row = u16(a); val col = u16(a + 2); val xf = u16(a + 4)
                    if (u16(a + 12) == 0xFFFF) when (u8(a + 6)) {
                        0 -> pendingString = intArrayOf(row, col, xf)
                        1 -> b.text(row, col, xf, if (u8(a + 8) != 0) "TRUE" else "FALSE")
                        2 -> b.text(row, col, xf, errorText(u8(a + 8)))
                        else -> b.blank(row, col, xf)
                    } else b.number(row, col, xf, f64(a + 6))
                }
                0x0207 -> pendingString?.let { (row, col, xf) -> b.text(row, col, xf, string(a, a + r.len, wide = true).first); pendingString = null }
                0x0201 -> if (r.len >= 6) b.blank(u16(a), u16(a + 2), u16(a + 4))
                0x00BE -> if (r.len >= 6) {
                    val row = u16(a); val firstCol = u16(a + 2)
                    for (k in 0 until (r.len - 6) / 2) b.blank(row, firstCol + k, u16(a + 4 + k * 2))
                }
                0x00E5 -> if (r.len >= 2) {
                    val n = minOf(u16(a), (r.len - 2) / 8)
                    for (k in 0 until n) {
                        val at = a + 2 + k * 8
                        val m = Merge(u16(at), u16(at + 4), u16(at + 2), u16(at + 6))
                        if (m.bottom >= m.top && m.right >= m.left && b.merges.size < 10_000) b.merges += m
                    }
                }
                // The window says whether panes are frozen; the pane record that follows says where
                0x023E -> if (r.len >= 2) frozen = u16(a) and 0x0008 != 0
                0x0041 -> if (r.len >= 4 && frozen) { b.frozenCols = u16(a).coerceAtMost(50); b.frozenRows = u16(a + 2).coerceAtMost(50) }
            }
        }
    }

    private fun errorText(code: Int) = when (code) {
        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"
        else -> "#ERR"
    }
}
