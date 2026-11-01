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

/** Elements whose text is kept: document text, field codes, and document properties. */
private val TEXT_ELEMENTS = setOf("t", "instrText", "delText", "creator", "lastModifiedBy", "title")

object Xml {
    /** Office parts never declare a DOCTYPE; one is refused, so entity tricks cannot expand. */
    fun checkNoDoctype(bytes: ByteArray) {
        // A DOCTYPE can only come before the root element: look at everything up to it
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == '<'.code.toByte()) {
                val next = bytes[i + 1].toInt().toChar()
                if (next.isLetter()) return
                if (next == '!' && String(bytes, i, minOf(9, bytes.size - i), Charsets.ISO_8859_1).equals("<!DOCTYPE", ignoreCase = true)) {
                    throw java.io.IOException("DOCTYPE is not allowed")
                }
            }
            i++
        }
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
