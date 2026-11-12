package ua.hramota.doc.slides.edit

import ua.hramota.doc.Templates
import ua.hramota.doc.docx.edit.Elem
import ua.hramota.doc.docx.edit.XmlScan
import java.io.IOException

/**
 * A presentation's package as far as adding, taking out and moving slides goes: the slide list, the
 * links between parts (all of them), the list of part types and the layouts. Nothing here is written;
 * [restructure] gives the parts that change.
 */
internal class Pack(
    private val pres: String,
    /** The slide list's entries in order; [Entry.shown] is the slide's number in the editor (null: hidden). */
    private val entries: List<Entry>,
    val shown: List<Shown>,
    val names: Set<String>,
    /** Links (every .rels), the list of part types and the layouts, as text. */
    private val texts: Map<String, String>,
) {
    class Entry(val raw: String, val shown: Int?)
    class Shown(val path: String, val rid: String, val id: Long)

    sealed interface Slide {
        /** A slide as opened (its number in the editor). */
        class Kept(val index: Int) : Slide
        /** A slide added: its part and the layout it follows. */
        class New(val path: String, val layout: String) : Slide
    }

    class Result(val parts: Map<String, String>, val created: Set<String>, val drop: Set<String>)

    val lowerNames: Set<String> = names.mapTo(HashSet()) { it.lowercase() }

    private class Rel(val id: String, val type: String, val target: String?, val start: Int, val end: Int)

    /** The links file of a part ("ppt/slides/slide1.xml" → "ppt/slides/_rels/slide1.xml.rels"; the package's own for ""). */
    private fun relsOf(part: String): String =
        if (part.isEmpty()) "_rels/.rels" else part.substringBeforeLast('/', "").let { d -> (if (d.isEmpty()) "" else "$d/") + "_rels/" + part.substringAfterLast('/') + ".rels" }

    private fun text(name: String): String? = texts[name] ?: texts.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The links in a .rels file, their targets as part names (null for links outside the package). */
    private fun rels(relsName: String, xml: String? = text(relsName)): List<Rel> {
        xml ?: return emptyList()
        val root = XmlScan.children(xml, 0, xml.length).firstOrNull() ?: return emptyList()
        val dir = relsName.substringBeforeLast("/_rels/", "").let { if (relsName.startsWith("_rels/")) "" else it }
        return XmlScan.children(xml, root).filter { it.local == "Relationship" }.map { e ->
            val t = e.attrs["Target"]
            val inside = t != null && e.attrs["TargetMode"] != "External" && !t.contains("://")
            Rel(e.attrs["Id"] ?: "", e.attrs["Type"] ?: "", if (inside) resolve(dir, t!!) else null, e.start, e.end)
        }
    }

    private fun resolve(dir: String, target: String): String {
        val t = target.substringBefore('#')
        if (t.startsWith("/")) return t.removePrefix("/")
        val out = ArrayList<String>()
        for (seg in ((if (dir.isEmpty()) "" else "$dir/") + t).split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out += seg
        }
        return out.joinToString("/")
    }

    private fun relative(fromDir: String, to: String): String {
        val a = fromDir.split('/').filter { it.isNotEmpty() }
        val b = to.split('/')
        var k = 0
        while (k < a.size && k < b.size - 1 && a[k] == b[k]) k++
        return "../".repeat(a.size - k) + b.drop(k).joinToString("/")
    }

    private fun layoutOf(slidePath: String): String? = rels(relsOf(slidePath)).firstOrNull { it.type.endsWith("/slideLayout") }?.target
    private fun masterOf(layout: String): String? = rels(relsOf(layout)).firstOrNull { it.type.endsWith("/slideMaster") }?.target
    private fun layoutType(layout: String): String? = text(layout)?.let { x -> XmlScan.children(x, 0, x.length).firstOrNull { it.local == "sldLayout" }?.attrs?.get("type") }

    /**
     * The layout for a slide added after [after] (a slide's part): that slide's own when it is one for
     * text ("Title and Content", "Title and Text"), else the one of these its design has.
     */
    fun layoutFor(after: String?): String? {
        val layouts = texts.keys.filter { it.lowercase().startsWith("ppt/slidelayouts/") && it.lowercase().endsWith(".xml") && "/_rels/" !in it.lowercase() }.sorted()
        val own = (after ?: shown.firstOrNull()?.path)?.let(::layoutOf)?.takeIf { it in layouts }
        if (own != null && layoutType(own) in setOf("obj", "tx")) return own
        val master = own?.let(::masterOf)
        for (type in listOf("obj", "tx")) {
            layouts.firstOrNull { layoutType(it) == type && (master == null || masterOf(it) == master) }?.let { return it }
        }
        for (type in listOf("obj", "tx")) layouts.firstOrNull { layoutType(it) == type }?.let { return it }
        return own ?: layouts.firstOrNull()
    }

    /** An empty slide following [layout]: its title and its first text placeholder, nothing else. */
    fun newSlideXml(layout: String, lang: String): String {
        val x = text(layout) ?: throw IOException("No layout")
        val phs = ArrayList<Elem>()
        fun find(e: Elem) {
            for (k in XmlScan.children(x, e)) {
                if (k.local == "ph") phs += k
                if (!k.empty && k.local != "txBody" && k.local != "spPr") find(k)
            }
        }
        XmlScan.children(x, 0, x.length).firstOrNull { it.local == "sldLayout" }?.let(::find)
        val title = phs.firstOrNull { it.attrs["type"] == "title" || it.attrs["type"] == "ctrTitle" }
        val body = phs.firstOrNull { it.attrs["type"] in setOf(null, "body", "obj", "subTitle") && it !== title }
        fun ph(e: Elem) = "<p:ph" + listOfNotNull(e.attrs["type"]?.let { "type" to it }, e.attrs["idx"]?.let { "idx" to it })
            .joinToString("") { (k, v) -> " $k=\"${XmlScan.escape(v)}\"" } + "/>"
        val shapes = StringBuilder()
        shapes.append(Templates.placeholder(2, "Title 1", title?.let(::ph) ?: "<p:ph type=\"title\"/>", lang))
        body?.let { shapes.append(Templates.placeholder(3, "Content Placeholder 2", ph(it), lang)) }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<p:sld xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:cSld><p:spTree>" + Templates.GROUP + shapes +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private val owned = HashMap<String, Set<String>>()

    /** What belongs to a slide only (lower case): the slide, its notes and comments. */
    @Synchronized fun ownParts(slide: String): Set<String> = owned.getOrPut(slide) {
        (setOf(slide) + rels(relsOf(slide)).filter { r -> r.type.endsWith("/notesSlide") || r.type.contains("comments", ignoreCase = true) }.mapNotNull { it.target })
            .mapTo(HashSet()) { it.lowercase() }
    }

    /** The part a links file belongs to ("" for the package's own). */
    private fun partOfRels(r: String): String =
        if (r.startsWith("_rels/")) "" else r.substringBeforeLast("/_rels/", "").let { d -> (if (d.isEmpty()) "" else "$d/") + r.substringAfterLast('/').removeSuffix(".rels") }

    /** For each part (lower case), the parts whose links lead to it; null if a links file cannot be read. */
    private val incoming: Map<String, Set<String>>? by lazy {
        try {
            val m = HashMap<String, HashSet<String>>()
            for (r in texts.keys) if (r.lowercase().endsWith(".rels")) {
                val from = partOfRels(r).lowercase()
                for (rel in rels(r)) rel.target?.let { m.getOrPut(it.lowercase()) { HashSet() } += from }
            }
            m
        } catch (e: Exception) { null }
    }

    /**
     * The parts (lower case) that link to a slide from elsewhere: another slide with a link to go to it,
     * say. Not counted: the slide list itself, the slide's own notes and comments, and the outline view's
     * list of slides (taken care of when saving). Null when the links cannot all be read.
     */
    fun linkSources(slide: String): Set<String>? {
        val all = incoming ?: return null
        val own = try { ownParts(slide) } catch (e: Exception) { return null }
        return all[slide.lowercase()].orEmpty().filterTo(HashSet()) { it !in own && it != "ppt/presentation.xml" && it != VIEW_PROPS }
    }

    /** Whether a new part could take this name: no file there, nothing listed for it, no links file of it. */
    fun isFree(path: String): Boolean {
        val l = path.lowercase()
        return l !in lowerNames && relsOf(path).lowercase() !in lowerNames && l !in overrides
    }

    /** Part names the list of part types has an entry for (lower case). */
    private val overrides: Set<String> by lazy {
        val ct = texts.entries.firstOrNull { it.key.equals("[Content_Types].xml", ignoreCase = true) }?.value ?: return@lazy emptySet()
        try {
            val root = XmlScan.children(ct, 0, ct.length).first()
            XmlScan.children(ct, root).filter { it.local == "Override" }.mapNotNull { it.attrs["PartName"]?.removePrefix("/")?.lowercase() }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    /** Every part reached through links from the package's start, with [override] for links files that change. */
    private fun reachable(override: Map<String, String>): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        fun linksOf(part: String): List<Rel> { val r = relsOf(part); return rels(r, override[r] ?: text(r)) }
        for (r in linksOf("")) r.target?.let { if (seen.add(it.lowercase())) queue += it }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (r in linksOf(p)) r.target?.let { if (seen.add(it.lowercase())) queue += it }
        }
        return seen
    }

    fun restructure(order: List<Slide>): Result {
        val out = HashMap<String, String>()
        val created = HashSet<String>()
        val presRelsName = texts.keys.firstOrNull { it.equals("ppt/_rels/presentation.xml.rels", ignoreCase = true) } ?: throw IOException("No links")
        val presRels = texts[presRelsName]!!
        val kept = order.filterIsInstance<Slide.Kept>().map { it.index }.toSet()
        val removed = shown.indices.filter { it !in kept }

        // The slide list: hidden slides go with the slide before them (those after a slide taken out, to the end)
        val idOf = Regex("\\bid\\s*=\\s*[\"'](\\d+)[\"']")
        val sample = entries.first { it.shown != null }.raw
        val tag = Regex("^<([\\w.-]+:)?sldId\\b").find(sample)?.groupValues?.get(1) ?: "p:"
        val rAttr = Regex("\\s([\\w.-]+):id\\s*=").find(sample)?.groupValues?.get(1) ?: "r"
        val leading = entries.takeWhile { it.shown == null }.map { it.raw }
        val groups = HashMap<Int, MutableList<String>>()
        var current: MutableList<String>? = null
        for (e in entries.drop(leading.size)) {
            if (e.shown != null) { current = mutableListOf(e.raw); groups[e.shown] = current } else current!! += e.raw
        }
        val ids = entries.mapNotNull { idOf.find(it.raw)?.groupValues?.get(1)?.toLongOrNull() }
        var nextId = maxOf(255L, ids.maxOrNull() ?: 255L) + 1
        val relIds = rels(presRelsName).map { it.id }.toHashSet()
        var nextRel = 1
        val list = ArrayList<String>(leading)
        val newRels = StringBuilder()
        // The slide's numeric id in the new order, for its section
        val newIds = HashMap<String, Long>()
        for (s in order) when (s) {
            is Slide.Kept -> list += groups[s.index]!!
            is Slide.New -> {
                while ("rIdHr$nextRel" in relIds) nextRel++
                val rid = "rIdHr$nextRel"; relIds += rid
                if (nextId >= 2147483648L) throw IOException("No slide id left")
                val id = nextId++
                val raw = "<${tag}sldId id=\"$id\" $rAttr:id=\"$rid\"/>"
                list += raw; newIds[raw] = id
                newRels.append("<Relationship Id=\"$rid\" Type=\"$R/slide\" Target=\"${XmlScan.escape(relative("ppt", s.path))}\"/>")
                val slideRels = relsOf(s.path)
                out[slideRels] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"$PKG\">" +
                    "<Relationship Id=\"rId1\" Type=\"$R/slideLayout\" Target=\"${XmlScan.escape(relative(s.path.substringBeforeLast('/'), s.layout))}\"/></Relationships>"
                created += s.path; created += slideRels
            }
        }
        // Hidden slides that came after a slide taken out stay after the slide kept before it (or at the start)
        val orphans = HashMap<Int?, MutableList<String>>()
        for (r in removed) {
            val before = (r - 1 downTo 0).firstOrNull { it in kept }
            orphans.getOrPut(before) { ArrayList() } += groups[r]!!.drop(1)
        }
        if (orphans.isNotEmpty()) {
            val withOrphans = ArrayList<String>(leading)
            orphans[null]?.let { withOrphans += it }
            var at = leading.size
            for (s in order) {
                val n = when (s) { is Slide.Kept -> groups[s.index]!!.size; is Slide.New -> 1 }
                withOrphans += list.subList(at, at + n); at += n
                if (s is Slide.Kept) orphans[s.index]?.let { withOrphans += it }
            }
            list.clear(); list += withOrphans
        }

        var p = pres
        val root = XmlScan.children(p, 0, p.length).first { it.local == "presentation" }
        val lst = XmlScan.children(p, root).first { it.local == "sldIdLst" }
        p = p.substring(0, lst.openEnd) + list.joinToString("") + p.substring(lst.closeStart)
        // Custom shows no longer list a slide taken out
        for (r in removed) {
            val rid = Regex.escape(shown[r].rid)
            p = p.replace(Regex("<([\\w.-]+:)?sld\\s+[\\w.-]+:id\\s*=\\s*[\"']$rid[\"']\\s*/>"), "")
        }
        p = sections(p, list.map { raw -> newIds[raw] ?: idOf.find(raw)?.groupValues?.get(1)?.toLongOrNull() })
        out["ppt/presentation.xml"] = p

        // The outline view's list of slides (PowerPoint keeps one): slides taken out leave it
        texts.keys.firstOrNull { it.equals(relsOf(VIEW_PROPS_NAME), ignoreCase = true) }?.let { vr ->
            val vx = texts[vr]!!
            val removedPaths = removed.map { shown[it].path.lowercase() }.toSet()
            val gone = rels(vr).filter { it.target?.lowercase() in removedPaths }
            if (gone.isEmpty()) return@let
            val b = StringBuilder(); var at = 0
            for (r in gone.sortedBy { it.start }) { b.append(vx, at, r.start); at = r.end }
            b.append(vx, at, vx.length)
            out[vr] = b.toString()
            val viewName = texts.keys.firstOrNull { it.equals(VIEW_PROPS_NAME, ignoreCase = true) }
            if (viewName != null) {
                var v = texts[viewName]!!
                for (r in gone) v = v.replace(Regex("<([\\w.-]+:)?sld\\s+[\\w.-]+:id\\s*=\\s*[\"']${Regex.escape(r.id)}[\"'][^>]*/>"), "")
                out[viewName] = v
            }
        }

        // Links of the presentation: those to slides taken out gone, the new ones added
        val removedRids = removed.map { shown[it].rid }.toSet()
        val sb = StringBuilder()
        var pos = 0
        for (r in rels(presRelsName).filter { it.id in removedRids }.sortedBy { it.start }) { sb.append(presRels, pos, r.start); pos = r.end }
        sb.append(presRels, pos, presRels.length)
        val close = sb.lastIndexOf("</")
        if (close < 0) throw IOException("Bad links")
        sb.insert(close, newRels)
        out[presRelsName] = sb.toString()

        // What only the slides taken out used (their notes, comments, pictures), found through the links
        val before = reachable(emptyMap())
        val after = reachable(out)
        val drop = HashSet<String>()
        for (name in names) {
            val l = name.lowercase()
            if (l in before && l !in after) { drop += name; names.firstOrNull { it.equals(relsOf(name), ignoreCase = true) }?.let { drop += it } }
        }

        // The list of part types
        val ctName = texts.keys.firstOrNull { it.equals("[Content_Types].xml", ignoreCase = true) } ?: throw IOException("No content types")
        val ct = texts[ctName]!!
        val ctRoot = XmlScan.children(ct, 0, ct.length).first()
        val prefix = ctRoot.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val dropLower = drop.map { it.lowercase() }.toSet()
        val c = StringBuilder()
        pos = 0
        for (e in XmlScan.children(ct, ctRoot)) {
            if (e.local == "Override" && e.attrs["PartName"]?.removePrefix("/")?.lowercase() in dropLower) { c.append(ct, pos, e.start); pos = e.end }
        }
        c.append(ct, pos, ctRoot.closeStart)
        for (s in order) if (s is Slide.New) c.append("<${prefix}Override PartName=\"/${XmlScan.escape(s.path)}\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        c.append(ct, ctRoot.closeStart, ct.length)
        out[ctName] = c.toString()
        return Result(out, created, drop)
    }

    /**
     * Sections (PowerPoint's groups of slides) follow the new order: a slide added joins the section of
     * the slide before it. If the order breaks a section in two, the sections are taken out (the slides
     * stay); PowerPoint would call such a file broken.
     */
    private fun sections(p: String, order: List<Long?>): String {
        val m = Regex("<([\\w.-]+):sectionLst\\b[^>]*>(.*?)</\\1:sectionLst>", RegexOption.DOT_MATCHES_ALL).find(p) ?: return p
        val pre = m.groupValues[1]
        val sectionRe = Regex("<$pre:section\\b[^>]*?(/>|>(.*?)</$pre:section>)", RegexOption.DOT_MATCHES_ALL)
        val sections = sectionRe.findAll(m.value).toList()
        val idRe = Regex("<$pre:sldId\\s+id\\s*=\\s*[\"'](\\d+)[\"']\\s*/>")
        val sectionOf = HashMap<Long, Int>()
        for ((k, s) in sections.withIndex()) for (i in idRe.findAll(s.value)) i.groupValues[1].toLongOrNull()?.let { sectionOf[it] = k }
        // Each slide's section in the new order (a slide added: the one before it, or the first section)
        val assigned = ArrayList<Pair<Long, Int>>()
        for (id in order) {
            id ?: continue
            assigned += id to (sectionOf[id] ?: assigned.lastOrNull()?.second ?: 0)
        }
        val seq = assigned.map { it.second }
        val runs = seq.zipWithNext().count { (a, b) -> a != b } + 1
        val ok = sections.isNotEmpty() && seq.isNotEmpty() && seq.distinct().size == runs && seq.distinct() == seq.distinct().sorted()
        if (!ok) {
            // The whole extension that holds the sections goes
            val ext = Regex("<([\\w.-]+:)?ext\\s+uri\\s*=\\s*[\"']\\{521415D9-36F7-43E2-AB2F-B90AF26B5E84\\}[\"']\\s*>.*?</\\1?ext>", RegexOption.DOT_MATCHES_ALL)
            return p.replace(ext, "")
        }
        val byId = assigned.groupBy({ it.second }, { it.first })
        val rebuilt = StringBuilder()
        var pos = 0
        val inner = m.value
        for ((k, s) in sections.withIndex()) {
            rebuilt.append(inner, pos, s.range.first)
            val list = byId[k].orEmpty().joinToString("") { "<$pre:sldId id=\"$it\"/>" }
            val body = s.value
            val lst = Regex("<$pre:sldIdLst\\s*/>|<$pre:sldIdLst\\b[^>]*>.*?</$pre:sldIdLst>", RegexOption.DOT_MATCHES_ALL)
            rebuilt.append(
                if (lst.containsMatchIn(body)) body.replaceFirst(lst, Regex.escapeReplacement("<$pre:sldIdLst>$list</$pre:sldIdLst>"))
                else body,
            )
            pos = s.range.last + 1
        }
        rebuilt.append(inner, pos, inner.length)
        return p.substring(0, m.range.first) + rebuilt + p.substring(m.range.last + 1)
    }

    companion object {
        private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val VIEW_PROPS_NAME = "ppt/viewProps.xml"
        private const val VIEW_PROPS = "ppt/viewprops.xml"
    }
}
