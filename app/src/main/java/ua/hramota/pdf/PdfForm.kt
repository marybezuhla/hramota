package ua.hramota.pdf

/** A place on a page, as fractions of its width and height (0..1, from the top left). */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A field of a PDF form. [kind]: "text", "check", "radio", "choice" (drop-down) or "list". For a
 * check box the value is "1" or "0"; for radio buttons the export value of the one chosen ([options]
 * are the export values of the buttons); for lists the chosen option's label.
 */
data class PdfField(
    val name: String,
    val label: String,
    val kind: String,
    val value: String,
    val options: List<String>,
    val readOnly: Boolean,
    val page: Int,
    val box: PageRect,
    val multiline: Boolean = false,
) {
    companion object {
        private const val PER = 10
        private const val READ_ONLY = 1
        private const val MULTILINE = 1 shl 12

        /** Fields from the engine's rows (see pdf_engine.c), one per name, in the order of the pages. */
        fun from(raw: Array<String?>, pages: Int): List<PdfField> {
            val g = PdfEngineService.GRID.toFloat()
            class W(val page: Int, val type: Int, val flags: Int, val name: String, val alt: String, val value: String, val checked: Boolean, val export: String, val box: PageRect, val options: List<String>)
            val widgets = (0 until raw.size / PER).mapNotNull { i ->
                val r = raw.copyOfRange(i * PER, i * PER + PER)
                val page = r[0]?.toIntOrNull()?.takeIf { it in 0 until pages } ?: return@mapNotNull null
                val b = r[8]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 } ?: listOf(0f, 0f, 0f, 0f)
                // What the engine process says is not trusted further than needed: names and values kept short
                W(page, r[1]?.toIntOrNull() ?: 0, r[2]?.toIntOrNull() ?: 0, (r[3] ?: "").take(500), (r[4] ?: "").take(300), (r[5] ?: "").take(5000),
                    r[6] == "1", (r[7] ?: "").take(300), PageRect(b[0] / g, b[1] / g, b[2] / g, b[3] / g),
                    (r[9] ?: "").split('\u001F').filter { it.isNotEmpty() }.take(500))
            }
            return widgets.filter { it.name.isNotEmpty() && it.type in 2..6 }.groupBy { it.name }.map { (name, ws) ->
                val w = ws.first()
                val kind = when (w.type) { 2 -> "check"; 3 -> "radio"; 4 -> "choice"; 5 -> "list"; else -> "text" }
                val value = when (kind) {
                    "check" -> if (ws.any { it.checked }) "1" else "0"
                    "radio" -> ws.firstOrNull { it.checked }?.export ?: ""
                    else -> w.value
                }
                PdfField(name, w.alt.ifBlank { name.substringAfterLast('.') }, kind, value,
                    if (kind == "radio") ws.map { it.export }.distinct() else w.options,
                    w.flags and READ_ONLY != 0, w.page, w.box, kind == "text" && w.flags and MULTILINE != 0)
            }
        }
    }
}

/** A hand-drawn signature to put on a page: its picture (BGRA bytes, [width] x [height]) and where. */
class PdfSignature(val page: Int, val box: PageRect, val width: Int, val height: Int, val bgra: ByteArray)
