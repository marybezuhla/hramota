package ua.hramota.doc

import androidx.compose.ui.graphics.ImageBitmap

/** Pages of a document (PDF or laid-out DOCX), drawn on demand at the width they are shown. */
interface Pages {
    /** Pages ready so far; a long DOCX is still being laid out while the first pages are shown. */
    val pageCount: Int
    /** False while more pages may still come. */
    val complete: Boolean get() = true
    /** True if laying out stopped before the end (not enough memory): the pages shown are not all. */
    val cutShort: Boolean get() = false
    /** Height divided by width of page [index]. */
    fun aspect(index: Int): Float
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?
    /** Why the last page could not be drawn, if known (shown on the page instead of a blank sheet). */
    val problem: String? get() = null
}

/**
 * Pages that can be drawn at their own size for printing or a PDF: as shapes and text, not as a
 * picture, so the text stays text and sharp at any size.
 */
interface PrintPages : Pages {
    /** Width and height of page [index] in points (1/72 inch). */
    fun pointSize(index: Int): Pair<Float, Float>
    /** Draws page [index] onto [canvas], whose units are points. */
    suspend fun drawInto(index: Int, canvas: android.graphics.Canvas)
}

/** A box on a page, in fractions of the page's width and height (0..1). */
data class PageBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Pages whose text can be searched, selected and copied. */
interface TextPages : Pages {
    /** The text of page [index]; a position in it is a character index for [boxes] and [charAt]. */
    suspend fun pageText(index: Int): String
    /** Boxes covering characters [start, end) of page [index]. */
    suspend fun boxes(index: Int, start: Int, end: Int): List<PageBox>
    /** The character at or near a point given in page fractions, or -1. */
    suspend fun charAt(index: Int, x: Float, y: Float): Int
}
