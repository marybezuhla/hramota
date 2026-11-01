package ua.hramota.doc

import androidx.compose.ui.graphics.ImageBitmap

/** Pages of a document (PDF or laid-out DOCX), drawn on demand at the width they are shown. */
interface Pages {
    val pageCount: Int
    /** Height divided by width of page [index]. */
    fun aspect(index: Int): Float
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?
}
