package ua.hramota.doc.docx

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a document as DOCX from what Hramota read (an old Word DOC, say): paragraphs with their
 * resolved formatting, list labels as text, tables, pictures, headers and footers, page size.
 * Styles are not carried over by name: every paragraph and run says how it looks itself.
 */
object DocxWriter {
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS = "xmlns:w=\"$W\" xmlns:r=\"$R\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""

    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for ((k, c) in s.withIndex()) when {
            c == '&' -> b.append("&amp;"); c == '<' -> b.append("&lt;"); c == '>' -> b.append("&gt;"); c == '"' -> b.append("&quot;")
            c < ' ' && c != '\t' -> {}
            c == '￾' || c == '￿' -> {}
            c.isHighSurrogate() -> if (k + 1 < s.length && s[k + 1].isLowSurrogate()) b.append(c)
            c.isLowSurrogate() -> if (k > 0 && s[k - 1].isHighSurrogate()) b.append(c)
            else -> b.append(c)
        }
        return b.toString()
    }

    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    /** A picture written into the package: its file, relationship and drawing number. */
    private class Media(val path: String, val rid: String, val ext: String)

    private class Part(val doc: DocxDocument) {
        val media = LinkedHashMap<String, Media>()
        val mediaBytes = LinkedHashMap<String, ByteArray>()
        var drawing = 1

        fun picture(rel: String, rels: MutableList<Pair<String, String>>): Media? {
            media[rel]?.let { return it }
            val bytes = doc.picture(rel) ?: return null
            val ext = kind(bytes) ?: return null
            val path = "media/image${mediaBytes.size + 1}.$ext"
            val m = Media(path, "rIdImg${mediaBytes.size + 1}", ext)
            mediaBytes[path] = bytes
            media[rel] = m
            return m
        }
    }

    /** The picture's format from its first bytes; null for what Word would not show. */
    private fun kind(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
        b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
        b.size > 44 && String(b, 40, 4, Charsets.ISO_8859_1) == " EMF" -> "emf"
        b.size > 4 && ((b[0] == 0xD7.toByte() && b[1] == 0xCD.toByte()) || ((b[0] == 1.toByte() || b[0] == 2.toByte()) && b[1] == 0.toByte() && b[2] == 9.toByte())) -> "wmf"
        else -> null
    }

    private fun rPr(p: RunProps, minor: String): String {
        val b = StringBuilder("<w:rPr>")
        val font = p.font ?: if (p.fontTheme != null) null else null
        font?.let { val f = esc(it); b.append("<w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:cs=\"$f\" w:eastAsia=\"$f\"/>") }
        if (p.bold == true) b.append("<w:b/><w:bCs/>")
        if (p.italic == true) b.append("<w:i/><w:iCs/>")
        if (p.caps == true) b.append("<w:caps/>")
        if (p.strike == true) b.append("<w:strike/>")
        if (p.vanish == true) b.append("<w:vanish/>")
        p.color?.let { b.append("<w:color w:val=\"${hex(it)}\"/>") }
        p.size?.let { val hp = (it * 2).toInt().coerceIn(2, 3276); b.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>") }
        if (p.underline == true) b.append("<w:u w:val=\"single\"/>")
        p.highlight?.let { b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        p.vertAlign?.takeIf { it == "superscript" || it == "subscript" }?.let { b.append("<w:vertAlign w:val=\"$it\"/>") }
        b.append("</w:rPr>")
        return if (b.length == "<w:rPr></w:rPr>".length) "" else b.toString()
    }

    private fun pPr(p: ParaProps, label: Boolean, mark: String = ""): String {
        val b = StringBuilder("<w:pPr>")
        if (p.keepNext == true) b.append("<w:keepNext/>")
        if (p.pageBreakBefore == true) b.append("<w:pageBreakBefore/>")
        p.shading?.let { b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        // A list label is written as text and a tab: the tab stops where the text begins
        val tabs = (p.tabs.orEmpty() + if (label && p.left != null) listOf(p.left) else emptyList()).distinct().sorted()
        if (tabs.isNotEmpty()) b.append("<w:tabs>").append(tabs.joinToString("") { "<w:tab w:val=\"left\" w:pos=\"$it\"/>" }).append("</w:tabs>")
        if (p.before != null || p.after != null || p.line != null) {
            b.append("<w:spacing")
            p.before?.let { b.append(" w:before=\"$it\"") }
            p.after?.let { b.append(" w:after=\"$it\"") }
            p.line?.let { b.append(" w:line=\"$it\" w:lineRule=\"${p.lineRule ?: "auto"}\"") }
            b.append("/>")
        }
        if (p.left != null || p.right != null || p.firstLine != null) {
            b.append("<w:ind")
            p.left?.let { b.append(" w:left=\"$it\"") }
            p.right?.let { b.append(" w:right=\"$it\"") }
            p.firstLine?.let { if (it < 0) b.append(" w:hanging=\"${-it}\"") else b.append(" w:firstLine=\"$it\"") }
            b.append("/>")
        }
        if (p.contextual == true) b.append("<w:contextualSpacing/>")
        p.align?.let { a -> val v = when (a) { "justify", "distribute" -> "both"; "start" -> "left"; "end" -> "right"; else -> a }; b.append("<w:jc w:val=\"${esc(v)}\"/>") }
        // The paragraph mark's own look (last in pPr): an empty line keeps its height
        b.append(mark).append("</w:pPr>")
        return if (b.length == "<w:pPr></w:pPr>".length) "" else b.toString()
    }

    private fun blocks(list: List<Block>, part: Part, rels: MutableList<Pair<String, String>>, out: Appendable, minor: String) {
        for (bl in list) when (bl) {
            is Block.Para -> para(bl, part, rels, out, minor)
            is Block.Table -> table(bl, part, rels, out, minor)
        }
    }

    private fun para(p: Block.Para, part: Part, rels: MutableList<Pair<String, String>>, b: Appendable, minor: String) {
        b.append("<w:p>").append(pPr(p.props, p.label != null, rPr(p.mark, minor)))
        p.label?.takeIf { it.isNotEmpty() }?.let { b.append("<w:r>").append(rPr(p.labelProps ?: p.mark, minor)).append("<w:t xml:space=\"preserve\">${esc(it)}</w:t><w:tab/></w:r>") }
        for (i in p.inlines) when (i) {
            is Inline.Text -> if (i.text.isNotEmpty()) b.append("<w:r>").append(rPr(i.props, minor)).append("<w:t xml:space=\"preserve\">${esc(i.text)}</w:t></w:r>")
            Inline.Tab -> b.append("<w:r><w:tab/></w:r>")
            Inline.LineBreak -> b.append("<w:r><w:br/></w:r>")
            Inline.PageBreak -> b.append("<w:r><w:br w:type=\"page\"/></w:r>")
            is Inline.Field -> b.append("<w:fldSimple w:instr=\"${if (i.total) "NUMPAGES" else "PAGE"}\"><w:r>").append(rPr(i.props, minor)).append("<w:t>1</w:t></w:r></w:fldSimple>")
            is Inline.Image -> {
                val m = part.picture(i.rel, rels) ?: continue
                if (rels.none { it.first == m.rid }) rels += m.rid to m.path
                val cx = (i.width.coerceIn(1f, 2000f) * 12700).toLong(); val cy = (i.height.coerceIn(1f, 2000f) * 12700).toLong()
                val id = part.drawing++
                b.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>")
                    .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    .append("<pic:blipFill><a:blip r:embed=\"${m.rid}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
            }
        }
        b.append("</w:p>")
    }

    private fun table(t: Block.Table, part: Part, rels: MutableList<Pair<String, String>>, b: Appendable, minor: String) {
        // Word needs a row to hold a cell and a table to hold a row
        val rows = t.rows.filter { it.cells.isNotEmpty() }
        if (rows.isEmpty()) return
        val spans = rows.maxOf { r -> r.cells.sumOf { it.span.coerceAtLeast(1) } }.coerceIn(1, DocxReader.MAX_COLUMNS)
        // Without a known grid the columns share the text width, and Word fits them to the content
        val fixed = t.columns.isNotEmpty() && t.columns.sum() > 0
        val columns = if (fixed) t.columns.map { it.coerceAtLeast(1) } + List((spans - t.columns.size).coerceAtLeast(0)) { t.columns.last().coerceAtLeast(1) }
            else List(spans) { 9000 / spans }
        val border = if (t.borders) "<w:tblBorders>" + listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" } + "</w:tblBorders>" else ""
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
        if (t.indent != 0) b.append("<w:tblInd w:w=\"${t.indent}\" w:type=\"dxa\"/>")
        b.append(border).append("<w:tblLayout w:type=\"${if (fixed) "fixed" else "autofit"}\"/></w:tblPr><w:tblGrid>")
        for (c in columns) b.append("<w:gridCol w:w=\"$c\"/>")
        b.append("</w:tblGrid>")
        // Where each cell starts in the grid, row by row: a merge goes on with the cell in the same column below
        val starts = rows.map { r -> var col = 0; IntArray(r.cells.size) { k -> col.also { col += r.cells[k].span.coerceAtLeast(1) } } }
        val below = rows.indices.map { ri ->
            if (ri + 1 >= rows.size) emptySet() else {
                val next = rows[ri + 1]
                starts[ri + 1].indices.filter { next.cells[it].mergedAbove }.map { starts[ri + 1][it] }.toHashSet()
            }
        }
        for ((ri, row) in rows.withIndex()) {
            b.append("<w:tr>")
            if (row.height != null || row.cantSplit) {
                b.append("<w:trPr>")
                if (row.cantSplit) b.append("<w:cantSplit/>")
                row.height?.let { b.append("<w:trHeight w:val=\"$it\" w:hRule=\"${if (row.exact) "exact" else "atLeast"}\"/>") }
                b.append("</w:trPr>")
            }
            for ((k, cell) in row.cells.withIndex()) {
                val span = cell.span.coerceAtLeast(1)
                val col = starts[ri][k]
                var width = 0
                for (c in col until minOf(col + span, columns.size)) width += columns[c]
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"${width.coerceAtLeast(1)}\" w:type=\"dxa\"/>")
                if (span > 1) b.append("<w:gridSpan w:val=\"$span\"/>")
                if (cell.mergedAbove && ri > 0) b.append("<w:vMerge/>")
                else if (col in below[ri]) b.append("<w:vMerge w:val=\"restart\"/>")
                cell.shading?.let { b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                b.append("</w:tcPr>")
                blocks(cell.blocks, part, rels, b, minor)
                // A cell ends with a paragraph (Word requires one)
                if (cell.blocks.lastOrNull() !is Block.Para) b.append("<w:p/>")
                b.append("</w:tc>")
            }
            b.append("</w:tr>")
        }
        b.append("</w:tbl>")
    }

    /** Writes [doc] as a DOCX package to [out]; the body goes straight into the package, so a long document never sits whole in memory. */
    fun write(doc: DocxDocument, out: OutputStream, lang: String) {
        val part = Part(doc)
        val minor = doc.fonts.minor
        val docRels = ArrayList<Pair<String, String>>()
        // Headers and footers first (small), each a part of its own with its own pictures: the section refers to them
        val hf = doc.headersFooters
        val extraParts = ArrayList<Pair<String, String>>()
        val refs = StringBuilder()
        var n = 0
        for ((kind, map) in listOf("header" to hf.headers, "footer" to hf.footers)) for ((which, blocksOf) in map) {
            if (blocksOf.isEmpty() || which !in setOf("default", "first", "even")) continue
            n++
            val name = "$kind$n.xml"
            val rels = ArrayList<Pair<String, String>>()
            val inner = StringBuilder()
            blocks(blocksOf, part, rels, inner, minor)
            if (blocksOf.lastOrNull() !is Block.Para) inner.append("<w:p/>")
            val tag = if (kind == "header") "hdr" else "ftr"
            extraParts += "word/$name" to HEAD + "<w:$tag $NS>$inner</w:$tag>"
            if (rels.isNotEmpty()) extraParts += "word/_rels/$name.rels" to HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                rels.joinToString("") { (id, path) -> "<Relationship Id=\"$id\" Type=\"$R/image\" Target=\"$path\"/>" } + "</Relationships>"
            val rid = "rIdHf$n"
            docRels += rid to "#$kind:$name"
            refs.append("<w:${kind}Reference w:type=\"$which\" r:id=\"$rid\"/>")
        }
        val pg = doc.page
        val sect = "<w:sectPr>$refs<w:pgSz w:w=\"${pg.width}\" w:h=\"${pg.height}\"${if (pg.width > pg.height) " w:orient=\"landscape\"" else ""}/>" +
            "<w:pgMar w:top=\"${pg.top}\" w:right=\"${pg.right}\" w:bottom=\"${pg.bottom}\" w:left=\"${pg.left}\" w:header=\"${pg.header}\" w:footer=\"${pg.footer}\" w:gutter=\"0\"/>" +
            (if (hf.firstPage) "<w:titlePg/>" else "") + "</w:sectPr>"
        val z = ZipOutputStream(out)
        val w = java.io.OutputStreamWriter(z, Charsets.UTF_8).buffered(1 shl 16)
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); w.write(text); w.flush(); z.closeEntry() }
        z.putNextEntry(ZipEntry("word/document.xml"))
        w.write(HEAD + "<w:document $NS><w:body>")
        blocks(doc.blocks, part, docRels, w, minor)
        w.write("$sect</w:body></w:document>")
        w.flush(); z.closeEntry()
        val types = StringBuilder(HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (ext in part.mediaBytes.keys.map { it.substringAfterLast('.') }.distinct()) {
            val ct = when (ext) { "png" -> "image/png"; "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "emf" -> "image/x-emf"; else -> "image/x-wmf" }
            types.append("<Default Extension=\"$ext\" ContentType=\"$ct\"/>")
        }
        types.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        types.append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        types.append("<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>")
        for ((name, _) in extraParts) if (!name.endsWith(".rels")) {
            val k = if (name.substringAfterLast('/').startsWith("header")) "header" else "footer"
            types.append("<Override PartName=\"/$name\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$k+xml\"/>")
        }
        types.append("</Types>")
        val docRelsXml = StringBuilder(HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdStyles\" Type=\"$R/styles\" Target=\"styles.xml\"/>")
        docRelsXml.append("<Relationship Id=\"rIdSettings\" Type=\"$R/settings\" Target=\"settings.xml\"/>")
        for ((id, target) in docRels) {
            if (target.startsWith("#")) {
                val (kind, name) = target.removePrefix("#").split(':')
                docRelsXml.append("<Relationship Id=\"$id\" Type=\"$R/$kind\" Target=\"$name\"/>")
            } else docRelsXml.append("<Relationship Id=\"$id\" Type=\"$R/image\" Target=\"$target\"/>")
        }
        docRelsXml.append("</Relationships>")
        val f = esc(minor)
        val styles = HEAD + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:cs=\"$f\" w:eastAsia=\"$f\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"${esc(lang)}\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr/></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"
        // The Word version the layout follows (an old DOC keeps its own, so pages break as before)
        val settings = HEAD + "<w:settings xmlns:w=\"$W\">" + (if (hf.evenPages) "<w:evenAndOddHeaders/>" else "") +
            "<w:compat>" + (if (doc.addSpacing) "<w:doNotUseHTMLParagraphAutoSpacing/>" else "") + "<w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"${doc.compatibilityMode.coerceIn(11, 15)}\"/></w:compat></w:settings>"
        put("[Content_Types].xml", types.toString())
        put("_rels/.rels", HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
        put("word/styles.xml", styles)
        put("word/settings.xml", settings)
        put("word/_rels/document.xml.rels", docRelsXml.toString())
        for ((name, text) in extraParts) put(name, text)
        for ((path, bytes) in part.mediaBytes) { w.flush(); z.putNextEntry(ZipEntry("word/$path")); z.write(bytes); z.closeEntry() }
        z.finish(); z.flush()
    }
}
