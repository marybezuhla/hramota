package ua.hramota.doc.docx

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * A small XML element: local name, attributes by local name, children and text.
 * DOCX parts use one main namespace, so local names are enough here.
 */
class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>(2)
    var text: String = ""

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlNode> = children.filter { it.name == name }
    fun attr(name: String): String? = attrs[name]

    /** Descendant by a path of local names, e.g. path("rPr", "sz"). */
    fun path(vararg names: String): XmlNode? {
        var n: XmlNode? = this
        for (s in names) n = n?.child(s)
        return n
    }

    /** First descendant at any depth with this local name (no recursion: depth is up to the file). */
    fun find(name: String): XmlNode? {
        val stack = ArrayDeque<XmlNode>()
        for (i in children.indices.reversed()) stack.addLast(children[i])
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.name == name) return n
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return null
    }
}

/** Elements whose text is kept: document text, field codes, document properties, and the values saved with charts. */
private val TEXT_ELEMENTS = setOf("t", "instrText", "delText", "creator", "lastModifiedBy", "title", "v", "formatCode", "f")

object Xml {
    /** Office parts never declare a DOCTYPE; one is refused, so entity tricks cannot expand. */
    fun checkNoDoctype(bytes: ByteArray, whole: Boolean = true) {
        // A DOCTYPE can only come before the root element: everything up to it is looked at, past
        // comments and processing instructions (a "<a" inside a comment is not the root)
        fun at(i: Int, s: String) = i + s.length <= bytes.size && String(bytes, i, s.length, Charsets.ISO_8859_1).equals(s, ignoreCase = true)
        fun find(from: Int, s: String): Int { var j = from; while (j <= bytes.size - s.length) { if (at(j, s)) return j; j++ }; return -1 }
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == '<'.code.toByte()) {
                when {
                    at(i, "<!--") -> { val e = find(i + 4, "-->"); if (e < 0) break; i = e + 3; continue }
                    at(i, "<?") -> { val e = find(i + 2, "?>"); if (e < 0) break; i = e + 2; continue }
                    at(i, "<!DOCTYPE") || at(i, "<!ENTITY") -> throw java.io.IOException("DOCTYPE is not allowed")
                    bytes[i + 1].toInt().toChar().isLetter() -> return
                }
            }
            i++
        }
        // The root was not reached in what was looked at (a part streamed, with a long beginning): not read
        if (!whole) throw java.io.IOException("No root element near the start")
    }

    /**
     * [input] after its start was checked for a DOCTYPE (for parts streamed, not read whole): the
     * parser could otherwise expand entities the file defines.
     */
    fun guarded(input: InputStream): InputStream {
        val b = java.io.BufferedInputStream(input, 64 * 1024)
        b.mark(64 * 1024)
        val head = ByteArray(64 * 1024)
        var n = 0
        while (n < head.size) { val r = b.read(head, n, head.size - n); if (r < 0) break; n += r }
        checkNoDoctype(head.copyOf(n), whole = n < head.size)
        b.reset()
        return b
    }

    fun parser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply { setInput(input, "UTF-8") }

    /** Reads the whole part as a tree (for small parts: styles, numbering, theme, rels). */
    fun tree(input: InputStream): XmlNode? {
        val p = parser(input)
        while (true) when (p.next()) {
            XmlPullParser.START_TAG -> return subtree(p)
            XmlPullParser.END_DOCUMENT -> return null
        }
    }

    /** Reads the element the parser is on (at START_TAG) with everything inside it. */
    fun subtree(p: XmlPullParser): XmlNode {
        val root = node(p)
        val stack = ArrayList<XmlNode>().apply { add(root) }
        while (stack.isNotEmpty()) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val n = node(p)
                    stack.last().children += n
                    stack += n
                }
                XmlPullParser.TEXT -> {
                    val top = stack.last()
                    // Only text elements keep their text; whitespace between elements is noise
                    if (top.name in TEXT_ELEMENTS) top.text += p.text
                }
                XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
                XmlPullParser.END_DOCUMENT -> return root
            }
        }
        return root
    }

    private fun node(p: XmlPullParser): XmlNode {
        val attrs = if (p.attributeCount == 0) emptyMap() else HashMap<String, String>(p.attributeCount * 2).apply {
            for (i in 0 until p.attributeCount) put(p.getAttributeName(i), p.getAttributeValue(i))
        }
        return XmlNode(p.name, attrs)
    }
}

/** OOXML on/off value: present with no value, "1", "true" or "on" means on. */
fun XmlNode?.isOn(): Boolean? {
    if (this == null) return null
    return when (attr("val")) { null, "1", "true", "on" -> true; else -> false }
}
