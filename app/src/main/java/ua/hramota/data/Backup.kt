package ua.hramota.data

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One encrypted backup file (.hramota): the document memory (bookmarks, notes, places, shelves),
 * the settings and, if chosen, the documents kept in Hramota with their covers. The password is
 * turned into a key with PBKDF2-HMAC-SHA256; the content is sealed like locked storage, with the
 * header bound in, so nothing in it can be changed unnoticed.
 *
 * Layout: "HRMB" · version (1) · iterations (4) · salt (16) · sealed ZIP.
 */
object Backup {
    private val MAGIC = "HRMB".toByteArray()
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val MAX_JSON = 32L * 1024 * 1024

    data class Result(val added: Int, val updated: Int, val documents: Int)

    /** Why a backup or restore did not happen, to say it plainly. */
    class Refused(val reason: Reason) : IOException(reason.name)
    enum class Reason { NOT_A_BACKUP, WRONG_PASSWORD_OR_DAMAGED, NO_SPACE, LOCKED }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun header(iterations: Int, salt: ByteArray): ByteArray =
        MAGIC + byteArrayOf(VERSION.toByte()) + java.nio.ByteBuffer.allocate(4).putInt(iterations).array() + salt

    // The header is part of what the seal checks
    private fun sealName(header: ByteArray) = "backup:" + android.util.Base64.encodeToString(header, android.util.Base64.NO_WRAP)

    /** Writes a backup; [documents] are the ids whose kept copies go in. Call off the main thread. */
    fun write(context: Context, out: OutputStream, password: CharArray, documents: Set<String>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        // Locked meanwhile (the memory is not readable now): no empty backup that looks complete
        if (Vault.locked(context)) throw Refused(Reason.LOCKED)
        val library = Library.get(context)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val head = header(ITERATIONS, salt)
        out.write(head)
        val key = key(password, salt, ITERATIONS)
        val sealing = Sealed.SealingStream(key, out.buffered(), sealName(head))
        val z = ZipOutputStream(sealing)
        try {
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("hramota.json", JSONObject().put("version", VERSION).put("created", System.currentTimeMillis()).toString().toByteArray())
            val entries = library.entries.value.values.toList()
            val chosen = entries.filter { it.id in documents && it.copy != null && File(it.copy).exists() }
            val list = JSONArray()
            for (e in entries) {
                val o = library.toJson(e)
                o.remove("copy")
                // Where the document is inside the backup, if it went in
                if (e in chosen) o.put("file", "documents/" + e.id + "." + ext(File(e.copy!!).name.removeSuffix(Vault.SUFFIX)))
                list.put(o)
            }
            put("library.json", list.toString().toByteArray())
            put("shelves.json", JSONArray().apply { library.shelves.value.forEach { put(library.shelfJson(it)) } }.toString().toByteArray())
            put("settings.json", Settings.get(context).toJson().toString().toByteArray())
            for (e in entries) Shelf.loadCoverBytes(context, e.id)?.let { put("covers/${e.id}.png", it) }
            chosen.forEachIndexed { i, e ->
                progress(i, chosen.size)
                val f = File(e.copy!!)
                val base = f.name.removeSuffix(Vault.SUFFIX)
                z.putNextEntry(ZipEntry("documents/" + e.id + "." + ext(base)))
                f.inputStream().buffered().use { input ->
                    if (f.name.endsWith(Vault.SUFFIX)) Sealed.open(Vault.key() ?: throw Refused(Reason.LOCKED), input, z, base) else input.copyTo(z)
                }
                z.closeEntry()
            }
            progress(chosen.size, chosen.size)
            z.finish()
            sealing.close()
        } catch (e: Throwable) {
            // Never a backup that opens but holds less than it should: the end is left unsealed
            sealing.abort()
            throw e
        }
    }

    private fun ext(name: String) = name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }

    /** True if the stream starts like a backup file. */
    fun looksLike(head: ByteArray) = head.size >= 4 && head.copyOfRange(0, 4).contentEquals(MAGIC)

    /**
     * Restores a backup by merging: nothing here is deleted or overwritten with less. Bookmarks, notes
     * and shelves are joined; the later place in a document wins; documents missing here are added.
     * A wrong password or a damaged file is refused before anything changes.
     */
    fun restore(context: Context, input: InputStream, password: CharArray): Result {
        if (Vault.locked(context)) throw Refused(Reason.LOCKED)
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        try { data.readFully(magic) } catch (e: IOException) { throw Refused(Reason.NOT_A_BACKUP) }
        if (!looksLike(magic) || data.readUnsignedByte() != VERSION) throw Refused(Reason.NOT_A_BACKUP)
        val iterations = data.readInt()
        if (iterations !in 100_000..MAX_ITERATIONS) throw Refused(Reason.WRONG_PASSWORD_OR_DAMAGED)
        val salt = ByteArray(16); data.readFully(salt)
        val key = key(password, salt, iterations)
        val library = Library.get(context)
        // Everything is read first into a staging folder, itself sealed with a key that exists only in
        // memory now: only a backup read whole is applied, and nothing plain is left if Hramota stops
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val stageKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        try {
            var listJson: String? = null; var shelvesJson: String? = null; var settingsJson: String? = null
            try {
                ZipInputStream(Sealed.OpeningStream(key, data, sealName(header(iterations, salt)))).use { z ->
                    var count = 0
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (++count > 100_000) throw IOException("Damaged backup")
                        val name = e.name
                        // Only names this format writes; nothing can climb out of the staging folder
                        if (!Regex("[A-Za-z0-9_.\\-/]{1,200}").matches(name) || name.contains("..") || name.startsWith("/")) continue
                        when {
                            name == "library.json" -> listJson = String(limited(z, MAX_JSON))
                            name == "shelves.json" -> shelvesJson = String(limited(z, MAX_JSON))
                            name == "settings.json" -> settingsJson = String(limited(z, 1024 * 1024))
                            name.startsWith("covers/") || name.startsWith("documents/") -> {
                                val f = File(staging, name).also { it.parentFile?.mkdirs() }
                                if (!f.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                                Sealed.SealingStream(stageKey, f.outputStream().buffered(), name).use { o -> copyWatchingSpace(z, o, staging) }
                            }
                        }
                    }
                }
            } catch (e: Refused) { throw e } catch (e: IOException) { throw Refused(Reason.WRONG_PASSWORD_OR_DAMAGED) }
            val list = JSONArray(listJson ?: throw Refused(Reason.WRONG_PASSWORD_OR_DAMAGED))
            shelvesJson?.let { text ->
                val arr = JSONArray(text)
                for (k in 0 until arr.length()) {
                    if (library.shelves.value.size >= Library.MAX_SHELVES) break
                    val d = arr.optJSONObject(k)?.let(library::shelfFrom) ?: continue
                    if (library.shelves.value.none { it.id == d.id }) library.putShelf(d)
                }
            }
            var added = 0; var updated = 0; var docs = 0
            for (k in 0 until list.length()) {
                val o = list.optJSONObject(k) ?: continue
                var incoming = (try { library.entry(o) } catch (e: Exception) { null }) ?: continue
                // Addresses from another phone open nothing here; only document addresses are kept
                if (incoming.uri?.startsWith("content://") != true) incoming = incoming.copy(uri = null)
                val fileName = o.optString("file").takeIf { it.startsWith("documents/") }
                val staged = fileName?.let { File(staging, it) }?.takeIf { it.exists() }
                val mine = library.get(incoming.id)
                var copyPath = mine?.copy?.takeIf { File(it).exists() }
                if (copyPath == null && staged != null && fileName != null) {
                    copyPath = placeCopy(context, staged, stageKey, fileName, incoming.id)?.path
                    if (copyPath != null) docs++
                }
                restoreCover(context, staging, stageKey, incoming.id)
                val finalCopy = copyPath
                library.update(incoming.id) { cur ->
                    if (cur == null) { added++; incoming.copy(copy = finalCopy) }
                    else {
                        updated++
                        val newer = incoming.openedAt > cur.openedAt
                        cur.copy(
                            copy = finalCopy ?: cur.copy,
                            position = if (newer) incoming.position else cur.position,
                            total = maxOf(cur.total, incoming.total),
                            openedAt = maxOf(cur.openedAt, incoming.openedAt),
                            bookmarks = (cur.bookmarks + incoming.bookmarks).take(Library.MAX_BOOKMARKS).toSet(),
                            notes = (cur.notes + incoming.notes.filter { n -> cur.notes.none { it.at == n.at } })
                                .sortedWith(compareBy({ it.block }, { it.start })).take(Library.MAX_NOTES),
                            shelves = cur.shelves + incoming.shelves,
                        )
                    }
                }
            }
            settingsJson?.let { s -> try { Settings.get(context).restore(JSONObject(s)) } catch (_: Exception) {} }
            return Result(added, updated, docs)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun restoreCover(context: Context, staging: File, stageKey: SecretKeySpec, id: String) {
        val c = File(staging, "covers/$id.png")
        if (!c.exists() || Shelf.hasCover(context, id)) return
        try {
            val bytes = ByteArrayOutputStream().also { o -> c.inputStream().buffered().use { Sealed.open(stageKey, it, o, "covers/$id.png") } }.toByteArray()
            if (bytes.size > 4 * 1024 * 1024) return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480 || bounds.outHeight / (sample * 2) >= 960) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { Shelf.saveCover(context, id, it) }
        } catch (_: Exception) {}
    }

    private fun limited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val k = input.read(buf); if (k < 0) break
            n += k
            if (n > max) throw IOException("Damaged backup")
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    /** Copies while checking, every megabyte, that the phone keeps room for the documents placed after. */
    private fun copyWatchingSpace(input: InputStream, out: OutputStream, dir: File) {
        val buf = ByteArray(64 * 1024)
        var since = 0L
        while (true) {
            val k = input.read(buf); if (k < 0) break
            out.write(buf, 0, k)
            since += k
            if (since >= 1024 * 1024) { since = 0; if (dir.usableSpace < 200L * 1024 * 1024) throw Refused(Reason.NO_SPACE) }
        }
    }

    /** Puts a restored document among the kept copies under its own name (sealed if the storage is locked). */
    private fun placeCopy(context: Context, staged: File, stageKey: SecretKeySpec, stagedName: String, id: String): File? {
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (sealing && key == null) return null
        val suffix = if (sealing) Vault.SUFFIX else ""
        var target = File(Library.copiesDir(context), "$id." + ext(staged.name) + suffix)
        // A file of that name held by another document is never taken over
        if (Library.get(context).entries.value.values.any { it.id != id && it.copy == target.path })
            target = File(Library.copiesDir(context), "$id-${System.nanoTime()}." + ext(staged.name) + suffix)
        val temp = File(target.path + ".part")
        return try {
            staged.inputStream().buffered().use { input ->
                temp.outputStream().buffered().use { out ->
                    val plain = Sealed.OpeningStream(stageKey, input, stagedName)
                    if (sealing) Sealed.seal(key!!, plain, out, target.name.removeSuffix(Vault.SUFFIX)) else plain.copyTo(out)
                }
            }
            if (!temp.renameTo(target)) { target.delete(); if (!temp.renameTo(target)) throw IOException("Could not place") }
            target
        } catch (e: Exception) { temp.delete(); null }
    }

    /** A file name for a new backup: "Hramota-2026-11-02.hramota". */
    fun suggestedName(): String = "Hramota-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".hramota"
}
