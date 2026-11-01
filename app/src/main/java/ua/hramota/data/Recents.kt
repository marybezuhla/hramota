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
class Recents(private val context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val files: StateFlow<List<RecentFile>> = state.asStateFlow()

    /** Remembers [uri] as opened now, keeping read access when the file's app allows it. */
    fun add(uri: Uri, name: String) {
        // The person chose not to keep a list
        if (!Settings.get(context).state.value.rememberRecent) return
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
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt)) }
        prefs.edit().putString(KEY, json.toString()).apply()
        state.value = list
    }

    private fun load(): List<RecentFile> = try {
        val json = JSONArray(prefs.getString(KEY, "[]"))
        (0 until json.length()).map {
            val o = json.getJSONObject(it)
            RecentFile(o.getString("uri"), o.getString("name"), o.getLong("at"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX = 30
        private const val KEY = "files"

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
