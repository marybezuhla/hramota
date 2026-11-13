package ua.hramota.doc.odf

import org.xmlpull.v1.XmlPullParser
import ua.hramota.doc.docx.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** An element of an OpenDocument part: its local name, attributes by local name, and children (elements and text, in order). */
class ONode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<Any>(2)
    fun attr(n: String): String? = attrs[n]
    val elements: List<ONode> get() = children.filterIsInstance<ONode>()
    fun child(n: String): ONode? = children.firstOrNull { it is ONode && it.name == n } as ONode?
    fun all(n: String): List<ONode> = children.filter { it is ONode && it.name == n }.map { it as ONode }
    /** All text inside, in order. */
    val text: String get() = buildString { fun walk(x: ONode) { for (c in x.children) if (c is String) append(c) else walk(c as ONode) }; walk(this@ONode) }
}

/**
 * OpenDocument files (LibreOffice and others): their parts, the XML read as trees that keep text in
 * order, styles with their parents resolved, and lengths. Limits keep a hostile file from using up memory.
 */
class OdfPackage private constructor(val parts: Map<String, ByteArray>, val media: Map<String, ByteArray>) {
    companion object {
        private const val MAX_PART = 64L * 1024 * 1024
        private const val MAX_MEDIA = 96L * 1024 * 1024
        private const val MAX_NODES = 1_500_000
        private const val MAX_DEPTH = 200

        fun read(input: InputStream): OdfPackage {
            val parts = HashMap<String, ByteArray>(); val media = HashMap<String, ByteArray>()
            var mediaTotal = 0L
            ZipInputStream(input).use { z ->
                var n = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++n > 20_000) throw IOException("Too many entries")
                    val name = e.name.removePrefix("/")
                    when {
                        name == "content.xml" || name == "styles.xml" || name == "meta.xml" -> parts[name] = readLimited(z, MAX_PART)
                        name.startsWith("Pictures/") && !name.endsWith("/") -> {
                            val b = readLimited(z, MAX_PART); mediaTotal += b.size
                            if (mediaTotal <= MAX_MEDIA) media[name] = b
                        }
                    }
                }
            }
            if ("content.xml" !in parts) throw IOException("Not an OpenDocument file")
            parts.values.forEach(Xml::checkNoDoctype)
            return OdfPackage(parts, media)
        }

        private fun readLimited(input: InputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
            while (true) { val k = input.read(buf); if (k < 0) break; total += k; if (total > limit) throw IOException("Part too large"); out.write(buf, 0, k) }
            return out.toByteArray()
        }

        fun tree(bytes: ByteArray): ONode {
            val p = Xml.parser(ByteArrayInputStream(bytes))
            val root = ONode("#root", emptyMap())
            val stack = ArrayList<ONode>().apply { add(root) }
            var nodes = 0
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        if (++nodes > MAX_NODES || stack.size > MAX_DEPTH) throw IOException("Document too large")
                        val attrs = if (p.attributeCount == 0) emptyMap() else HashMap<String, String>().apply { for (i in 0 until p.attributeCount) put(p.getAttributeName(i), p.getAttributeValue(i)) }
                        val n = ONode(p.name, attrs)
                        stack.last().children += n
                        stack += n
                    }
                    XmlPullParser.TEXT -> { val t = p.text; if (!t.isNullOrEmpty()) stack.last().children += t }
                    XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return root
        }
    }

    val content: ONode by lazy { tree(parts.getValue("content.xml")) }
    val styles: ONode? by lazy { parts["styles.xml"]?.let { tree(it) } }

    /** Every style by name (automatic ones of the content over named ones), and defaults by family. */
    val styleMap: Styles by lazy {
        val named = HashMap<String, ONode>(); val defaults = HashMap<String, ONode>(); val lists = HashMap<String, ONode>()
        fun take(container: ONode?) {
            container ?: return
            for (s in container.elements) when (s.name) {
                "style" -> s.attr("name")?.let { named[it] = s }
                "default-style" -> s.attr("family")?.let { defaults[it] = s }
                "list-style" -> s.attr("name")?.let { lists[it] = s }
            }
        }
        val st = styles?.child("document-styles")
        take(st?.child("styles")); take(st?.child("automatic-styles"))
        val doc = content.child("document-content")
        take(doc?.child("automatic-styles"))
        Styles(named, defaults, lists)
    }

    class Styles(val named: Map<String, ONode>, val defaults: Map<String, ONode>, val lists: Map<String, ONode>) {
        /** The properties a style gives (its parents' first, then its own), from all its *-properties elements. */
        fun props(name: String?, family: String): Map<String, String> {
            val out = HashMap<String, String>()
            defaults[family]?.let { d -> for (p in d.elements) out.putAll(p.attrs) }
            val chain = ArrayList<ONode>()
            var n = name; var guard = 0
            while (n != null && guard++ < 30) { val s = named[n] ?: break; chain += s; n = s.attr("parent-style-name") }
            for (s in chain.asReversed()) for (p in s.elements) if (p.name.endsWith("-properties")) out.putAll(p.attrs)
            return out
        }
    }

    /** The page's size and margins in points, from the first master page's layout. */
    fun page(): FloatArray {
        val st = styles?.child("document-styles")
        val master = st?.child("master-styles")?.elements?.firstOrNull { it.name == "master-page" }
        val layoutName = master?.attr("page-layout-name")
        val layout = st?.child("automatic-styles")?.all("page-layout")?.firstOrNull { it.attr("name") == layoutName }
            ?: st?.child("automatic-styles")?.all("page-layout")?.firstOrNull()
        val p = layout?.child("page-layout-properties")?.attrs ?: emptyMap()
        return floatArrayOf(
            length(p["page-width"]) ?: 595f, length(p["page-height"]) ?: 842f,
            length(p["margin-top"]) ?: 56f, length(p["margin-bottom"]) ?: 56f, length(p["margin-left"]) ?: 56f, length(p["margin-right"]) ?: 56f,
        )
    }
}

/** An ODF length ("2cm", "0.5in", "12pt", "10mm", "1pc", "96px") in points; null if unreadable. */
fun length(v: String?): Float? {
    v ?: return null
    val m = Regex("^\\s*(-?[0-9]*\\.?[0-9]+)\\s*([a-z%]*)\\s*$").matchEntire(v) ?: return null
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    val pt = when (m.groupValues[2]) {
        "cm" -> n * 72f / 2.54f; "mm" -> n * 72f / 25.4f; "in", "inch" -> n * 72f; "pt", "" -> n; "pc" -> n * 12f; "px" -> n * 0.75f
        else -> return null
    }
    return pt.takeIf { it.isFinite() }?.coerceIn(-20000f, 20000f)
}

/** An ODF colour ("#3b5b92") as ARGB; null for "transparent" or anything else. */
fun color(v: String?): Int? = v?.trim()?.takeIf { it.length == 7 && it.startsWith("#") }?.substring(1)?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
