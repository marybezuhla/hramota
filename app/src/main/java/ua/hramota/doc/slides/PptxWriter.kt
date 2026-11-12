package ua.hramota.doc.slides

import ua.hramota.doc.Templates
import java.io.OutputStream
import kotlin.math.roundToLong

/**
 * An old PowerPoint presentation (PPT, as read) written as PPTX: every slide with what was read of it,
 * shapes and text boxes where they were, with their fill, outline, text and its look, and pictures.
 * Placeholders become ordinary text boxes (the old file's own designs are not carried over), and what
 * was not read (animations, notes, charts, embedded objects) is not there.
 */
object PptxWriter {
    private const val EMU = 12700.0

    fun write(deck: Deck, out: OutputStream, lang: String) {
        val media = HashMap<String, ByteArray>()
        val names = HashMap<String, String>()
        val slides = deck.slides.map { slide ->
            val pictures = LinkedHashMap<String, String>()
            val b = StringBuilder()
            b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            b.append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
            b.append("<p:cSld><p:bg><p:bgPr>").append(fill(slide.background)).append("<a:effectLst/></p:bgPr></p:bg><p:spTree>").append(Templates.GROUP)
            var id = 2
            for (s in slide.shapes) {
                when (s) {
                    is SlideShape.Picture -> {
                        val bytes = deck.media[s.key] ?: continue
                        val ext = extension(bytes) ?: continue
                        val file = names.getOrPut(s.key) { "image${names.size + 1}.$ext".also { media[it] = bytes } }
                        val rel = pictures.getOrPut(file) { "rIdP${pictures.size + 1}" }
                        b.append("<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
                        b.append("<p:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
                        b.append("<p:spPr>").append(xfrm(s)).append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
                    }
                    is SlideShape.Box -> box(b, s, id, lang)
                }
                id++
            }
            b.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
            Templates.PresentationSlide(b.toString(), layout = 2, pictures = pictures.entries.map { (file, rel) -> rel to file })
        }
        out.write(Templates.presentation(lang, (deck.width * EMU).roundToLong(), (deck.height * EMU).roundToLong(), slides, media))
        out.flush()
    }

    private fun box(b: StringBuilder, s: SlideShape.Box, id: Int, lang: String) {
        val text = s.paragraphs.any { p -> p.runs.any { it.text.isNotEmpty() } }
        b.append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Shape $id\"/><p:cNvSpPr")
        if (text && s.fill == null && s.line == null) b.append(" txBox=\"1\"")
        b.append("/><p:nvPr/></p:nvSpPr><p:spPr>").append(xfrm(s))
        val geom = when (s.geometry) { "ellipse", "roundRect", "line" -> s.geometry; else -> "rect" }
        b.append("<a:prstGeom prst=\"$geom\"><a:avLst/></a:prstGeom>")
        b.append(s.fill?.let { fill(it) } ?: "<a:noFill/>")
        if (s.line != null) b.append("<a:ln w=\"${(s.lineWidth * EMU).roundToLong()}\">").append(fill(s.line)).append("</a:ln>") else b.append("<a:ln><a:noFill/></a:ln>")
        b.append("</p:spPr><p:txBody>")
        val i = s.inset
        val anchor = when (s.anchor) { "center" -> "ctr"; "bottom" -> "b"; else -> "t" }
        b.append("<a:bodyPr wrap=\"${if (s.wrap) "square" else "none"}\" lIns=\"${e(i[0])}\" tIns=\"${e(i[1])}\" rIns=\"${e(i[2])}\" bIns=\"${e(i[3])}\" anchor=\"$anchor\" rtlCol=\"0\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
        val l = esc(lang)
        if (s.paragraphs.isEmpty()) b.append("<a:p><a:endParaRPr lang=\"$l\"/></a:p>")
        for (p in s.paragraphs) {
            b.append("<a:p><a:pPr algn=\"").append(when (p.align) { "center" -> "ctr"; "right" -> "r"; "justify" -> "just"; else -> "l" }).append('"')
            b.append(" marL=\"${e(p.marginLeft)}\" indent=\"${e(p.indent)}\">")
            if (p.lineExact != null) b.append("<a:lnSpc><a:spcPts val=\"${(p.lineExact * 100).roundToLong()}\"/></a:lnSpc>")
            else if (p.lineSpacing != 1f) b.append("<a:lnSpc><a:spcPct val=\"${(p.lineSpacing * 100000).roundToLong()}\"/></a:lnSpc>")
            b.append("<a:spcBef><a:spcPts val=\"${(p.spaceBefore * 100).roundToLong()}\"/></a:spcBef><a:spcAft><a:spcPts val=\"${(p.spaceAfter * 100).roundToLong()}\"/></a:spcAft>")
            val bullet = p.bullet
            if (bullet == null) b.append("<a:buNone/>") else {
                p.bulletColor?.let { b.append("<a:buClr>").append(color(it)).append("</a:buClr>") }
                p.bulletSize?.let { b.append("<a:buSzPts val=\"${(it * 100).roundToLong().coerceIn(100, 400000)}\"/>") }
                b.append("<a:buChar char=\"${esc(bullet.take(2))}\"/>")
            }
            b.append("</a:pPr>")
            for (r in p.runs) {
                if (r.text.isEmpty()) continue
                val rPr = StringBuilder("<a:rPr lang=\"$l\" sz=\"${(r.size * 100).roundToLong().coerceIn(100, 400000)}\"")
                if (r.bold) rPr.append(" b=\"1\"")
                if (r.italic) rPr.append(" i=\"1\"")
                if (r.underline) rPr.append(" u=\"sng\"")
                if (r.strike) rPr.append(" strike=\"sngStrike\"")
                if (r.baseline != 0f) rPr.append(" baseline=\"${(r.baseline * 1000).roundToLong()}\"")
                rPr.append(" dirty=\"0\">").append(fill(r.color))
                r.font?.let { rPr.append("<a:latin typeface=\"${esc(it)}\"/><a:cs typeface=\"${esc(it)}\"/>") }
                rPr.append("</a:rPr>")
                // A line break within the paragraph stays one
                for ((k, line) in clean(r.text).split('\n').withIndex()) {
                    if (k > 0) b.append("<a:br>").append(rPr).append("</a:br>")
                    if (line.isNotEmpty()) b.append("<a:r>").append(rPr).append("<a:t>").append(esc(line)).append("</a:t></a:r>")
                }
            }
            b.append("<a:endParaRPr lang=\"$l\" sz=\"${(p.emptySize * 100).roundToLong().coerceIn(100, 400000)}\"/></a:p>")
        }
        b.append("</p:txBody></p:sp>")
    }

    private fun e(points: Float) = (points * EMU).roundToLong()

    private fun xfrm(s: SlideShape): String {
        val rot = if (s.rotation != 0f) " rot=\"${(s.rotation * 60000).roundToLong()}\"" else ""
        return "<a:xfrm$rot><a:off x=\"${e(s.x)}\" y=\"${e(s.y)}\"/><a:ext cx=\"${e(s.w).coerceAtLeast(0)}\" cy=\"${e(s.h).coerceAtLeast(0)}\"/></a:xfrm>"
    }

    private fun color(argb: Int): String {
        val rgb = "%06X".format(argb and 0xFFFFFF)
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) "<a:srgbClr val=\"$rgb\"/>" else "<a:srgbClr val=\"$rgb\"><a:alpha val=\"${alpha * 100000 / 255}\"/></a:srgbClr>"
    }

    private fun fill(argb: Int) = "<a:solidFill>${color(argb)}</a:solidFill>"

    /** The kind of picture file, by its first bytes (only those PowerPoint shows). */
    private fun extension(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
        b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
        b.size > 44 && b[0] == 1.toByte() && b[1] == 0.toByte() && b[40] == ' '.code.toByte() && b[41] == 'E'.code.toByte() -> "emf"
        b.size > 4 && b[0] == 0xD7.toByte() && b[1] == 0xCD.toByte() -> "wmf"
        else -> null
    }

    /** Characters XML cannot hold are left out; line breaks stay (written as breaks). */
    private fun clean(s: String) = s.filter { it == '\t' || it == '\n' || it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
