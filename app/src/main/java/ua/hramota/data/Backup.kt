package ua.hramota.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
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
 * turned into a key with PBKDF2-HMAC-SHA256; the content is sealed like locked storage.
 *
 * Layout: "HRMB" · version (1) · iterations (4) · salt (16) · sealed ZIP.
 */
object Backup {
    private val MAGIC = "HRMB".toByteArray()
    private const val VERSION = 1
    private const val ITERATIONS = 600_000

    data class Result(val added: Int, val updated: Int, val documents: Int)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally { spec.clearPassword() }
    }

    /** Writes a backup; [documents] are the ids whose kept copies go in. Call off the main thread. */
    fun write(context: Context, out: OutputStream, password: CharArray, documents: Set<String>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val library = Library.get(context)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val data = DataOutputStream(out)
        data.write(MAGIC); data.writeByte(VERSION); data.writeInt(ITERATIONS); data.write(salt)
        val key = key(password, salt, ITERATIONS)
        val zip = ZipOutputStream(Sealed.SealingStream(key, out.buffered()))
        zip.use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("hramota.json", JSONObject().put("version", VERSION).put("created", System.currentTimeMillis()).toString().toByteArray())
            val entries = library.entries.value.values.toList()
            val chosen = entries.filter { it.id in documents && it.copy != null }
            val list = JSONArray()
            for (e in entries) {
                val o = library.toJson(e)
                o.remove("copy")
                // Where the document is inside the backup, if it went in
                if (e in chosen) o.put("file", "documents/" + File(e.copy!!).name.removeSuffix(Vault.SUFFIX))
                list.put(o)
            }
            put("library.json", list.toString().toByteArray())
            put("shelves.json", JSONArray().apply { library.shelves.value.forEach { put(library.shelfJson(it)) } }.toString().toByteArray())
            put("settings.json", Settings.get(context).toJson().toString().toByteArray())
            for (e in entries) Shelf.loadCoverBytes(context, e.id)?.let { put("covers/${e.id}.png", it) }
            chosen.forEachIndexed { i, e ->
                progress(i, chosen.size)
                val f = File(e.copy!!)
                if (!f.exists()) return@forEachIndexed
                z.putNextEntry(ZipEntry("documents/" + f.name.removeSuffix(Vault.SUFFIX)))
                f.inputStream().buffered().use { input ->
                    if (f.name.endsWith(Vault.SUFFIX)) Sealed.open(Vault.key() ?: throw IOException("Locked"), input, z) else input.copyTo(z)
                }
                z.closeEntry()
            }
            progress(chosen.size, chosen.size)
        }
    }

    /** True if the stream starts like a backup file. */
    fun looksLike(head: ByteArray) = head.size >= 4 && head.copyOfRange(0, 4).contentEquals(MAGIC)

    /**
     * Restores a backup by merging: nothing here is deleted or overwritten with less. Bookmarks, notes
     * and shelves are joined; the later place in a document wins; documents missing here are added.
     * A wrong password or a damaged file throws [IOException] before anything changes.
     */
    fun restore(context: Context, input: InputStream, password: CharArray): Result {
        val data = DataInputStream(input.buffered())
        val head = ByteArray(4); data.readFully(head)
        if (!looksLike(head)) throw IOException("Not a backup")
        if (data.readUnsignedByte() != VERSION) throw IOException("Unknown backup version")
        val iterations = data.readInt()
        if (iterations !in 100_000..10_000_000) throw IOException("Damaged backup")
        val salt = ByteArray(16); data.readFully(salt)
        val key = key(password, salt, iterations)
        val library = Library.get(context)
        // Read everything first into a staging folder: only a backup read whole is applied
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var listJson: String? = null; var shelvesJson: String? = null; var settingsJson: String? = null
            var total = 0L
            ZipInputStream(Sealed.OpeningStream(key, data)).use { z ->
                var count = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++count > 100_000) throw IOException("Damaged backup")
                    val name = e.name
                    // Only names this format writes; nothing can climb out of the staging folder
                    if (!Regex("[A-Za-z0-9_.\\-/]{1,200}").matches(name) || name.contains("..") || name.startsWith("/")) continue
                    when {
                        name == "library.json" -> listJson = String(z.readBytes())
                        name == "shelves.json" -> shelvesJson = String(z.readBytes())
                        name == "settings.json" -> settingsJson = String(z.readBytes())
                        name.startsWith("covers/") || name.startsWith("documents/") -> {
                            val f = File(staging, name).also { it.parentFile?.mkdirs() }
                            if (!f.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                            f.outputStream().use { o -> total += z.copyTo(o) }
                            // Room left on the phone is checked as the backup is read
                            if (staging.usableSpace < 50L * 1024 * 1024) throw IOException("Not enough space")
                        }
                    }
                }
            }
            val list = JSONArray(listJson ?: throw IOException("Damaged backup"))
            for (k in 0 until (shelvesJson?.let { JSONArray(it).length() } ?: 0)) {
                val d = library.shelfFrom(JSONArray(shelvesJson).getJSONObject(k)) ?: continue
                if (library.shelves.value.none { it.id == d.id }) library.putShelf(d)
            }
            var added = 0; var updated = 0; var docs = 0
            for (k in 0 until list.length()) {
                val o = list.optJSONObject(k) ?: continue
                val incoming = (try { library.entry(o) } catch (e: Exception) { null }) ?: continue
                val file = o.optString("file").takeIf { it.startsWith("documents/") }?.let { File(staging, it) }?.takeIf { it.exists() }
                val mine = library.get(incoming.id)
                var copyPath = mine?.copy
                if (copyPath == null && file != null) {
                    copyPath = placeCopy(context, file)?.path
                    if (copyPath != null) docs++
                }
                File(staging, "covers/${incoming.id}.png").takeIf { it.exists() }?.let { c ->
                    if (!Shelf.hasCover(context, incoming.id)) android.graphics.BitmapFactory.decodeFile(c.path)?.let { Shelf.saveCover(context, incoming.id, it) }
                }
                library.update(incoming.id) { cur ->
                    if (cur == null) { added++; incoming.copy(copy = copyPath) }
                    else {
                        updated++
                        val newer = incoming.openedAt > cur.openedAt
                        cur.copy(
                            copy = copyPath,
                            position = if (newer) incoming.position else cur.position,
                            total = maxOf(cur.total, incoming.total),
                            openedAt = maxOf(cur.openedAt, incoming.openedAt),
                            bookmarks = (cur.bookmarks + incoming.bookmarks).take(Library.MAX_BOOKMARKS).toSet(),
                            notes = (cur.notes + incoming.notes.filter { n -> cur.notes.none { it.at == n.at } }).take(Library.MAX_NOTES),
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

    /** Puts a restored document among the kept copies (sealed if the storage is locked). */
    private fun placeCopy(context: Context, staged: File): File? {
        val sealing = Vault.sealing(context)
        val key = Vault.key()
        if (sealing && key == null) return null
        val target = File(Library.copiesDir(context), staged.name + if (sealing) Vault.SUFFIX else "")
        if (target.exists()) return target
        return try {
            staged.inputStream().buffered().use { input -> target.outputStream().buffered().use { if (sealing) Sealed.seal(key!!, input, it) else input.copyTo(it) } }
            target
        } catch (e: Exception) { target.delete(); null }
    }

    /** A file name for a new backup: "Hramota-2026-11-02.hramota". */
    fun suggestedName(): String = "Hramota-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".hramota"
}
