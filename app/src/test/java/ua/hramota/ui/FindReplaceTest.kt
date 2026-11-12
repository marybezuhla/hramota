package ua.hramota.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.readingText
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.slides.edit.PptxEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** "Find and replace" in the Word and slide editors: the right places, the formatting kept, one step to undo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FindReplaceTest {
    private fun bytes(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI()).readBytes()

    @Test fun placesFound() {
        val texts = listOf(1L to "Кіт і кіт", 2L to "КІТ", 3L to "кит")
        assertEquals(listOf(Found(1, 6, 9)), findAll(texts, "кіт", matchCase = true))
        assertEquals(3, findAll(texts, "кіт", matchCase = false).size)
        assertTrue(findAll(texts, "", matchCase = false).isEmpty())
        // Not overlapping
        assertEquals(2, findAll(listOf(1L to "аааа"), "аа", matchCase = true).size)
    }

    @Test fun wordReplaceAllIsOneStep() {
        val name = "docs/02-paragraphs.docx"
        val edit = DocxEdit.open(ByteArrayInputStream(bytes(name)))
        val state = EditorState(edit)
        val word = "Постачальник"
        val found = findAll(state.searchable(), word, matchCase = true)
        assertTrue(found.isNotEmpty())
        val n = state.replaceAll(found, "ЗАМІНА")
        assertEquals(found.size, n)
        assertTrue(findAll(state.searchable(), word, matchCase = true).isEmpty())
        val out = ByteArrayOutputStream().also { edit.save(state.paras, ByteArrayInputStream(bytes(name)), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "replaced-02-paragraphs.docx").writeBytes(out) }
        val text = DocxReader.read(ByteArrayInputStream(out)).readingText().joinToString("\n") { it.text }
        assertEquals(n, Regex("ЗАМІНА").findAll(text).count())
        // One step back gives the text as it was
        state.undo()
        assertEquals(found.size, findAll(state.searchable(), word, matchCase = true).size)
    }

    @Test fun slideReplaceKeepsTheFormatting() {
        val name = "slides/01-project.pptx"
        val edit = PptxEdit.open(ByteArrayInputStream(bytes(name)))
        val state = SlideEditorState(edit)
        val found = findAll(state.searchable(), "Грамота", matchCase = true)
        assertTrue(found.isNotEmpty())
        val first = state.paras.first { it.key == found[0].key }
        val style = first.marks[found[0].start]
        assertTrue(state.replace(found[0], "Hramota"))
        val after = state.paras.first { it.key == found[0].key }
        assertTrue(after.text.contains("Hramota"))
        assertEquals(style, after.marks[found[0].start])
        val out = ByteArrayOutputStream().also { edit.save(state.paras, ByteArrayInputStream(bytes(name)), it) }.toByteArray()
        File("build/edited").apply { mkdirs() }.let { File(it, "replaced-01-project.pptx").writeBytes(out) }
    }
}
