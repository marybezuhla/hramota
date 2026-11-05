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
            // A name of its own for each opening, so two windows never share (and delete) one file
            val base = f.name.removeSuffix(Vault.SUFFIX)
            val out = File(dir, base.substringBeforeLast('.') + "-" + System.nanoTime() + "." + base.substringAfterLast('.', "bin"))
            opened += out.canonicalPath
            try {
                f.inputStream().buffered().use { input -> out.outputStream().buffered().use { Sealed.open(key, input, it, f.name.removeSuffix(Vault.SUFFIX)) } }
            } catch (e: Exception) { out.delete(); throw e }
            out
        } else f
        FileProvider.getUriForFile(context, context.packageName + ".files", shown)
    } catch (e: Exception) { null }

    // Temporary plain copies made in this process: only these are ever deleted on a viewer's request
    private val opened = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Deletes a temporary copy, if it is one this process made (never anything named from outside). */
    fun closeOpened(context: Context, uri: Uri) {
        val segments = uri.pathSegments
        if (!isOwn(context, uri) || segments.size != 2 || segments[0] != "open") return
        val name = segments[1]
        if (name.contains('/') || name.contains("..")) return
        val f = File(File(context.cacheDir, "open"), name)
        val path = try { f.canonicalPath } catch (e: Exception) { return }
        if (f.parentFile?.canonicalPath != File(context.cacheDir, "open").canonicalPath) return
        if (opened.remove(path)) f.delete()
    }

    /** Removes every temporary plain copy (on locking, and when Hramota starts). */
    fun clearOpened(context: Context) {
        File(context.cacheDir, "open").deleteRecursively()
        opened.clear()
    }

    /** True for an address of Hramota's own files, however it is written (Android ignores a "user@" part). */
    fun isOwn(context: Context, uri: Uri?): Boolean = uri != null && uri.host == context.packageName + ".files"

    /** Copies the document into Hramota's private storage, so it opens from the shelf whatever happens to the original. */
    fun keepCopy(context: Context, e: LibraryEntry): Boolean {
        val source = e.uri?.let(Uri::parse) ?: return false
        val ext = e.title.substringAfterLast('.', "").ifEmpty { e.format }.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (sealing && key == null) return false
        val target = File(Library.copiesDir(context), "${e.id}.$ext" + if (sealing) Vault.SUFFIX else "")
        // Written beside and put in place whole: a copy stopped halfway is never taken for the document
        val part = File(target.path + ".part")
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                java.io.FileOutputStream(part).use { fos ->
                    val out = fos.buffered()
                    if (sealing) Sealed.seal(key!!, input.buffered(), out, "${e.id}.$ext") else input.copyTo(out)
                    out.flush(); fos.fd.sync()
                }
            } ?: return false
            if (!part.renameTo(target)) { part.delete(); return false }
            Library.get(context).update(e.id) { it?.copy(copy = target.path, onShelf = true) }
            true
        } catch (ex: Exception) { part.delete(); false }
    }

    private fun coverFile(context: Context, id: String, sealed: Boolean): File {
        // An id is a fingerprint; anything else could name a file outside the covers
        require(id.none { it == '/' || it == '\\' } && ".." !in id) { "Bad id" }
        return File(Library.coversDir(context), "$id.png" + if (sealed) Vault.SUFFIX else "")
    }

    fun hasCover(context: Context, id: String) = coverFile(context, id, false).exists() || coverFile(context, id, true).exists()

    /** The document's cover, if there is one and it can be read now. */
    fun loadCover(context: Context, id: String): Bitmap? = try {
        val sealed = coverFile(context, id, true)
        if (sealed.exists()) Vault.key()?.let { k -> Sealed.open(k, sealed.readBytes(), "$id.png").let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        else coverFile(context, id, false).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) { null }

    /** The cover as PNG bytes (opened if sealed), for a backup. */
    fun loadCoverBytes(context: Context, id: String): ByteArray? = try {
        val sealed = coverFile(context, id, true)
        if (sealed.exists()) Vault.key()?.let { Sealed.open(it, sealed.readBytes(), "$id.png") }
        else coverFile(context, id, false).takeIf { it.exists() }?.readBytes()
    } catch (e: Exception) { null }

    /** Saves a small picture (of the first page, or a book's own) as the document's cover: once, unless [replace]. */
    fun saveCover(context: Context, id: String, bitmap: Bitmap, replace: Boolean = false): Boolean {
        if (!replace && hasCover(context, id)) return false
        return try {
            val w = 240
            val scaled = Bitmap.createScaledBitmap(bitmap, w, (w.toFloat() * bitmap.height / bitmap.width).toInt().coerceIn(1, 600), true)
            val png = java.io.ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
            putCover(context, id, png)
        } catch (_: Exception) { false }
    }

    /** Counts cover changes, so the shelf shows a new picture at once. */
    val coversChanged = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Puts [png] in place as the cover (sealed while the storage is locked), or removes the cover when null: written
     * beside first, then moved over the old one, so a cut-off write never leaves half a picture. Used to undo a change.
     */
    fun putCover(context: Context, id: String, png: ByteArray?): Boolean {
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (png != null && sealing && key == null) return false
        val plain = coverFile(context, id, false); val sealed = coverFile(context, id, true)
        if (png == null) { plain.delete(); sealed.delete(); coversChanged.value++; return true }
        val f = if (sealing) sealed else plain
        val tmp = File(f.parentFile, f.name + ".new")
        return try {
            tmp.writeBytes(if (sealing) Sealed.seal(key!!, png, "$id.png") else png)
            if (!tmp.renameTo(f)) { tmp.delete(); return false }
            (if (sealing) plain else sealed).delete()
            coversChanged.value++
            true
        } catch (_: Exception) { tmp.delete(); false }
    }

    /**
     * Seals or opens one file in place (for turning locked storage on or off): the new form is written
     * and checked first, then the old one removed. Returns the file in its new form, or null if untouched.
     */
    fun convertFile(key: SecretKey, f: File, seal: Boolean): File? {
        val isSealed = f.name.endsWith(Vault.SUFFIX)
        if (isSealed == seal) return null
        val out = if (seal) File(f.path + Vault.SUFFIX) else File(f.path.removeSuffix(Vault.SUFFIX))
        val name = f.name.removeSuffix(Vault.SUFFIX)
        try {
            f.inputStream().buffered().use { input -> out.outputStream().buffered().use { if (seal) Sealed.seal(key, input, it, name) else Sealed.open(key, input, it, name) } }
            // Checked before the original goes: a sealed file must open again
            if (seal) out.inputStream().buffered().use { Sealed.open(key, it, NullOut, name) }
        } catch (e: Exception) { out.delete(); throw IOException("Could not convert ${f.name}", e) }
        f.delete()
        return out
    }

    private object NullOut : java.io.OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
}
