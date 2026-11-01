package ua.hramota.data

import android.content.Context
import java.security.MessageDigest

/**
 * Bookmarked pages of each document, only on this phone. Documents are keyed by a hash of their
 * address, so the list of bookmarks does not name the files.
 */
class Bookmarks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun pages(uri: String): Set<Int> =
        prefs.getString(key(uri), null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSortedSet() ?: emptySet()

    /** Adds or removes the bookmark on [page] (0-based) and returns the new set. */
    fun toggle(uri: String, page: Int): Set<Int> {
        val set = pages(uri).toSortedSet()
        if (!set.add(page)) set.remove(page)
        prefs.edit().apply {
            if (set.isEmpty()) remove(key(uri)) else putString(key(uri), set.take(MAX).joinToString(","))
        }.apply()
        return set
    }

    fun remove(uri: String) = prefs.edit().remove(key(uri)).apply()

    fun clearAll() = prefs.edit().clear().apply()

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val MAX = 1000 }
}
