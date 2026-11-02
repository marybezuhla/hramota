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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    // Locked storage: whether it is on (changes when turned on or off here), and a prompt under way
    private var vaultOn by mutableStateOf(false)
    private var unlocking = false

    // The system file picker: Hramota sees only the file chosen there
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recents = Recents(applicationContext)
        vaultOn = ua.hramota.data.Vault.isOn(this)
        compareFirst = savedInstanceState?.getString(KEY_FIRST)?.let(Uri::parse)
        comparing = savedInstanceState?.getBoolean(KEY_COMPARING) ?: false
        showSettings = savedInstanceState?.getBoolean(KEY_SETTINGS) ?: false
        applySecureScreen()
        setContent {
            AppTheme { settings ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val open by ua.hramota.data.Vault.unlocked.collectAsStateWithLifecycle()
                    if (vaultOn && !open) { ua.hramota.ui.LockScreen { unlock() }; return@Surface }
                    val files by recents.files.collectAsStateWithLifecycle()
                    val library = ua.hramota.data.Library.get(applicationContext)
                    val known by library.entries.collectAsStateWithLifecycle()
                    val shelves by library.shelves.collectAsStateWithLifecycle()
                    // The shelf holds only documents kept inside Hramota
                    val shelf = known.values.filter { it.kept }.sortedByDescending { it.openedAt }
                    BackHandler(showSettings) { showSettings = false }
                    if (showSettings) SettingsScreen(
                        settings = settings,
                        version = appVersion(),
                        onChange = { Settings.get(this).update(it) },
                        onClearRecent = { recents.clear(); Bookmarks(applicationContext).clearAll(); ua.hramota.data.Library.get(applicationContext).clearAll() },
                        onBack = { showSettings = false },
                        vault = vaultOn,
                        vaultPossible = ua.hramota.data.Vault.canUse(this),
                        onVault = { on -> setVault(on) },
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
                        // The recent list only: bookmarks and the shelf stay with the documents
                        onRemove = { recents.remove(it.uri) },
                        onClear = { recents.clear() },
                        onSettings = { showSettings = true },
                        shelf = shelf,
                        shelves = shelves,
                        onOpenShelf = { e -> openShelf(e) },
                        onShelfAction = { e, a -> shelfAction(e, a) },
                        onPutShelf = { library.putShelf(it) },
                        onRemoveShelf = { library.removeShelf(it) },
                        onSetShelves = { e, s -> library.setShelves(e.id, s) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vaultOn = ua.hramota.data.Vault.isOn(this)
        // Coming back while locked: ask straight away
        if (vaultOn && ua.hramota.data.Vault.key() == null) unlock()
    }

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        lifecycleScope.launch {
            try {
                if (ua.hramota.data.Vault.unlock(this@MainActivity, getString(R.string.unlock_title), getString(R.string.unlock_subtitle))) recents.reload()
            } finally { unlocking = false }
        }
    }

    /** Turns locked storage on or off; everything kept is sealed or opened again, then a word on how it went. */
    private fun setVault(on: Boolean) {
        lifecycleScope.launch {
            val ok = try {
                if (on) ua.hramota.data.Vault.enable(this@MainActivity, getString(R.string.vault_title), getString(R.string.unlock_subtitle))
                else ua.hramota.data.Vault.disable(this@MainActivity)
            } catch (e: Exception) { false }
            vaultOn = ua.hramota.data.Vault.isOn(this@MainActivity)
            recents.reload()
            android.widget.Toast.makeText(this@MainActivity, getString(if (!ok) R.string.vault_failed else if (on) R.string.vault_on_done else R.string.vault_off_done), android.widget.Toast.LENGTH_LONG).show()
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
        // Each file in its own window: the viewer is declared with documentLaunchMode="intoExisting"
        val intent = Intent(this, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(ViewerActivity.EXTRA_NAME, name)
        try {
            startActivity(intent)
            recents.add(uri, name)
        } catch (e: SecurityException) {
            // A file opened once from another app ("Open with") gives access only that time:
            // from the list it cannot be opened again, so say so and take it off the list
            recents.remove(uri.toString())
            android.widget.Toast.makeText(this, getString(R.string.recent_no_access, name), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** A shelf document: its copy inside Hramota if there is one, else where it was opened from. */
    private fun openShelf(e: ua.hramota.data.LibraryEntry) {
        // A sealed copy is opened into a private temporary file first, off the main thread
        lifecycleScope.launch {
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { e.copy?.let { ua.hramota.data.Shelf.copyUri(this@MainActivity, it, e.title) } }
                ?: e.uri?.let(Uri::parse) ?: return@launch
            openViewer(uri, e.title)
        }
    }

    private fun shelfAction(e: ua.hramota.data.LibraryEntry, a: ua.hramota.ui.ShelfAction) {
        val library = ua.hramota.data.Library.get(applicationContext)
        when (a) {
            ua.hramota.ui.ShelfAction.FORGET -> library.forget(e.id)
            // Off the shelf; bookmarks, notes and the place stay remembered for the original file
            ua.hramota.ui.ShelfAction.DROP_COPY -> library.dropCopy(e.id)
        }
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
