package ua.hramota.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Sealed files open to exactly what went in, and any change, cut, addition or swap is caught. */
class SealedTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun anySize() {
        for (n in listOf(0, 1, 100, Sealed.SEGMENT - 1, Sealed.SEGMENT, Sealed.SEGMENT + 1, Sealed.SEGMENT * 3, Sealed.SEGMENT * 3 + 7)) {
            val data = Random(n).nextBytes(n)
            assertArrayEquals(data, Sealed.open(key, Sealed.seal(key, data, "f"), "f"))
        }
    }

    @Test fun streams() {
        val data = Random(1).nextBytes(Sealed.SEGMENT * 5 + 123)
        val out = ByteArrayOutputStream()
        Sealed.SealingStream(key, out, "b").use { s -> var i = 0; while (i < data.size) { val k = minOf(777, data.size - i); s.write(data, i, k); i += k } }
        assertArrayEquals(data, Sealed.OpeningStream(key, ByteArrayInputStream(out.toByteArray()), "b").readBytes())
        val back = ByteArrayOutputStream()
        Sealed.open(key, ByteArrayInputStream(out.toByteArray()), back, "b")
        assertArrayEquals(data, back.toByteArray())
        // A full last segment
        val exact = Random(3).nextBytes(Sealed.SEGMENT * 2)
        val o2 = ByteArrayOutputStream(); Sealed.SealingStream(key, o2, "b").use { it.write(exact) }
        assertArrayEquals(exact, Sealed.open(key, o2.toByteArray(), "b"))
    }

    private fun fails(bytes: ByteArray, name: String = "f") = runCatching { Sealed.open(key, bytes, name) }.isFailure

    @Test fun catchesTampering() {
        val data = Random(2).nextBytes(Sealed.SEGMENT * 2 + 50)
        val sealed = Sealed.seal(key, data, "f")
        assertTrue("a changed byte", fails(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        assertTrue("a changed prefix", fails(sealed.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        val seg = Sealed.SEGMENT + 16
        assertTrue("the last segment dropped", fails(sealed.copyOfRange(0, 12 + seg * 2)))
        assertTrue("cut in the middle", fails(sealed.copyOfRange(0, sealed.size - 5)))
        assertTrue("data added after the end", fails(sealed + ByteArray(40)))
        assertTrue("only the header", fails(sealed.copyOfRange(0, 12)))
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, 12 + seg, swapped, 12, seg); System.arraycopy(sealed, 12, swapped, 12 + seg, seg)
        assertTrue("segments swapped", fails(swapped))
        assertTrue("passed off as another file", fails(sealed, "other"))
        assertTrue("another key", runCatching { Sealed.open(SecretKeySpec(ByteArray(32), "AES"), sealed, "f") }.isFailure)
        assertTrue("not sealed", fails("plain text".toByteArray()))
    }
}
