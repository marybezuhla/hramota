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
 * reordered, dropped or cut off without the opening failing.
 *
 * Layout: "HRV1" · prefix (8) · segments (each its ciphertext and 16-byte tag).
 */
object Sealed {
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'R'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val SEGMENT = 64 * 1024
    private const val TAG = 16
    private val random = SecureRandom()

    fun seal(key: SecretKey, input: InputStream, output: OutputStream) {
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
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            output.write(c.doFinal(cur, 0, n))
            if (last) break
            cur = next; n = m; counter++
            if (counter > 0xFFFFFFFFL) throw IOException("File too large")
        }
    }

    /** Opens a sealed stream; a changed, cut or reordered file throws [IOException] and gives nothing after the damage. */
    fun open(key: SecretKey, input: InputStream, output: OutputStream) {
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
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            val plain = try { c.doFinal(cur, 0, n) } catch (e: AEADBadTagException) { throw IOException("Damaged sealed file", e) }
            output.write(plain)
            if (last) break
            cur = next; n = m; counter++
            if (counter > 0xFFFFFFFFL) throw IOException("Damaged sealed file")
        }
    }

    fun seal(key: SecretKey, bytes: ByteArray): ByteArray = ByteArrayOutputStream(bytes.size + 64).also { seal(key, ByteArrayInputStream(bytes), it) }.toByteArray()
    fun open(key: SecretKey, bytes: ByteArray): ByteArray = ByteArrayOutputStream(bytes.size).also { open(key, ByteArrayInputStream(bytes), it) }.toByteArray()

    fun isSealed(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
        return n
    }
}
