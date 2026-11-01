package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/** The document is encrypted (password-protected). */
class EncryptedDocumentException : IOException("Encrypted document")

/**
 * The text of old Word documents (.doc, Word 97–2003): an OLE compound file holding the
 * "WordDocument" and table streams; the text is assembled from pieces listed in the table stream.
 * Only the main text is read (not headers, footnotes or comments). Every offset read from the file
 * is checked, so a damaged or hostile file fails cleanly.
 */
object DocReader {
    /** Largest .doc file read whole into memory. */
    const val MAX_FILE = 32L * 1024 * 1024
    /** Longest text read. */
    const val MAX_CHARS = 20_000_000
    private const val MAX_FIELD_DEPTH = 20
    private const val MAX_FIELD_CODE = 8192

    fun read(input: InputStream): List<Paragraph> {
        val file = readAll(input)
        val cfb = OleFile(file)
        val word = cfb.stream("WordDocument") ?: throw IOException("Not a Word document")
        if (word.size < 0x200 || u16(word, 0) != 0xA5EC) throw IOException("Unsupported Word version")
        val flags = u16(word, 0x0A)
        if (flags and 0x0100 != 0) throw EncryptedDocumentException()
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw IOException("Table stream missing")

        // FIB: lengths of the variable parts, then the main text length and where the piece table is
        val csw = u16(word, 32)
        val lwStart = 34 + csw * 2 + 2
        val cslw = u16(word, lwStart - 2)
        val ccpText = i32(word, lwStart + 3 * 4).coerceIn(0, MAX_CHARS)
        val fcLcbStart = lwStart + cslw * 4 + 2
        val fcClx = i32(word, fcLcbStart + 33 * 8)
        val lcbClx = i32(word, fcLcbStart + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx.toLong() + lcbClx > table.size) throw IOException("Piece table out of range")

        // Clx: skip property runs (0x01), then the piece table (0x02)
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end && table[pos].toInt() == 0x01) pos += 3 + u16(table, pos + 1)
        if (pos >= end || table[pos].toInt() != 0x02) throw IOException("Piece table missing")
        val lcb = i32(table, pos + 1)
        val plc = pos + 5
        if (lcb < 16 || plc.toLong() + lcb > table.size) throw IOException("Piece table damaged")
        val n = (lcb - 4) / 12
        val text = StringBuilder()
        // Pieces must follow one another; overlapping or reordered ones are not read twice
        var lastEnd = 0
        val cp1252 = Charset.forName("windows-1252")
        for (i in 0 until n) {
            val cpStart = i32(table, plc + i * 4)
            val cpEnd = minOf(i32(table, plc + (i + 1) * 4), ccpText)
            if (cpStart >= ccpText) break
            if (cpStart < lastEnd) throw IOException("Pieces out of order")
            lastEnd = cpEnd
            val count = minOf(cpEnd - cpStart, MAX_CHARS - text.length)
            if (count <= 0) continue
            val pcd = plc + (n + 1) * 4 + i * 8
            val fcRaw = i32(table, pcd + 2)
            if (fcRaw and 0x40000000 != 0) {
                // 8-bit text (Windows-1252)
                val fc = (fcRaw and 0x3FFFFFFF) / 2
                if (fc < 0 || fc.toLong() + count > word.size) throw IOException("Text piece out of range")
                text.append(String(word, fc, count, cp1252))
            } else {
                if (fcRaw < 0 || fcRaw.toLong() + count * 2L > word.size) throw IOException("Text piece out of range")
                text.append(String(word, fcRaw, count * 2, Charsets.UTF_16LE))
            }
            if (text.length >= MAX_CHARS) break
        }
        return paragraphs(text)
    }

    /** Word's control characters become paragraph and line breaks; field codes keep only their result. */
    private fun paragraphs(text: CharSequence): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        val sb = StringBuilder()
        // Inside a field: text before its separator (the field code) is skipped
        val fields = ArrayList<Boolean>()
        // A field code is short; one running this long was never closed, so its text is shown after all
        var skipped = 0
        fun flush() { out += Paragraph(sb.toString().trimEnd()); sb.clear() }
        for (c in text) {
            when (c.code) {
                0x13 -> { if (fields.size < MAX_FIELD_DEPTH) fields += false; skipped = 0; continue }
                0x14 -> { if (fields.isNotEmpty()) fields[fields.size - 1] = true; continue }
                0x15 -> { if (fields.isNotEmpty()) fields.removeAt(fields.size - 1); continue }
            }
            if (fields.isNotEmpty() && !fields.last()) {
                if (++skipped <= MAX_FIELD_CODE) continue
                fields.clear()
            }
            if (out.size >= TextExtractor.MAX_PARAGRAPHS) break
            when (c.code) {
                0x0D, 0x07, 0x0C -> flush()
                0x0B -> sb.append('\n')
                0x09 -> sb.append('\t')
                0x1E -> sb.append('-')
                0x1F, 0x01, 0x08, 0x05, 0x02, 0x03, 0x04 -> Unit
                else -> if (c.code >= 0x20) sb.append(c)
            }
            if (sb.length >= TextExtractor.MAX_PARAGRAPH * 4) flush()
        }
        if (sb.isNotEmpty()) flush()
        return out
    }

    internal fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FILE) throw IOException("File too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal fun u16(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) throw IOException("Read past end")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    internal fun i32(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) throw IOException("Read past end")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}

/** A minimal reader of OLE compound files (version 3 and 4): directory, FAT, mini stream; every offset checked. */
internal class OleFile(private val f: ByteArray) {
    private val shift: Int
    private val sectorSize: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private class Entry(val name: String, val type: Int, val start: Int, val size: Long, val left: Int, val right: Int, val child: Int)
    private val entries = ArrayList<Entry>()
    /** Streams directly inside the root storage (not inside embedded objects). */
    private val top = HashMap<String, Entry>()
    /** Names of everything in the file at any depth (for the inspector: macros, embedded objects). */
    val names: Set<String> get() = entries.drop(1).map { it.name }.toSet()
    private val miniCutoff: Int

    init {
        if (f.size < 512 || DocReader.i32(f, 0) != 0xE011CFD0.toInt() || DocReader.i32(f, 4) != 0xE11AB1A1.toInt()) throw IOException("Not an OLE file")
        shift = DocReader.u16(f, 0x1E)
        if (shift != 9 && shift != 12) throw IOException("Bad sector size")
        sectorSize = 1 shl shift
        val sectors = maxOf(0, (f.size - sectorSize) / sectorSize)
        miniCutoff = DocReader.i32(f, 0x38).let { if (it in 1..65536) it else 4096 }
        // FAT sector list: 109 in the header, the rest in the DIFAT chain
        val perSector = sectorSize / 4
        // Never more FAT sectors than needed to describe the whole file
        val needed = sectors / perSector + 1
        val fatSectors = IntArray(needed)
        var fatCount = 0
        for (i in 0 until 109) { val s = DocReader.i32(f, 0x4C + i * 4); if (s >= 0 && s < sectors && fatCount < needed) fatSectors[fatCount++] = s }
        var dif = DocReader.i32(f, 0x44)
        val seen = java.util.BitSet(sectors)
        while (dif >= 0 && dif < sectors && !seen[dif] && fatCount < needed) {
            seen.set(dif)
            val base = offset(dif)
            val per = perSector - 1
            for (i in 0 until per) { val s = DocReader.i32(f, base + i * 4); if (s >= 0 && s < sectors && fatCount < needed) fatSectors[fatCount++] = s }
            dif = DocReader.i32(f, base + per * 4)
        }
        fat = IntArray(minOf(fatCount * perSector, sectors + perSector))
        loop@ for (k in 0 until fatCount) {
            val s = fatSectors[k]
            val base = offset(s)
            for (i in 0 until perSector) {
                val idx = k * perSector + i
                if (idx >= fat.size) break@loop
                fat[idx] = DocReader.i32(f, base + i * 4)
            }
        }
        // Directory entries
        val dir = chain(DocReader.i32(f, 0x30), Long.MAX_VALUE)
        for (e in 0 until dir.size / 128) {
            val at = e * 128
            val nameLen = DocReader.u16(dir, at + 0x40).coerceIn(0, 64)
            val name = if (nameLen >= 2) String(dir, at, nameLen - 2, Charsets.UTF_16LE) else ""
            val type = dir[at + 0x42].toInt()
            val start = DocReader.i32(dir, at + 0x74)
            val size = DocReader.i32(dir, at + 0x78).toLong() and 0xFFFFFFFFL
            entries += Entry(name, type, start, size, DocReader.i32(dir, at + 0x44), DocReader.i32(dir, at + 0x48), DocReader.i32(dir, at + 0x4C))
        }
        val root = entries.firstOrNull()?.takeIf { it.type == 5 } ?: throw IOException("No root entry")
        // The root's children form a tree through left and right siblings
        val stack = ArrayDeque<Int>()
        val visited = java.util.BitSet(entries.size)
        if (root.child in entries.indices) stack += root.child
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i !in entries.indices || visited[i]) continue
            visited.set(i)
            val e = entries[i]
            if (e.type == 2) top.putIfAbsent(e.name, e)
            if (e.left in entries.indices) stack += e.left
            if (e.right in entries.indices) stack += e.right
        }
        miniStream = chain(root.start, root.size)
        miniFat = chain(DocReader.i32(f, 0x3C), Long.MAX_VALUE).let { b -> IntArray(b.size / 4) { DocReader.i32(b, it * 4) } }
    }

    private fun offset(sector: Int): Int {
        val o = (sector.toLong() + 1) shl shift
        if (o < 0 || o + sectorSize > f.size) throw IOException("Sector out of range")
        return o.toInt()
    }

    /** Bytes of a sector chain from [start], at most [size] (and never past a loop). */
    private fun chain(start: Int, size: Long): ByteArray {
        // Count the chain first, so the result is allocated once at its real size
        var s = start
        var length = 0
        val seen = java.util.BitSet(fat.size)
        while (s >= 0 && s < fat.size && !seen[s] && length.toLong() * sectorSize < size) { seen.set(s); length++; s = fat[s] }
        val total = minOf(length.toLong() * sectorSize, size, DocReader.MAX_FILE).toInt()
        val out = ByteArray(total)
        s = start
        var at = 0
        while (at < total) {
            val n = minOf(sectorSize, total - at)
            System.arraycopy(f, offset(s), out, at, n)
            at += n
            s = fat[s]
        }
        return out
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var s = start
        var guard = 0
        val mini = 64
        while (s >= 0 && s < miniFat.size && out.size() < size && guard++ <= miniFat.size) {
            val at = s * mini
            if (at + mini > miniStream.size) throw IOException("Mini sector out of range")
            out.write(miniStream, at, mini)
            s = miniFat[s]
        }
        val bytes = out.toByteArray()
        return if (size < bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    fun stream(name: String): ByteArray? {
        val e = top[name] ?: return null
        if (e.size > DocReader.MAX_FILE) throw IOException("Stream too large")
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }
}
