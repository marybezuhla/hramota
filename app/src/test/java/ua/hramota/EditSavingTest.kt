package ua.hramota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** Writing the whole file through another app: what happens when that app misbehaves. */
class EditSavingTest {
    /** A file held by another app: [truncates] false ignores "truncate"; [failAfter] stops writing after that many bytes. */
    private class Fake(var bytes: ByteArray, val truncates: Boolean = true, val failAfter: Int = Int.MAX_VALUE, val writable: Boolean = true) : EditSaving.Io {
        override fun read() = ByteArrayInputStream(bytes)
        override fun write(mode: String): OutputStream? {
            if (!writable) throw SecurityException("read only")
            val start = if (truncates && 't' in mode) ByteArray(0) else bytes.copyOf()
            return object : OutputStream() {
                val written = ByteArrayOutputStream()
                override fun write(b: Int) {
                    if (written.size() >= failAfter) { flush(); throw java.io.IOException("disk full") }
                    written.write(b)
                }
                override fun flush() {
                    val w = written.toByteArray()
                    bytes = if (w.size >= start.size) w else w + start.copyOfRange(w.size, start.size)
                }
                override fun close() = flush()
            }
        }
    }

    private val dir = File("build/edit-saving").apply { mkdirs() }
    private fun file(name: String, b: ByteArray) = File(dir, name).apply { writeBytes(b) }
    private fun rescue() = EditSaving.Rescue(File(dir, "r.docx"), File(dir, "r.txt"), null, dir)
    private val original = ByteArray(5000) { (it % 251).toByte() }
    private val edited = ByteArray(3000) { (it % 13).toByte() }

    @Test fun writesAndChecks() {
        val f = Fake(original)
        assertEquals(EditSaving.Write.DONE, EditSaving.writeWhole(f, file("a", edited), rescue()))
        assertArrayEquals(edited, f.bytes)
    }

    @Test fun appIgnoringTruncateGetsTheOriginalBack() {
        // A shorter file written over a longer one leaves the old end: the original is put back
        val f = Fake(original, truncates = false)
        assertEquals(EditSaving.Write.RESTORED, EditSaving.writeWhole(f, file("a", edited), rescue()))
        assertArrayEquals(original, f.bytes)
    }

    @Test fun writingStoppedHalfwayGetsTheOriginalBack() {
        val f = Fake(original, failAfter = 1000)
        val r = EditSaving.writeWhole(f, file("a", edited), rescue())
        // Writing the original back stops at the same point: then it is said to be damaged, and the original stays kept
        assertEquals(EditSaving.Write.BROKEN, r)
        assertArrayEquals(original, File(dir, "r.docx").readBytes())
    }

    @Test fun readOnlyFileIsLeftAlone() {
        val f = Fake(original, writable = false)
        assertEquals(EditSaving.Write.NOT_ALLOWED, EditSaving.writeWhole(f, file("a", edited), rescue()))
        assertArrayEquals(original, f.bytes)
    }
}
