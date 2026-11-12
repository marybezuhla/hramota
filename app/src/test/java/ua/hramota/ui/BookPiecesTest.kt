package ua.hramota.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.hramota.doc.book.BookBlock
import ua.hramota.doc.book.BookBlock.Kind

class BookPiecesTest {
    private fun t(kind: Kind) = BookBlock.Text("x", emptyList(), kind)

    /** Pieces cover every block once, in order. */
    private fun assertCovers(pieces: List<IntRange>, size: Int) {
        assertEquals(0, pieces.first().first)
        assertEquals(size - 1, pieces.last().last)
        for (k in 1 until pieces.size) assertEquals(pieces[k - 1].last + 1, pieces[k].first)
    }

    @Test fun partsWithChaptersAreCutAtTheChapters() {
        // Three parts (first-level headings), each of ten chapters of 200 paragraphs
        val blocks = ArrayList<BookBlock>()
        repeat(3) { blocks += t(Kind.HEADING1); repeat(10) { blocks += t(Kind.HEADING2); repeat(200) { blocks += t(Kind.PARAGRAPH) } } }
        val pieces = bookPieces(blocks)
        assertCovers(pieces, blocks.size)
        // Not three pieces of 2000 paragraphs: a first page needs a few hundred laid out, not a part
        assertTrue(pieces.size >= 15)
        assertTrue(pieces.all { it.count() <= 700 })
        // Every cut is at a heading
        assertTrue(pieces.all { val b = blocks[it.first] as BookBlock.Text; it.first == 0 || b.kind == Kind.HEADING1 || b.kind == Kind.HEADING2 })
    }

    @Test fun shortChaptersStayWhole() {
        // Poems: many small titled pieces; no page break is added before each one
        val blocks = ArrayList<BookBlock>()
        repeat(5) { blocks += t(Kind.HEADING1); repeat(100) { blocks += t(Kind.HEADING2); repeat(4) { blocks += t(Kind.POEM) } } }
        val pieces = bookPieces(blocks)
        assertCovers(pieces, blocks.size)
        assertEquals(5, pieces.size)
    }

    @Test fun aBookWithNoHeadingsIsCutIntoRuns() {
        val blocks = List(10_000) { t(Kind.PARAGRAPH) }
        val pieces = bookPieces(blocks)
        assertCovers(pieces, blocks.size)
        assertTrue(pieces.all { it.count() <= 3000 })
    }

    @Test fun emptyBook() {
        assertEquals(listOf(0 until 0), bookPieces(emptyList()))
    }
}
