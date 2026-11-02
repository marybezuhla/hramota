package ua.hramota.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Sealed files open to exactly what went in, and any change, cut or reordering is caught. */
class SealedTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        assertArrayEquals(data, Sealed.open(key, Sealed.seal(key, data)))
    }

    @Test fun anySize() {
        for (n in listOf(0, 1, 100, Sealed.SEGMENT - 1, Sealed.SEGMENT, Sealed.SEGMENT + 1, Sealed.SEGMENT * 3, Sealed.SEGMENT * 3 + 7)) roundTrip(n)
    }

    @Test fun streams() {
        val data = Random(1).nextBytes(Sealed.SEGMENT * 5 + 123)
        val out = ByteArrayOutputStream()
        Sealed.seal(key, ByteArrayInputStream(data), out)
        val back = ByteArrayOutputStream()
        Sealed.open(key, ByteArrayInputStream(out.toByteArray()), back)
        assertArrayEquals(data, back.toByteArray())
    }

    private fun fails(bytes: ByteArray) = runCatching { Sealed.open(key, bytes) }.isFailure

    @Test fun catchesTampering() {
        val data = Random(2).nextBytes(Sealed.SEGMENT * 2 + 50)
        val sealed = Sealed.seal(key, data)
        assertTrue("a changed byte", fails(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        val seg = Sealed.SEGMENT + 16
        assertTrue("the last segment dropped", fails(sealed.copyOfRange(0, 12 + seg * 2)))
        assertTrue("cut in the middle", fails(sealed.copyOfRange(0, sealed.size - 5)))
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, 12 + seg, swapped, 12, seg); System.arraycopy(sealed, 12, swapped, 12 + seg, seg)
        assertTrue("segments swapped", fails(swapped))
        assertTrue("another key", runCatching { Sealed.open(SecretKeySpec(ByteArray(32), "AES"), sealed) }.isFailure)
        assertTrue("not sealed", fails("plain text".toByteArray()))
    }
}
