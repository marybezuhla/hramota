package ua.hramota.doc.docx.edit

/** What can be done to a table at the cursor's cell. */
enum class TableOp { ROW_ABOVE, ROW_BELOW, ROW_DELETE, MERGE_RIGHT, MERGE_DOWN, SPLIT }

/**
 * A table as a grid, the same for Word and LibreOffice: its rows, and its cells each standing on a
 * block of the grid ([top], [left], [rows] by [cols]) with its paragraphs. Cells are merged, split,
 * rows added and taken out here; each format writes the rows back its own way.
 *
 * [F] is how a format keeps a cell's look (its properties, its first paragraph's), [R] a row's.
 */
class TableGrid<F, R>(val rows: MutableList<R>, val cells: MutableList<Cell<F>>, val columns: Int) {
    class Cell<F>(var top: Int, var left: Int, var rows: Int, var cols: Int, val format: F, var paras: MutableList<EditPara>)

    /** The cell standing on [row], [col]; null where none does (a row shorter than the table). */
    fun at(row: Int, col: Int): Cell<F>? = cells.firstOrNull { row in it.top until it.top + it.rows && col in it.left until it.left + it.cols }

    /** Every place covered at most once, all cells within the grid. */
    fun valid(): Boolean {
        val seen = HashSet<Long>()
        for (c in cells) {
            if (c.rows < 1 || c.cols < 1 || c.top < 0 || c.left < 0 || c.top + c.rows > rows.size || c.left + c.cols > columns) return false
            for (r in c.top until c.top + c.rows) for (k in c.left until c.left + c.cols) if (!seen.add(r.toLong() shl 20 or k.toLong())) return false
        }
        return true
    }

    private fun empty(c: Cell<F>) = c.paras.all { it.text.isBlank() }

    /** One cell's paragraphs and another's together: the second's after the first's (an empty one's left out). */
    private fun join(a: Cell<F>, b: Cell<F>) {
        a.paras = when {
            empty(b) -> a.paras
            empty(a) -> b.paras
            else -> (a.paras + b.paras).toMutableList()
        }
    }

    fun right(a: Cell<F>): Cell<F>? = at(a.top, a.left + a.cols)?.takeIf { it.top == a.top && it.rows == a.rows && it.left == a.left + a.cols }
    fun below(a: Cell<F>): Cell<F>? = at(a.top + a.rows, a.left)?.takeIf { it.left == a.left && it.cols == a.cols && it.top == a.top + a.rows }

    /** [a] and the cell on its right (as tall as it) made one. */
    fun mergeRight(a: Cell<F>): Boolean {
        val b = right(a) ?: return false
        a.cols += b.cols; join(a, b); cells.remove(b)
        return true
    }

    /** [a] and the cell under it (as wide as it) made one. */
    fun mergeDown(a: Cell<F>): Boolean {
        val b = below(a) ?: return false
        a.rows += b.rows; join(a, b); cells.remove(b)
        return true
    }

    /** A merged cell back into single ones: its text stays in the first, the others empty. */
    fun split(a: Cell<F>): Boolean {
        if (a.rows == 1 && a.cols == 1) return false
        for (r in a.top until a.top + a.rows) for (k in a.left until a.left + a.cols)
            if (r != a.top || k != a.left) cells += Cell(r, k, 1, 1, a.format, ArrayList())
        a.rows = 1; a.cols = 1
        return true
    }

    /**
     * An empty row put in at [at] (0: above the first), made like row [like] (its row's look, its
     * cells' widths): a cell merged down across the place grows by the new row instead.
     */
    fun insertRow(at: Int, like: Int, template: R) {
        val ref = like.coerceIn(0, rows.size - 1)
        val refCells = (0 until columns).map { at(ref, it) }
        for (c in cells) {
            when {
                c.top >= at -> c.top++
                c.top + c.rows > at -> c.rows++ // passes through the new row
            }
        }
        rows.add(at, template)
        var k = 0
        while (k < columns) {
            val through = at(at, k)
            if (through != null) { k = through.left + through.cols; continue }
            // (a place the reference row leaves empty, as a row starting further in, stays empty)
            val r = refCells[k] ?: run { k++; null } ?: continue
            val width = (r.left + r.cols - k).coerceAtLeast(1)
            val w = (0 until width).takeWhile { k + it < columns && at(at, k + it) == null }.size.coerceAtLeast(1)
            cells += Cell(at, k, 1, w, r.format, ArrayList())
            k += w
        }
    }

    /**
     * Row [i] taken out: a cell merged down from it goes on in the row under it (its text with it);
     * a cell only in it goes. Returns the paragraphs that go with it.
     */
    fun deleteRow(i: Int): List<EditPara> {
        val gone = ArrayList<EditPara>()
        val it = cells.iterator()
        while (it.hasNext()) {
            val c = it.next()
            when {
                c.top == i && c.rows == 1 -> { gone += c.paras; it.remove() }
                c.top == i -> c.rows-- // its top now the row under it
                c.top < i && c.top + c.rows > i -> c.rows--
                c.top > i -> c.top--
            }
        }
        rows.removeAt(i)
        return gone
    }

    /** Which row and cell hold a paragraph (by its key). */
    fun cellOf(key: Long): Cell<F>? = cells.firstOrNull { c -> c.paras.any { it.key == key } }

    /**
     * Whether [op] can be done at the paragraph [key]'s cell, and does it. False (nothing changed
     * only when checking first: call [can] before) when it cannot.
     */
    fun apply(op: TableOp, key: Long): Boolean {
        val c = cellOf(key) ?: return false
        return when (op) {
            TableOp.MERGE_RIGHT -> mergeRight(c)
            TableOp.MERGE_DOWN -> mergeDown(c)
            TableOp.SPLIT -> split(c)
            TableOp.ROW_ABOVE -> { insertRow(c.top, c.top, rows[c.top]); true }
            TableOp.ROW_BELOW -> { insertRow(c.top + c.rows, c.top + c.rows - 1, rows[c.top + c.rows - 1]); true }
            TableOp.ROW_DELETE -> {
                if (rows.size <= 1) return false
                val gone = deleteRow(c.top)
                gone.none { it.anchors.isNotEmpty() }
            }
        }
    }

    /** Whether [op] can be done at the paragraph [key]'s cell (nothing is changed). */
    fun can(op: TableOp, key: Long): Boolean {
        val c = cellOf(key) ?: return false
        return when (op) {
            TableOp.MERGE_RIGHT -> right(c) != null
            TableOp.MERGE_DOWN -> below(c) != null
            TableOp.SPLIT -> c.rows > 1 || c.cols > 1
            TableOp.ROW_ABOVE, TableOp.ROW_BELOW -> true
            // The last row stays; a comment in the row's own cells is not taken out with it
            TableOp.ROW_DELETE -> rows.size > 1 && cells.none { it.top == c.top && it.rows == 1 && it.paras.any { p -> p.anchors.isNotEmpty() } }
        }
    }
}
