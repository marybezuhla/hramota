package ua.hramota.doc.docx.edit

import ua.hramota.doc.docx.DocxDocument
import java.io.InputStream
import java.io.OutputStream

/**
 * A text document opened for editing paragraph by paragraph: a Word document ([DocxEdit]) or an
 * OpenDocument text ([ua.hramota.doc.odf.OdtEdit]). The editor works the same on either; only what
 * each file can hold differs ([canStyle], [canList], [canComment]).
 */
interface TextEdit {
    /** The paragraphs as opened, in reading order. */
    val paragraphs: List<EditPara>
    /** Headings can be set. */
    val canStyle: Boolean
    /** Lists can be started and ended. */
    val canList: Boolean get() = true
    /** Comments can be added and removed. */
    val canComment: Boolean
    /** The file's extension, as saved. */
    val extension: String

    fun newKey(): Long
    fun look(p: EditPara, m: RunStyle): Look
    fun shown(list: List<EditPara>): List<Shown>
    fun canJoin(a: EditPara, b: EditPara): Boolean
    fun join(a: EditPara, b: EditPara): EditPara
    fun retyped(p: EditPara, text: String, marks: List<RunStyle>, from: Int, oldEnd: Int): EditPara
    fun split(p: EditPara, at: Int): Pair<EditPara, EditPara>
    fun kind(p: EditPara): Kind
    fun withKind(p: EditPara, kind: Kind): EditPara
    fun withList(p: EditPara, kind: ListKind?, before: EditPara?): EditPara

    /** The character formatting of [m] with one thing changed. */
    fun restyle(m: RunStyle, bold: Boolean? = null, italic: Boolean? = null, underline: Boolean? = null, strike: Boolean? = null) =
        m.copy(bold = bold ?: m.bold, italic = italic ?: m.italic, underline = underline ?: m.underline, strike = strike ?: m.strike)

    /** Where in [list] the paragraphs of [p]'s table row are; null outside tables. */
    fun rowRange(list: List<EditPara>, p: EditPara): IntRange? = null
    /** A row like [p]'s can be added to its table. */
    fun canAddRow(p: EditPara): Boolean = false
    /** [p]'s table row can be taken out (its paragraphs left out of the list). */
    fun canDeleteRow(list: List<EditPara>, p: EditPara): Boolean = false
    /** An empty row like [p]'s, below or above it; its paragraphs, for the list next to the row's. */
    fun addRow(p: EditPara, below: Boolean): List<EditPara> = throw java.io.IOException("Rows are not added here")

    fun comments(): List<Comment>
    fun comment(p: EditPara, from: Int, to: Int, author: String, text: String): EditPara
    fun uncomment(list: List<EditPara>, id: String): List<EditPara>?
    fun commentPlace(list: List<EditPara>, id: String): Pair<Pair<Int, Int>, String>?

    /** Writes the edited document: the file is read again from [source] and copied with the changes. */
    fun save(list: List<EditPara>, source: InputStream, out: OutputStream)

    /** Reads a document of this kind (to check a saved one, or show its pages). */
    fun readBack(input: InputStream): DocxDocument
}
