package ua.hramota.doc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * New, empty documents: the smallest files Word, Excel and PowerPoint open without complaint, with A4
 * pages (a 16:9 screen for slides), Calibri text (Carlito on the phone) and the language of the phone
 * set for spell checking.
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
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>",
            "_rels/.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to HEAD + "<Relationships xmlns=\"$PKG\"><Relationship Id=\"rId1\" Type=\"$R/styles\" Target=\"styles.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$R/settings\" Target=\"settings.xml\"/></Relationships>",
            // Laid out as by current Word (without this, Word opens the file in compatibility mode)
            "word/settings.xml" to HEAD + "<w:settings xmlns:w=\"$W\"><w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>",
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

    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PML = "application/vnd.openxmlformats-officedocument.presentationml"

    /** A part of the presentation template kept with the code (theme, master and the two layouts). */
    private fun resource(name: String): String =
        Templates::class.java.getResourceAsStream("/ua/hramota/doc/pptx/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    /**
     * An empty presentation: a 16:9 title slide (title and subtitle to fill in), with "Title and Content"
     * ready for the slides added after it. Plain colours, nothing animated.
     */
    fun pptx(lang: String): ByteArray {
        val l = esc(lang)
        fun withLang(x: String) = x.replace("lang=\"uk-UA\"", "lang=\"$l\"")
        fun rels(vararg r: Pair<String, String>) = HEAD + "<Relationships xmlns=\"$PKG\">" +
            r.withIndex().joinToString("") { (i, t) -> "<Relationship Id=\"rId${i + 1}\" Type=\"${t.first}\" Target=\"${t.second}\"/>" } + "</Relationships>"
        return zip(listOf(
            "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$PML.presentation.main+xml\"/>" +
                "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$PML.slideMaster+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$PML.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"$PML.slideLayout+xml\"/>" +
                "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"$PML.slide+xml\"/>" +
                "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                "<Override PartName=\"/ppt/presProps.xml\" ContentType=\"$PML.presProps+xml\"/>" +
                "<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"$PML.viewProps+xml\"/>" +
                "<Override PartName=\"/ppt/tableStyles.xml\" ContentType=\"$PML.tableStyles+xml\"/></Types>",
            "_rels/.rels" to rels("$R/officeDocument" to "ppt/presentation.xml"),
            "ppt/presentation.xml" to HEAD + "<p:presentation xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\" saveSubsetFonts=\"1\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst>" +
                "<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
                "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"$l\"/></a:defPPr></p:defaultTextStyle></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to rels(
                "$R/slideMaster" to "slideMasters/slideMaster1.xml", "$R/slide" to "slides/slide1.xml", "$R/theme" to "theme/theme1.xml",
                "$R/presProps" to "presProps.xml", "$R/viewProps" to "viewProps.xml", "$R/tableStyles" to "tableStyles.xml",
            ),
            "ppt/presProps.xml" to HEAD + "<p:presentationPr xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"/>",
            "ppt/viewProps.xml" to HEAD + "<p:viewPr xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>",
            "ppt/tableStyles.xml" to HEAD + "<a:tblStyleLst xmlns:a=\"$A\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>",
            "ppt/theme/theme1.xml" to resource("theme1.xml"),
            "ppt/slideMasters/slideMaster1.xml" to withLang(resource("slideMaster1.xml")),
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(
                "$R/slideLayout" to "../slideLayouts/slideLayout1.xml", "$R/slideLayout" to "../slideLayouts/slideLayout2.xml", "$R/theme" to "../theme/theme1.xml",
            ),
            "ppt/slideLayouts/slideLayout1.xml" to withLang(resource("slideLayout1.xml")),
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels("$R/slideMaster" to "../slideMasters/slideMaster1.xml"),
            "ppt/slideLayouts/slideLayout2.xml" to withLang(resource("slideLayout2.xml")),
            "ppt/slideLayouts/_rels/slideLayout2.xml.rels" to rels("$R/slideMaster" to "../slideMasters/slideMaster1.xml"),
            "ppt/slides/slide1.xml" to HEAD + "<p:sld xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:cSld><p:spTree>" + GROUP +
                placeholder(2, "Title 1", "<p:ph type=\"ctrTitle\"/>", lang) + placeholder(3, "Subtitle 2", "<p:ph type=\"subTitle\" idx=\"1\"/>", lang) +
                "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
            "ppt/slides/_rels/slide1.xml.rels" to rels("$R/slideLayout" to "../slideLayouts/slideLayout1.xml"),
        ))
    }

    /** The shape tree's own properties, as every slide starts. */
    const val GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    /** An empty placeholder of the layout ([ph]: its p:ph element), one empty paragraph in [lang] to type into. */
    fun placeholder(id: Int, name: String, ph: String, lang: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"${esc(name)}\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>" +
            "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"${esc(lang)}\" dirty=\"0\"/></a:p></p:txBody></p:sp>"
}
