package ua.hramota.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.hramota.doc.book.Book
import ua.hramota.doc.book.BookBlock

/** Selected text found in the book where it is shown, whatever joins the paragraphs. */
class LocateTest {
    private val book = Book("t", "a", listOf(
        BookBlock.Text("Перший абзац про млин.", emptyList(), BookBlock.Kind.PARAGRAPH),
        BookBlock.Text("Другий абзац про млин і воду.", emptyList(), BookBlock.Kind.PARAGRAPH),
        BookBlock.Text("Рядок\nвірша", emptyList(), BookBlock.Kind.POEM),
    ), emptyMap(), null)
    private val all = listOf(Visible.Part(0, 0, Int.MAX_VALUE), Visible.Part(1, 0, Int.MAX_VALUE), Visible.Part(2, 0, Int.MAX_VALUE))

    @Test fun withinOneBlock() {
        val n = locate(book, all, "абзац про млин і")!!
        assertEquals(1, n.block); assertEquals(7, n.start); assertEquals(1, n.endBlock); assertEquals(23, n.end)
    }

    @Test fun acrossBlocksWithOrWithoutLineEnd() {
        for (sel in listOf("про млин.\nДругий", "про млин.Другий")) {
            val n = locate(book, all, sel)!!
            assertEquals(0, n.block); assertEquals(13, n.start); assertEquals(1, n.endBlock); assertEquals(6, n.end)
        }
        // A line break inside a poem comes back as a line end in the selection
        assertEquals(2, locate(book, all, "Рядок\nвірша")!!.block)
    }

    @Test fun onlyWhatIsShown() {
        // The first paragraph's start is on the previous page: the match is the one on this page
        val page = listOf(Visible.Part(0, 13, Int.MAX_VALUE), Visible.Part(1, 0, Int.MAX_VALUE))
        val n = locate(book, page, "про млин")!!
        assertEquals(0, n.block); assertEquals(13, n.start)
        assertNull(locate(book, page, "Перший"))
    }

    @Test fun longParagraphsAreCut() {
        val b = ua.hramota.doc.book.BlockBuilder()
        b.text((1..2000).joinToString(" ") { "Речення номер $it." })
        b.end(BookBlock.Kind.PARAGRAPH)
        assertEquals(true, b.blocks.size > 1)
        assertEquals(BookBlock.Kind.CONTINUED, (b.blocks[1] as BookBlock.Text).kind)
        assertEquals(true, b.blocks.all { (it as BookBlock.Text).text.length <= 6000 })
    }
}
