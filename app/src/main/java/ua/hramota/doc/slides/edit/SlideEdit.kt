package ua.hramota.doc.slides.edit

import ua.hramota.doc.docx.edit.RunStyle
import ua.hramota.doc.slides.Deck
import java.io.InputStream
import java.io.OutputStream

/**
 * A presentation opened for editing its slides' text: a PowerPoint one ([PptxEdit]) or an
 * OpenDocument one ([ua.hramota.doc.odf.OdpEdit]). The editor works the same on either;
 * [canAddSlides], [canPlace], [canNotes] and [canComment] say what besides text can be changed.
 */
interface SlideEdit {
    val paragraphs: List<SlidePara>
    /** How many slides are shown. */
    val slideCount: Int
    val boxes: List<TextBox>
    /** The file's extension, as saved. */
    val extension: String
    /** Slides can be added. */
    val canAddSlides: Boolean get() = true
    /** Shapes can be moved and sized, text boxes and pictures added. */
    val canPlace: Boolean get() = true
    /** Speaker notes can be added to a slide without them. */
    val canNotes: Boolean get() = true

    fun addSlide(after: Int?, lang: String): Pair<Int, List<SlidePara>>
    fun canRemove(slide: Int, order: Collection<Int> = (0 until slideCount).toList()): Boolean
    fun addNotes(slide: Int, lang: String): List<SlidePara>?
    fun canComment(slide: Int): Boolean
    fun setComments(slide: Int, list: List<String>)
    fun addTextBox(slide: Int, rect: LongArray, lang: String): Pair<PptxEdit.ShapeRef, List<SlidePara>>
    fun addPicture(slide: Int, bytes: ByteArray, png: Boolean, rect: LongArray): PptxEdit.ShapeRef
    fun isAnimated(ref: PptxEdit.ShapeRef): Boolean

    /** The look of a character as set on its run. */
    fun bold(m: RunStyle): Boolean
    fun italic(m: RunStyle): Boolean
    fun underline(m: RunStyle): Boolean
    fun strike(m: RunStyle): Boolean
    fun restyle(m: RunStyle, bold: Boolean? = null, italic: Boolean? = null, underline: Boolean? = null, strike: Boolean? = null) =
        m.copy(bold = bold ?: m.bold, italic = italic ?: m.italic, underline = underline ?: m.underline, strike = strike ?: m.strike)

    fun canJoin(a: SlidePara, b: SlidePara): Boolean
    fun canSplit(p: SlidePara): Boolean
    fun join(a: SlidePara, b: SlidePara): SlidePara
    fun split(p: SlidePara, at: Int): Pair<SlidePara, SlidePara>

    /** Writes the edited presentation with its slides in [order]: the file is read again from [source]. */
    fun save(list: List<SlidePara>, order: List<Int>, source: InputStream, out: OutputStream, shapes: PptxEdit.Shapes = PptxEdit.Shapes())
    /** Reads a presentation of this kind (to check a saved one, or show it). */
    fun readBack(input: InputStream): Deck
}
