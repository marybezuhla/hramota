package ua.hramota.doc.slides

import ua.hramota.doc.Inspection

/** A run of text with its formatting. Sizes in points, colours ARGB. */
data class SlideRun(
    val text: String,
    val size: Float = 18f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val font: String? = null,
    val baseline: Float = 0f,
)

/**
 * A paragraph in a text box. Lengths in points: [marginLeft] is where the text starts,
 * [indent] moves the first line (negative for a hanging bullet). [lineSpacing] is a multiple
 * of single spacing; [lineExact] (points) wins when set.
 */
data class SlidePara(
    val runs: List<SlideRun>,
    val align: String = "left",
    val bullet: String? = null,
    val bulletColor: Int? = null,
    val bulletSize: Float? = null,
    val marginLeft: Float = 0f,
    val indent: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    val lineSpacing: Float = 1f,
    val lineExact: Float? = null,
    /** Size of an empty paragraph's line. */
    val emptySize: Float = 18f,
)

/** Something on a slide. Positions and sizes in points from the slide's top left; [rotation] in degrees. */
sealed interface SlideShape {
    val x: Float; val y: Float; val w: Float; val h: Float; val rotation: Float

    /** A shape with an optional fill, outline and text. */
    data class Box(
        override val x: Float, override val y: Float, override val w: Float, override val h: Float,
        override val rotation: Float = 0f,
        val geometry: String = "rect",
        val fill: Int? = null,
        val line: Int? = null,
        val lineWidth: Float = 0.75f,
        val paragraphs: List<SlidePara> = emptyList(),
        /** Text insets: left, top, right, bottom. */
        val inset: FloatArray = floatArrayOf(7.2f, 3.6f, 7.2f, 3.6f),
        /** "top", "center" or "bottom". */
        val anchor: String = "top",
        val wrap: Boolean = true,
    ) : SlideShape

    /** A picture; [key] finds its bytes in [Deck.media]. */
    data class Picture(
        override val x: Float, override val y: Float, override val w: Float, override val h: Float,
        override val rotation: Float = 0f,
        val key: String,
    ) : SlideShape
}

/** One slide: its background and shapes in drawing order (back to front). */
class Slide(val background: Int, val shapes: List<SlideShape>)

/** A presentation: slide size in points, slides, pictures by key. */
class Deck(
    val width: Float,
    val height: Float,
    val slides: List<Slide>,
    val media: Map<String, ByteArray>,
    val inspection: Inspection = Inspection(),
) {
    companion object {
        const val MAX_SLIDES = 2000
        const val MAX_SHAPES = 2000
        const val MAX_MEDIA = 192L * 1024 * 1024
    }
}

/** EMU (English Metric Units: 12 700 per point) to points, kept within reason. */
fun emu(v: Long?): Float = ((v ?: 0L) / 12700f).coerceIn(-20000f, 20000f)

/** The slides' text for the reading view and for comparing versions: each slide under its number. */
fun Deck.paragraphs(label: (Int) -> String): List<ua.hramota.doc.Paragraph> = slides.withIndex().flatMap { (i, s) ->
    listOf(ua.hramota.doc.Paragraph(label(i + 1), 2)) + s.shapes.filterIsInstance<SlideShape.Box>().flatMap { b ->
        b.paragraphs.map { p -> ua.hramota.doc.Paragraph((p.bullet?.let { "$it " } ?: "") + p.runs.joinToString("") { it.text }) }.filter { it.text.isNotBlank() }
    }
}
