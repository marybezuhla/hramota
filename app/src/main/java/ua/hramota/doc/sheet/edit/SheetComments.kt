package ua.hramota.doc.sheet.edit

import ua.hramota.doc.docx.edit.XmlScan
import ua.hramota.doc.sheet.columnName
import java.io.IOException

/**
 * Notes added to cells (Excel's comments): the comments part with the words, and the old-style
 * drawing (VML) Excel needs to show the little yellow box. Both are added to when a sheet has them
 * already; otherwise made anew with the links to them.
 */
internal object SheetComments {
    const val COMMENTS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments"
    const val VML_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing"
    const val COMMENTS_CONTENT = "application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"
    const val VML_CONTENT = "application/vnd.openxmlformats-officedocument.vmlDrawing"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** One note: the cell and the words. */
    class Note(val row: Int, val col: Int, val text: String)

    /** The comments part with [notes] put in (a cell's note replaced if it had one), by [author]. */
    fun comments(existing: String?, notes: List<Note>, author: String): String {
        val x = existing ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<comments xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><authors></authors><commentList></commentList></comments>"""
        val root = XmlScan.children(x, 0, x.length).firstOrNull { it.local == "comments" } ?: throw IOException("No comments")
        val p = root.name.substringBefore(':', "").let { if (it.isEmpty() || it == root.name) "" else "$it:" }
        val kids = XmlScan.children(x, root)
        val authors = kids.firstOrNull { it.local == "authors" }
        val names = authors?.let { a -> XmlScan.children(x, a).filter { it.local == "author" }.map { if (it.empty) "" else XmlScan.unescape(x.substring(it.openEnd, it.closeStart)) } } ?: emptyList()
        var authorId = names.indexOf(author)
        val edits = ArrayList<Triple<Int, Int, String>>()
        if (authorId < 0) {
            authorId = names.size
            val tag = "<${p}author>${XmlScan.escape(author)}</${p}author>"
            if (authors == null) edits += Triple(root.openEnd, root.openEnd, "<${p}authors>$tag</${p}authors>")
            else if (authors.selfClosing || authors.empty) edits += Triple(authors.start, authors.end, "<${p}authors>$tag</${p}authors>")
            else edits += Triple(authors.closeStart, authors.closeStart, tag)
        }
        val list = kids.firstOrNull { it.local == "commentList" }
        val refs = notes.associateBy { columnName(it.col) + (it.row + 1) }
        // A cell's earlier note goes: one note to a cell
        val had = HashSet<String>()
        if (list != null) for (c in XmlScan.children(x, list)) if (c.local == "comment") {
            val ref = c.attrs["ref"]?.uppercase() ?: continue
            if (ref in refs) { edits += Triple(c.start, c.end, ""); had += ref }
        }
        val added = notes.joinToString("") { n ->
            "<${p}comment ref=\"${columnName(n.col)}${n.row + 1}\" authorId=\"$authorId\"><${p}text><${p}r><${p}t xml:space=\"preserve\">${XmlScan.escape(n.text)}</${p}t></${p}r></${p}text></${p}comment>"
        }
        when {
            list == null -> {
                val at = kids.firstOrNull { it.local == "extLst" }?.start ?: root.closeStart
                edits += Triple(at, at, "<${p}commentList>$added</${p}commentList>")
            }
            list.selfClosing || list.empty -> edits += Triple(list.start, list.end, "<${p}commentList>$added</${p}commentList>")
            else -> edits += Triple(list.closeStart, list.closeStart, added)
        }
        val b = StringBuilder(x)
        for ((s, e, t) in edits.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })) b.replace(s, e, t)
        return b.toString()
    }

    /** The old-style drawing with a hidden box for each of [notes] (made anew when [existing] is null). */
    fun vml(existing: String?, notes: List<Note>, drawingNumber: Int, firstShape: Int): String {
        val start = existing ?: """<xml xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="$drawingNumber"/></o:shapelayout><v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe"><v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/></v:shapetype></xml>"""
        // A box is known by its cell: the boxes of cells given a new note go (one box to a cell)
        val cells = notes.mapTo(HashSet()) { it.row to it.col }
        // One pass over the shapes: those of cells given a new note left out
        val x = StringBuilder(start.length)
        var pos = 0
        while (true) {
            val s = start.indexOf("<v:shape", pos).takeIf { it >= 0 && !start.startsWith("<v:shapetype", it) }
                ?: start.indexOf("<v:shape ", pos).takeIf { it >= 0 } ?: break
            if (start.startsWith("<v:shapetype", s)) { x.append(start, pos, s + 1); pos = s + 1; continue }
            val e = start.indexOf("</v:shape>", s).takeIf { it >= 0 }?.plus("</v:shape>".length) ?: break
            val shape = start.substring(s, e)
            val row = Regex("<x:Row>\\s*(\\d+)").find(shape)?.groupValues?.get(1)?.toIntOrNull()
            val col = Regex("<x:Column>\\s*(\\d+)").find(shape)?.groupValues?.get(1)?.toIntOrNull()
            x.append(start, pos, s)
            if (row == null || col == null || (row to col) !in cells) x.append(shape)
            pos = e
        }
        x.append(start, pos, start.length)
        var next = firstShape
        val shapes = notes.joinToString("") { n ->
            val id = next++
            "<v:shape id=\"_x0000_s$id\" type=\"#_x0000_t202\" style=\"position:absolute;margin-left:59.25pt;margin-top:1.5pt;width:108pt;height:59.25pt;z-index:${id % 1024};visibility:hidden\" fillcolor=\"#ffffe1\" o:insetmode=\"auto\">" +
                "<v:fill color2=\"#ffffe1\"/><v:shadow on=\"t\" color=\"black\" obscured=\"t\"/><v:path o:connecttype=\"none\"/>" +
                "<v:textbox style=\"mso-direction-alt:auto\"><div style=\"text-align:left\"></div></v:textbox>" +
                "<x:ClientData ObjectType=\"Note\"><x:MoveWithCells/><x:SizeWithCells/><x:Anchor>${n.col + 1}, 15, ${n.row}, 10, ${n.col + 3}, 15, ${n.row + 3}, 4</x:Anchor>" +
                "<x:AutoFill>False</x:AutoFill><x:Row>${n.row}</x:Row><x:Column>${n.col}</x:Column></x:ClientData></v:shape>"
        }
        val end = x.lastIndexOf("</xml>")
        if (end < 0) throw IOException("Unusual drawing")
        return x.substring(0, end) + shapes + x.substring(end)
    }

    /** A sheet's relationships with one more ([type] to [target]); returns the text and the new id. */
    fun addRel(rels: String?, type: String, target: String): Pair<String, String> {
        val x = rels ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$PKG_RELS"></Relationships>"""
        val root = XmlScan.children(x, 0, x.length).firstOrNull { it.local == "Relationships" } ?: throw IOException("No relationships")
        val ids = XmlScan.children(x, root).mapNotNull { it.attrs["Id"] }.toSet()
        var n = 1
        while ("rId$n" in ids) n++
        val id = "rId$n"
        val tag = "<Relationship Id=\"$id\" Type=\"$type\" Target=\"${XmlScan.escape(target)}\"/>"
        val text = if (root.selfClosing || root.empty) x.substring(0, root.start) + "<Relationships xmlns=\"$PKG_RELS\">$tag</Relationships>" + x.substring(root.end)
            else x.substring(0, root.closeStart) + tag + x.substring(root.closeStart)
        return text to id
    }

    /** The sheet with its link to the old-style drawing (put where the schema wants it). */
    fun legacyDrawing(sheet: String, rid: String): String {
        val root = XmlScan.children(sheet, 0, sheet.length).first { it.local == "worksheet" }
        val p = root.name.substringBefore(':', "").let { if (it.isEmpty() || it == root.name) "" else "$it:" }
        // The prefix of the relationships namespace (declared on the root if the sheet has none)
        var head = sheet.substring(root.start, root.openEnd)
        val r = root.attrs.entries.firstOrNull { it.value == REL_NS && it.key.startsWith("xmlns:") }?.key?.removePrefix("xmlns:")
            ?: "r".let { cand -> var c = cand; var i = 0; while (root.attrs.containsKey("xmlns:$c")) c = "r${++i}"; head = head.replaceFirst(Regex("^(<[^\\s>/]+)"), "$1 xmlns:$c=\"$REL_NS\""); c }
        val after = setOf("legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst")
        val kids = XmlScan.children(sheet, root)
        val at = kids.firstOrNull { it.local in after }?.start ?: root.closeStart
        return head + sheet.substring(root.openEnd, at) + "<${p}legacyDrawing $r:id=\"$rid\"/>" + sheet.substring(at)
    }

    /** [Content_Types].xml knowing the comments part and VML drawings. */
    fun types(types: String, commentsPath: String?): String {
        val root = XmlScan.children(types, 0, types.length).firstOrNull { it.local == "Types" } ?: throw IOException("No content types")
        val kids = XmlScan.children(types, root)
        val add = StringBuilder()
        if (kids.none { it.local == "Default" && it.attrs["Extension"]?.equals("vml", ignoreCase = true) == true }) add.append("<Default Extension=\"vml\" ContentType=\"$VML_CONTENT\"/>")
        if (commentsPath != null && kids.none { it.local == "Override" && it.attrs["PartName"]?.removePrefix("/") == commentsPath })
            add.append("<Override PartName=\"/$commentsPath\" ContentType=\"$COMMENTS_CONTENT\"/>")
        if (add.isEmpty()) return types
        return types.substring(0, root.closeStart) + add + types.substring(root.closeStart)
    }
}
