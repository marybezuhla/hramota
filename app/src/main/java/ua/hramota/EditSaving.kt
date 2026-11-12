package ua.hramota

import android.content.Context
import android.net.Uri
import ua.hramota.data.Library
import ua.hramota.data.Sealed
import ua.hramota.data.Shelf
import ua.hramota.data.Vault
import ua.hramota.doc.docx.DocxReader
import ua.hramota.doc.docx.edit.DocxEdit
import ua.hramota.doc.docx.edit.EditPara
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

/**
 * Saving an edited DOCX. The new file is first made and checked privately; only then does it replace
 * the kept copy or the file itself. Before a file is overwritten its content is kept in Hramota's own
 * storage (sealed while the storage is locked); it is written back if the result does not read back the
 * same, and kept for the person to save if even that fails or Hramota stops halfway.
 */
object EditSaving {
    enum class Result { SAVED, NOT_ALLOWED, RESTORED, BROKEN, FAILED, COPY_SAVED, LOCKED }

    class Outcome(val result: Result, val newId: String? = null, val copy: Uri? = null)

    /** What makes the edited file from the original (read again) and checks the result opens. */
    interface Maker {
        /** "docx" or "xlsx". */
        val ext: String
        fun write(source: InputStream, out: OutputStream)
        fun check(made: InputStream)
    }

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
    private fun temp(context: Context, what: String, ext: String) = File(File(context.cacheDir, "open").apply { mkdirs() }, "$what-${System.nanoTime()}.$ext")

    /** The edited document written to [out] from the file at [uri] (read again), and checked to open. */
    private fun build(context: Context, maker: Maker, uri: Uri, out: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().buffered().use { maker.write(input.buffered(), it) } } ?: return false
        out.inputStream().buffered().use { maker.check(it) }
        true
    } catch (e: Exception) { false } catch (e: OutOfMemoryError) { false }

    /** A Word document's edits. */
    fun docx(edit: DocxEdit, list: List<EditPara>) = object : Maker {
        override val ext = "docx"
        override fun write(source: InputStream, out: OutputStream) = edit.save(list, source, out)
        override fun check(made: InputStream) { DocxReader.read(made) }
    }

    /** Saves into the kept copy or into the file itself; the document's memory moves to its new fingerprint. Off the main thread. */
    fun saveInto(context: Context, maker: Maker, uri: Uri, id: String?, name: String): Outcome {
        val made = temp(context, "edit", maker.ext)
        try {
            if (!build(context, maker, uri, made)) return Outcome(Result.FAILED)
            val library = Library.get(context)
            val entry = id?.let { library.get(it) }
            val copy = entry?.copy?.let { File(it) }
            val newId = Library.fingerprint(context, Uri.fromFile(made))
            val own = ua.hramota.data.Shelf.isOwn(context, uri)
            // A document kept in Hramota is saved only into its copy: without one (taken off meanwhile) nothing is said to be saved
            if (own && (copy == null || newId == null || !copy.exists())) return Outcome(Result.FAILED)
            if (!own) {
                // Under lock the original could only be kept plain: not saved until the storage is open
                if (Vault.sealing(context) && Vault.key() == null) return Outcome(Result.LOCKED)
                val rescue = Rescue.create(context, name, maker.ext)
                val written = try { writeWhole(io(context, uri), made, rescue) } finally { Rescue.done(rescue) }
                when (written) {
                    Write.DONE -> rescue.delete()
                    Write.NOT_ALLOWED -> { rescue.delete(); return Outcome(Result.NOT_ALLOWED) }
                    Write.RESTORED -> { rescue.delete(); return Outcome(Result.RESTORED) }
                    // The original stays in Hramota, offered on the home screen
                    Write.BROKEN -> return Outcome(Result.BROKEN)
                }
            }
            // The kept copy (the document itself when opened from the shelf) gets the changes, under its new name
            var newCopy: File? = null
            if (copy != null && newId != null) {
                newCopy = copyFor(copy, newId)
                if (!replaceKeptCopy(newCopy, made)) {
                    if (own) return Outcome(Result.FAILED)
                    newCopy = null
                }
            }
            if (newId == null) return Outcome(Result.SAVED)
            if (entry != null && id != null && newId != id) {
                if (copy != null && newCopy == null) {
                    // The copy could not be updated: it stays with the old version, and the new one is remembered without it
                    library.update(newId) { entry.copy(id = newId, copy = null, onShelf = false) }
                } else {
                    library.update(newId) { entry.copy(id = newId, copy = newCopy?.path ?: entry.copy) }
                    library.update(id) { null }
                    if (copy != null && newCopy != null && copy.path != newCopy.path) copy.delete()
                }
                // The cover goes with the document. Only a presentation's own first slide (not one chosen) is
                // made again, as it may look different now
                if (entry.cover != "picture" && entry.format in setOf("PPTX", "PPT", "ODP")) Shelf.putCover(context, id, null)
                else Shelf.moveCover(context, id, newId)
            }
            return Outcome(Result.SAVED, newId)
        } finally {
            made.delete()
        }
    }

    /** The kept copy's file for the new content: named by the new fingerprint, as every kept copy is. */
    private fun copyFor(copy: File, newId: String): File {
        val sealed = copy.name.endsWith(Vault.SUFFIX)
        val base = copy.name.removeSuffix(Vault.SUFFIX)
        val ext = base.substringAfterLast('.', "docx")
        return File(copy.parentFile, "$newId.$ext" + if (sealed) Vault.SUFFIX else "")
    }

    /**
     * Writes the edited document into a file the person chose. Off the main thread. A new, empty file
     * is just written (and removed again when that fails); an existing one the person chose to
     * replace is kept first, as for a save into the file itself.
     */
    fun saveCopy(context: Context, maker: Maker, source: Uri, target: Uri): Outcome {
        val made = temp(context, "edit", maker.ext)
        val wasEmpty = isEmpty(context, target)
        try {
            if (!build(context, maker, source, made)) {
                if (wasEmpty) remove(context, target)
                return Outcome(Result.FAILED)
            }
            if (wasEmpty) {
                val ok = try { writeChecked(io(context, target)) { made.inputStream() } } catch (e: Exception) { false }
                if (!ok) { remove(context, target); return Outcome(Result.FAILED) }
                return Outcome(Result.COPY_SAVED, copy = target)
            }
            // Under lock the old content could only be kept plain: not written until the storage is open
            if (Vault.sealing(context) && Vault.key() == null) return Outcome(Result.LOCKED)
            val rescue = Rescue.create(context, ua.hramota.data.Recents.displayName(context, target), maker.ext)
            val written = try { writeWhole(io(context, target), made, rescue) } finally { Rescue.done(rescue) }
            return when (written) {
                Write.DONE -> { rescue.delete(); Outcome(Result.COPY_SAVED, copy = target) }
                // The chosen file's old content stays in Hramota, offered on the home screen
                Write.BROKEN -> Outcome(Result.FAILED)
                else -> { rescue.delete(); Outcome(Result.FAILED) }
            }
        } finally { made.delete() }
    }

    /** True for a file with nothing in it (as a file picker makes a new one). */
    fun isEmpty(context: Context, uri: Uri): Boolean {
        try { context.contentResolver.openInputStream(uri)?.use { return it.read() < 0 } } catch (e: Exception) {}
        // Some apps do not let a file just made be read yet: its size says it then
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                c.moveToFirst() && !c.isNull(0) && c.getLong(0) == 0L
            } == true
        } catch (e: Exception) { false }
    }

    /** Removes a file the picker made (only ever called for one that was empty when chosen). */
    fun remove(context: Context, uri: Uri) {
        try { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) {}
    }

    /** Removes a file the picker made, only while it is still empty (a file chosen from those already there stays). Off the main thread. */
    fun removeIfEmpty(context: Context, uri: Uri) { if (isEmpty(context, uri)) remove(context, uri) }

    /** Writes a kept original (from a save that did not finish) into a file the person chose. */
    fun saveRescue(context: Context, rescue: Rescue, target: Uri): Boolean = try {
        writeChecked(io(context, target)) { rescue.open() }.also { if (it) rescue.delete() }
    } catch (e: Exception) { false }

    enum class Write { DONE, NOT_ALLOWED, RESTORED, BROKEN }

    /**
     * Replaces the file's content with [made]. The original is kept in [rescue] first; if the new
     * content does not read back the same (some apps ignore "truncate", or writing stops halfway), the
     * original is written back. BROKEN leaves [rescue] in place.
     */
    fun writeWhole(io: Io, made: File, rescue: Rescue): Write {
        try {
            io.read()?.use { input -> rescue.keep(input) } ?: return Write.NOT_ALLOWED
        } catch (e: Exception) { return Write.NOT_ALLOWED }
        try {
            if (writeChecked(io) { made.inputStream() }) return Write.DONE
        } catch (e: SecurityException) {
            if (sameAs(io) { rescue.open() }) return Write.NOT_ALLOWED
        } catch (e: IOException) {
            // Not allowed to open for writing at all: nothing was changed
            if (e is java.io.FileNotFoundException && sameAs(io) { rescue.open() }) return Write.NOT_ALLOWED
        } catch (e: Exception) {}
        return try { if (writeChecked(io) { rescue.open() }) Write.RESTORED else Write.BROKEN } catch (e: Exception) { Write.BROKEN }
    }

    /** Writes the content whole and checks it reads back exactly; tries "rwt" where "wt" did not truncate. */
    fun writeChecked(io: Io, source: () -> InputStream): Boolean {
        var refused: Exception? = null
        var opened = false
        for (mode in listOf("wt", "rwt")) {
            // An app may refuse one mode and take the other
            val out = try { io.write(mode) } catch (e: SecurityException) { refused = e; null } catch (e: java.io.FileNotFoundException) { refused = e; null } catch (e: IllegalArgumentException) { refused = e; null }
                ?: continue
            opened = true
            out.use { o -> source().use { it.copyTo(o) } }
            if (sameAs(io, source)) return true
        }
        if (!opened) throw (refused as? SecurityException) ?: java.io.FileNotFoundException("Cannot write")
        return false
    }

    private fun sameAs(io: Io, source: () -> InputStream): Boolean = try {
        io.read()?.use { a -> source().buffered().use { b -> sameStream(a.buffered(), b) } } == true
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

    /** Replaces (or makes) a copy kept in Hramota (sealed if its name says so), through a file synced to disk and then put in place at once. */
    fun replaceKeptCopy(copy: File, made: File): Boolean {
        val part = File(copy.path + ".part")
        try {
            FileOutputStream(part).use { fos ->
                val out = fos.buffered()
                if (copy.name.endsWith(Vault.SUFFIX)) {
                    val key = Vault.key() ?: return false
                    made.inputStream().buffered().use { input -> Sealed.seal(key, input, out, copy.name.removeSuffix(Vault.SUFFIX)) }
                } else made.inputStream().use { it.copyTo(out) }
                out.flush()
                fos.fd.sync()
            }
            if (!part.renameTo(copy)) { part.delete(); return false }
            return true
        } catch (e: Exception) { part.delete(); return false }
    }

    /**
     * A file's content kept while it is overwritten, in Hramota's own storage (not the cache, which is
     * emptied at start), sealed while the storage is locked. What is left after a save that did not
     * finish is offered on the home screen.
     */
    class Rescue(private val file: File, private val label: File, private val key: SecretKey?, private val cache: File) {
        /** The document's name (sealed like the copy, so locked storage hides it too). */
        val name: String get() = try {
            if (label.name.endsWith(Vault.SUFFIX)) (key ?: Vault.key())?.let { String(Sealed.open(it, label.readBytes(), label.name.removeSuffix(Vault.SUFFIX)), Charsets.UTF_8).take(200) } ?: ""
            else label.readText().take(200)
        } catch (e: Exception) { "" }
        val sealed: Boolean get() = file.name.endsWith(Vault.SUFFIX)
        val path: String get() = file.path
        /** "docx" or "xlsx". */
        val ext: String get() = file.name.removeSuffix(Vault.SUFFIX).substringAfterLast('.')

        /** Keeps the content: written beside, synced, then put in place, so only a whole copy is ever left. */
        fun keep(input: InputStream) {
            val part = File(file.path + ".part")
            FileOutputStream(part).use { fos ->
                val out = fos.buffered()
                if (key != null) Sealed.seal(key, input, out, file.name.removeSuffix(Vault.SUFFIX)) else input.copyTo(out)
                out.flush()
                fos.fd.sync()
            }
            if (!part.renameTo(file)) { part.delete(); throw IOException("Could not keep the original") }
        }

        /** The kept content; a sealed one is opened into a temporary file, removed when the stream is closed. */
        fun open(): InputStream {
            if (!sealed) return file.inputStream()
            val k = key ?: Vault.key() ?: throw IOException("Locked")
            val plain = File(cache.apply { mkdirs() }, "rescue-${System.nanoTime()}.$ext")
            try {
                file.inputStream().buffered().use { input -> plain.outputStream().buffered().use { Sealed.open(k, input, it, file.name.removeSuffix(Vault.SUFFIX)) } }
            } catch (e: Exception) { plain.delete(); throw e }
            return object : java.io.FilterInputStream(plain.inputStream()) {
                override fun close() { try { super.close() } finally { plain.delete() } }
            }
        }

        fun delete() { file.delete(); label.delete(); inUse.remove(file.path) }

        /** Turned sealed or plain with the rest of the storage. */
        fun convert(key: SecretKey, seal: Boolean) {
            ua.hramota.data.Shelf.convertFile(key, file, seal)
            ua.hramota.data.Shelf.convertFile(key, label, seal)
        }

        companion object {
            private fun dir(context: Context) = File(context.filesDir, "rescue").apply { mkdirs() }

            /** Rescues of saves under way in this process: never offered or removed meanwhile. */
            private val inUse = java.util.Collections.synchronizedSet(HashSet<String>())

            fun create(context: Context, name: String, ext: String): Rescue {
                val stem = "r" + System.currentTimeMillis().toString(36) + System.nanoTime().toString(36).takeLast(4)
                val key = if (Vault.sealing(context)) Vault.key() else null
                val suffix = if (key != null) Vault.SUFFIX else ""
                val label = File(dir(context), "$stem.txt$suffix")
                val bytes = name.take(200).toByteArray(Charsets.UTF_8)
                label.writeBytes(if (key != null) Sealed.seal(key, bytes, "$stem.txt") else bytes)
                val file = File(dir(context), "$stem.${if (ext == "xlsx" || ext == "pptx" || ext == "pdf") ext else "docx"}$suffix")
                inUse += file.path
                return Rescue(file, label, key, File(context.cacheDir, "open"))
            }

            /** A save is over: its rescue (if still there) may be offered from now on. */
            fun done(r: Rescue) { inUse.remove(r.path) }

            fun busy(): Boolean = inUse.isNotEmpty()

            /** Originals left by saves that did not finish. */
            fun left(context: Context): List<Rescue> {
                val files = dir(context).listFiles().orEmpty()
                fun stem(f: File) = f.name.removeSuffix(".part").removeSuffix(Vault.SUFFIX).substringBeforeLast('.')
                // Saves under way in another window: their files (half-written ones too) are left alone
                val busy = synchronized(inUse) { inUse.map { stem(File(it)) }.toSet() }
                // A copy that was never finished holds nothing worth offering
                files.filter { it.name.endsWith(".part") && stem(it) !in busy }.forEach { it.delete() }
                val kept = files.filter { it.name.matches(Regex("r[0-9a-z]{1,30}\\.(docx|xlsx|pptx|pdf)(${Regex.escape(Vault.SUFFIX)})?")) && it.path !in inUse }
                // Labels whose copy never came to be (not of a save under way)
                files.filter { it.name.removeSuffix(Vault.SUFFIX).endsWith(".txt") && stem(it) !in busy && files.none { k -> !k.name.removeSuffix(Vault.SUFFIX).endsWith(".txt") && !k.name.endsWith(".part") && stem(k) == stem(it) } }.forEach { it.delete() }
                return kept.map { k ->
                    val label = File(k.parentFile, stem(k) + ".txt" + if (k.name.endsWith(Vault.SUFFIX)) Vault.SUFFIX else "")
                    Rescue(k, label, null, File(context.cacheDir, "open"))
                }
            }
        }
    }
}
