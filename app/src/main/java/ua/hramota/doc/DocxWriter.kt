package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A piece of text with its look. */
data class WRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Marked with a highlighter. */
    val highlight: Boolean = false,
)

/** What a new Word document is made of. */
sealed interface WBlock {
    /**
     * A paragraph. [style]: null (ordinary), "Title", "Heading1"…"Heading3" or "Quote"; [list]: null,
     * "bullet" or "number"; [checked]: null, or a to-do item (done or not, shown as ☐ / ☑).
     */
    data class Para(
        val runs: List<WRun>,
        val style: String? = null,
        val list: String? = null,
        val checked: Boolean? = null,
        val align: String? = null,
    ) : WBlock {
        constructor(text: String, style: String? = null) : this(listOf(WRun(text)), style)
    }

    /** A picture on a line of its own, at most the width of the text; [alt] is read by screen readers. */
    class Picture(val bytes: ByteArray, val png: Boolean, val widthPx: Int, val heightPx: Int, val alt: String = "") : WBlock

    data object PageBreak : WBlock
}

/**
 * New Word documents with text, headings, lists, to-do items and pictures: notes, scanned pages,
 * ready-made letters. The same A4 page and fonts as an empty new document ([Templates.docx]).
 */
object DocxWriter {
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    /** The width of the text on A4 with these margins, in EMU (16,5 cm). */
    private const val TEXT_WIDTH = 5_939_790L

    private fun esc(s: String) = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '"' -> append("&quot;")
            // Characters XML does not allow are left out
            c < ' ' && c != '\t' -> {}
            c == '￾' || c == '￿' -> {}
            else -> append(c)
        }
    }

    fun write(blocks: List<WBlock>, lang: String, title: String = ""): ByteArray {
        val media = ArrayList<Pair<String, ByteArray>>()
        val rels = StringBuilder()
        val body = StringBuilder()
        var pictureId = 1
        for (b in blocks) when (b) {
            is WBlock.Para -> body.append(para(b))
            is WBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            is WBlock.Picture -> {
                val n = media.size + 1
                val name = "image$n." + if (b.png) "png" else "jpeg"
                media += name to b.bytes
                val rid = "rIdImg$n"
                rels.append("<Relationship Id=\"$rid\" Type=\"$R/image\" Target=\"media/$name\"/>")
                // Pixels at 96 per inch, never wider than the text
                var cx = b.widthPx.coerceAtLeast(1) * 9525L
                var cy = b.heightPx.coerceAtLeast(1) * 9525L
                if (cx > TEXT_WIDTH) { cy = cy * TEXT_WIDTH / cx; cx = TEXT_WIDTH }
                val id = pictureId++
                body.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                    .append("<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\" descr=\"${esc(b.alt)}\"/>")
                    .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$A\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                    .append("<a:graphic xmlns:a=\"$A\"><a:graphicData uri=\"$PIC\"><pic:pic xmlns:pic=\"$PIC\">")
                    .append("<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\" descr=\"${esc(b.alt)}\"/><pic:cNvPicPr/></pic:nvPicPr>")
                    .append("<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                    .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
                    .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
            }
        }
        if (blocks.isEmpty()) body.append("<w:p/>")
        val l = esc(lang)
        fun heading(n: Int, size: Int, before: Int) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        val parts = ArrayList<Pair<String, ByteArray>>()
        fun put(name: String, text: String) { parts += name to text.toByteArray(Charsets.UTF_8) }
        val types = StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        if (media.any { it.first.endsWith("png") }) types.append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        if (media.any { it.first.endsWith("jpeg") }) types.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
        types.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>")
        put("[Content_Types].xml", HEAD + types)
        put("_rels/.rels", HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>")
        put("docProps/core.xml", HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            (if (title.isNotBlank()) "<dc:title>${esc(title)}</dc:title>" else "") + "<dc:language>$l</dc:language></cp:coreProperties>")
        put("word/_rels/document.xml.rels", HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$R/settings\" Target=\"settings.xml\"/><Relationship Id=\"rId3\" Type=\"$R/numbering\" Target=\"numbering.xml\"/>$rels</Relationships>")
        put("word/settings.xml", HEAD + "<w:settings xmlns:w=\"$W\"><w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>")
        put("word/styles.xml", HEAD + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/>" +
            "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"$l\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val=\"44\"/><w:szCs w:val=\"44\"/></w:rPr></w:style>" +
            heading(1, 32, 360) + heading(2, 28, 240) + heading(3, 24, 240) +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:ind w:left=\"567\" w:right=\"567\"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val=\"404040\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/></w:pPr></w:style>" +
            "</w:styles>")
        put("word/numbering.xml", HEAD + "<w:numbering xmlns:w=\"$W\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num></w:numbering>")
        put("word/document.xml", HEAD + "<w:document xmlns:w=\"$W\" xmlns:r=\"$R\" xmlns:wp=\"$WP\"><w:body>" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>")
        for ((name, bytes) in media) parts += "word/media/$name" to bytes
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in parts) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun para(p: WBlock.Para): String {
        val sb = StringBuilder("<w:p>")
        val ppr = StringBuilder()
        val style = p.style ?: if (p.list != null) "ListParagraph" else null
        if (style != null) ppr.append("<w:pStyle w:val=\"${esc(style)}\"/>")
        when (p.list) {
            "bullet" -> ppr.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>")
            "number" -> ppr.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr>")
        }
        if (p.align != null) ppr.append("<w:jc w:val=\"${esc(p.align)}\"/>")
        if (ppr.isNotEmpty()) sb.append("<w:pPr>").append(ppr).append("</w:pPr>")
        p.checked?.let { sb.append(run(WRun(if (it) "☑ " else "☐ "))) }
        for (r in p.runs) sb.append(run(r))
        return sb.append("</w:p>").toString()
    }

    private fun run(r: WRun): String {
        if (r.text.isEmpty()) return ""
        val rpr = StringBuilder()
        if (r.bold) rpr.append("<w:b/><w:bCs/>")
        if (r.italic) rpr.append("<w:i/><w:iCs/>")
        if (r.strike) rpr.append("<w:strike/>")
        if (r.underline) rpr.append("<w:u w:val=\"single\"/>")
        if (r.highlight) rpr.append("<w:highlight w:val=\"yellow\"/>")
        val sb = StringBuilder("<w:r>")
        if (rpr.isNotEmpty()) sb.append("<w:rPr>").append(rpr).append("</w:rPr>")
        // Line breaks and tabs inside a paragraph as Word keeps them
        val pieces = r.text.split('\n')
        pieces.forEachIndexed { i, line ->
            if (i > 0) sb.append("<w:br/>")
            line.split('\t').forEachIndexed { j, t ->
                if (j > 0) sb.append("<w:tab/>")
                if (t.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(esc(t)).append("</w:t>")
            }
        }
        return sb.append("</w:r>").toString()
    }
}
