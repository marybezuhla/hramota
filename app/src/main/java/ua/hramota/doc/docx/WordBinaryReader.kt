package ua.hramota.doc.docx

import ua.hramota.doc.DocReader
import ua.hramota.doc.EncryptedDocumentException
import ua.hramota.doc.Inspection
import ua.hramota.doc.OleFile
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Old Word documents (.doc, Word 97–2003) with their formatting, read into the same model as DOCX
 * so they are laid out on pages the same way: styles, fonts, paragraph and character formatting,
 * lists, tables, page size and inline pictures. Headers, footnotes and floating shapes are not read
 * (as for DOCX). Every offset read from the file is checked, so a damaged file fails cleanly.
 */
object WordBinaryReader {
    fun read(input: InputStream): DocxDocument = Parser(DocReader.readAll(input)).document()
}

private fun u8(b: ByteArray, at: Int): Int {
    if (at < 0 || at >= b.size) throw IOException("Read past end")
    return b[at].toInt() and 0xFF
}
private fun u16(b: ByteArray, at: Int) = DocReader.u16(b, at)
private fun s16(b: ByteArray, at: Int) = DocReader.u16(b, at).toShort().toInt()
private fun i32(b: ByteArray, at: Int) = DocReader.i32(b, at)

/** A property and where its operand is: (sprm, offset, length). */
private class Sprm(val code: Int, val at: Int, val len: Int)

/** Properties in a grpprl from [from] to [to]; stops quietly at anything that does not fit. */
private fun sprms(b: ByteArray, from: Int, to: Int): List<Sprm> {
    val out = ArrayList<Sprm>()
    var i = from
    val end = minOf(to, b.size)
    while (i + 2 <= end && out.size < 4096) {
        val code = u16(b, i); i += 2
        val len = when (code ushr 13) {
            0, 1 -> 1
            2, 4, 5 -> 2
            3 -> 4
            7 -> 3
            else -> when (code) {
                // sprmTDefTable: a two-byte size, counting itself as one
                0xD608 -> { if (i + 2 > end) return out; val n = u16(b, i) - 1; i += 2; n }
                else -> { if (i >= end) return out; val n = u8(b, i); i += 1; n }
            }
        }
        if (len < 0 || i + len > end) return out
        out += Sprm(code, i, len)
        i += len
    }
    return out
}

/** Word's 16 standard colours (ico). */
private fun icoColor(ico: Int): Int? = when (ico) {
    1 -> 0xFF000000.toInt(); 2 -> 0xFF0000FF.toInt(); 3 -> 0xFF00FFFF.toInt(); 4 -> 0xFF00FF00.toInt()
    5 -> 0xFFFF00FF.toInt(); 6 -> 0xFFFF0000.toInt(); 7 -> 0xFFFFFF00.toInt(); 8 -> 0xFFFFFFFF.toInt()
    9 -> 0xFF000080.toInt(); 10 -> 0xFF008080.toInt(); 11 -> 0xFF008000.toInt(); 12 -> 0xFF800080.toInt()
    13 -> 0xFF800000.toInt(); 14 -> 0xFF808000.toInt(); 15 -> 0xFF808080.toInt(); 16 -> 0xFFC0C0C0.toInt()
    else -> null
}

/** A COLORREF (red, green, blue, flag); null when it means "automatic". */
private fun colorRef(b: ByteArray, at: Int): Int? {
    if (u8(b, at + 3) == 0xFF) return null
    return (0xFF shl 24) or (u8(b, at) shl 16) or (u8(b, at + 1) shl 8) or u8(b, at + 2)
}

/** Character formatting of a run, with what the inspector and pictures need. */
private class Chp(val props: RunProps, val special: Boolean, val picture: Int?, val deleted: Boolean, val inserted: Boolean)

/** Paragraph properties that are not formatting: where the paragraph sits in a table. */
private class PapExtra {
    var inTable = false
    var rowEnd = false
    var depth = 1
    var innerRowEnd = false
    var tap: Tap? = null
}

/** A table row's definition, from its row-end paragraph. */
private class Tap {
    var centers = IntArray(0)
    var tcFlags = IntArray(0)
    var height = 0
    var cantSplit = false
    var gapHalf = 108
    var borders = false
    var shading = arrayOfNulls<Int>(0)
}

private class Parser(file: ByteArray) {
    private val ole = OleFile(file)
    private val word: ByteArray = ole.stream("WordDocument") ?: throw IOException("Not a Word document")
    private val table: ByteArray
    private val data: ByteArray?

    init {
        if (word.size < 0x200 || u16(word, 0) != 0xA5EC) throw IOException("Unsupported Word version")
        // Word 6 and 95 files lay the header out differently
        if (u16(word, 2) < 0xC1) throw IOException("Word 6/95 is not supported")
        val flags = u16(word, 0x0A)
        if (flags and 0x0100 != 0) throw EncryptedDocumentException()
        table = ole.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw IOException("Table stream missing")
        data = ole.stream("Data")
    }

    // The file information block: text lengths and where each structure lives in the table stream
    private val csw = u16(word, 32)
    private val lwStart = 34 + csw * 2 + 2
    private val cslw = u16(word, lwStart - 2)
    private val ccpText = i32(word, lwStart + 3 * 4).coerceIn(0, DocReader.MAX_CHARS)
    private val ccpAtn = if (cslw > 7) i32(word, lwStart + 7 * 4) else 0
    private val fcLcbStart = lwStart + cslw * 4 + 2
    private val fcLcbCount = u16(word, fcLcbStart - 2)

    private fun part(index: Int): ByteArray? {
        if (index >= fcLcbCount) return null
        val fc = i32(word, fcLcbStart + index * 8)
        val lcb = i32(word, fcLcbStart + index * 8 + 4)
        if (lcb <= 0 || fc < 0 || fc.toLong() + lcb > table.size) return null
        return table.copyOfRange(fc, fc + lcb)
    }

    // ---- Fonts: font table entries by index ----
    private val fonts: List<String> = run {
        val b = part(15) ?: return@run emptyList()
        val count = u16(b, 0).coerceAtMost(4096)
        var pos = 4
        val out = ArrayList<String>()
        repeat(count) {
            if (pos >= b.size) return@repeat
            val size = u8(b, pos) + 1
            val nameAt = pos + 40
            val sb = StringBuilder()
            var i = nameAt
            while (i + 1 < minOf(b.size, pos + size)) { val c = u16(b, i); if (c == 0) break; sb.append(c.toChar()); i += 2 }
            out += sb.toString()
            pos += size
        }
        out
    }

    // ---- Styles ----
    private class Std(val sti: Int, val kind: Int, val base: Int, val papx: Pair<Int, Int>?, val chpx: Pair<Int, Int>?, val bytes: ByteArray)
    private val styleBytes = part(1)
    private var defaultFont: String? = null
    private val stds: List<Std?> = run {
        val b = styleBytes ?: return@run emptyList()
        val cbStshi = u16(b, 0)
        val cstd = u16(b, 2).coerceAtMost(4095)
        val cbBase = u16(b, 4)
        if (cbStshi >= 14) defaultFont = fonts.getOrNull(u16(b, 2 + 12))
        var pos = 2 + cbStshi
        val out = ArrayList<Std?>()
        repeat(cstd) {
            if (pos + 2 > b.size) { out += null; return@repeat }
            val cb = u16(b, pos); pos += 2
            if (cb == 0 || pos + cb > b.size) { out += null; pos += cb; return@repeat }
            val s = b.copyOfRange(pos, pos + cb)
            pos += cb
            try {
                val sti = u16(s, 0) and 0x0FFF
                val kind = u16(s, 2) and 0xF
                val base = u16(s, 2) ushr 4
                val cupx = u16(s, 4) and 0xF
                var off = cbBase
                val cch = u16(s, off)
                off += 2 + cch * 2 + 2
                val upx = ArrayList<Pair<Int, Int>>()
                repeat(cupx) {
                    if (off % 2 == 1) off++
                    val n = u16(s, off); off += 2
                    if (off + n <= s.size) upx += off to off + n
                    off += n
                }
                out += when (kind) {
                    1 -> Std(sti, kind, base, upx.getOrNull(0), upx.getOrNull(1), s)
                    2 -> Std(sti, kind, base, null, upx.getOrNull(0), s)
                    else -> Std(sti, kind, base, null, null, s)
                }
            } catch (e: IOException) { out += null }
        }
        out
    }

    private val defaultRun = RunProps(font = "Times New Roman", size = 10f, bold = false, italic = false, underline = false, strike = false, caps = false, vanish = false)
    private val styleParas = HashMap<Int, ParaProps>()
    private val styleRuns = HashMap<Int, RunProps>()

    private fun styleId(istd: Int): String {
        val sti = stds.getOrNull(istd)?.sti
        return if (sti != null && sti in 1..9) "Heading$sti" else "s$istd"
    }

    /** A paragraph style's paragraph properties, base styles first. */
    private fun stylePara(istd: Int, depth: Int = 0): ParaProps = styleParas[istd] ?: run {
        val s = stds.getOrNull(istd)
        var p = if (s == null || depth > 20 || s.base == 0x0FFF || s.base == istd) ParaProps() else stylePara(s.base, depth + 1)
        val upx = s?.papx
        // The UPX starts with the style's own number, then its properties
        if (upx != null && upx.second - upx.first >= 2) p = applyPap(p, s.bytes, upx.first + 2, upx.second, null)
        p.copy(styleId = styleId(istd)).also { styleParas[istd] = it }
    }

    /** A style's character properties, base styles first. */
    private fun styleRun(istd: Int, depth: Int = 0): RunProps = styleRuns[istd] ?: run {
        val s = stds.getOrNull(istd)
        val base = if (s == null || depth > 20 || s.base == 0x0FFF || s.base == istd) defaultRun.copy(font = defaultFont ?: defaultRun.font) else styleRun(s.base, depth + 1)
        val upx = s?.chpx
        val r = if (upx != null) applyChp(base, base, s.bytes, upx.first, upx.second).props else base
        r.also { styleRuns[istd] = it }
    }

    // ---- Character and paragraph properties ----
    var hidden = false
    var tracked = false

    private fun applyChp(start: RunProps, style: RunProps, b: ByteArray, from: Int, to: Int): Chp {
        var r = start
        var special = false
        var picture: Int? = null
        var deleted = false
        var inserted = false
        fun toggle(v: Int, styleValue: Boolean?): Boolean = when (v) { 0 -> false; 1 -> true; 0x80 -> styleValue == true; 0x81 -> styleValue != true; else -> v != 0 }
        for (s in sprms(b, from, to)) {
            val a = s.at
            when (s.code) {
                0x4A30 -> { val cs = styleRun(u16(b, a)); r = r.with(cs.copy(size = cs.size)) }
                0x0835 -> r = r.copy(bold = toggle(u8(b, a), style.bold))
                0x0836 -> r = r.copy(italic = toggle(u8(b, a), style.italic))
                0x0837 -> r = r.copy(strike = toggle(u8(b, a), style.strike))
                0x083B -> r = r.copy(caps = toggle(u8(b, a), style.caps))
                0x083C -> r = r.copy(vanish = toggle(u8(b, a), style.vanish))
                0x2A3E -> r = r.copy(underline = u8(b, a) != 0)
                0x2A42 -> r = r.copy(color = icoColor(u8(b, a)) ?: 0xFF000000.toInt())
                0x6870 -> r = r.copy(color = colorRef(b, a) ?: 0xFF000000.toInt())
                0x4A43 -> r = r.copy(size = (u16(b, a) / 2f).coerceIn(1f, 1638f))
                0x2A48 -> r = r.copy(vertAlign = when (u8(b, a)) { 1 -> "superscript"; 2 -> "subscript"; else -> "baseline" })
                0x4A4F -> fonts.getOrNull(u16(b, a))?.let { r = r.copy(font = it, fontTheme = null) }
                0x2A0C -> r = r.copy(highlight = icoColor(u8(b, a)) ?: 0)
                0x0855 -> special = u8(b, a) != 0
                0x6A03 -> picture = i32(b, a)
                0x0800 -> deleted = u8(b, a) != 0
                0x0801 -> inserted = u8(b, a) != 0
            }
        }
        return Chp(r, special, picture, deleted, inserted)
    }

    private fun applyPap(start: ParaProps, b: ByteArray, from: Int, to: Int, extra: PapExtra?): ParaProps {
        var p = start
        for (s in sprms(b, from, to)) {
            val a = s.at
            when (s.code) {
                0x2403, 0x2461 -> p = p.copy(align = when (u8(b, a)) { 1 -> "center"; 2 -> "right"; 3 -> "both"; 4 -> "distribute"; else -> "left" })
                0x840F, 0x845E -> p = p.copy(left = s16(b, a))
                0x840E, 0x845D -> p = p.copy(right = s16(b, a))
                0x8411, 0x8460 -> p = p.copy(firstLine = s16(b, a))
                0x6412 -> {
                    val line = s16(b, a)
                    p = if (s16(b, a + 2) == 1) p.copy(line = line.coerceIn(1, ParaProps.MAX_LEN), lineRule = "auto")
                    else if (line < 0) p.copy(line = (-line).coerceAtMost(ParaProps.MAX_LEN), lineRule = "exact")
                    else p.copy(line = line.coerceAtMost(ParaProps.MAX_LEN), lineRule = "atLeast")
                }
                0xA413 -> p = p.copy(before = u16(b, a).coerceAtMost(ParaProps.MAX_LEN))
                0xA414 -> p = p.copy(after = u16(b, a).coerceAtMost(ParaProps.MAX_LEN))
                0x245B -> p = p.copy(beforeAuto = u8(b, a) != 0)
                0x245C -> p = p.copy(afterAuto = u8(b, a) != 0)
                0x2406 -> p = p.copy(keepNext = u8(b, a) != 0)
                0x2407 -> p = p.copy(pageBreakBefore = u8(b, a) != 0)
                0x2431 -> p = p.copy(widowControl = u8(b, a) != 0)
                0x246D -> p = p.copy(contextual = u8(b, a) != 0)
                0x260A -> p = p.copy(numLevel = u8(b, a).coerceIn(0, 8))
                0x460B -> p = p.copy(numId = u16(b, a).toString())
                0xC60D -> {
                    // Tab stops: positions removed, then positions added
                    val tabs = p.tabs.orEmpty().toMutableSet()
                    var i = a
                    val del = u8(b, i); i++
                    repeat(del) { tabs.remove(s16(b, i)); i += 2 }
                    val add = u8(b, i); i++
                    repeat(add) { tabs.add(s16(b, i)); i += 2 }
                    p = p.copy(tabs = tabs.sorted().take(64))
                }
                0x442D -> {
                    val v = u16(b, a)
                    val back = icoColor((v ushr 5) and 0x1F)
                    val fore = icoColor(v and 0x1F)
                    p = p.copy(shading = when (v ushr 10) { 0 -> back; 1 -> fore; else -> back })
                }
                0xC64D -> if (s.len >= 10) p = p.copy(shading = if (u16(b, a + 8) == 1) colorRef(b, a) else colorRef(b, a + 4))
                0x2416 -> extra?.inTable = u8(b, a) != 0
                0x2417 -> extra?.rowEnd = u8(b, a) != 0
                0x6649 -> extra?.depth = i32(b, a).coerceIn(0, 64)
                0x244C -> extra?.innerRowEnd = u8(b, a) != 0
                else -> if (extra != null && (s.code ushr 10 and 7) == 5) tableSprm(s, b, extra)
            }
        }
        return p
    }

    private fun tableSprm(s: Sprm, b: ByteArray, extra: PapExtra) {
        val t = extra.tap ?: Tap().also { extra.tap = it }
        val a = s.at
        fun brcSet(type: Int) = type != 0 && type != 0xFF
        when (s.code) {
            0xD608 -> {
                val n = u8(b, a).coerceAtMost(63)
                t.centers = IntArray(n + 1) { s16(b, a + 1 + it * 2) }
                val tcAt = a + 1 + (n + 1) * 2
                t.tcFlags = IntArray(n) { k -> if (tcAt + k * 20 + 2 <= a + s.len) u16(b, tcAt + k * 20) else 0 }
                // Cell borders: four BRC80 after the flags and width
                for (k in 0 until n) for (e in 0 until 4) {
                    val at = tcAt + k * 20 + 4 + e * 4
                    if (at + 4 <= a + s.len && brcSet(u8(b, at + 1))) t.borders = true
                }
            }
            0x9407 -> t.height = s16(b, a)
            0x3403, 0x3466 -> t.cantSplit = u8(b, a) != 0
            0x9602 -> t.gapHalf = s16(b, a).coerceIn(0, 1440)
            0xD605 -> for (e in 0 until 6) { val at = a + e * 4; if (at + 4 <= a + s.len && brcSet(u8(b, at + 1))) t.borders = true }
            0xD613 -> for (e in 0 until 6) { val at = a + e * 8; if (at + 8 <= a + s.len && brcSet(u8(b, at + 5))) t.borders = true }
            0xD612 -> t.shading = arrayOfNulls<Int>(s.len / 10).also { arr -> for (k in arr.indices) arr[k] = colorRef(b, a + k * 10 + 4) }
            0xD609 -> t.shading = arrayOfNulls<Int>(s.len / 2).also { arr -> for (k in arr.indices) arr[k] = icoColor((u16(b, a + k * 2) ushr 5) and 0x1F) }
        }
    }

    // ---- Lists ----
    private val numbering: Numbering = run {
        val lst = part(73) ?: return@run Numbering.EMPTY
        val lfo = part(74) ?: return@run Numbering.EMPTY
        try {
            val count = u16(lst, 0).coerceAtMost(4096)
            val lists = HashMap<Int, Map<Int, NumLevel>>()
            var pos = 2 + count * 28
            for (k in 0 until count) {
                val lsid = i32(lst, 2 + k * 28)
                val simple = u8(lst, 2 + k * 28 + 26) and 1 != 0
                val levels = HashMap<Int, NumLevel>()
                for (lvl in 0 until if (simple) 1 else 9) {
                    val (level, next) = readLevel(lst, pos)
                    levels[lvl] = level
                    pos = next
                }
                lists[lsid] = levels
            }
            val lfoCount = i32(lfo, 0).coerceIn(0, 4096)
            val nums = HashMap<String, Map<Int, NumLevel>>()
            var at = 4 + lfoCount * 16
            for (k in 0 until lfoCount) {
                val lsid = i32(lfo, 4 + k * 16)
                val overridesCount = u8(lfo, 4 + k * 16 + 12)
                val overrides = HashMap<Int, Int>()
                if (at + 4 <= lfo.size) {
                    at += 4
                    repeat(overridesCount) {
                        if (at + 8 > lfo.size) return@repeat
                        val start = i32(lfo, at)
                        val flags = i32(lfo, at + 4)
                        at += 8
                        if (flags and 0x10 != 0) overrides[flags and 0xF] = start.coerceIn(0, Numbering.MAX_NUMBER)
                        if (flags and 0x20 != 0) at = readLevel(lfo, at).second
                    }
                }
                val levels = lists[lsid] ?: continue
                nums[(k + 1).toString()] = levels.mapValues { (i, l) -> overrides[i]?.let { NumLevel(l.format, l.text, it, l.para, l.run) } ?: l }
            }
            Numbering(nums)
        } catch (e: IOException) { Numbering.EMPTY }
    }

    private fun readLevel(b: ByteArray, pos: Int): Pair<NumLevel, Int> {
        val start = i32(b, pos).coerceIn(0, Numbering.MAX_NUMBER)
        val nfc = u8(b, pos + 4)
        val cbChpx = u8(b, pos + 24)
        val cbPapx = u8(b, pos + 25)
        var p = pos + 28
        val para = applyPap(ParaProps(), b, p, p + cbPapx, null)
        p += cbPapx
        val run = applyChp(RunProps(), RunProps(), b, p, p + cbChpx).props
        p += cbChpx
        val cch = u16(b, p).coerceAtMost(255); p += 2
        val text = StringBuilder()
        repeat(cch) { val c = u16(b, p + it * 2); if (c < 9) text.append('%').append(c + 1) else text.append(c.toChar()) }
        p += cch * 2
        val format = when (nfc) {
            1 -> "upperRoman"; 2 -> "lowerRoman"; 3 -> "upperLetter"; 4 -> "lowerLetter"
            22 -> "decimalZero"; 23 -> "bullet"; 255 -> "none"; 58 -> "russianLower"; 59 -> "russianUpper"
            else -> "decimal"
        }
        return NumLevel(format, text.toString(), start, para, run) to p
    }

    // ---- Formatting runs, found by file position ----
    private class Runs(val starts: IntArray, val ends: IntArray, val pages: Array<ByteArray>, val from: IntArray, val to: IntArray, val istd: IntArray) {
        private var last = 0
        fun find(fc: Int): Int {
            if (last < starts.size && fc >= starts[last] && fc < ends[last]) return last
            var lo = 0; var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    fc < starts[mid] -> hi = mid - 1
                    fc >= ends[mid] -> lo = mid + 1
                    else -> { last = mid; return mid }
                }
            }
            return -1
        }
    }

    private fun bins(index: Int, paragraphs: Boolean): Runs {
        val plc = part(index) ?: return Runs(IntArray(0), IntArray(0), emptyArray(), IntArray(0), IntArray(0), IntArray(0))
        val n = (plc.size - 4) / 8
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>(); val pages = ArrayList<ByteArray>()
        val from = ArrayList<Int>(); val to = ArrayList<Int>(); val istd = ArrayList<Int>()
        for (k in 0 until n) {
            val pn = i32(plc, 4 * (n + 1) + k * 4) and 0x3FFFFF
            val off = pn.toLong() * 512
            if (off + 512 > word.size) continue
            val page = word.copyOfRange(off.toInt(), off.toInt() + 512)
            val count = u8(page, 511)
            for (r in 0 until count) {
                if (4 * (r + 2) > 511) break
                val a = i32(page, 4 * r); val b = i32(page, 4 * (r + 1))
                if (paragraphs) {
                    val bx = 4 * (count + 1) + r * 13
                    if (bx >= 511) break
                    val o = u8(page, bx) * 2
                    if (o == 0) { starts += a; ends += b; pages += page; from += 0; to += 0; istd += 0; continue }
                    val cb = u8(page, o)
                    val (s, len) = if (cb == 0) (o + 2) to 2 * u8(page, o + 1) else (o + 1) to (2 * cb - 1)
                    if (len < 2 || s + len > 512) continue
                    starts += a; ends += b; pages += page; istd += u16(page, s); from += s + 2; to += s + len
                } else {
                    val o = u8(page, 4 * (count + 1) + r) * 2
                    val cb = if (o == 0) 0 else u8(page, o)
                    starts += a; ends += b; pages += page; istd += 0
                    from += if (o == 0) 0 else o + 1; to += if (o == 0) 0 else minOf(512, o + 1 + cb)
                }
            }
        }
        return Runs(starts.toIntArray(), ends.toIntArray(), pages.toTypedArray(), from.toIntArray(), to.toIntArray(), istd.toIntArray())
    }

    private val chpRuns = bins(12, paragraphs = false)
    private val papRuns = bins(13, paragraphs = true)

    // ---- Sections: page size and where new pages start ----
    private var page = PageSetup(width = 12240, height = 15840, top = 1440, bottom = 1440, left = 1800, right = 1800)
    private val sectionBreaks = HashSet<Int>()
    init {
        part(6)?.let { plc ->
            val n = ((plc.size - 4) / 16).coerceAtMost(4096)
            for (k in 0 until n) {
                val cp = i32(plc, k * 4)
                val fc = i32(plc, 4 * (n + 1) + k * 12 + 2)
                var newPage = true
                if (fc >= 0 && fc + 2 <= word.size) {
                    val cb = u16(word, fc)
                    var p = page
                    for (s in sprms(word, fc + 2, fc + 2 + cb)) when (s.code) {
                        0xB01F -> p = p.copy(width = u16(word, s.at).coerceIn(1440, ParaProps.MAX_LEN))
                        0xB020 -> p = p.copy(height = u16(word, s.at).coerceIn(1440, ParaProps.MAX_LEN))
                        0xB021 -> p = p.copy(left = u16(word, s.at).coerceAtMost(ParaProps.MAX_LEN / 2))
                        0xB022 -> p = p.copy(right = u16(word, s.at).coerceAtMost(ParaProps.MAX_LEN / 2))
                        0x9023 -> p = p.copy(top = kotlin.math.abs(s16(word, s.at)).coerceAtMost(ParaProps.MAX_LEN / 2))
                        0x9024 -> p = p.copy(bottom = kotlin.math.abs(s16(word, s.at)).coerceAtMost(ParaProps.MAX_LEN / 2))
                        0x3009 -> newPage = u8(word, s.at) != 0
                    }
                    if (k == 0) page = p
                }
                if (k > 0 && newPage) sectionBreaks += cp
            }
        }
    }

    // ---- Text ----
    private val links = intArrayOf(0)
    private var dde = false

    fun document(): DocxDocument {
        val clx = part(33) ?: throw IOException("Piece table missing")
        var pos = 0
        while (pos < clx.size && clx[pos].toInt() == 0x01) pos += 3 + u16(clx, pos + 1)
        if (pos >= clx.size || clx[pos].toInt() != 0x02) throw IOException("Piece table missing")
        val lcb = i32(clx, pos + 1)
        val plc = pos + 5
        if (lcb < 16 || plc.toLong() + lcb > clx.size) throw IOException("Piece table damaged")
        val n = (lcb - 4) / 12

        val builder = BodyBuilder()
        val cp1252 = Charset.forName("windows-1252")
        var lastEnd = 0
        for (i in 0 until n) {
            val cpStart = i32(clx, plc + i * 4)
            val cpEnd = minOf(i32(clx, plc + (i + 1) * 4), ccpText)
            if (cpStart >= ccpText) break
            if (cpStart < lastEnd) throw IOException("Pieces out of order")
            lastEnd = cpEnd
            val count = cpEnd - cpStart
            if (count <= 0) continue
            val fcRaw = i32(clx, plc + (n + 1) * 4 + i * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = if (compressed) (fcRaw and 0x3FFFFFFF) / 2 else fcRaw
            val width = if (compressed) 1 else 2
            if (fc < 0 || fc.toLong() + count.toLong() * width > word.size) throw IOException("Text piece out of range")
            val text = if (compressed) String(word, fc, count, cp1252) else String(word, fc, count * 2, Charsets.UTF_16LE)
            for (k in 0 until count) builder.char(text[k], cpStart + k, fc + k * width)
            if (builder.size > DocReader.MAX_CHARS) break
        }
        builder.finish()
        val names = ole.names
        val inspection = Inspection(
            macros = names.any { it == "Macros" || it == "_VBA_PROJECT" || it == "VBA" },
            embedded = names.filter { it.startsWith("_") && it.drop(1).all(Char::isDigit) && it.length > 2 }.map { "OLE $it" }.take(100),
            dde = dde,
            links = links[0],
            trackedChanges = tracked,
            comments = if (ccpAtn > 0) maxOf(1, ((part(4)?.size ?: 4) - 4) / 34) else 0,
            hiddenText = hidden,
        )
        return DocxDocument(
            page = page, blocks = builder.blocks, media = builder.media, fonts = ThemeFonts(),
            rels = builder.media.keys.associateWith { it },
            compatibilityMode = 12, inspection = inspection,
        )
    }

    /** Turns characters into paragraphs and tables. */
    private inner class BodyBuilder {
        val blocks = ArrayList<Block>()
        val media = HashMap<String, ByteArray>()
        var size = 0
        private var mediaBytes = 0L

        // The paragraph being read: text runs by character formatting run
        private val runText = ArrayList<StringBuilder>()
        private val runIndex = ArrayList<Int>()
        private val pieces = ArrayList<Any>()  // a run number (Int) or a ready inline (Inline)
        private var fields = ArrayList<Boolean>()
        private var fieldCode = StringBuilder()
        private var pendingBreak = false

        // Table being built
        private val cell = ArrayList<Block>()
        private val cells = ArrayList<List<Block>>()
        private val rows = ArrayList<Pair<List<List<Block>>, Tap?>>()
        private val runCache = HashMap<Long, Chp>()

        private fun chp(run: Int, istd: Int): Chp {
            val key = (run.toLong() shl 20) or istd.toLong()
            return runCache.getOrPut(key) {
                val base = styleRun(istd)
                if (run < 0) Chp(base, false, null, false, false)
                else applyChp(base, base, chpRuns.pages[run], chpRuns.from[run], chpRuns.to[run])
            }
        }

        private fun add(c: Char, run: Int) {
            val lastRun = runIndex.lastOrNull()
            if (lastRun == run && pieces.lastOrNull() is Int) runText.last().append(c)
            else { runText += StringBuilder().append(c); runIndex += run; pieces += runText.size - 1 }
            size++
        }

        fun char(c: Char, cp: Int, fc: Int) {
            if (cp in sectionBreaks && runText.isEmpty() && pieces.isEmpty()) pendingBreak = true
            val run = chpRuns.find(fc)
            when (c.code) {
                0x13 -> { if (fields.size < 20) fields += false; fieldCode.clear(); return }
                0x14 -> { if (fields.isNotEmpty()) { fields[fields.size - 1] = true; fieldDone() }; return }
                0x15 -> { if (fields.isNotEmpty()) { if (!fields.last()) fieldDone(); fields.removeAt(fields.size - 1) }; return }
            }
            if (fields.isNotEmpty() && !fields.last()) { if (fieldCode.length < 200) fieldCode.append(c); return }
            when (c.code) {
                0x0D, 0x07 -> endParagraph(fc, c.code == 0x07, run)
                // A section mark ends its paragraph; the next section starts on a new page (or not, as it says)
                0x0C -> if (cp + 1 in sectionBreaks || cp + 1 >= ccpText) endParagraph(fc, false, run) else pieces += Inline.PageBreak
                0x0B -> pieces += Inline.LineBreak
                0x09 -> pieces += Inline.Tab
                0x1E -> add('‑', run)
                0x01 -> picture(run)
                0x1F, 0x02, 0x03, 0x04, 0x05, 0x08 -> Unit
                else -> if (c.code >= 0x20) add(c, run)
            }
        }

        private fun fieldDone() {
            val code = fieldCode.toString().trim().uppercase()
            if (code.startsWith("DDE")) dde = true
            if (code.startsWith("HYPERLINK")) links[0]++
            fieldCode.clear()
        }

        private fun picture(run: Int) {
            if (run < 0) return
            val c = chp(run, 0)
            val fc = c.picture ?: return
            val d = data ?: return
            if (fc < 0 || fc + 0x44 > d.size) return
            val lcb = i32(d, fc)
            val header = u16(d, fc + 4)
            val end = (fc.toLong() + lcb).coerceAtMost(d.size.toLong()).toInt()
            val dxa = s16(d, fc + 0x1C); val dya = s16(d, fc + 0x1E)
            val mx = u16(d, fc + 0x20).takeIf { it > 0 } ?: 1000; val my = u16(d, fc + 0x22).takeIf { it > 0 } ?: 1000
            val w = dxa * mx / 1000f / 20f; val h = dya * my / 1000f / 20f
            val key = "pic$fc"
            if (key !in media && mediaBytes < DocxReader.MAX_MEDIA_TOTAL) {
                extractImage(d, fc + header, end)?.let { media[key] = it; mediaBytes += it.size }
            }
            pieces += Inline.Image(key, w.coerceIn(0f, 1584f), h.coerceIn(0f, 1584f))
        }

        /** The PNG or JPEG inside a picture record (other kinds are not drawn yet). */
        private fun extractImage(d: ByteArray, from: Int, to: Int): ByteArray? {
            var i = maxOf(0, from)
            while (i + 8 < to) {
                if (d[i] == 0x89.toByte() && d[i + 1] == 'P'.code.toByte() && d[i + 2] == 'N'.code.toByte() && d[i + 3] == 'G'.code.toByte()) return d.copyOfRange(i, to)
                if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte() && d[i + 2] == 0xFF.toByte()) return d.copyOfRange(i, to)
                i++
            }
            return null
        }

        private fun endParagraph(fc: Int, cellMark: Boolean, markRun: Int) {
            val pr = papRuns.find(fc)
            val istd = if (pr >= 0) papRuns.istd[pr] else 0
            val extra = PapExtra()
            val direct = if (pr >= 0) applyPap(ParaProps(), papRuns.pages[pr], papRuns.from[pr], papRuns.to[pr], extra) else ParaProps()
            val styleP = stylePara(istd)
            var props = styleP.with(direct)
            var label: String? = null
            var labelProps: RunProps? = null
            val baseRun = styleRun(istd)
            val numId = props.numId
            if (numId != null && numId != "0") {
                val lvl = props.numLevel ?: 0
                numbering.level(numId, lvl)?.let { level ->
                    props = styleP.with(level.para).with(direct)
                    label = numbering.next(numId, lvl)
                    labelProps = baseRun.with(level.run)
                }
            }
            if (pendingBreak) { props = props.copy(pageBreakBefore = true); pendingBreak = false }
            val inlines = ArrayList<Inline>()
            for (piece in pieces) {
                if (piece is Inline) { inlines += piece; continue }
                val k = piece as Int
                val c = chp(runIndex[k], istd)
                if (c.inserted || c.deleted) tracked = true
                if (c.props.vanish == true) hidden = true
                if (c.deleted || c.props.vanish == true) continue
                inlines += Inline.Text(runText[k].toString(), c.props)
            }
            val mark = chp(markRun, istd).props
            runText.clear(); runIndex.clear(); pieces.clear()
            val para = Block.Para(props, mark, inlines, label, labelProps)

            // Where it goes: a table cell, the end of a row, or the body
            when {
                extra.inTable && extra.depth > 1 && extra.innerRowEnd -> Unit  // a nested table's row mark: its text is already in the cell
                extra.inTable && extra.rowEnd && extra.depth <= 1 -> {
                    if (cell.isNotEmpty()) { cells += cell.toList(); cell.clear() }
                    rows += cells.toList() to extra.tap
                    cells.clear()
                }
                extra.inTable -> {
                    flushTableIfNeeded(keep = true)
                    cell += para
                    if (cellMark && extra.depth <= 1) { cells += cell.toList(); cell.clear() }
                }
                else -> { flushTableIfNeeded(keep = false); blocks += para }
            }
        }

        private fun flushTableIfNeeded(keep: Boolean) {
            if (keep || rows.isEmpty()) return
            blocks += buildTable()
            rows.clear()
        }

        fun finish() {
            if (pieces.isNotEmpty() || runText.isNotEmpty()) endParagraph(-1, false, -1)
            if (cell.isNotEmpty()) { cells += cell.toList(); cell.clear() }
            if (cells.isNotEmpty()) { rows += cells.toList() to null; cells.clear() }
            if (rows.isNotEmpty()) { blocks += buildTable(); rows.clear() }
        }

        /** A table from its rows: the column grid is every cell edge of every row. */
        private fun buildTable(): Block.Table {
            val edges = sortedSetOf<Int>()
            for ((_, tap) in rows) tap?.centers?.forEach { edges += it }
            val grid = edges.toList().take(DocxReader.MAX_COLUMNS + 1)
            val gap = rows.firstNotNullOfOrNull { it.second }?.gapHalf ?: 108
            val outRows = rows.map { (rowCells, tap) ->
                val centers = tap?.centers
                val outCells = ArrayList<Cell>()
                for ((k, blocks) in rowCells.withIndex()) {
                    val flags = tap?.tcFlags?.getOrNull(k) ?: 0
                    val span = if (centers != null && k + 1 < centers.size) {
                        val a = grid.indexOf(centers[k]); val b = grid.indexOf(centers[k + 1])
                        if (a >= 0 && b > a) b - a else 1
                    } else 1
                    // Horizontally merged cells continue the cell before them
                    if (flags and 0x0002 != 0 && outCells.isNotEmpty()) {
                        val prev = outCells.removeAt(outCells.size - 1)
                        outCells += prev.copy(span = (prev.span + span).coerceAtMost(DocxReader.MAX_COLUMNS))
                        continue
                    }
                    outCells += Cell(
                        span = span.coerceIn(1, DocxReader.MAX_COLUMNS),
                        mergedAbove = flags and 0x0020 != 0 && flags and 0x0040 == 0,
                        blocks = blocks,
                        shading = tap?.shading?.getOrNull(k),
                    )
                }
                val h = tap?.height ?: 0
                Row(outCells, if (h != 0) kotlin.math.abs(h).coerceAtMost(ParaProps.MAX_LEN) else null, h < 0, tap?.cantSplit == true)
            }
            val columns = grid.zipWithNext { a, b -> (b - a).coerceIn(0, ParaProps.MAX_LEN) }
            val borders = rows.any { it.second?.borders == true }
            val indent = ((grid.firstOrNull() ?: 0) + gap).coerceIn(-ParaProps.MAX_LEN, ParaProps.MAX_LEN)
            return Block.Table(columns, outRows, borders, indent)
        }
    }
}
