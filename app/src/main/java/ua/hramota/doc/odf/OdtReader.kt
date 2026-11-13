package ua.hramota.doc.odf

import ua.hramota.doc.Inspection
import ua.hramota.doc.docx.Block
import ua.hramota.doc.docx.Cell
import ua.hramota.doc.docx.DocxDocument
import ua.hramota.doc.docx.Inline
import ua.hramota.doc.docx.PageSetup
import ua.hramota.doc.docx.ParaProps
import ua.hramota.doc.docx.Row
import ua.hramota.doc.docx.RunProps
import ua.hramota.doc.docx.ThemeFonts
import java.io.InputStream

/**
 * OpenDocument text (ODT) laid out on pages as LibreOffice shows it: paragraphs and headings with
 * their styles, lists, tables, pictures, notes after the text, and the page's size and margins.
 * Read into the same model as Word documents, so it is drawn, printed and read aloud the same way.
 */
object OdtReader {
    private const val MAX_BLOCKS = 400_000

    fun read(input: InputStream): DocxDocument {
        val pkg = OdfPackage.read(input)
        val styles = pkg.styleMap
        val page = pkg.page()
        val body = pkg.content.child("document-content")?.child("body")?.child("text") ?: throw java.io.IOException("No text")
        val r = Reader(styles)
        val blocks = ArrayList<Block>()
        for (c in body.elements) r.block(c, blocks, 0, 0)
        // Notes after the text, numbered as in it
        if (r.notes.isNotEmpty()) {
            blocks += Block.Para(ParaProps(before = 240), RunProps(size = 10f), listOf(Inline.Text("————————", RunProps(size = 10f))), null, null)
            for ((label, paras) in r.notes) {
                val first = paras.indexOfFirst { it is Block.Para }
                val list = paras.toMutableList()
                if (first >= 0) { val p = list[first] as Block.Para; list[first] = p.copy(inlines = listOf(Inline.Text("$label ", RunProps(size = 10f, vertAlign = "superscript"))) + p.inlines) }
                blocks += list
            }
        }
        fun tw(pt: Float) = (pt * 20).toInt()
        val setup = PageSetup(tw(page[0]), tw(page[1]), tw(page[2]), tw(page[3]), tw(page[4]), tw(page[5]))
        val meta = pkg.parts["meta.xml"]?.let { OdfPackage.tree(it) }?.child("document-meta")?.child("meta")
        val inspection = Inspection(
            author = meta?.child("initial-creator")?.text?.takeIf { it.isNotBlank() }?.take(200),
            title = meta?.child("title")?.text?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
        )
        val rels = pkg.media.keys.associateWith { it }
        return DocxDocument(setup, blocks, pkg.media, ThemeFonts("Liberation Serif", "Liberation Sans"), rels, inspection = inspection, complete = r.count <= MAX_BLOCKS)
    }

    private class Reader(val styles: OdfPackage.Styles) {
        var count = 0
        val notes = ArrayList<Pair<String, List<Block>>>()
        /** List numbers by list style and level. */
        private val counters = HashMap<String, IntArray>()

        fun runProps(p: Map<String, String>, base: RunProps = RunProps()): RunProps = base.with(RunProps(
            font = p["font-name"] ?: p["font-family"]?.trim('\'', '"'),
            size = length(p["font-size"]) ?: p["font-size"]?.removeSuffix("%")?.toFloatOrNull()?.let { (base.size ?: 12f) * it / 100f },
            bold = p["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
            italic = p["font-style"]?.let { it == "italic" || it == "oblique" },
            underline = p["text-underline-style"]?.let { it != "none" },
            strike = p["text-line-through-style"]?.let { it != "none" },
            color = color(p["color"]),
            highlight = color(p["background-color"]),
            caps = p["text-transform"]?.let { it == "uppercase" },
            vertAlign = p["text-position"]?.let { when { it.startsWith("super") || it.startsWith("33%") -> "superscript"; it.startsWith("sub") || it.startsWith("-33%") -> "subscript"; else -> null } },
        ))

        fun paraProps(p: Map<String, String>): ParaProps {
            fun tw(k: String) = length(p[k])?.let { (it * 20).toInt() }
            val lh = p["line-height"]
            return ParaProps(
                align = when (p["text-align"]) { "center" -> "center"; "end", "right" -> "right"; "justify" -> "both"; "start", "left" -> "left"; else -> null },
                left = tw("margin-left"), right = tw("margin-right"), firstLine = tw("text-indent"),
                before = tw("margin-top"), after = tw("margin-bottom"),
                line = lh?.removeSuffix("%")?.toFloatOrNull()?.takeIf { lh.endsWith("%") }?.let { (it * 2.4f).toInt() } ?: length(lh)?.let { (it * 20).toInt() },
                lineRule = if (lh?.endsWith("%") == true) "auto" else if (lh != null && length(lh) != null) "exact" else null,
                pageBreakBefore = p["break-before"]?.let { it == "page" },
                keepNext = p["keep-with-next"]?.let { it == "always" },
                shading = color(p["background-color"]),
            )
        }

        fun block(n: ONode, out: MutableList<Block>, listDepth: Int, depth: Int, listStyle: String? = null) {
            if (count > MAX_BLOCKS || depth > 40) return
            when (n.name) {
                "p", "h" -> { count++; out += para(n, null, null) }
                "list" -> {
                    val style = n.attr("style-name") ?: listStyle
                    if (n.attr("continue-numbering") != "true" && listDepth == 0 && n.attr("continue-list") == null) counters.remove(style ?: "")
                    for (item in n.all("list-item") + n.all("list-header")) {
                        var first = true
                        for (c in item.elements) {
                            when (c.name) {
                                "p", "h" -> {
                                    count++
                                    val label = if (first && item.name == "list-item") label(style, listDepth) else null
                                    val indent = ((listDepth + 1) * 360)
                                    val p = para(c, label, indent)
                                    out += p
                                    first = false
                                }
                                else -> block(c, out, listDepth + 1, depth + 1, style)
                            }
                        }
                    }
                }
                "table" -> table(n, depth)?.let { out += it }
                "section", "index-body", "table-of-content", "alphabetical-index", "illustration-index", "bibliography", "object-index", "user-index", "table-index" ->
                    for (c in n.elements) block(c, out, listDepth, depth + 1, listStyle)
                "soft-page-break" -> {}
            }
        }

        private fun label(style: String?, level: Int): String {
            val ls = styles.lists[style]
            val lvl = ls?.elements?.firstOrNull { (it.attr("level")?.toIntOrNull() ?: 1) == level + 1 }
            if (lvl == null || lvl.name == "list-level-style-bullet") return lvl?.attr("bullet-char")?.takeIf { it.isNotBlank() } ?: "•"
            if (lvl.name != "list-level-style-number") return ""
            val c = counters.getOrPut(style ?: "") { IntArray(10) }
            val l = level.coerceIn(0, 9)
            c[l]++
            for (k in l + 1 until 10) c[k] = 0
            val start = lvl.attr("start-value")?.toIntOrNull() ?: 1
            val n = c[l] + start - 1
            val shown = (lvl.attr("display-levels")?.toIntOrNull() ?: 1).coerceIn(1, l + 1)
            val nums = (l - shown + 1..l).map { k -> format(if (k == l) n else c[k] + start - 1, if (k == l) lvl.attr("num-format") else "1") }
            return (lvl.attr("num-prefix") ?: "") + nums.joinToString(".") + (lvl.attr("num-suffix") ?: ".")
        }

        private fun format(n: Int, fmt: String?): String = when (fmt) {
            "a" -> letters(n).lowercase(); "A" -> letters(n); "i" -> roman(n).lowercase(); "I" -> roman(n)
            "а" -> ukrainian(n); "А" -> ukrainian(n).uppercase(); "" -> ""; else -> n.toString()
        }
        private fun letters(n: Int): String { var x = n; val sb = StringBuilder(); while (x > 0) { sb.append('A' + (x - 1) % 26); x = (x - 1) / 26 }; return sb.reverse().toString() }
        private fun ukrainian(n: Int): String { val a = "абвгґдеєжзиіїйклмнопрстуфхцчшщюя"; return if (n in 1..a.length) a[n - 1].toString() else n.toString() }
        private fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n; val sb = StringBuilder(); for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }; return sb.toString()
        }

        fun para(n: ONode, label: String?, listIndent: Int?): Block.Para {
            val st = n.attr("style-name")
            val props = styles.props(st, "paragraph")
            var pp = ParaProps(after = 0, before = 0, line = 240, lineRule = "auto").with(paraProps(props))
            if (listIndent != null) pp = pp.copy(left = (pp.left ?: 0) + listIndent, firstLine = -360)
            val base = runProps(props, RunProps(size = 12f, font = "Liberation Serif"))
            val inlines = ArrayList<Inline>()
            inline(n, base, inlines, 0)
            return Block.Para(pp, base, inlines, label, if (label != null) base else null)
        }

        private fun inline(n: ONode, base: RunProps, out: MutableList<Inline>, depth: Int) {
            if (depth > 30) return
            for (c in n.children) {
                if (c is String) { if (c.isNotEmpty()) out += Inline.Text(c.replace('\n', ' ').replace(Regex("\\s{2,}"), " "), base); continue }
                c as ONode
                when (c.name) {
                    "span" -> inline(c, runProps(styles.props(c.attr("style-name"), "text"), base), out, depth + 1)
                    "a", "meta", "ruby-base", "bookmark-ref", "reference-ref", "sequence", "date", "time", "author-name", "title", "subject", "chapter", "file-name", "page-count" -> inline(c, base, out, depth + 1)
                    "s" -> out += Inline.Text(" ".repeat((c.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 100)), base)
                    "tab" -> out += Inline.Tab
                    "line-break" -> out += Inline.LineBreak
                    "page-number" -> out += Inline.Field(false, base)
                    "note" -> {
                        val citation = c.child("note-citation")?.text?.trim().orEmpty().ifEmpty { "${notes.size + 1}" }
                        out += Inline.Text(citation, base.with(RunProps(vertAlign = "superscript")))
                        val paras = ArrayList<Block>()
                        c.child("note-body")?.elements?.forEach { block(it, paras, 0, depth + 1) }
                        if (notes.size < 5000) notes += citation to paras
                    }
                    "frame" -> {
                        val img = c.child("image")?.attr("href") ?: continue
                        val w = length(c.attr("width")) ?: 100f; val h = length(c.attr("height")) ?: 100f
                        out += Inline.Image(img.removePrefix("./"), w, h)
                    }
                }
            }
        }

        private fun table(t: ONode, depth: Int): Block.Table? {
            if (depth > 16) return null
            // Columns (a repeated one counts many times)
            val widths = ArrayList<Int>()
            fun columns(n: ONode) {
                for (c in n.elements) when (c.name) {
                    "table-column" -> {
                        val w = length(styles.props(c.attr("style-name"), "table-column")["column-width"])?.let { (it * 20).toInt() } ?: 1440
                        repeat((c.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)) { if (widths.size < 63) widths += w }
                    }
                    "table-columns", "table-header-columns", "table-column-group" -> columns(c)
                }
            }
            columns(t)
            val rows = ArrayList<Row>()
            val spanning = HashMap<Int, Int>()
            fun rowsOf(n: ONode) {
                for (r in n.elements) when (r.name) {
                    "table-row" -> {
                        if (rows.size > 5000) return
                        val rp = styles.props(r.attr("style-name"), "table-row")
                        val height = length(rp["min-row-height"] ?: rp["row-height"])?.let { (it * 20).toInt() }
                        val cells = ArrayList<Cell>()
                        var col = 0
                        for (c in r.elements) {
                            // A row is at most 63 cells wide (as the columns are)
                            if (col >= 63) break
                            val repeat = (c.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 63 - col)
                            when (c.name) {
                                "table-cell" -> {
                                    // A repeated cell is read once and shown as many times
                                    val span = (c.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, 63)
                                    val down = (c.attr("number-rows-spanned")?.toIntOrNull() ?: 1)
                                    val blocks = ArrayList<Block>()
                                    for (k in c.elements) block(k, blocks, 0, depth + 1)
                                    if (blocks.isEmpty()) blocks += Block.Para(ParaProps(), RunProps(size = 12f), emptyList(), null, null)
                                    val fill = color(styles.props(c.attr("style-name"), "table-cell")["background-color"])
                                    repeat(repeat) {
                                        if (down > 1) spanning[col] = down - 1
                                        cells += Cell(span, false, blocks, fill)
                                        col += span
                                    }
                                }
                                "covered-table-cell" -> repeat(repeat) {
                                    // Covered from above (a cell spanning rows): a merged cell; covered from the left: nothing
                                    val left = spanning[col]
                                    if (left != null && left > 0) {
                                        cells += Cell(1, true, emptyList(), null)
                                        spanning[col] = left - 1
                                    }
                                    col++
                                }
                            }
                        }
                        if (cells.isNotEmpty()) rows += Row(cells, height, false)
                    }
                    "table-rows", "table-header-rows", "table-row-group" -> rowsOf(r)
                }
            }
            rowsOf(t)
            if (rows.isEmpty()) return null
            val n = rows.maxOf { r -> r.cells.sumOf { it.span } }
            while (widths.size < n) widths += 1440
            return Block.Table(widths.take(n.coerceAtLeast(1)), rows, true, 0)
        }
    }
}
