package ua.hramota.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    @Test fun sameTextsHaveNoChanges() {
        val c = Diff.compare(listOf("Один", "Два"), listOf("Один", "Два"))
        assertTrue(c.changes.isEmpty())
        assertEquals(listOf(Block.Same("Один"), Block.Same("Два")), c.blocks)
    }

    @Test fun addedAndRemovedParagraphs() {
        val c = Diff.compare(listOf("Перший пункт", "Старий пункт про штрафи", "Кінець"), listOf("Перший пункт", "Кінець", "Новий додаток"))
        assertEquals(
            listOf(Block.Same("Перший пункт"), Block.Removed("Старий пункт про штрафи"), Block.Same("Кінець"), Block.Added("Новий додаток")),
            c.blocks,
        )
        assertEquals(listOf(1, 3), c.changes)
    }

    @Test fun editedParagraphShowsChangedWords() {
        val c = Diff.compare(listOf("Оплата протягом 10 днів після підписання."), listOf("Оплата протягом 5 днів після підписання."))
        val changed = c.blocks.single() as Block.Changed
        assertEquals(
            listOf(
                Segment("Оплата протягом ", Segment.Kind.SAME),
                Segment("10 ", Segment.Kind.REMOVED),
                Segment("5 ", Segment.Kind.ADDED),
                Segment("днів після підписання.", Segment.Kind.SAME),
            ),
            changed.segments,
        )
    }

    @Test fun wordsRebuildBothVersions() {
        val old = "The supplier delivers the goods within ten working days."
        val new = "The seller delivers all goods within five calendar days, at its own cost."
        val s = Diff.words(old, new)
        assertEquals(old, s.filter { it.kind != Segment.Kind.ADDED }.joinToString("") { it.text })
        assertEquals(new, s.filter { it.kind != Segment.Kind.REMOVED }.joinToString("") { it.text })
    }

    @Test fun unrelatedReplacementIsRemovedThenAdded() {
        val c = Diff.compare(listOf("a", "Зовсім інший текст тут", "z"), listOf("a", "Nothing in common at all", "z"))
        assertEquals(Block.Removed("Зовсім інший текст тут"), c.blocks[1])
        assertEquals(Block.Added("Nothing in common at all"), c.blocks[2])
    }

    @Test fun hugeDifferenceFallsBackToBlock() {
        val a = List(3000) { "old line $it" }
        val b = List(3000) { "new line $it" }
        val c = Diff.compare(a, b)
        assertTrue(c.coarse)
        assertEquals(6000, c.changes.size)
    }

    @Test fun largeDocumentWithOneEditIsFast() {
        val a = List(20000) { "Paragraph number $it of a long contract." }
        val b = a.toMutableList().also { it[12345] = "Paragraph number 12345 of a short contract." }
        val start = System.nanoTime()
        val c = Diff.compare(a, b)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(1, c.changes.size)
        assertTrue("took $ms ms", ms < 2000)
    }
}
