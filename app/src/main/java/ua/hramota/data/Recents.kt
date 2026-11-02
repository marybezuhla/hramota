package ua.hramota.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A file opened before: where it is, its name and when it was last opened. */
data class RecentFile(val uri: String, val name: String, val openedAt: Long)

/**
 * The recent files list: only on this phone (no cloud backup), at most [MAX] entries, and it can be
 * cleared. Hramota keeps read access only to files listed here and gives it back when removed.
 */
class Recents private constructor(private val context: Context) {
    private val state = MutableStateFlow(load())
    val files: StateFlow<List<RecentFile>> = state.asStateFlow()

    /** Remembers [uri] as opened now, keeping read access when the file's app allows it. */
    fun add(uri: Uri, name: String) {
        // The person chose not to keep a list
        if (!Settings.get(context).state.value.rememberRecent) return
        // Hramota's own copies are on the shelf, not in the recent list; nothing is written while locked
        if (Shelf.isOwn(context, uri) || Vault.locked(context)) return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some apps only grant one-time access; the file then just will not reopen from the list.
        }
        val entry = RecentFile(uri.toString(), name, System.currentTimeMillis())
        val list = listOf(entry) + state.value.filter { it.uri != entry.uri }
        list.drop(MAX).forEach { release(it.uri) }
        save(list.take(MAX))
    }

    /** Reads the list again (after the locked storage is opened). */
    fun reload() { state.value = load() }

    fun remove(uri: String) {
        release(uri)
        save(state.value.filter { it.uri != uri })
    }

    fun clear() {
        state.value.forEach { release(it.uri) }
        save(emptyList())
    }

    private fun release(uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun save(list: List<RecentFile>) {
        if (Vault.locked(context)) return
        write(context, list)
        state.value = list
    }

    private fun load(): List<RecentFile> = read(context)

    companion object {
        @Volatile private var instance: Recents? = null
        /** One list for the whole app, so every window sees the same and none writes over another's. */
        fun get(context: Context): Recents = instance ?: synchronized(this) { instance ?: Recents(context.applicationContext).also { instance = it } }

        const val MAX = 30
        private const val KEY = "files"
        private const val SEALED = "files.sealed"

        private fun read(context: Context): List<RecentFile> = try {
            val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            val sealed = prefs.getString(SEALED, null)
            val text = if (sealed != null) {
                val key = Vault.key() ?: return emptyList()
                String(Sealed.open(key, android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP), "recents"))
            } else prefs.getString(KEY, "[]")
            val json = JSONArray(text)
            (0 until json.length()).map {
                val o = json.getJSONObject(it)
                RecentFile(o.getString("uri"), o.getString("name"), o.getLong("at"))
            }
        } catch (_: Exception) {
            emptyList()
        }

        /** The list as sealed text while the storage is locked, plain otherwise. */
        private fun write(context: Context, list: List<RecentFile>) {
            val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            val json = JSONArray()
            list.forEach { json.put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt)) }
            val key = Vault.key()
            if (Vault.sealing(context) && key != null)
                prefs.edit().putString(SEALED, android.util.Base64.encodeToString(Sealed.seal(key, json.toString().toByteArray(), "recents"), android.util.Base64.NO_WRAP)).remove(KEY).commit()
            else prefs.edit().putString(KEY, json.toString()).remove(SEALED).commit()
        }

        /** Rewrites the list in the new form (locked storage turned on or off). */
        fun convert(context: Context, seal: Boolean) {
            write(context, read(context))
            get(context).reload()
        }

        /** The file's display name, or the last part of its address. */
        fun displayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
                }
            } catch (_: Exception) {
            }
            return uri.lastPathSegment?.substringAfterLast('/') ?: "?"
        }
    }
}
