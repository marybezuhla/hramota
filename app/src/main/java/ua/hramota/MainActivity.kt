package ua.hramota

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.hramota.data.Bookmarks
import ua.hramota.data.Recents
import ua.hramota.ui.HomeScreen
import androidx.activity.compose.BackHandler
import ua.hramota.data.Settings
import ua.hramota.ui.AppTheme
import ua.hramota.ui.SettingsScreen
import ua.hramota.ui.applySecureScreen

/** Home: open a file, start a comparison, reopen a recent file. */
class MainActivity : ComponentActivity() {
    private lateinit var recents: Recents

    /** The earlier version, while the newer one is being chosen. */
    private var compareFirst by mutableStateOf<Uri?>(null)
    private var comparing by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    // The system file picker: Hramota sees only the file chosen there
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recents = Recents(applicationContext)
        compareFirst = savedInstanceState?.getString(KEY_FIRST)?.let(Uri::parse)
        comparing = savedInstanceState?.getBoolean(KEY_COMPARING) ?: false
        showSettings = savedInstanceState?.getBoolean(KEY_SETTINGS) ?: false
        applySecureScreen()
        setContent {
            AppTheme { settings ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val files by recents.files.collectAsStateWithLifecycle()
                    BackHandler(showSettings) { showSettings = false }
                    if (showSettings) SettingsScreen(
                        settings = settings,
                        version = appVersion(),
                        onChange = { Settings.get(this).update(it) },
                        onClearRecent = { recents.clear(); Bookmarks(applicationContext).clearAll() },
                        onBack = { showSettings = false },
                    ) else HomeScreen(
                        recents = files,
                        comparePrompt = when {
                            !comparing -> null
                            compareFirst == null -> getString(R.string.compare_pick_first)
                            else -> getString(R.string.compare_pick_second)
                        },
                        onOpenFile = { pick.launch(arrayOf("*/*")) },
                        onCompare = { comparing = true; compareFirst = null; pick.launch(arrayOf("*/*")) },
                        onOpenRecent = { openViewer(Uri.parse(it.uri), it.name) },
                        // Forgetting a file also forgets its bookmarks
                        onRemove = { recents.remove(it.uri); Bookmarks(applicationContext).remove(it.uri) },
                        onClear = { recents.clear(); Bookmarks(applicationContext).clearAll() },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        compareFirst?.let { outState.putString(KEY_FIRST, it.toString()) }
        outState.putBoolean(KEY_COMPARING, comparing)
        outState.putBoolean(KEY_SETTINGS, showSettings)
    }

    private fun onPicked(uri: Uri?) {
        if (uri == null) { comparing = false; compareFirst = null; return }
        val name = Recents.displayName(this, uri)
        recents.add(uri, name)
        if (!comparing) { openViewer(uri, name); return }
        val first = compareFirst
        if (first == null) {
            compareFirst = uri
            pick.launch(arrayOf("*/*"))
        } else {
            comparing = false
            compareFirst = null
            startActivity(
                Intent(this, CompareActivity::class.java)
                    .putExtra(CompareActivity.EXTRA_OLDER, first.toString())
                    .putExtra(CompareActivity.EXTRA_NEWER, uri.toString())
            )
        }
    }

    private fun openViewer(uri: Uri, name: String) {
        recents.add(uri, name)
        // Each file in its own window: the viewer is declared with documentLaunchMode="intoExisting"
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private companion object {
        const val KEY_FIRST = "compare_first"
        const val KEY_COMPARING = "comparing"
        const val KEY_SETTINGS = "settings"
    }
}
