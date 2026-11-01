package ua.hramota.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Copies kept inside Hramota, and shelf covers. */
object Shelf {
    /** The address Hramota's own viewer opens a kept copy by. */
    fun copyUri(context: Context, path: String): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + ".files", File(path))
    } catch (e: IllegalArgumentException) { null }

    /** Copies the document into Hramota's private storage, so it opens from the shelf whatever happens to the original. */
    fun keepCopy(context: Context, e: LibraryEntry): Boolean {
        val source = e.uri?.let(Uri::parse) ?: return false
        val ext = e.title.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { e.format.lowercase() }
        val target = File(Library.copiesDir(context), "${e.id}.$ext")
        return try {
            context.contentResolver.openInputStream(source)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return false
            Library.get(context).update(e.id) { it?.copy(copy = target.path, onShelf = true) }
            true
        } catch (ex: Exception) { target.delete(); false }
    }

    /** Saves a small picture of the first page as the document's cover (once). */
    fun saveCover(context: Context, id: String, bitmap: Bitmap) {
        val f = File(Library.coversDir(context), "$id.png")
        if (f.exists()) return
        try {
            val w = 240
            val scaled = Bitmap.createScaledBitmap(bitmap, w, (w.toFloat() * bitmap.height / bitmap.width).toInt().coerceIn(1, 600), true)
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: Exception) { f.delete() }
    }
}
