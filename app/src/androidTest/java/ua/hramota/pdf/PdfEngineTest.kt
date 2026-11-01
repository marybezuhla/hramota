package ua.hramota.pdf

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * PDFium in its isolated process, on a real Android system: opening, drawing through shared memory,
 * the inspector, page text, and a damaged file that must fail cleanly.
 */
@RunWith(AndroidJUnit4::class)
class PdfEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun asset(name: String): ParcelFileDescriptor {
        val file = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test fun opensDrawsAndInspects() = runBlocking {
        val pages = PdfiumPages.open(context, asset("sample.pdf"))
        try {
            assertEquals(2, pages.pageCount)
            assertTrue("portrait first page", pages.aspect(0) > 1f)
            assertTrue("landscape second page", pages.aspect(1) < 1f)

            val image = pages.render(0, 600)
            assertNotNull(image)
            val bitmap = image!!.asAndroidBitmap()
            assertEquals(600, bitmap.width)
            // The blue box on the page must come out blue (colour channels in the right order)
            var blue = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                if ((c and 0xFF) > 200 && ((c shr 16) and 0xFF) < 60 && ((c shr 8) and 0xFF) < 60) blue++
            }
            assertTrue("blue pixels: $blue", blue > 500)

            val i = pages.inspection
            assertEquals(1, i.javascript)
            assertEquals(listOf("notes.txt"), i.embedded)
            assertEquals(1, i.links)
            assertEquals("Test Author", i.author)
            assertTrue(i.risky)

            assertTrue(pages.text(0)!!.contains("Hramota test page"))

            // Search and selection: text, boxes on the page, and the character under a finger
            val text = pages.pageText(0)
            val at = text.indexOf("test")
            val boxes = pages.boxes(0, at, at + 4)
            assertEquals(1, boxes.size)
            val b = boxes[0]
            assertTrue("$b", b.top in 0.05f..0.15f && b.left in 0.3f..0.4f)
            val c = pages.charAt(0, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
            assertTrue("charAt $c", c in at until at + 4)
            assertEquals(-1, pages.charAt(0, 0.98f, 0.98f))
        } finally {
            pages.close()
        }
    }

    @Test fun damagedFileFailsCleanly() = runBlocking {
        try {
            PdfiumPages.open(context, asset("broken.pdf")).close()
            fail("a damaged file must not open")
        } catch (e: PdfEngineUnavailableException) {
            fail("the engine itself must start: ${e.message}")
        } catch (e: IOException) {
            // expected: the file is reported as unreadable
        }
    }

    @Test fun twoDocumentsAtOnce() = runBlocking {
        val a = PdfiumPages.open(context, asset("sample.pdf"))
        val b = PdfiumPages.open(context, asset("sample.pdf"))
        try {
            assertNotNull(a.render(1, 300))
            assertNotNull(b.render(0, 300))
        } finally {
            a.close(); b.close()
        }
    }
}
