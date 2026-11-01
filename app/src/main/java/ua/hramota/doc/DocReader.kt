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
    const val MAX_FILE = 64L * 1024 * 1024
    /** Longest text read. */
    const val MAX_CHARS = 20_000_000

    fun read(input: InputStream): List<Paragraph> {
        val file = readAll(input)
        val cfb = Compound(file)
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
        val cp1252 = Charset.forName("windows-1252")
        for (i in 0 until n) {
            val cpStart = i32(table, plc + i * 4)
            val cpEnd = minOf(i32(table, plc + (i + 1) * 4), ccpText)
            if (cpStart >= ccpText) break
            val count = cpEnd - cpStart
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
            if (text.length > MAX_CHARS) break
        }
        return paragraphs(text)
    }

    /** Word's control characters become paragraph and line breaks; field codes keep only their result. */
    private fun paragraphs(text: CharSequence): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        val sb = StringBuilder()
        // Inside a field: text before its separator (the field code) is skipped
        val fields = ArrayList<Boolean>()
        fun flush() { out += Paragraph(sb.toString().trimEnd()); sb.clear() }
        for (c in text) {
            when (c.code) {
                0x13 -> { fields += false; continue }
                0x14 -> { if (fields.isNotEmpty()) fields[fields.size - 1] = true; continue }
                0x15 -> { if (fields.isNotEmpty()) fields.removeAt(fields.size - 1); continue }
            }
            if (fields.isNotEmpty() && !fields.last()) continue
            when (c.code) {
                0x0D, 0x07, 0x0C -> flush()
                0x0B -> sb.append('\n')
                0x09 -> sb.append('\t')
                0x1E -> sb.append('-')
                0x1F, 0x01, 0x08, 0x05, 0x02, 0x03, 0x04 -> Unit
                else -> if (c.code >= 0x20) sb.append(c)
            }
        }
        if (sb.isNotEmpty()) flush()
        return out
    }

    private fun readAll(input: InputStream): ByteArray {
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

    private fun u16(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) throw IOException("Read past end")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) throw IOException("Read past end")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }

    /** A minimal reader of OLE compound files (version 3 and 4): directory, FAT, mini stream. */
    private class Compound(private val f: ByteArray) {
        private val shift: Int
        private val sectorSize: Int
        private val fat: IntArray
        private val miniFat: IntArray
        private val miniStream: ByteArray
        private val entries = ArrayList<Triple<String, Int, Long>>()  // name, start sector, size
        private val miniCutoff: Int

        init {
            if (f.size < 512 || i32(f, 0) != 0xE011CFD0.toInt() || i32(f, 4) != 0xE11AB1A1.toInt()) throw IOException("Not an OLE file")
            shift = u16(f, 0x1E)
            if (shift != 9 && shift != 12) throw IOException("Bad sector size")
            sectorSize = 1 shl shift
            val sectors = maxOf(0, (f.size - sectorSize) / sectorSize)
            miniCutoff = i32(f, 0x38).let { if (it in 1..65536) it else 4096 }
            // FAT sector list: 109 in the header, the rest in the DIFAT chain
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) { val s = i32(f, 0x4C + i * 4); if (s >= 0 && s < sectors) fatSectors += s }
            var dif = i32(f, 0x44)
            var guard = 0
            while (dif >= 0 && dif < sectors && guard++ < sectors) {
                val base = offset(dif)
                val per = sectorSize / 4 - 1
                for (i in 0 until per) { val s = i32(f, base + i * 4); if (s >= 0 && s < sectors) fatSectors += s }
                dif = i32(f, base + per * 4)
            }
            val perSector = sectorSize / 4
            fat = IntArray(minOf(fatSectors.size * perSector, sectors + perSector))
            loop@ for ((k, s) in fatSectors.withIndex()) {
                val base = offset(s)
                for (i in 0 until perSector) {
                    val idx = k * perSector + i
                    if (idx >= fat.size) break@loop
                    fat[idx] = i32(f, base + i * 4)
                }
            }
            // Directory entries
            val dir = chain(i32(f, 0x30), Long.MAX_VALUE)
            for (e in 0 until dir.size / 128) {
                val at = e * 128
                val nameLen = u16(dir, at + 0x40).coerceIn(0, 64)
                val name = if (nameLen >= 2) String(dir, at, nameLen - 2, Charsets.UTF_16LE) else ""
                val type = dir[at + 0x42].toInt()
                val start = i32(dir, at + 0x74)
                val size = i32(dir, at + 0x78).toLong() and 0xFFFFFFFFL
                if (type == 1 || type == 2 || type == 5) entries += Triple(name, start, size)
            }
            val root = entries.firstOrNull() ?: throw IOException("No root entry")
            miniStream = chain(root.second, root.third)
            miniFat = chain(i32(f, 0x3C), Long.MAX_VALUE).let { b -> IntArray(b.size / 4) { i32(b, it * 4) } }
        }

        private fun offset(sector: Int): Int {
            val o = (sector.toLong() + 1) shl shift
            if (o < 0 || o + sectorSize > f.size) throw IOException("Sector out of range")
            return o.toInt()
        }

        /** Bytes of a sector chain from [start], at most [size] (and never past a loop). */
        private fun chain(start: Int, size: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size && out.size() < size && guard++ <= fat.size) {
                out.write(f, offset(s), sectorSize)
                s = fat[s]
            }
            val bytes = out.toByteArray()
            return if (size < bytes.size) bytes.copyOf(size.toInt()) else bytes
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
            val e = entries.drop(1).firstOrNull { it.first == name } ?: return null
            if (e.third > MAX_FILE) throw IOException("Stream too large")
            return if (e.third < miniCutoff) miniChain(e.second, e.third) else chain(e.second, e.third)
        }
    }
}
