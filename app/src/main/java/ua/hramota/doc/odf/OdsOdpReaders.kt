package ua.hramota.doc.odf

import ua.hramota.doc.Inspection
import ua.hramota.doc.sheet.CellStyle
import ua.hramota.doc.sheet.Merge
import ua.hramota.doc.sheet.SheetBuilder
import ua.hramota.doc.sheet.SheetLimits
import ua.hramota.doc.sheet.Workbook
import ua.hramota.doc.slides.Deck
import ua.hramota.doc.slides.Slide
import ua.hramota.doc.slides.SlidePara
import ua.hramota.doc.slides.SlideRun
import ua.hramota.doc.slides.SlideShape
import java.io.IOException
import java.io.InputStream

/**
 * OpenDocument spreadsheets (ODS): cells as LibreOffice shows them (the text it saved for each), with
 * fills, bold and italic, alignment, column widths, row heights and merged cells.
 */
object OdsReader {
    fun read(input: InputStream): Workbook {
        val pkg = OdfPackage.read(input)
        val styles = pkg.styleMap
        val doc = pkg.content.child("document-content")?.child("body")?.child("spreadsheet") ?: throw IOException("No spreadsheet")
        val budget = intArrayOf(SheetLimits.MAX_CELLS)
        val looks = ArrayList<CellStyle>().apply { add(CellStyle.DEFAULT) }
        val lookIndex = HashMap<String, Int>()
        fun look(name: String?, numeric: Boolean): Int {
            val key = (name ?: "") + if (numeric) "#n" else ""
            return lookIndex.getOrPut(key) {
                val p = styles.props(name, "table-cell")
                val align = when (p["text-align"]) { "center" -> "center"; "end", "right" -> "right"; "start", "left" -> "left"; else -> null }
                val s = CellStyle(
                    bold = p["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } == true,
                    italic = p["font-style"] == "italic",
                    underline = p["text-underline-style"]?.let { it != "none" } == true,
                    size = length(p["font-size"]) ?: 10f,
                    color = color(p["color"]),
                    fill = color(p["background-color"]),
                    align = align ?: if (numeric) "right" else null,
                    vertical = when (p["vertical-align"]) { "top" -> "top"; "middle" -> "center"; else -> null },
                    wrap = p["wrap-option"] == "wrap",
                    borders = listOf("border-left" to 1, "border-top" to 2, "border-right" to 4, "border-bottom" to 8).sumOf { (k, bit) -> if ((p[k] ?: p["border"])?.let { it != "none" && !it.startsWith("0") } == true) bit else 0 },
                )
                looks += s; looks.size - 1
            }
        }
        val sheets = ArrayList<ua.hramota.doc.sheet.Sheet>()
        for (t in doc.all("table").take(SheetLimits.MAX_SHEETS)) {
            val name = t.attr("name") ?: "Sheet${sheets.size + 1}"
            val b = SheetBuilder(name, budget)
            b.defaultWidth = 64f * 0.75f * 1.06f; b.defaultHeight = 12.8f
            var col = 0
            fun cols(n: ONode) {
                for (c in n.elements) when (c.name) {
                    "table-column" -> {
                        val w = length(styles.props(c.attr("style-name"), "table-column")["column-width"])
                        val rep = (c.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, SheetLimits.MAX_COLS)
                        if (w != null && rep <= 1024) for (k in 0 until rep) b.width(col + k, w)
                        col += rep
                    }
                    "table-columns", "table-header-columns", "table-column-group" -> cols(c)
                }
            }
            cols(t)
            var row = 0
            fun rows(n: ONode) {
                for (r in n.elements) when (r.name) {
                    "table-row" -> {
                        if (row >= SheetLimits.MAX_ROWS) return
                        val rep = (r.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, SheetLimits.MAX_ROWS)
                        val h = length(styles.props(r.attr("style-name"), "table-row")["row-height"])
                        // The same row many times over: written out only while it holds something (not the empty rest of the sheet)
                        val filled = r.elements.any { c -> c.name == "table-cell" && (c.elements.isNotEmpty() || c.attr("value-type") != null) }
                        val times = if (filled) rep.coerceAtMost(1000) else 1
                        // Each cell worked out once, then written on every repeat of the row
                        class Cl(val at: Int, val crep: Int, val look: Int, val text: String?, val across: Int, val down: Int)
                        val line = ArrayList<Cl>()
                        var at = 0
                        for (cell in r.elements) {
                            if (at >= SheetLimits.MAX_COLS) break
                            val crep = (cell.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, SheetLimits.MAX_COLS)
                            if (cell.name == "table-cell" || cell.name == "covered-table-cell") {
                                val type = cell.attr("value-type")
                                val text = cell.all("p").joinToString("\n") { it.text }
                                val numeric = type in setOf("float", "percentage", "currency")
                                val style = cell.attr("style-name") ?: r.attr("default-cell-style-name")
                                val has = text.isNotEmpty() || type != null
                                val shown = cell.name == "table-cell" && (has || styles.props(style, "table-cell")["background-color"]?.let { it != "transparent" } == true) && crep <= 1024
                                val across = (cell.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, SheetLimits.MAX_COLS)
                                val down = (cell.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceIn(1, SheetLimits.MAX_ROWS)
                                if (shown || across > 1 || down > 1) line += Cl(at, if (shown) crep else 0, if (shown) look(style, numeric) else 0, if (has) text else null, across, down)
                            }
                            at += crep
                        }
                        for (k in 0 until times) {
                            // The sheet's cells all used: the rest is not read
                            if (budget[0] <= 0) break
                            if (h != null && (rep <= 1000)) b.height(row + k, h)
                            for (cl in line) {
                                for (j in 0 until cl.crep) if (cl.text != null) b.text(row + k, cl.at + j, cl.look, cl.text) else b.blank(row + k, cl.at + j, cl.look)
                                if ((cl.across > 1 || cl.down > 1) && b.merges.size < 10_000) b.merges += Merge(row + k, cl.at, row + k + cl.down - 1, cl.at + cl.across - 1)
                            }
                        }
                        row += rep
                    }
                    "table-rows", "table-header-rows", "table-row-group" -> rows(r)
                }
            }
            rows(t)
            sheets += b.build(looks, false, emptyList(), name)
        }
        if (sheets.isEmpty()) throw IOException("No sheets")
        // Every sheet uses the one list of looks, filled as the sheets were read
        return Workbook(sheets.map { it.shown(newStyles = looks) }, Inspection())
    }
}

/**
 * OpenDocument presentations (ODP): slides with their text frames, shapes and pictures, and the
 * slides' backgrounds, at the size the presentation has.
 */
object OdpReader {
    fun read(input: InputStream): Deck {
        val pkg = OdfPackage.read(input)
        val styles = pkg.styleMap
        val pres = pkg.content.child("document-content")?.child("body")?.child("presentation") ?: throw IOException("No presentation")
        val page = pkg.page()
        val width = page[0].takeIf { it > 0 } ?: 720f; val height = page[1].takeIf { it > 0 } ?: 405f
        val media = HashMap<String, ByteArray>()
        val slides = ArrayList<Slide>()
        val masters = pkg.styles?.child("document-styles")?.child("master-styles")?.all("master-page").orEmpty().associateBy { it.attr("name") }
        for (p in pres.all("page").take(Deck.MAX_SLIDES)) {
            val shapes = ArrayList<SlideShape>()
            val bg = styles.props(p.attr("style-name"), "drawing-page").let { color(it["fill-color"]).takeIf { _ -> it["fill"] == "solid" } }
                ?: masters[p.attr("master-page-name")]?.let { m -> styles.props(m.attr("style-name"), "drawing-page").let { color(it["fill-color"]).takeIf { _ -> it["fill"] == "solid" } } }
                ?: 0xFFFFFFFF.toInt()
            for (e in p.elements) shape(e, styles, pkg, media, shapes, 0)
            slides += Slide(bg, shapes.take(Deck.MAX_SHAPES))
        }
        if (slides.isEmpty()) throw IOException("No slides")
        return Deck(width, height, slides, media, Inspection())
    }

    private fun shape(e: ONode, styles: OdfPackage.Styles, pkg: OdfPackage, media: MutableMap<String, ByteArray>, out: MutableList<SlideShape>, depth: Int) {
        if (depth > 20 || out.size > Deck.MAX_SHAPES) return
        val x = length(e.attr("x")) ?: 0f; val y = length(e.attr("y")) ?: 0f
        val w = length(e.attr("width")) ?: 0f; val h = length(e.attr("height")) ?: 0f
        val g = styles.props(e.attr("style-name"), "graphic")
        val fill = if (g["fill"] == "solid" || g["fill"] == null && g["fill-color"] != null && e.name != "frame") color(g["fill-color"]) else null
        val line = if (g["stroke"] != null && g["stroke"] != "none") color(g["stroke-color"]) ?: 0xFF000000.toInt() else null
        when (e.name) {
            "frame" -> {
                // (a table carries a preview picture of itself as well: the table is drawn instead)
                if (e.child("table") == null) e.child("image")?.attr("href")?.removePrefix("./")?.takeIf { h -> listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp").any { h.lowercase().endsWith(it) } }?.let { href ->
                    val bytes = pkg.media[href]
                    if (bytes != null) { media[href] = bytes; out += SlideShape.Picture(x, y, w, h, 0f, href); return }
                }
                e.child("text-box")?.let { tb -> out += SlideShape.Box(x, y, w, h, 0f, "rect", fill, line, 0.75f, paragraphs(tb, styles, presentationClass(e)), anchor = anchor(g)) }
                // A table: its cells as boxes in a grid (columns by their widths, rows sharing the height)
                e.child("table")?.let { t ->
                    val widths = t.all("table-column").flatMap { c -> List((c.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)) { length(styles.props(c.attr("style-name"), "table-column")["column-width"]) } }
                    val rows = t.all("table-row").take(200)
                    if (rows.isEmpty()) return@let
                    // At most 64 columns are drawn (a slide has no room for more)
                    val n = rows.maxOf { r -> r.elements.count { it.name == "table-cell" || it.name == "covered-table-cell" } }.coerceIn(1, 64)
                    val cw = (0 until n).map { widths.getOrNull(it) ?: (w / n) }
                    val scale = if (cw.sum() > 0f) w / cw.sum() else 1f
                    val rh = h / rows.size
                    for ((ri, r) in rows.withIndex()) {
                        var cx = x
                        for ((ci, c) in r.elements.filter { it.name == "table-cell" || it.name == "covered-table-cell" }.take(n).withIndex()) {
                            val span = (c.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, n)
                            val cellW = (ci until minOf(n, ci + span)).sumOf { (cw[it] * scale).toDouble() }.toFloat()
                            if (c.name == "table-cell") {
                                val cp = styles.props(c.attr("style-name"), "graphic") + styles.props(c.attr("style-name"), "table-cell")
                                out += SlideShape.Box(cx, y + ri * rh, cellW, rh, 0f, "rect", color(cp["fill-color"] ?: cp["background-color"]), 0xFF7F7F7F.toInt(), 0.75f, paragraphs(c, styles, null).map { p -> p.copy(runs = p.runs.map { it.copy(size = minOf(it.size, 14f)) }) }, anchor = "center")
                            }
                            cx += (cw.getOrElse(ci) { w / n }) * scale
                        }
                    }
                }
            }
            "custom-shape", "rect", "ellipse", "circle" -> {
                val geometry = when {
                    e.name == "ellipse" || e.name == "circle" -> "ellipse"
                    e.child("enhanced-geometry")?.attr("type")?.contains("ellipse") == true -> "ellipse"
                    e.child("enhanced-geometry")?.attr("type") == "round-rectangle" -> "roundRect"
                    else -> "rect"
                }
                out += SlideShape.Box(x, y, w, h, 0f, geometry, fill ?: if (e.name == "custom-shape") 0xFF729FCF.toInt() else null, line, 0.75f, paragraphs(e, styles, null), anchor = anchor(g).let { if (it == "top") "center" else it })
            }
            "g" -> for (c in e.elements) shape(c, styles, pkg, media, out, depth + 1)
        }
    }

    private fun anchor(g: Map<String, String>) = when (g["textarea-vertical-align"]) { "middle" -> "center"; "bottom" -> "bottom"; else -> "top" }

    private fun presentationClass(e: ONode) = e.attr("class")

    private fun paragraphs(box: ONode, styles: OdfPackage.Styles, kind: String?): List<SlidePara> {
        val out = ArrayList<SlidePara>()
        val base = if (kind == "title") 40f else 24f
        fun para(p: ONode, bullet: String?, level: Int) {
            val pp = styles.props(p.attr("style-name"), "paragraph")
            val baseRun = run(pp, SlideRun("", base))
            val runs = ArrayList<SlideRun>()
            fun walk(n: ONode, r: SlideRun, depth: Int) {
                if (depth > 20) return
                for (c in n.children) when {
                    c is String -> runs += r.copy(text = c)
                    (c as ONode).name == "span" -> walk(c, run(styles.props(c.attr("style-name"), "text"), r), depth + 1)
                    c.name == "s" -> runs += r.copy(text = " ".repeat((c.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 50)))
                    c.name == "tab" -> runs += r.copy(text = "\t")
                    c.name == "line-break" -> runs += r.copy(text = "\n")
                    c.name == "a" -> walk(c, r, depth + 1)
                }
            }
            walk(p, baseRun, 0)
            val align = when (pp["text-align"]) { "center" -> "center"; "end", "right" -> "right"; "justify" -> "justify"; else -> "left" }
            out += SlidePara(runs, align, bullet, marginLeft = level * 24f + if (bullet != null) 18f else 0f, indent = if (bullet != null) -18f else 0f, emptySize = baseRun.size)
        }
        fun list(l: ONode, level: Int) {
            for (item in l.all("list-item") + l.all("list-header")) for (c in item.elements) when (c.name) {
                "p", "h" -> para(c, if (item.name == "list-item" && kind != "title") "•" else null, level)
                "list" -> list(c, level + 1)
            }
        }
        for (c in box.elements) when (c.name) {
            "p", "h" -> para(c, null, 0)
            "list" -> list(c, 0)
        }
        return out
    }

    private fun run(p: Map<String, String>, base: SlideRun) = base.copy(
        size = length(p["font-size"]) ?: base.size,
        bold = p["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: base.bold,
        italic = p["font-style"]?.let { it == "italic" } ?: base.italic,
        underline = p["text-underline-style"]?.let { it != "none" } ?: base.underline,
        strike = p["text-line-through-style"]?.let { it != "none" } ?: base.strike,
        color = color(p["color"]) ?: base.color,
        font = p["font-name"] ?: base.font,
    )
}
