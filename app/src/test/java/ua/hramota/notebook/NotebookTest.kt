package ua.hramota.notebook

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ua.hramota.doc.docx.DocxReader
import ua.hramota.ink.InkPoint
import ua.hramota.ink.Stroke
import ua.hramota.readingText
import java.io.File

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35])
class NotebookTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun marksFollowTheText() {
        val bold = listOf(Span(4, 9, "b"))
        // Typed before: moved
        assertEquals(listOf(Span(6, 11, "b")), adjustSpans(bold, "Дуже важливо", "Ой Дуже важливо".substring(1)))
        // Typed right after a bold word: bold goes on
        assertEquals(listOf(Span(4, 11, "b")), adjustSpans(bold, "abcdefghijkl", "abcdefghi!!jkl"))
        // Deleted inside: shorter
        assertEquals(listOf(Span(4, 7, "b")), adjustSpans(bold, "abcdefghijkl", "abcdehijkl"))
        // All of it deleted: gone
        assertEquals(emptyList<Span>(), adjustSpans(bold, "abcdefghijkl", "abcdjkl"))
    }

    @Test fun marksAreToggled() {
        val on = toggleSpan(emptyList(), 2, 5, "b")
        assertEquals(listOf(Span(2, 5, "b")), on)
        assertEquals(listOf(Span(2, 8, "b")), toggleSpan(on, 4, 8, "b"))
        assertEquals(listOf(Span(2, 3, "b"), Span(4, 5, "b")), toggleSpan(on, 3, 4, "b"))
        assertEquals(emptyList<Span>(), toggleSpan(on, 2, 5, "b"))
    }

    @Test fun enterSplitsAndListsGoOn() {
        val s = NoteState(NoteMeta("n"), listOf(NBlock.Text(1, "Купити хліб", "check")))
        s.typed(1, "Купити хліб\n", 12)
        assertEquals(2, s.blocks.size)
        assertEquals("check", (s.blocks[1] as NBlock.Text).style)
        // Enter on an empty item ends the list
        s.typed(s.blocks[1].id, "\n", 1)
        assertEquals("body", (s.blocks.last() as NBlock.Text).style)
        // Backspace at the start of an ordinary paragraph joins it to the one before
        s.typed(s.blocks.last().id, "і молоко", 8)
        assertTrue(s.backspaceAtStart(s.blocks.last().id))
        assertEquals("Купити хлібі молоко", (s.blocks.last() as NBlock.Text).text)
        s.undo(); s.undo()
        assertEquals(2, s.blocks.size)
    }

    @Test fun notesAreKeptFoundAndBinned() {
        Notebook.reload(context)
        val book = Notebook.newBook("Робота", Notebook.COLORS[1]).also { Notebook.putBook(context, it) }
        val ink = NBlock.Ink(2, listOf(Stroke(listOf(InkPoint(1f, 1f, 0, 0.5f), InkPoint(40f, 30f, 30, 0.5f)))), recognized = "рукописне слово")
        val m = Notebook.create(context, book.id, "lines", listOf(NBlock.Text(1, "Перший рядок", "h1", spans = listOf(Span(0, 6, "b"))), ink), "Нарада")
        val back = Notebook.read(context, m.id)!!
        assertEquals("Перший рядок", (back.blocks[0] as NBlock.Text).text)
        assertEquals(listOf(Span(0, 6, "b")), (back.blocks[0] as NBlock.Text).spans)
        assertEquals(1, (back.blocks[1] as NBlock.Ink).strokes.size)
        assertEquals("lines", back.meta.paper)
        // Found by its handwriting as read
        assertEquals(listOf(m.id), searchNotes(Notebook.notes(context).value, "рукописне").map { it.id })
        // Locked: not searched
        Notebook.update(context, m.id) { it.copy(locked = true) }
        assertTrue(searchNotes(Notebook.notes(context).value, "рукописне").isEmpty())
        Notebook.toBin(context, m.id)
        assertTrue(Notebook.notes(context).value.first { it.id == m.id }.deleted > 0)
        Notebook.toBin(context, m.id, back = true)
        Notebook.removeBook(context, book.id)
        assertEquals(null, Notebook.notes(context).value.first { it.id == m.id }.book)
        Notebook.deleteForGood(context, m.id)
        assertTrue(Notebook.notes(context).value.none { it.id == m.id })
    }

    private fun sample() = Note(NoteMeta("x", title = "Протокол"), listOf(
        NBlock.Text(1, "Порядок денний", "h1"),
        NBlock.Text(2, "Звичайний *текст* з жирним", spans = listOf(Span(19, 26, "b"), Span(0, 9, "hl"))),
        NBlock.Text(3, "Перше", "number"), NBlock.Text(4, "Друге", "number"),
        NBlock.Text(5, "Зроблено", "check", checked = true), NBlock.Text(6, "Не зроблено", "check"),
        NBlock.Text(7, "Мудра думка", "quote"),
        NBlock.Ink(8, listOf(Stroke(listOf(InkPoint(10f, 10f, 0, 0.5f), InkPoint(120f, 60f, 50, 0.5f)))), recognized = "привіт"),
    ))

    @Test fun markdown() {
        val md = NoteExport.markdown(sample())
        assertTrue(md, md.startsWith("# Протокол\n\n## Порядок денний\n"))
        assertTrue(md, md.contains("1. Перше\n2. Друге\n"))
        assertTrue(md, md.contains("- [x] Зроблено\n- [ ] Не зроблено\n"))
        assertTrue(md, md.contains("**жирним**"))
        assertTrue(md, md.contains("==Звичайний=="))
        assertTrue(md, md.contains("\\*текст\\*"))
        assertTrue(md, md.contains("> Мудра думка"))
        assertTrue(md, md.contains("> ✍ привіт"))
    }

    @Test fun wordAndPicture() {
        val docx = NoteExport.docx(context, sample(), "uk-UA")
        File("build/edited").apply { mkdirs() }.let { File(it, "note-export.docx").writeBytes(docx) }
        val text = DocxReader.read(docx.inputStream()).readingText().map { it.text }
        assertTrue(text.toString(), text.any { it.contains("Порядок денний") })
        assertTrue(text.any { it.contains("☑ Зроблено") })
        val png = NoteExport.png(context, sample())
        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size))
    }

    @Test fun templates() {
        for (k in NoteTemplates.KEYS) {
            val (title, blocks) = NoteTemplates.make(context, k)
            assertFalse(k, title.isBlank())
            assertTrue(k, blocks.isNotEmpty())
            assertEquals(k, blocks.size, blocks.map { it.id }.toSet().size)
        }
    }
}
