package ua.hramota.doc.docx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.TabStopSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Something drawn on a page, in layout units ([DocxLayout.U] per point). */
sealed interface PageItem {
    /** Lines [first]..[last] of a paragraph layout, their top at ([x], [y]). */
    class Text(val source: TextSource, val first: Int, val last: Int, val x: Float, val y: Float) : PageItem {
        val layout: StaticLayout get() = source.layout
    }
    class Fill(val rect: RectF, val color: Int) : PageItem
    class Rule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : PageItem
}

class LaidOutPage(val items: List<PageItem>)

/**
 * A paragraph's layout, built when it is needed and kept in a small shared cache: pages remember
 * only where lines go, so a document of a thousand pages never holds all its layouts at once.
 * Building again gives the same layout (same text, fonts and width).
 */
class TextSource internal constructor(private val cache: LayoutCache, private val build: () -> StaticLayout) {
    val layout: StaticLayout get() = cache.get(this, build)
    /** A new layout, not kept (for measuring). */
    fun fresh(): StaticLayout = build()
}

/** The most recently used paragraph layouts. */
class LayoutCache(private val max: Int) {
    private val map = object : LinkedHashMap<TextSource, StaticLayout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextSource, StaticLayout>?) = size > max
    }
    fun get(key: TextSource, build: () -> StaticLayout): StaticLayout {
        synchronized(this) { map[key]?.let { return it } }
        // Built outside the lock, so pages being drawn never wait for one another's paragraphs
        val layout = build()
        synchronized(this) { map[key] = layout }
        return layout
    }
}

/**
 * Lays a DOCX out on pages the way Word does: Word's line heights, greedy line breaking without
 * hyphenation, no kerning or ligatures, widow and orphan control, "keep with next".
 * Everything is measured in units of 1/[U] point, independent of the screen.
 */
class DocxLayout(private val doc: DocxDocument, private val fonts: Fonts, private val pictures: (String, Int, Int) -> Bitmap?) {
    companion object {
        /** Layout units per point: fine enough that rounding never moves a line. */
        const val U = 8f
        fun tw(twips: Int): Float = twips * U / 20f
        private const val DEFAULT_TAB = 720
        private const val CELL_MARGIN = 108
        /** Paragraphs and rows measured as one piece of work. */
        private const val WINDOW = 48
        private val THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
    }

    /** Layouts of the paragraphs on the last few pages drawn or searched. */
    private val cache = LayoutCache(512)

    val pageWidth = tw(doc.page.width)
    val pageHeight = tw(doc.page.height)
    private val left = tw(doc.page.left)
    private val contentWidth = tw(doc.page.width - doc.page.left - doc.page.right)
    private val top = tw(doc.page.top)
    private val bottom = pageHeight - tw(doc.page.bottom)

    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        // Word neither kerns nor joins letters by default
        fontFeatureSettings = "'kern' 0, 'liga' 0, 'clig' 0"
        textSize = 11 * U
        color = 0xFF000000.toInt()
        typeface = fonts.typeface(Fonts.Family.CARLITO, bold = false, italic = false)
    }

    /** Each measuring thread has its own copy of the paint. */
    private val paints = ThreadLocal.withInitial { TextPaint(basePaint) }

    /**
     * Lays out every page. With [parallel], paragraphs and table rows are measured on several threads
     * a little ahead of placing them; [onPage] receives each page as soon as it is complete, and
     * [stop] ends the work early (the window was closed).
     */
    fun layout(parallel: Boolean = false, stop: () -> Boolean = { false }, onPage: (LaidOutPage) -> Unit = {}): List<LaidOutPage> {
        val pool = if (parallel) Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "docx-layout").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } } else null
        try { return place(Ahead(split(doc.blocks), pool), stop, onPage) } finally { pool?.shutdownNow() }
    }

    private fun place(ahead: Ahead, stop: () -> Boolean, onPage: (LaidOutPage) -> Unit): List<LaidOutPage> {
        val pages = ArrayList<LaidOutPage>()
        var items = ArrayList<PageItem>()
        var y = top
        var firstPage = true
        var pendingAfter = 0f
        fun newPage() {
            val page = LaidOutPage(items)
            pages += page; onPage(page)
            items = ArrayList(); y = top; firstPage = false; pendingAfter = 0f
        }
        fun atTop() = items.isEmpty()

        val blocks = ahead.blocks
        var prev: Block.Para? = null
        for ((index, entry) in blocks.withIndex()) {
            if (stop()) return pages
            val (block, breakBefore) = entry
            if (breakBefore && !atTop()) newPage()
            when (block) {
                is Block.Para -> {
                    if (block.props.pageBreakBefore == true && !atTop()) newPage()
                    val p = ahead.para(index)
                    // "Don't add space between paragraphs of the same style" works on both sides of the gap
                    val nextBlock = blocks.getOrNull(index + 1)?.first as? Block.Para
                    val sameAsPrev = prev?.props?.styleId == block.props.styleId
                    val sameAsNext = nextBlock?.props?.styleId == block.props.styleId
                    val contextual = sameAsPrev && (block.props.contextual == true || prev?.props?.contextual == true)
                    val dropAfter = sameAsNext && (block.props.contextual == true || nextBlock?.props?.contextual == true)
                    var before = if (contextual) 0f else p.before
                    // Word drops space before at the top of a page after the first
                    if (atTop() && !firstPage) before = 0f
                    val next = blocks.getOrNull(index + 1)?.first
                    if (block.props.keepNext == true && next is Block.Para && !atTop()) {
                        val nextP = ahead.para(index + 1)
                        val need = gap(pendingAfter, before) + p.height + gap(p.after, nextP.before) + nextP.lineHeight(0) + if (nextP.lines > 1) nextP.lineHeight(1) else 0f
                        if (y + need > bottom && need <= bottom - top) newPage().also { before = 0f }
                    }
                    y += gap(pendingAfter, before)
                    pendingAfter = 0f
                    var line = 0
                    while (line < p.lines) {
                        var fit = 0
                        var h = 0f
                        while (line + fit < p.lines && y + h + p.lineHeight(line + fit) <= bottom + 0.5f) { h += p.lineHeight(line + fit); fit++ }
                        val remaining = p.lines - line
                        if (fit < remaining && p.widow) {
                            if (line == 0 && fit == 1 && remaining > 1 && !atTop()) fit = 0
                            else if (remaining - fit == 1 && fit >= 2) fit--
                        }
                        if (fit == 0) {
                            if (atTop()) fit = 1 else { newPage(); continue }
                        }
                        val sliceH = (line until line + fit).sumOf { p.lineHeight(it).toDouble() }.toFloat()
                        p.shading?.let { items += PageItem.Fill(RectF(left + p.shadeLeft, y, left + contentWidth - p.shadeRight, y + sliceH), it) }
                        items += PageItem.Text(p.source, line, line + fit - 1, left + p.x, y)
                        y += sliceH
                        line += fit
                        if (line < p.lines) newPage()
                    }
                    // Space after waits for the next paragraph: Word uses the larger of the two gaps
                    pendingAfter = if (dropAfter) 0f else p.after
                    prev = block
                }
                is Block.Table -> {
                    prev = null
                    y = min(y + pendingAfter, bottom); pendingAfter = 0f
                    for (r in block.rows.indices) {
                        if (stop()) return pages
                        val row = ahead.row(index, r)
                        // A row that cannot break (exact height, "don't split") moves whole to the next page
                        if ((row.exact || row.cantSplit) && y + row.height > bottom && !atTop()) newPage()
                        val next = IntArray(row.cells.size)
                        var used = 0f
                        while (true) {
                            val unbroken = row.exact || row.cantSplit
                            val seg = row.segment(next, if (unbroken) Float.MAX_VALUE else bottom - y, used, forceOne = atTop())
                            if (seg == null) { newPage(); continue }
                            seg.items.forEach { items += it.moved(0f, y) }
                            y += seg.height
                            used += seg.height
                            if (seg.done) break
                            // As in Word, a row breaks across pages, each cell going on from its next line
                            newPage()
                        }
                    }
                }
            }
        }
        val last = LaidOutPage(items)
        pages += last; onPage(last)
        return pages
    }

    /**
     * Paragraphs and table rows measured ahead of placement: the work is cut into windows of
     * [WINDOW] pieces, several computed at once; windows already placed are let go.
     */
    private inner class Ahead(val blocks: List<Pair<Block, Boolean>>, private val pool: ExecutorService?) {
        private val firstUnit = IntArray(blocks.size + 1)
        private val tables = arrayOfNulls<TableGeometry>(blocks.size)
        private val unitBlock: IntArray
        private val results: Array<Any?>
        private val futures = HashMap<Int, Future<*>>()

        init {
            var n = 0
            for ((i, e) in blocks.withIndex()) {
                firstUnit[i] = n
                n += when (val b = e.first) {
                    is Block.Para -> 1
                    is Block.Table -> { tables[i] = geometry(b); b.rows.size }
                }
            }
            firstUnit[blocks.size] = n
            unitBlock = IntArray(n)
            for (i in blocks.indices) for (u in firstUnit[i] until firstUnit[i + 1]) unitBlock[u] = i
            results = arrayOfNulls(n)
        }

        fun para(block: Int): Para = get(firstUnit[block]) as Para
        fun row(block: Int, row: Int): LaidRow = get(firstUnit[block] + row) as LaidRow

        private fun compute(u: Int): Any {
            val i = unitBlock[u]
            return when (val b = blocks[i].first) {
                is Block.Para -> paragraph(b, contentWidth)
                is Block.Table -> tableRow(b.rows[u - firstUnit[i]], tables[i]!!)
            }
        }

        private fun get(u: Int): Any {
            if (pool == null) return compute(u)
            val w = u / WINDOW
            for (k in w..w + THREADS * 2) submit(k)
            try { futures[w]?.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            // Windows behind the one being placed are no longer needed
            val done = futures.keys.filter { it < w - 1 }
            for (k in done) {
                futures.remove(k)
                for (x in k * WINDOW until minOf(results.size, (k + 1) * WINDOW)) results[x] = null
            }
            return results[u] ?: compute(u)
        }

        private fun submit(w: Int) {
            if (w * WINDOW >= results.size || futures.containsKey(w)) return
            futures[w] = pool!!.submit {
                for (u in w * WINDOW until minOf(results.size, (w + 1) * WINDOW)) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    results[u] = compute(u)
                }
            }
        }
    }

    /** Splits paragraphs at page breaks; the flag says "start a new page before this". */
    private fun split(blocks: List<Block>): List<Pair<Block, Boolean>> {
        val out = ArrayList<Pair<Block, Boolean>>()
        // A break at the very end of a paragraph keeps the paragraph mark with it (as Word shows it)
        // and moves the next block to the new page
        var carry = false
        for (b in blocks) {
            if (b !is Block.Para || b.inlines.none { it == Inline.PageBreak }) { out += b to carry; carry = false; continue }
            var part = ArrayList<Inline>()
            var breakNext = carry
            carry = false
            var first = true
            for (i in b.inlines) {
                if (i == Inline.PageBreak) {
                    out += b.copy(inlines = part, label = if (first) b.label else null, props = if (first) b.props else b.props.copy(before = 0)) to breakNext
                    part = ArrayList(); breakNext = true; first = false
                } else part += i
            }
            // Text after the break starts the next page; an empty remainder is just the paragraph mark
            if (part.isEmpty()) carry = true
            else out += b.copy(inlines = part, label = null, props = b.props.copy(before = 0)) to breakNext
        }
        return out
    }

    /** A paragraph laid out at a width, with its spacing in layout units. */
    /** A measured paragraph: where each line starts ([tops], with the bottom last), not the layout itself. */
    inner class Para(val source: TextSource, val tops: FloatArray, val x: Float, val before: Float, val after: Float, val widow: Boolean, val shading: Int?, val shadeLeft: Float, val shadeRight: Float) {
        val lines: Int get() = tops.size - 1
        fun lineHeight(i: Int): Float = tops[i + 1] - tops[i]
        val height: Float get() = tops[lines]
    }

    fun paragraph(b: Block.Para, width: Float): Para {
        val pp = b.props
        val l = tw(pp.left ?: 0)
        val r = tw(pp.right ?: 0)
        val f = tw(pp.firstLine ?: 0)
        val baseX = l + min(0f, f)
        val firstIndent = f - min(0f, f)
        val restIndent = -min(0f, f)
        val layoutWidth = max(U, width - baseX - r).roundToInt()
        val before = tw(if (pp.beforeAuto == true) 280 else pp.before ?: 0)
        val after = tw(if (pp.afterAuto == true) 280 else pp.after ?: 0)
        val source = TextSource(cache) { build(b, width, baseX, firstIndent, restIndent, layoutWidth) }
        val measured = source.fresh()
        val n = measured.lineCount
        val tops = FloatArray(n + 1) { if (it < n) measured.getLineTop(it).toFloat() else measured.getLineBottom(n - 1).toFloat() }
        return Para(source, tops, baseX, before, after, pp.widowControl != false, pp.shading, l, r)
    }

    private fun build(b: Block.Para, width: Float, baseX: Float, firstIndent: Float, restIndent: Float, layoutWidth: Int): StaticLayout {
        val pp = b.props
        val l = tw(pp.left ?: 0)
        val f = tw(pp.firstLine ?: 0)
        val text = SpannableStringBuilder()
        val metrics = ArrayList<Metric>()
        fun runSpans(start: Int, props: RunProps) {
            val family = Fonts.familyFor(props.font ?: themeFont(props.fontTheme))
            val size = (props.size ?: 11f) * (if (props.vertAlign == "superscript" || props.vertAlign == "subscript") 0.65f else 1f)
            val end = text.length
            if (end <= start) return
            text.setSpan(TypefaceSpan(fonts.typeface(family, props.bold == true, props.italic == true)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(AbsoluteSizeSpan((size * U).roundToInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            props.color?.let { text.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            props.highlight?.let { text.setSpan(BackgroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (props.underline == true) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (props.strike == true) text.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (props.vertAlign == "superscript" || props.vertAlign == "subscript") {
                text.setSpan(Shift(if (props.vertAlign == "superscript") -0.33f else 0.14f, (props.size ?: 11f) * U), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // Line height is measured from the full size, as Word does
            val full = (props.size ?: 11f) * U
            metrics += Metric(start, end, full * family.ascent, full * (family.descent + family.leading))
        }

        b.label?.takeIf { it.isNotEmpty() }?.let { label ->
            val start = text.length
            text.append(label)
            runSpans(start, b.labelProps ?: b.mark)
            text.append('\t')
            runSpans(start + label.length, b.labelProps ?: b.mark)
        }
        for (i in b.inlines) {
            val start = text.length
            when (i) {
                is Inline.Text -> { text.append(if (i.props.caps == true) i.text.uppercase() else i.text); runSpans(start, i.props) }
                Inline.Tab -> { text.append('\t'); runSpans(start, b.mark) }
                Inline.LineBreak -> { text.append('\n'); runSpans(start, b.mark) }
                Inline.PageBreak -> Unit
                is Inline.Image -> {
                    var w = i.width * U
                    var h = i.height * U
                    if (w <= 0f || h <= 0f) { w = width / 2; h = width / 3 }
                    // A picture wider than the column is scaled down to fit, as Word shows it
                    if (w > layoutWidth) { h *= layoutWidth / w; w = layoutWidth.toFloat() }
                    text.append('￼')
                    text.setSpan(Picture(i.rel, w, h), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    metrics += Metric(start, start + 1, h, 0f)
                }
            }
        }
        if (text.isEmpty() || text.last() == '\n') {
            // An empty paragraph still has the height of its paragraph mark
            val start = text.length
            text.append(' ')
            runSpans(start, b.mark)
        }

        // Tab stops: the document's own, the hanging indent, then every half inch, measured from the first line's start
        val origin = baseX + firstIndent
        val stops = sortedSetOf<Float>()
        pp.tabs?.forEach { stops += tw(it) - origin }
        if (f < 0) stops += l - origin
        val lastCustom = stops.maxOrNull() ?: 0f
        // Default stops from the first one past the line start, at most 64 of them
        val step = tw(DEFAULT_TAB)
        var d = (kotlin.math.floor(max(origin, 0f) / step) + 1) * step
        var added = 0
        while (d - origin < layoutWidth + step && added < 64) {
            if (d - origin > lastCustom + 1f) { stops += d - origin; added++ }
            d += step
        }
        stops.filter { it > 0f }.take(128).forEach { text.setSpan(TabStopSpan.Standard(it.roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }

        text.setSpan(WordLines(metrics, pp.line, pp.lineRule), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val align = when (pp.align) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paints.get()!!, layoutWidth)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setUseLineSpacingFromFallbacks(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIndents(intArrayOf(firstIndent.roundToInt(), restIndent.roundToInt()), null)
        if (pp.align == "both" || pp.align == "distribute") builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }

    /** The gap between two paragraphs: the larger of the two spacings, or their sum if the document asks. */
    private fun gap(after: Float, before: Float): Float = if (doc.addSpacing) after + before else max(after, before)

    private fun themeFont(theme: String?): String? = when {
        theme == null -> doc.fonts.minor
        theme.startsWith("major") -> doc.fonts.major
        else -> doc.fonts.minor
    }

    /** One line of a cell, its top and bottom measured from the top of the row. */
    private class LineSlot(val source: TextSource, val line: Int, val x: Float, val top: Float, val bottom: Float)

    /** A cell of a laid-out row: where it is, its lines, and where its content ends (with spacing after). */
    private class CellLines(val x: Float, val width: Float, val shading: Int?, val mergedAbove: Boolean, val lines: List<LineSlot>, val end: Float)

    /** A piece of a row on one page. */
    private class Segment(val items: List<PageItem>, val height: Float, val done: Boolean)

    /** A table row laid out from y = 0, which can be placed whole or in pieces across pages. */
    private inner class LaidRow(
        val cells: List<CellLines>, val height: Float, val minHeight: Float,
        val exact: Boolean, val cantSplit: Boolean, private val borders: Boolean, private val inset: Float,
    ) {
        /**
         * The next piece of the row that fits in [avail]: each cell goes on from line [next] (updated).
         * Null when not one line fits and [forceOne] is false (the caller starts a new page).
         */
        fun segment(next: IntArray, avail: Float, used: Float, forceOne: Boolean): Segment? {
            val out = ArrayList<PageItem>()
            var segHeight = 0f
            var placedAny = false
            var done = true
            for ((c, cell) in cells.withIndex()) {
                val lines = cell.lines
                var i = next[c]
                if (i >= lines.size) continue
                // This cell's lines on this page start just under the top border
                val base = lines[i].top - inset
                val start = i
                while (i < lines.size && (lines[i].bottom - base <= avail || (forceOne && i == start))) {
                    val l = lines[i]
                    out += PageItem.Text(l.source, l.line, l.line, l.x, l.top - base)
                    i++
                }
                if (i > start) {
                    placedAny = true
                    val last = lines[i - 1]
                    // The cell's spacing after its last paragraph counts only where its content ends
                    val bottomEdge = if (i == lines.size) maxOf(last.bottom, cell.end) else last.bottom
                    segHeight = maxOf(segHeight, bottomEdge - base)
                }
                next[c] = i
                if (i < lines.size) done = false
            }
            if (!placedAny && cells.any { it.lines.isNotEmpty() } && !forceOne && !done) return null
            if (done) segHeight = maxOf(segHeight, minHeight - used, if (used == 0f) height else 0f).coerceAtLeast(segHeight)
            if (exact) segHeight = height
            val h = segHeight
            // Shading behind and borders around every cell, for this piece's height
            val drawn = ArrayList<PageItem>()
            for (cell in cells) cell.shading?.let { drawn += PageItem.Fill(RectF(cell.x, 0f, cell.x + cell.width, h), it) }
            drawn += merge(out)
            if (borders) for (cell in cells) {
                if (!cell.mergedAbove || used > 0f) drawn += PageItem.Rule(cell.x, 0f, cell.x + cell.width, 0f, U * 0.5f)
                drawn += PageItem.Rule(cell.x, h, cell.x + cell.width, h, U * 0.5f)
                drawn += PageItem.Rule(cell.x, 0f, cell.x, h, U * 0.5f)
                drawn += PageItem.Rule(cell.x + cell.width, 0f, cell.x + cell.width, h, U * 0.5f)
            }
            return Segment(drawn, h, done)
        }

        /** Consecutive lines of the same paragraph become one item. */
        private fun merge(lines: List<PageItem>): List<PageItem> {
            val out = ArrayList<PageItem>()
            for (item in lines) {
                val t = item as PageItem.Text
                val prev = out.lastOrNull() as? PageItem.Text
                if (prev != null && prev.source === t.source && prev.last + 1 == t.first && prev.x == t.x) {
                    out[out.size - 1] = PageItem.Text(prev.source, prev.first, t.last, prev.x, prev.y)
                } else out += t
            }
            return out
        }
    }

    private fun PageItem.moved(dx: Float, dy: Float): PageItem = when (this) {
        is PageItem.Text -> PageItem.Text(source, first, last, x + dx, y + dy)
        is PageItem.Fill -> PageItem.Fill(RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy), color)
        is PageItem.Rule -> PageItem.Rule(x1 + dx, y1 + dy, x2 + dx, y2 + dy, width)
    }

    /** Where a table's columns are: computed once per table. */
    private class TableGeometry(val cols: List<Float>, val edges: FloatArray, val x0: Float, val margin: Float, val inset: Float, val borders: Boolean)

    private fun geometry(t: Block.Table): TableGeometry {
        val spans = (t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1).coerceIn(1, DocxReader.MAX_COLUMNS)
        val grid = if (t.columns.isNotEmpty() && t.columns.sum() > 0) t.columns.map { tw(it) } else List(spans) { contentWidth / spans }
        // Cells may span past the grid: extra columns get the last width
        val cols = if (grid.size >= spans) grid else grid + List(spans - grid.size) { grid.lastOrNull() ?: (contentWidth / spans) }
        // Left edge of every column, computed once (a row is then linear, not quadratic)
        val edges = FloatArray(cols.size + 1).also { e -> for (i in cols.indices) e[i + 1] = e[i] + cols[i] }
        val margin = tw(CELL_MARGIN)
        // Before Word 2013 the table's edge sits one cell margin left of the text, so cell text lines up with it
        val x0 = left + tw(t.indent) - if (doc.compatibilityMode < 15) margin else 0f
        val rule = U * 0.5f
        return TableGeometry(cols, edges, x0, margin, if (t.borders) rule else 0f, t.borders)
    }

    /** One table row laid out from y = 0; rows are measured one at a time, so a long table is never all in memory. */
    private fun tableRow(row: Row, g: TableGeometry): LaidRow {
        val cols = g.cols; val edges = g.edges; val margin = g.margin; val inset = g.inset
        var col = 0
        var height = 0f
        val cells = ArrayList<CellLines>()
        for (cell in row.cells) {
            if (col >= cols.size) break
            val end = minOf(col + cell.span, cols.size)
            val cx = g.x0 + edges[col]
            val cw = edges[end] - edges[col]
            col = end
            val lines = ArrayList<LineSlot>()
            // Borders take room inside the row
            var y = inset
            if (!cell.mergedAbove) {
                var cellAfter = 0f
                val paras = flatten(cell.blocks)
                for ((k, p) in paras.withIndex()) {
                    val laid = paragraph(p, max(U, cw - 2 * margin))
                    val prevP = paras.getOrNull(k - 1)
                    val nextP = paras.getOrNull(k + 1)
                    val same = { o: Block.Para? -> o != null && o.props.styleId == p.props.styleId && (o.props.contextual == true || p.props.contextual == true) }
                    y += gap(cellAfter, if (same(prevP)) 0f else laid.before)
                    for (line in 0 until laid.lines) {
                        lines += LineSlot(laid.source, line, cx + margin + laid.x, y + laid.tops[line], y + laid.tops[line + 1])
                    }
                    y += laid.height
                    cellAfter = if (same(nextP)) 0f else laid.after
                }
                // Space after the last paragraph of a cell still counts, as in Word
                y += cellAfter
            }
            height = max(height, y)
            cells += CellLines(cx, cw, cell.shading, cell.mergedAbove, lines, y)
        }
        val min = row.height?.let { tw(it) } ?: 0f
        if (row.exact) height = min else height = max(height, min)
        return LaidRow(cells, height, min, row.exact, row.cantSplit, g.borders, inset)
    }

    /** Paragraphs of a cell; a table inside a cell is read row by row for now. */
    private fun flatten(blocks: List<Block>, depth: Int = 0): List<Block.Para> = if (depth > DocxReader.MAX_TABLE_DEPTH) emptyList() else blocks.flatMap { b ->
        when (b) {
            is Block.Para -> listOf(b)
            is Block.Table -> b.rows.flatMap { r -> r.cells.flatMap { flatten(it.blocks, depth + 1) } }
        }
    }

    /** Where each run's Word ascent and descent apply, by character range. */
    class Metric(val start: Int, val end: Int, val ascent: Float, val descent: Float)

    /** Line heights as Word computes them: single, multiple, exact or at least. */
    class WordLines(private val metrics: List<Metric>, private val line: Int?, private val rule: String?) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            var asc = 0f
            var desc = 0f
            // A line's trailing newline belongs to it but has no glyphs of its own
            val last = if (end > start && text[end - 1] == '\n') end - 1 else end
            for (m in metrics) {
                if (m.end <= start || m.start >= max(last, start + 1)) continue
                asc = max(asc, m.ascent); desc = max(desc, m.descent)
            }
            if (asc + desc <= 0f) return
            val natural = asc + desc
            val target = when (rule) {
                "exact" -> line?.let { tw(it) } ?: natural
                "atLeast" -> max(natural, line?.let { tw(it) } ?: 0f)
                else -> natural * ((line ?: 240) / 240f)
            }
            // Word and LibreOffice keep the text at the top of a taller line and add the space below it;
            // an exact height smaller than the text cuts from the top instead
            val total = target.roundToInt()
            val a = if (target >= natural) asc.roundToInt() else (asc - (natural - target)).roundToInt()
            fm.ascent = -a; fm.top = -a
            fm.descent = total - a; fm.bottom = total - a
        }
    }

    /** Raises or lowers superscript and subscript text by a share of the full size. */
    private class Shift(private val share: Float, private val fullSize: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) { p.baselineShift += (share * fullSize).roundToInt() }
        override fun updateDrawState(p: TextPaint) { p.baselineShift += (share * fullSize).roundToInt() }
    }

    /** A picture inside a line; decoded only when drawn, at the size it is shown. */
    private inner class Picture(private val rel: String, private val w: Float, private val h: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { it.ascent = -h.roundToInt(); it.top = it.ascent; it.descent = 0; it.bottom = 0 }
            return w.roundToInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val scale = canvas.matrix.let { m -> FloatArray(9).also(m::getValues)[0] }
            val rect = RectF(x, y - h, x + w, y.toFloat())
            val bitmap = pictures(rel, (w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, imagePaint)
            else canvas.drawRect(rect, placeholderPaint)
        }
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = 0xFFE8E8E8.toInt() }
}

/** Draws laid-out pages; one per document, so paints are never shared between windows. */
class PagePainter {
    private val fill = Paint()
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE }

    fun draw(canvas: Canvas, page: LaidOutPage) {
        for (item in page.items) when (item) {
            is PageItem.Fill -> { fill.color = item.color; canvas.drawRect(item.rect, fill) }
            is PageItem.Rule -> { rule.strokeWidth = item.width; canvas.drawLine(item.x1, item.y1, item.x2, item.y2, rule) }
            is PageItem.Text -> {
                val l = item.layout
                val top = l.getLineTop(item.first).toFloat()
                val bottom = l.getLineBottom(item.last).toFloat()
                canvas.save()
                canvas.translate(item.x, item.y - top)
                canvas.clipRect(-DocxLayout.U * 200, top, l.width + DocxLayout.U * 200, bottom)
                l.draw(canvas)
                canvas.restore()
            }
        }
    }
}
