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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Something drawn on a page, in layout units ([DocxLayout.U] per point). */
sealed interface PageItem {
    /** Lines [first]..[last] of a paragraph layout, their top at ([x], [y]). */
    class Text(val layout: StaticLayout, val first: Int, val last: Int, val x: Float, val y: Float) : PageItem
    class Fill(val rect: RectF, val color: Int) : PageItem
    class Rule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : PageItem
}

class LaidOutPage(val items: List<PageItem>)

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
    }

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

    fun layout(): List<LaidOutPage> {
        val pages = ArrayList<LaidOutPage>()
        var items = ArrayList<PageItem>()
        var y = top
        var firstPage = true
        var pendingAfter = 0f
        fun newPage() {
            pages += LaidOutPage(items); items = ArrayList(); y = top; firstPage = false; pendingAfter = 0f
        }
        fun atTop() = items.isEmpty()

        val blocks = split(doc.blocks)
        var prev: Block.Para? = null
        for ((index, entry) in blocks.withIndex()) {
            val (block, breakBefore) = entry
            if (breakBefore && !atTop()) newPage()
            when (block) {
                is Block.Para -> {
                    if (block.props.pageBreakBefore == true && !atTop()) newPage()
                    val p = paragraph(block, contentWidth)
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
                        val nextP = paragraph(next, contentWidth)
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
                        items += PageItem.Text(p.layout, line, line + fit - 1, left + p.x, y)
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
                    for (row in tableRows(block)) {
                        if (y + row.height > bottom && !atTop()) newPage()
                        row.items.forEach { items += it.moved(0f, y) }
                        y += row.height
                    }
                }
            }
        }
        pages += LaidOutPage(items)
        return pages
    }

    /** Splits paragraphs at page breaks; the flag says "start a new page before this". */
    private fun split(blocks: List<Block>): List<Pair<Block, Boolean>> {
        val out = ArrayList<Pair<Block, Boolean>>()
        for (b in blocks) {
            if (b !is Block.Para || b.inlines.none { it == Inline.PageBreak }) { out += b to false; continue }
            var part = ArrayList<Inline>()
            var breakNext = false
            var first = true
            for (i in b.inlines) {
                if (i == Inline.PageBreak) {
                    out += b.copy(inlines = part, label = if (first) b.label else null, props = if (first) b.props else b.props.copy(before = 0)) to breakNext
                    part = ArrayList(); breakNext = true; first = false
                } else part += i
            }
            // Text after the break starts the next page; an empty remainder is just the paragraph mark
            out += b.copy(inlines = part, label = null, props = b.props.copy(before = 0)) to breakNext
        }
        return out
    }

    /** A paragraph laid out at a width, with its spacing in layout units. */
    inner class Para(val layout: StaticLayout, val x: Float, val before: Float, val after: Float, val widow: Boolean, val shading: Int?, val shadeLeft: Float, val shadeRight: Float) {
        val lines: Int get() = layout.lineCount
        fun lineHeight(i: Int): Float = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
        val height: Float get() = layout.height.toFloat()
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
        var d = tw(DEFAULT_TAB)
        while (d - origin < layoutWidth + tw(DEFAULT_TAB)) {
            if (d - origin > lastCustom + 1f) stops += d - origin
            d += tw(DEFAULT_TAB)
        }
        stops.filter { it > 0f }.forEach { text.setSpan(TabStopSpan.Standard(it.roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }

        text.setSpan(WordLines(metrics, pp.line, pp.lineRule), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val align = when (pp.align) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, layoutWidth)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setUseLineSpacingFromFallbacks(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIndents(intArrayOf(firstIndent.roundToInt(), restIndent.roundToInt()), null)
        if (pp.align == "both" || pp.align == "distribute") builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)

        val before = tw(if (pp.beforeAuto == true) 280 else pp.before ?: 0)
        val after = tw(if (pp.afterAuto == true) 280 else pp.after ?: 0)
        return Para(builder.build(), baseX, before, after, pp.widowControl != false, pp.shading, l, r)
    }

    /** The gap between two paragraphs: the larger of the two spacings, or their sum if the document asks. */
    private fun gap(after: Float, before: Float): Float = if (doc.addSpacing) after + before else max(after, before)

    private fun themeFont(theme: String?): String? = when {
        theme == null -> doc.fonts.minor
        theme.startsWith("major") -> doc.fonts.major
        else -> doc.fonts.minor
    }

    /** A table row laid out at y = 0: its cells' contents, shading and borders. */
    private class LaidRow(val items: List<PageItem>, val height: Float)

    private fun PageItem.moved(dx: Float, dy: Float): PageItem = when (this) {
        is PageItem.Text -> PageItem.Text(layout, first, last, x + dx, y + dy)
        is PageItem.Fill -> PageItem.Fill(RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy), color)
        is PageItem.Rule -> PageItem.Rule(x1 + dx, y1 + dy, x2 + dx, y2 + dy, width)
    }

    private fun tableRows(t: Block.Table): List<LaidRow> {
        val cols = if (t.columns.isNotEmpty() && t.columns.sum() > 0) t.columns.map { tw(it) } else {
            val n = t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1
            List(n) { contentWidth / n }
        }
        val x0 = left + tw(t.indent)
        val margin = tw(CELL_MARGIN)
        val rule = U * 0.5f
        return t.rows.map { row ->
            val items = ArrayList<PageItem>()
            var col = 0
            val cellBoxes = ArrayList<Triple<Float, Float, Cell>>()
            var height = 0f
            val contents = ArrayList<Pair<Float, List<PageItem>>>()
            for (cell in row.cells) {
                val cx = x0 + cols.take(col).sum()
                val cw = cols.drop(col).take(cell.span).sum()
                col += cell.span
                val inner = ArrayList<PageItem>()
                var y = 0f
                if (!cell.mergedAbove) {
                    var cellAfter = 0f
                    val paras = flatten(cell.blocks)
                    for ((k, p) in paras.withIndex()) {
                        val laid = paragraph(p, max(U, cw - 2 * margin))
                        val prevP = paras.getOrNull(k - 1)
                        val nextP = paras.getOrNull(k + 1)
                        val same = { o: Block.Para? -> o != null && o.props.styleId == p.props.styleId && (o.props.contextual == true || p.props.contextual == true) }
                        y += gap(cellAfter, if (same(prevP)) 0f else laid.before)
                        inner += PageItem.Text(laid.layout, 0, laid.lines - 1, cx + margin + laid.x, y)
                        y += laid.height
                        cellAfter = if (same(nextP)) 0f else laid.after
                    }
                    // Space after the last paragraph of a cell still counts, as in Word
                    y += cellAfter
                }
                height = max(height, y)
                cellBoxes += Triple(cx, cw, cell)
                contents += y to inner
            }
            row.height?.let { h -> height = if (row.exact) tw(h) else max(height, tw(h)) }
            for ((cx, cw, cell) in cellBoxes) cell.shading?.let { items += PageItem.Fill(RectF(cx, 0f, cx + cw, height), it) }
            contents.forEach { items += it.second }
            if (t.borders) for ((cx, cw, cell) in cellBoxes) {
                if (!cell.mergedAbove) items += PageItem.Rule(cx, 0f, cx + cw, 0f, rule)
                items += PageItem.Rule(cx, height, cx + cw, height, rule)
                items += PageItem.Rule(cx, 0f, cx, height, rule)
                items += PageItem.Rule(cx + cw, 0f, cx + cw, height, rule)
            }
            LaidRow(items, height)
        }
    }

    /** Paragraphs of a cell; a table inside a cell is read row by row for now. */
    private fun flatten(blocks: List<Block>): List<Block.Para> = blocks.flatMap { b ->
        when (b) {
            is Block.Para -> listOf(b)
            is Block.Table -> b.rows.flatMap { r -> r.cells.flatMap { flatten(it.blocks) } }
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

/** Draws laid-out pages. */
object PagePainter {
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
