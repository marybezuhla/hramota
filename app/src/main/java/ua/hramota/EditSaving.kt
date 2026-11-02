package ua.hramota

import android.content.Context
import android.net.Uri
import ua.hramota.data.Library
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.docx.edit.EditPara
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Saving an edited DOCX. The new file is first made and checked privately; only then does it replace
 * the kept copy or the file itself. The file's original content is kept aside while it is overwritten,
 * and written back if the result does not read back the same.
 */
object EditSaving {
    enum class Result { SAVED, NOT_ALLOWED, RESTORED, BROKEN, FAILED, COPY_SAVED }

    class Outcome(val result: Result, val newId: String? = null, val copy: Uri? = null)

    /** Where a file is read and written: through its app, or (in tests) anything else. */
    interface Io {
        fun read(): InputStream?
        fun write(mode: String): OutputStream?
    }

    private fun io(context: Context, uri: Uri) = object : Io {
        override fun read() = context.contentResolver.openInputStream(uri)
        override fun write(mode: String) = context.contentResolver.openOutputStream(uri, mode)
    }

    /** A private file for this save, in the folder emptied when Hramota locks and when it starts. */
    private fun temp(context: Context, what: String) = File(File(context.cacheDir, "open").apply { mkdirs() }, "$what-${System.nanoTime()}.docx")

    /** The edited document written to [out] from the file at [uri] (read again), and checked to open as a document. */
    private fun build(context: Context, edit: DocxEdit, list: List<EditPara>, uri: Uri, out: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().buffered().use { edit.save(list, input.buffered(), it) } } ?: return false
        out.inputStream().buffered().use { DocxReader.read(it) }
        true
    } catch (e: Exception) { false } catch (e: OutOfMemoryError) { false }

    /** Saves into the kept copy or into the file itself; the document's memory moves to its new fingerprint. Off the main thread. */
    fun saveInto(context: Context, edit: DocxEdit, list: List<EditPara>, uri: Uri, id: String?): Outcome {
        val made = temp(context, "edit")
        val backup = temp(context, "original")
        try {
            if (!build(context, edit, list, uri, made)) return Outcome(Result.FAILED)
            val library = Library.get(context)
            val entry = id?.let { library.get(it) }
            val copy = entry?.copy?.let { File(it) }
            if (ua.hramota.data.Shelf.isOwn(context, uri)) {
                if (copy == null || !replaceKeptCopy(copy, made)) return Outcome(Result.FAILED)
            } else {
                when (writeWhole(io(context, uri), made, backup)) {
                    Write.DONE -> {}
                    Write.NOT_ALLOWED -> return Outcome(Result.NOT_ALLOWED)
                    Write.RESTORED -> return Outcome(Result.RESTORED)
                    Write.BROKEN -> return Outcome(Result.BROKEN)
                }
                // A copy of this document kept in Hramota gets the same changes, so the shelf shows them too
                if (copy != null) replaceKeptCopy(copy, made)
            }
            val newId = Library.fingerprint(context, Uri.fromFile(made)) ?: return Outcome(Result.SAVED)
            if (entry != null && id != null && newId != id) {
                library.update(newId) { entry.copy(id = newId) }
                library.update(id) { null }
                // The first page may look different now: its picture is made again
                File(Library.coversDir(context), "$id.png").delete()
                File(Library.coversDir(context), "$id.png${ua.hramota.data.Vault.SUFFIX}").delete()
            }
            return Outcome(Result.SAVED, newId)
        } finally {
            made.delete()
            backup.delete()
        }
    }

    /** Writes the edited document as a new file the person chose. Off the main thread. */
    fun saveCopy(context: Context, edit: DocxEdit, list: List<EditPara>, source: Uri, target: Uri): Outcome {
        val made = temp(context, "edit")
        try {
            val ok = build(context, edit, list, source, made) && try { writeChecked(io(context, target), made) } catch (e: Exception) { false }
            if (!ok) {
                try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) } catch (_: Exception) {}
                return Outcome(Result.FAILED)
            }
            return Outcome(Result.COPY_SAVED, copy = target)
        } finally { made.delete() }
    }

    enum class Write { DONE, NOT_ALLOWED, RESTORED, BROKEN }

    /**
     * Replaces the file's content with [made]. The original is read into [backup] first; if the new
     * content does not read back the same (some apps ignore "truncate", or writing stops halfway), the
     * original is written back.
     */
    fun writeWhole(io: Io, made: File, backup: File): Write {
        try {
            io.read()?.use { input -> backup.outputStream().use { input.copyTo(it) } } ?: return Write.NOT_ALLOWED
        } catch (e: Exception) { return Write.NOT_ALLOWED }
        try {
            if (writeChecked(io, made)) return Write.DONE
        } catch (e: SecurityException) {
            if (sameAs(io, backup)) return Write.NOT_ALLOWED
        } catch (e: IOException) {
            // Not allowed to open for writing at all: nothing was changed
            if (e is java.io.FileNotFoundException && sameAs(io, backup)) return Write.NOT_ALLOWED
        } catch (e: Exception) {}
        return try { if (writeChecked(io, backup)) Write.RESTORED else Write.BROKEN } catch (e: Exception) { Write.BROKEN }
    }

    /** Writes [file] whole and checks it reads back exactly; tries "rwt" where "wt" did not truncate. */
    fun writeChecked(io: Io, file: File): Boolean {
        for (mode in listOf("wt", "rwt")) {
            val out = io.write(mode) ?: throw java.io.FileNotFoundException("Cannot write")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (sameAs(io, file)) return true
        }
        return false
    }

    private fun sameAs(io: Io, file: File): Boolean = try {
        io.read()?.use { a -> file.inputStream().buffered().use { b -> sameStream(a.buffered(), b) } } == true
    } catch (e: Exception) { false }

    private fun sameStream(a: InputStream, b: InputStream): Boolean {
        val x = ByteArray(64 * 1024); val y = ByteArray(64 * 1024)
        while (true) {
            val n = fill(a, x); val m = fill(b, y)
            if (n != m) return false
            if (n == 0) return true
            for (i in 0 until n) if (x[i] != y[i]) return false
        }
    }

    private fun fill(s: InputStream, buf: ByteArray): Int {
        var k = 0
        while (k < buf.size) { val r = s.read(buf, k, buf.size - k); if (r < 0) break; k += r }
        return k
    }

    /** Replaces a copy kept in Hramota (sealed again if it was), through a file synced to disk and then put in place at once. */
    fun replaceKeptCopy(copy: File, made: File): Boolean {
        val part = File(copy.path + ".part")
        try {
            FileOutputStream(part).use { fos ->
                val out = fos.buffered()
                if (copy.name.endsWith(ua.hramota.data.Vault.SUFFIX)) {
                    val key = ua.hramota.data.Vault.key() ?: return false
                    made.inputStream().buffered().use { input -> ua.hramota.data.Sealed.seal(key, input, out, copy.name.removeSuffix(ua.hramota.data.Vault.SUFFIX)) }
                } else made.inputStream().use { it.copyTo(out) }
                out.flush()
                fos.fd.sync()
            }
            if (!part.renameTo(copy)) { part.delete(); return false }
            return true
        } catch (e: Exception) { part.delete(); return false }
    }
}
