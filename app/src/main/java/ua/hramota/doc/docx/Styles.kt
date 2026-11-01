package ua.hramota.doc.docx

/** Character formatting; null means "not set here, inherit". Sizes in points. */
data class RunProps(
    val font: String? = null,
    val fontTheme: String? = null,
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val highlight: Int? = null,
    val caps: Boolean? = null,
    val vertAlign: String? = null,
    val vanish: Boolean? = null,
) {
    /** [other] on top of this: its set values win. */
    fun with(other: RunProps?): RunProps = if (other == null) this else RunProps(
        font = other.font ?: if (other.fontTheme != null) null else font,
        fontTheme = other.fontTheme ?: if (other.font != null) null else fontTheme,
        size = other.size ?: size,
        bold = other.bold ?: bold,
        italic = other.italic ?: italic,
        underline = other.underline ?: underline,
        strike = other.strike ?: strike,
        color = other.color ?: color,
        highlight = other.highlight ?: highlight,
        caps = other.caps ?: caps,
        vertAlign = other.vertAlign ?: vertAlign,
        vanish = other.vanish ?: vanish,
    )

    companion object {
        fun parse(n: XmlNode?): RunProps? {
            if (n == null) return null
            val fonts = n.child("rFonts")
            val font = fonts?.let { it.attr("ascii") ?: it.attr("hAnsi") ?: it.attr("cs") }
            val theme = fonts?.let { it.attr("asciiTheme") ?: it.attr("hAnsiTheme") }
            val u = n.child("u")?.attr("val")
            return RunProps(
                font = font,
                fontTheme = if (font == null) theme else null,
                size = n.child("sz")?.attr("val")?.toFloatOrNull()?.div(2f),
                bold = n.child("b").isOn(),
                italic = n.child("i").isOn(),
                underline = if (n.child("u") == null) null else u != "none",
                strike = n.child("strike").isOn() ?: n.child("dstrike").isOn(),
                color = n.child("color")?.attr("val")?.let(::hexColor),
                highlight = n.child("highlight")?.attr("val")?.let(::highlightColor)
                    ?: n.child("shd")?.attr("fill")?.let(::hexColor),
                caps = n.child("caps").isOn(),
                vertAlign = n.child("vertAlign")?.attr("val"),
                vanish = n.child("vanish").isOn(),
            )
        }
    }
}

/** Paragraph formatting; lengths in twips (1/20 pt). */
data class ParaProps(
    val styleId: String? = null,
    val align: String? = null,
    val left: Int? = null,
    val right: Int? = null,
    val firstLine: Int? = null,
    val before: Int? = null,
    val after: Int? = null,
    val beforeAuto: Boolean? = null,
    val afterAuto: Boolean? = null,
    val line: Int? = null,
    val lineRule: String? = null,
    val contextual: Boolean? = null,
    val keepNext: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
    val widowControl: Boolean? = null,
    val numId: String? = null,
    val numLevel: Int? = null,
    val tabs: List<Int>? = null,
    val shading: Int? = null,
) {
    fun with(o: ParaProps?): ParaProps = if (o == null) this else ParaProps(
        styleId = o.styleId ?: styleId,
        align = o.align ?: align,
        left = o.left ?: left,
        right = o.right ?: right,
        firstLine = o.firstLine ?: firstLine,
        before = o.before ?: before,
        after = o.after ?: after,
        beforeAuto = o.beforeAuto ?: beforeAuto,
        afterAuto = o.afterAuto ?: afterAuto,
        line = o.line ?: line,
        lineRule = o.lineRule ?: lineRule,
        contextual = o.contextual ?: contextual,
        keepNext = o.keepNext ?: keepNext,
        pageBreakBefore = o.pageBreakBefore ?: pageBreakBefore,
        widowControl = o.widowControl ?: widowControl,
        numId = o.numId ?: numId,
        numLevel = o.numLevel ?: numLevel,
        tabs = o.tabs ?: tabs,
        shading = o.shading ?: shading,
    )

    companion object {
        fun parse(n: XmlNode?): ParaProps? {
            if (n == null) return null
            val ind = n.child("ind")
            val sp = n.child("spacing")
            val hanging = ind?.attr("hanging")?.toIntOrNull()
            val num = n.child("numPr")
            return ParaProps(
                styleId = n.child("pStyle")?.attr("val"),
                align = n.child("jc")?.attr("val"),
                left = (ind?.attr("left") ?: ind?.attr("start"))?.toIntOrNull(),
                right = (ind?.attr("right") ?: ind?.attr("end"))?.toIntOrNull(),
                firstLine = if (hanging != null) -hanging else ind?.attr("firstLine")?.toIntOrNull(),
                before = sp?.attr("before")?.toIntOrNull(),
                after = sp?.attr("after")?.toIntOrNull(),
                beforeAuto = sp?.attr("beforeAutospacing")?.let { it == "1" || it == "true" },
                afterAuto = sp?.attr("afterAutospacing")?.let { it == "1" || it == "true" },
                line = sp?.attr("line")?.toIntOrNull(),
                lineRule = sp?.let { if (it.attr("line") != null) it.attr("lineRule") ?: "auto" else null },
                contextual = n.child("contextualSpacing").isOn(),
                keepNext = n.child("keepNext").isOn(),
                pageBreakBefore = n.child("pageBreakBefore").isOn(),
                widowControl = n.child("widowControl").isOn(),
                numId = num?.child("numId")?.attr("val"),
                numLevel = num?.child("ilvl")?.attr("val")?.toIntOrNull(),
                tabs = n.child("tabs")?.all("tab")?.filter { it.attr("val") != "clear" }?.mapNotNull { it.attr("pos")?.toIntOrNull() }?.sorted(),
                shading = n.child("shd")?.attr("fill")?.let(::hexColor),
            )
        }
    }
}

/** A paragraph or character style, with the style it is based on. */
class Style(val id: String, val type: String, val basedOn: String?, val name: String?, val para: ParaProps?, val run: RunProps?, val tableBorders: Boolean)

/** styles.xml: document defaults and named styles, resolved through "based on" chains. */
class Styles(
    private val styles: Map<String, Style>,
    val defaultRun: RunProps,
    val defaultPara: ParaProps,
    private val defaultParaStyle: String?,
    private val defaultTableStyle: String?,
) {
    fun paraStyleId(id: String?): String? = id?.takeIf { styles.containsKey(it) } ?: defaultParaStyle

    /** Paragraph and run properties a paragraph style gives, base styles first. */
    fun paragraph(id: String?): Pair<ParaProps, RunProps> {
        var p = ParaProps()
        var r = RunProps()
        for (s in chain(paraStyleId(id))) { p = p.with(s.para); r = r.with(s.run) }
        return p to r
    }

    fun character(id: String?): RunProps {
        var r = RunProps()
        for (s in chain(id)) r = r.with(s.run)
        return r
    }

    fun styleName(id: String?): String? = id?.let { styles[it]?.name }

    /** True when the table style (or the default one) draws borders. */
    fun tableHasBorders(id: String?): Boolean = chain(id ?: defaultTableStyle).any { it.tableBorders }

    private fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id
        while (cur != null && out.size < 20) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    companion object {
        val EMPTY = Styles(emptyMap(), RunProps(size = 10f), ParaProps(), null, null)

        fun parse(root: XmlNode?): Styles {
            if (root == null) return EMPTY
            val defaults = root.child("docDefaults")
            val defRun = RunProps(size = 10f).with(RunProps.parse(defaults?.path("rPrDefault", "rPr")))
            val defPara = ParaProps().with(ParaProps.parse(defaults?.path("pPrDefault", "pPr")))
            val map = HashMap<String, Style>()
            var defaultPara: String? = null
            var defaultTable: String? = null
            for (s in root.all("style")) {
                val id = s.attr("styleId") ?: continue
                val type = s.attr("type") ?: "paragraph"
                if (s.attr("default").let { it == "1" || it == "true" }) {
                    if (type == "paragraph") defaultPara = id
                    if (type == "table") defaultTable = id
                }
                val borders = s.path("tblPr", "tblBorders")?.children?.any { b -> b.attr("val").let { it != null && it != "nil" && it != "none" } } == true
                map[id] = Style(id, type, s.child("basedOn")?.attr("val"), s.child("name")?.attr("val"),
                    ParaProps.parse(s.child("pPr")), RunProps.parse(s.child("rPr")), borders)
            }
            return Styles(map, defRun, defPara, defaultPara, defaultTable)
        }
    }
}

/** One numbering level: how the number looks and where the text starts. */
class NumLevel(val format: String, val text: String, val start: Int, val para: ParaProps?, val run: RunProps?)

/** numbering.xml: list numbering, with counters kept while the document is read. */
class Numbering(private val nums: Map<String, Map<Int, NumLevel>>) {
    private val counters = HashMap<String, IntArray>()

    fun level(numId: String?, lvl: Int): NumLevel? = numId?.let { nums[it]?.get(lvl) }

    /** The label for the next item at [lvl] of list [numId], e.g. "1.", "а)", "•". */
    fun next(numId: String, lvl: Int): String? {
        val levels = nums[numId] ?: return null
        val l = levels[lvl] ?: return null
        val c = counters.getOrPut(numId) { IntArray(9) { i -> (levels[i]?.start ?: 1) - 1 } }
        if (lvl !in 0..8) return null
        c[lvl]++
        for (deeper in lvl + 1..8) c[deeper] = (levels[deeper]?.start ?: 1) - 1
        if (l.format == "bullet") return bullet(l.text)
        if (l.format == "none") return ""
        return Regex("%([1-9])").replace(l.text) { m ->
            val i = m.groupValues[1].toInt() - 1
            val fmt = levels[i]?.format ?: "decimal"
            format(maxOf(c[i], levels[i]?.start ?: 1), fmt)
        }
    }

    companion object {
        val EMPTY = Numbering(emptyMap())

        fun parse(root: XmlNode?): Numbering {
            if (root == null) return EMPTY
            val abstract = HashMap<String, Map<Int, NumLevel>>()
            for (a in root.all("abstractNum")) {
                val id = a.attr("abstractNumId") ?: continue
                abstract[id] = a.all("lvl").associate { l ->
                    (l.attr("ilvl")?.toIntOrNull() ?: 0) to NumLevel(
                        format = l.child("numFmt")?.attr("val") ?: "decimal",
                        text = l.child("lvlText")?.attr("val") ?: "",
                        start = l.child("start")?.attr("val")?.toIntOrNull() ?: 1,
                        para = ParaProps.parse(l.child("pPr")),
                        run = RunProps.parse(l.child("rPr")),
                    )
                }
            }
            val nums = HashMap<String, Map<Int, NumLevel>>()
            for (n in root.all("num")) {
                val id = n.attr("numId") ?: continue
                val levels = abstract[n.child("abstractNumId")?.attr("val")] ?: continue
                val overrides = n.all("lvlOverride").associate { (it.attr("ilvl")?.toIntOrNull() ?: 0) to it.child("startOverride")?.attr("val")?.toIntOrNull() }
                nums[id] = levels.mapValues { (i, l) -> overrides[i]?.let { s -> NumLevel(l.format, l.text, s, l.para, l.run) } ?: l }
            }
            return Numbering(nums)
        }

        /** Symbol-font bullets are private-use characters; show them as ordinary ones. */
        fun bullet(text: String): String = when (text.firstOrNull()) {
            null -> "•"
            '', '·', '' -> if (text.first() == '') "▪" else "•"
            '', '' -> "✓"
            'o' -> "◦"
            '', '-' -> "–"
            else -> if (text.first().code in 0xF000..0xF0FF) "•" else text
        }

        fun format(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> letters(n, 'a')
            "upperLetter" -> letters(n, 'A')
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "russianLower", "ukrainianLower" -> cyrillic(n).lowercase()
            "russianUpper", "ukrainianUpper" -> cyrillic(n)
            "decimalZero" -> if (n < 10) "0$n" else "$n"
            else -> n.toString()
        }

        private fun letters(n: Int, a: Char): String {
            val k = (n - 1).coerceAtLeast(0)
            return (a + k % 26).toString().repeat(k / 26 + 1)
        }

        private const val UK = "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЮЯ"
        private fun cyrillic(n: Int): String {
            val k = (n - 1).coerceAtLeast(0)
            return UK[k % UK.length].toString().repeat(k / UK.length + 1)
        }

        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            return buildString { for (i in v.indices) while (x >= v[i]) { append(s[i]); x -= v[i] } }
        }
    }
}

fun hexColor(v: String): Int? {
    if (v == "auto" || v.length != 6) return null
    return v.toIntOrNull(16)?.let { it or (0xFF shl 24) }
}

fun highlightColor(v: String): Int? = when (v) {
    "yellow" -> 0xFFFFFF00.toInt(); "green" -> 0xFF00FF00.toInt(); "cyan" -> 0xFF00FFFF.toInt()
    "magenta" -> 0xFFFF00FF.toInt(); "blue" -> 0xFF0000FF.toInt(); "red" -> 0xFFFF0000.toInt()
    "darkBlue" -> 0xFF000080.toInt(); "darkCyan" -> 0xFF008080.toInt(); "darkGreen" -> 0xFF008000.toInt()
    "darkMagenta" -> 0xFF800080.toInt(); "darkRed" -> 0xFF800000.toInt(); "darkYellow" -> 0xFF808000.toInt()
    "darkGray" -> 0xFF808080.toInt(); "lightGray" -> 0xFFC0C0C0.toInt(); "black" -> 0xFF000000.toInt()
    else -> null
}
