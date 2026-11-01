package ua.hramota.doc.docx

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * Old Word files laid out on pages: the test documents saved as .doc (by LibreOffice) must come
 * out like the same documents as .docx, and Word-made files must open without errors.
 * Pictures go to build/render-doc and are compared with LibreOffice in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocRenderTest {
    private val fonts = Fonts(ApplicationProvider.getApplicationContext<android.content.Context>().assets)

    private fun dir(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    @Test fun rendersEveryTestDocument() {
        val out = File("build/render-doc").apply { mkdirs() }
        val summary = StringBuilder()
        for (file in dir("docs-doc").listFiles { f -> f.name.endsWith(".doc") }!!.sortedBy { it.name }) {
            val doc = file.inputStream().use { WordBinaryReader.read(it) }
            val pages = DocxPages(doc, fonts)
            for (i in 0 until pages.pageCount) {
                File(out, "${file.nameWithoutExtension}-${i + 1}.png").outputStream().use { pages.draw(i, 1240)!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val docx = DocxPages(File(dir("docs"), file.nameWithoutExtension + ".docx").inputStream().use { DocxReader.read(it) }, fonts)
            summary.append("${file.name}: ${pages.pageCount} pages (DOCX ${docx.pageCount}), page ${doc.page}, blocks ${doc.blocks.size}\n")
        }
        File(out, "pages.txt").writeText(summary.toString())
    }

    // The same text as the .docx: every paragraph, in order
    @Test fun sameTextAsDocx() {
        for (name in listOf("02-paragraphs", "03-lists", "04-tables", "05-flow")) {
            val doc = File(dir("docs-doc"), "$name.doc").inputStream().use { WordBinaryReader.read(it) }
            val docx = File(dir("docs"), "$name.docx").inputStream().use { DocxReader.read(it) }
            fun words(d: DocxDocument): List<String> {
                val p = DocxPages(d, fonts)
                val text = runBlocking { (0 until p.pageCount).map { p.pageText(it) } }.joinToString(" ")
                return text.split(Regex("\\s+")).filter { it.isNotBlank() }
            }
            assertEquals(name, words(docx), words(doc))
        }
    }

    // Word-made files, including damaged ones: open or fail cleanly
    @Test fun wordFilesOpenOrFailCleanly() {
        for (file in dir("legacy").listFiles { f -> f.name.endsWith(".doc") }!!) {
            try {
                val doc = file.inputStream().use { WordBinaryReader.read(it) }
                val pages = DocxPages(doc, fonts)
                assertTrue(file.name, pages.pageCount > 0)
                // For review: the first pages of Word-made files
                val out = File("build/render-doc-word").apply { mkdirs() }
                for (i in 0 until minOf(2, pages.pageCount)) {
                    File(out, "${file.nameWithoutExtension}-${i + 1}.png").outputStream().use { pages.draw(i, 1240)!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } catch (e: IOException) {
                assertTrue(file.name, file.name.startsWith("fuzzed"))
            }
        }
    }
}
