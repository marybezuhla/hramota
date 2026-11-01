package ua.hramota

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import ua.hramota.doc.Format
import ua.hramota.doc.Paragraph
import ua.hramota.doc.TextExtractor
import java.io.IOException

/** The format of a file the app was given, by its name and the type its app reports. */
fun formatOf(context: Context, uri: Uri, name: String): Format =
    Format.detect(name, runCatching { context.contentResolver.getType(uri) }.getOrNull())

/** Reads a document's text; call off the main thread. */
fun readText(context: Context, uri: Uri, format: Format): List<Paragraph> {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    return input.use { TextExtractor.read(it, format) }
}

/** Shows the file's name on the window's card in Recents. */
fun Activity.labelTask(name: String) {
    val description = if (Build.VERSION.SDK_INT >= 33) {
        ActivityManager.TaskDescription.Builder().setLabel(name).build()
    } else {
        @Suppress("DEPRECATION")
        ActivityManager.TaskDescription(name)
    }
    setTaskDescription(description)
}
