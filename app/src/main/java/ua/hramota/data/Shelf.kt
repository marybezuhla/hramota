package ua.hramota.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import javax.crypto.SecretKey

/** Copies kept inside Hramota, and shelf covers; sealed while the storage is locked. */
object Shelf {
    /**
     * The address Hramota's own viewer opens a kept copy by. A sealed copy is first opened into a
     * private temporary file (deleted when the viewer closes or Hramota locks); call off the main thread.
     */
    fun copyUri(context: Context, path: String, title: String = ""): Uri? = try {
        val f = File(path)
        val shown = if (f.name.endsWith(Vault.SUFFIX)) {
            val key = Vault.key() ?: return null
            val dir = File(context.cacheDir, "open").apply { mkdirs() }
            val out = File(dir, f.name.removeSuffix(Vault.SUFFIX))
            try {
                f.inputStream().buffered().use { input -> out.outputStream().buffered().use { Sealed.open(key, input, it) } }
            } catch (e: Exception) { out.delete(); throw e }
            out
        } else f
        FileProvider.getUriForFile(context, context.packageName + ".files", shown)
    } catch (e: Exception) { null }

    /** Copies the document into Hramota's private storage, so it opens from the shelf whatever happens to the original. */
    fun keepCopy(context: Context, e: LibraryEntry): Boolean {
        val source = e.uri?.let(Uri::parse) ?: return false
        val ext = e.title.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { e.format.lowercase() }
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (sealing && key == null) return false
        val target = File(Library.copiesDir(context), "${e.id}.$ext" + if (sealing) Vault.SUFFIX else "")
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().buffered().use { out -> if (sealing) Sealed.seal(key!!, input.buffered(), out) else input.copyTo(out) }
            } ?: return false
            Library.get(context).update(e.id) { it?.copy(copy = target.path, onShelf = true) }
            true
        } catch (ex: Exception) { target.delete(); false }
    }

    private fun coverFile(context: Context, id: String, sealed: Boolean) = File(Library.coversDir(context), "$id.png" + if (sealed) Vault.SUFFIX else "")

    fun hasCover(context: Context, id: String) = coverFile(context, id, false).exists() || coverFile(context, id, true).exists()

    /** The document's cover, if there is one and it can be read now. */
    fun loadCover(context: Context, id: String): Bitmap? = try {
        val sealed = coverFile(context, id, true)
        if (sealed.exists()) Vault.key()?.let { k -> Sealed.open(k, sealed.readBytes()).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        else coverFile(context, id, false).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) { null }

    /** Saves a small picture of the first page as the document's cover (once). */
    fun saveCover(context: Context, id: String, bitmap: Bitmap) {
        if (hasCover(context, id)) return
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (sealing && key == null) return
        val f = coverFile(context, id, sealing)
        try {
            val w = 240
            val scaled = Bitmap.createScaledBitmap(bitmap, w, (w.toFloat() * bitmap.height / bitmap.width).toInt().coerceIn(1, 600), true)
            val png = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
            f.writeBytes(if (sealing) Sealed.seal(key!!, png) else png)
        } catch (e: Exception) { f.delete() }
    }

    /**
     * Seals or opens one file in place (for turning locked storage on or off): the new form is written
     * and checked first, then the old one removed. Returns the file in its new form, or null if untouched.
     */
    fun convertFile(key: SecretKey, f: File, seal: Boolean): File? {
        val isSealed = f.name.endsWith(Vault.SUFFIX)
        if (isSealed == seal) return null
        val out = if (seal) File(f.path + Vault.SUFFIX) else File(f.path.removeSuffix(Vault.SUFFIX))
        try {
            f.inputStream().buffered().use { input -> out.outputStream().buffered().use { if (seal) Sealed.seal(key, input, it) else Sealed.open(key, input, it) } }
            // Checked before the original goes: a sealed file must open again
            if (seal) out.inputStream().buffered().use { Sealed.open(key, it, NullOut) }
        } catch (e: Exception) { out.delete(); throw IOException("Could not convert ${f.name}", e) }
        f.delete()
        return out
    }

    private object NullOut : java.io.OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
}
