package ua.hramota.big

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.hramota.BigDocs
import ua.hramota.diff.Diff
import ua.hramota.doc.CsvReader
import ua.hramota.doc.Format
import ua.hramota.doc.Search
import ua.hramota.doc.TextExtractor
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.Fonts
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Very large documents on the computer: they must open, lay out, search and compare within
 * bounds. Times and memory go to build/big/report.txt (the emulator test measures the phone).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigDocumentsTest {
    private val fonts by lazy { Fonts(ApplicationProvider.getApplicationContext<Context>().assets) }

    @Test fun thousandPageTable() = runBlocking {
        val bytes = BigDocs.docxTable(ROWS, 1)
        val (doc, readMs, readMb) = measure { DocxReader.read(ByteArrayInputStream(bytes)) }
        // As the viewer opens it: the first page at once, the rest on other threads
        val (pages, firstMs, _) = measure { DocxPages(doc, fonts, background = true) }
        val (_, layoutMs, layoutMb) = measure { while (!pages.complete) Thread.sleep(5) }
        val (hit, searchMs, _) = measure { runBlocking { (0 until pages.pageCount).lastOrNull { Search.find(pages.pageText(it), BigDocs.NEEDLE.lowercase()).isNotEmpty() } } }
        val (_, drawMs, _) = measure { pages.draw(pages.pageCount - 1, 1080) }
        report("DOCX table, ${ROWS} rows, ${bytes.size / 1024} KB zipped: ${pages.pageCount} pages; read $readMs ms (+$readMb MB), " +
            "first page $firstMs ms, all pages +$layoutMs ms (+$layoutMb MB), search all pages $searchMs ms, draw last page $drawMs ms")
        // Laid out on several threads, the pages are exactly those of the one-thread layout
        val sequential = DocxPages(doc, fonts)
        assertEquals(sequential.pageCount, pages.pageCount)
        assertEquals(runBlocking { sequential.pageText(pages.pageCount / 2) }, runBlocking { pages.pageText(pages.pageCount / 2) })
        assertTrue("pages: ${pages.pageCount}", pages.pageCount >= 900)
        // The last row can break across the last two pages
        assertTrue("found on $hit", hit != null && hit >= pages.pageCount - 2)
    }

    @Test fun longProse() = runBlocking {
        val bytes = BigDocs.docxProse(PARAGRAPHS)
        val (doc, readMs, readMb) = measure { DocxReader.read(ByteArrayInputStream(bytes)) }
        val (pages, layoutMs, layoutMb) = measure { DocxPages(doc, fonts) }
        val (hit, searchMs, _) = measure { runBlocking { (0 until pages.pageCount).lastOrNull { Search.find(pages.pageText(it), BigDocs.NEEDLE).isNotEmpty() } } }
        report("DOCX prose, $PARAGRAPHS paragraphs: ${pages.pageCount} pages; read $readMs ms (+$readMb MB), layout $layoutMs ms (+$layoutMb MB), search $searchMs ms")
        assertEquals(pages.pageCount - 1, hit)
    }

    @Test fun compareThousandPages() {
        val a = TextExtractor.read(ByteArrayInputStream(BigDocs.docxTable(ROWS, 1)), Format.DOCX).map { it.text }
        val b = TextExtractor.read(ByteArrayInputStream(BigDocs.docxTable(ROWS, 2)), Format.DOCX).map { it.text }
        val (c, ms, mb) = measure { Diff.compare(a, b) }
        report("Compare ${a.size} vs ${b.size} paragraphs: ${c.changes.size} changes in $ms ms (+$mb MB)")
        assertFalse("compared as one replaced block", c.coarse)
        assertEquals(BigDocs.changesExpected(ROWS), c.changes.size)
    }

    @Test fun bigCsv() {
        val text = BigDocs.csv(CsvReader.MAX_ROWS - 1)
        val (t, ms, mb) = measure { CsvReader.parse(text) }
        assertEquals(CsvReader.MAX_ROWS, t.rows.size)
        assertFalse(t.truncated)
        report("CSV ${t.rows.size} rows, ${text.length / 1024} K chars: $ms ms (+$mb MB)")
    }

    // Where reading a large DOCX spends its time: the reading thread's stack, sampled every millisecond
    @Test fun readerProfile() {
        val bytes = BigDocs.docxTable(ROWS, 1)
        repeat(2) { DocxReader.read(ByteArrayInputStream(bytes)) }
        val worker = Thread.currentThread()
        val own = HashMap<String, Int>()
        val inside = HashMap<String, Int>()
        var samples = 0
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val stack = worker.stackTrace
                if (stack.isNotEmpty()) {
                    samples++
                    val top = stack.first()
                    own.merge("${top.className.substringAfterLast('.')}.${top.methodName}", 1, Int::plus)
                    stack.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }.filter { it.contains("Styles") || it.contains("Props") || it.contains("Body") || it.contains("Xml") || it.contains("Parser") || it.contains("Docx") }
                        .distinct().forEach { inside.merge(it, 1, Int::plus) }
                }
                Thread.sleep(1)
            }
        }
        sampler.start()
        repeat(3) { DocxReader.read(ByteArrayInputStream(bytes)) }
        running.set(false)
        sampler.join()
        report("Reader profile, $samples samples. Own time: " + own.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key} ${it.value * 100 / samples}%" })
        report("Reader profile, inclusive: " + inside.entries.sortedByDescending { it.value }.take(20).joinToString { "${it.key} ${it.value * 100 / samples}%" })
    }

    private data class Measured<T>(val value: T, val ms: Long, val mb: Long)

    private fun <T> measure(block: () -> T): Measured<T> {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val v = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        return Measured(v, ms, (after - before) / (1024 * 1024))
    }

    companion object {
        const val ROWS = 9000
        const val PARAGRAPHS = 30_000
        private val lines = StringBuffer()
        fun report(line: String) { println(line); lines.append(line).append('\n') }

        @AfterClass @JvmStatic fun write() {
            File("build/big").mkdirs()
            File("build/big/report.txt").appendText(lines.toString())
        }
    }
}
