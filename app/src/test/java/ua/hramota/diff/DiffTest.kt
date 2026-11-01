package ua.hramota.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // Nothing in common and too large for an exact match: paragraphs are paired in order
    @Test fun hugeDifferenceFallsBackToBlock() {
        val a = List(3000) { "old line $it" }
        val b = List(3000) { "new line $it" }
        val c = Diff.compare(a, b)
        assertTrue(c.coarse)
        assertEquals(3000, c.changes.size)
    }

    // Large documents with a few changes are matched exactly, wherever the changes are
    @Test fun largeDocumentsWithScatteredChanges() {
        val a = List(40_000) { "Пункт $it. Текст пункту номер $it." }
        val b = a.toMutableList()
        b[100] = "Пункт 100. Змінений текст пункту номер 100."
        b.removeAt(20_000)
        b.add(30_000, "Новий пункт")
        b[39_000] = "Пункт 39001. Текст пункту номер 39001 — уточнено."
        val c = Diff.compare(a, b)
        assertFalse(c.coarse)
        assertEquals(4, c.changes.size)
        assertEquals(Block.Removed("Пункт 20000. Текст пункту номер 20000."), c.blocks[c.changes[1]])
        assertEquals(Block.Added("Новий пункт"), c.blocks[c.changes[2]])
    }

    // Repeated paragraphs (empty lines, "—") have no anchors of their own and are matched in the gaps
    @Test fun repeatedParagraphsBetweenAnchors() {
        val a = listOf("Вступ", "", "—", "Розділ 1", "", "—", "Розділ 2")
        val b = listOf("Вступ", "", "—", "Розділ 1", "", "Розділ 2")
        val c = Diff.compare(a, b)
        assertEquals(listOf(Block.Removed("—")), c.changes.map { c.blocks[it] })
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
