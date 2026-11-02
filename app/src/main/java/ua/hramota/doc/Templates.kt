package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * New, empty documents: the smallest files Word and Excel open without complaint, with A4 pages,
 * Calibri text (Carlito on the phone) and the language of the phone set for spell checking.
 */
object Templates {
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, text) in parts) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** An empty Word document: one paragraph, A4 with 2 cm margins (2,5 cm on the left), headings ready. */
    fun docx(lang: String): ByteArray {
        val l = esc(lang)
        fun heading(n: Int, size: Int, before: Int) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        return zip(listOf(
            "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/styles.xml" to HEAD + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/>" +
                "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"$l\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
                heading(1, 32, 360) + heading(2, 28, 240) + heading(3, 24, 240) + "</w:styles>",
            "word/document.xml" to HEAD + "<w:document xmlns:w=\"$W\" xmlns:r=\"$R\"><w:body><w:p/>" +
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
                "</w:body></w:document>",
        ))
    }

    /** An empty workbook with one sheet. */
    fun xlsx(sheetName: String): ByteArray = zip(listOf(
        "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
        "_rels/.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
        "xl/_rels/workbook.xml.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$R/styles\" Target=\"styles.xml\"/></Relationships>",
        "xl/workbook.xml" to HEAD + "<workbook xmlns=\"$S\" xmlns:r=\"$R\"><sheets><sheet name=\"${esc(sheetName.take(31).ifBlank { "Sheet1" })}\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr calcId=\"191029\"/></workbook>",
        "xl/styles.xml" to HEAD + "<styleSheet xmlns=\"$S\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>",
        "xl/worksheets/sheet1.xml" to HEAD + "<worksheet xmlns=\"$S\" xmlns:r=\"$R\"><sheetFormatPr defaultRowHeight=\"15\"/><sheetData/></worksheet>",
    ))
}
