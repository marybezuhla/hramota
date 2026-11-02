package ua.hramota.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Files sealed with AES-256-GCM in segments of 64 KiB, so a file of any size is sealed and opened as a
 * stream and every part is checked. Each segment's nonce is the file's random 8-byte prefix and the
 * segment's number; the last segment is marked in its associated data, so segments cannot be
 * reordered, dropped or cut off without the opening failing. The file's name is bound in too, so one
 * sealed file cannot be passed off as another.
 *
 * Layout: "HRV1" · prefix (8) · segments (each its ciphertext and 16-byte tag).
 */
object Sealed {
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'R'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val SEGMENT = 64 * 1024
    private const val TAG = 16
    private val random = SecureRandom()

    private fun aad(last: Boolean, name: String) = byteArrayOf(if (last) 1 else 0) + name.toByteArray()

    fun seal(key: SecretKey, input: InputStream, output: OutputStream, name: String) {
        val prefix = ByteArray(8).also(random::nextBytes)
        output.write(MAGIC); output.write(prefix)
        var cur = ByteArray(SEGMENT); var n = readFully(input, cur)
        var counter = 0L
        while (true) {
            val next = ByteArray(SEGMENT)
            val m = if (n < SEGMENT) 0 else readFully(input, next)
            val last = m == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter)))
            c.updateAAD(aad(last, name))
            output.write(c.doFinal(cur, 0, n))
            if (last) break
            cur = next; n = m; counter++
            if (counter > 0xFFFFFFFFL) throw IOException("File too large")
        }
    }

    /** Opens a sealed stream; a changed, cut or reordered file throws [IOException] and gives nothing after the damage. */
    fun open(key: SecretKey, input: InputStream, output: OutputStream, name: String) {
        val head = ByteArray(12)
        if (readFully(input, head) != 12 || !head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not a sealed file")
        val prefix = head.copyOfRange(4, 12)
        var cur = ByteArray(SEGMENT + TAG); var n = readFully(input, cur)
        var counter = 0L
        while (true) {
            if (n < TAG) throw IOException("Damaged sealed file")
            val next = ByteArray(SEGMENT + TAG)
            val m = if (n < SEGMENT + TAG) 0 else readFully(input, next)
            val last = m == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter)))
            c.updateAAD(aad(last, name))
            val plain = try { c.doFinal(cur, 0, n) } catch (e: AEADBadTagException) { throw IOException("Damaged sealed file", e) }
            output.write(plain)
            if (last) break
            cur = next; n = m; counter++
            if (counter > 0xFFFFFFFFL) throw IOException("Damaged sealed file")
        }
    }

    fun seal(key: SecretKey, bytes: ByteArray, name: String): ByteArray = ByteArrayOutputStream(bytes.size + 64).also { seal(key, ByteArrayInputStream(bytes), it, name) }.toByteArray()
    fun open(key: SecretKey, bytes: ByteArray, name: String): ByteArray = ByteArrayOutputStream(bytes.size).also { open(key, ByteArrayInputStream(bytes), it, name) }.toByteArray()

    fun isSealed(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
        return n
    }

    /** Sealing as data is written (for a backup built on the fly); [close] seals the last segment. */
    class SealingStream(private val key: SecretKey, private val out: OutputStream, private val name: String) : OutputStream() {
        private val prefix = ByteArray(8).also(random::nextBytes)
        private var buf = ByteArray(SEGMENT)
        private var n = 0
        private var counter = 0L
        private var closed = false

        init { out.write(MAGIC); out.write(prefix) }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                // A full segment is sealed only when more data comes, so the last one can be marked
                if (n == SEGMENT) { segment(last = false); n = 0 }
                val k = minOf(l, SEGMENT - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; l -= k
            }
        }

        private fun segment(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter)))
            c.updateAAD(aad(last, name))
            out.write(c.doFinal(buf, 0, n))
            counter++
            if (counter > 0xFFFFFFFFL) throw IOException("File too large")
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            segment(last = true)
            buf.fill(0)
            out.close()
        }

        /** Stops without sealing the last segment: what was written will never open as complete. */
        fun abort() {
            if (closed) return
            closed = true
            buf.fill(0)
            try { out.close() } catch (_: Exception) {}
        }
    }

    /** Opening as data is read; a damaged file throws [IOException] before any of its damaged part is given out. */
    class OpeningStream(private val key: SecretKey, private val input: InputStream, private val name: String) : InputStream() {
        private var prefix = ByteArray(0)
        private var plain = ByteArray(0)
        private var pos = 0
        private var next: ByteArray? = null
        private var nextLen = 0
        private var counter = 0L
        private var done = false

        init {
            val head = ByteArray(12)
            if (readFully(input, head) != 12 || !head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not a sealed file")
            prefix = head.copyOfRange(4, 12)
            next = ByteArray(SEGMENT + TAG); nextLen = readFully(input, next!!)
        }

        private fun fill(): Boolean {
            if (done) return false
            val cur = next!!; val n = nextLen
            if (n < TAG) throw IOException("Damaged sealed file")
            val following = ByteArray(SEGMENT + TAG)
            val m = if (n < SEGMENT + TAG) 0 else readFully(input, following)
            val last = m == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, counter)))
            c.updateAAD(aad(last, name))
            plain = try { c.doFinal(cur, 0, n) } catch (e: AEADBadTagException) { throw IOException("Damaged sealed file", e) }
            pos = 0
            counter++
            if (last) done = true else { next = following; nextLen = m }
            return true
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!fill()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!fill()) return -1
            val k = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, k)
            pos += k
            return k
        }

        override fun close() = input.close()
    }
}
