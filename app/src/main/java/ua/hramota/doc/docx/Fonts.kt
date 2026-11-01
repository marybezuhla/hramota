package ua.hramota.doc.docx

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle

/**
 * Office fonts and the free fonts with the same letter widths that stand in for them.
 * Line height comes from the office font's own figures (as Word measures it), not from the
 * free font's file, whose vertical metrics may differ.
 */
class Fonts(private val assets: AssetManager?) {
    /** A bundled family and how Word measures a line of the office font it replaces. */
    enum class Family(val file: String, val ascent: Float, val descent: Float, val fallback: Family? = null, val system: String) {
        CARLITO("Carlito", 0.9521f, 0.2686f, system = "sans-serif"),
        TINOS("Tinos", 0.8911f, 0.2163f, system = "serif"),
        CALADEA("Caladea", 0.9502f, 0.2222f, fallback = TINOS, system = "serif"),
        ARIMO("Arimo", 0.9053f, 0.2119f, system = "sans-serif"),
        COUSINE("Cousine", 0.8325f, 0.3003f, system = "monospace");

        /** Height of a single line at size 1, as Word lays it out. */
        val line: Float get() = ascent + descent
    }

    private val cache = HashMap<Long, Typeface>()

    fun typeface(family: Family, bold: Boolean, italic: Boolean): Typeface {
        val key = family.ordinal.toLong() * 4 + (if (bold) 2 else 0) + (if (italic) 1 else 0)
        return synchronized(cache) { cache.getOrPut(key) { load(family, bold, italic) } }
    }

    private fun load(family: Family, bold: Boolean, italic: Boolean): Typeface {
        val fallbackStyle = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        val am = assets ?: return Typeface.create(family.system, fallbackStyle)
        return try {
            val main = FontFamily.Builder(font(am, family, bold, italic)).build()
            val builder = Typeface.CustomFallbackBuilder(main).setSystemFallback(family.system)
            // Caladea has no Cyrillic: Ukrainian letters come from Tinos, the closest serif
            family.fallback?.let { builder.addCustomFallback(FontFamily.Builder(font(am, it, bold, italic)).build()) }
            builder.setStyle(FontStyle(if (bold) FontStyle.FONT_WEIGHT_BOLD else FontStyle.FONT_WEIGHT_NORMAL, if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT))
            builder.build()
        } catch (e: Exception) {
            Typeface.create(family.system, fallbackStyle)
        }
    }

    private fun font(am: AssetManager, family: Family, bold: Boolean, italic: Boolean): Font {
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        return Font.Builder(am, "fonts/${family.file}-$style.ttf").build()
    }

    companion object {
        /** The bundled family for a font named in a document. */
        fun familyFor(name: String?): Family {
            val n = name?.lowercase()?.trim().orEmpty()
            return when {
                n.isEmpty() -> Family.CARLITO
                n.startsWith("calibri") || n == "carlito" || n.startsWith("aptos") || n == "segoe ui" || n == "candara" || n == "corbel" -> Family.CARLITO
                n.startsWith("cambria") || n == "caladea" -> Family.CALADEA
                n.startsWith("times") || n == "tinos" || n.contains("liberation serif") || n == "georgia" || n == "book antiqua" ||
                    n == "garamond" || n == "palatino linotype" || n == "constantia" || n.contains("serif") && !n.contains("sans") -> Family.TINOS
                n.startsWith("courier") || n == "cousine" || n.contains("mono") || n == "consolas" || n == "lucida console" -> Family.COUSINE
                else -> Family.ARIMO
            }
        }
    }
}
