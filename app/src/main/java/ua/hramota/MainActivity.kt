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
import ua.hramota.data.Recents
import ua.hramota.ui.HomeScreen
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ua.hramota.data.Settings
import ua.hramota.ui.AppTheme
import ua.hramota.ui.SettingsScreen
import ua.hramota.ui.applySecureScreen

/** Home: open a file, start a comparison, reopen a recent file. */
class MainActivity : ComponentActivity() {
    private lateinit var recents: Recents

    /** The earlier version, while the newer one is being chosen. */
    private var compareFirst by mutableStateOf<Uri?>(null)
    private var compareFirstName: String? = null
    private var comparing by mutableStateOf(false)
    // The list a version to compare is chosen from (shelf, recent files, or another file)
    private var choosing by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    // Locked storage: whether it is on (changes when turned on or off here), and a prompt under way
    private var vaultOn by mutableStateOf(false)
    private var unlocking = false
    // The confirmation screen returning is not a new arrival: no second prompt then
    private var skipResume = false
    private var keyLost by mutableStateOf(false)
    // Backup: the dialog, the password waiting for the chosen file, a restore's file and work under way
    private var backupDialog by mutableStateOf(false)
    private var pendingBackup: Pair<CharArray, Set<String>>? = null
    private var restoreUri by mutableStateOf<Uri?>(null)
    private var restoreWrong by mutableStateOf(false)
    private var restoreProblem by mutableStateOf(ua.hramota.data.Backup.Reason.WRONG_PASSWORD_OR_DAMAGED)
    private var busy by mutableStateOf<Triple<String, Int, Int>?>(null)
    private var dictionaries by mutableStateOf<List<ua.hramota.data.Dictionary>>(emptyList())
    private val addDictionary = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onDictionaryFiles(uris) }
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> onBackupTarget(uri) }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { restoreWrong = false; restoreUri = uri } }

    // The system file picker: Hramota sees only the file chosen there
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(uri) }
    // A new document: the kind chosen, then the place the person picks for it
    private var creating by mutableStateOf(false)
    private val createDocx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { uri -> onCreated(uri, "docx") }
    private val createXlsx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> onCreated(uri, "xlsx") }

    /** Writes the empty document into the new file and opens it ready to edit. */
    private fun onCreated(uri: Uri?, kind: String) {
        if (uri == null) return
        lifecycleScope.launch {
            val app = applicationContext
            val sheetName = getString(R.string.new_sheet_name)
            // null: written; otherwise what to say
            val problem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // A file chosen from those already there is never written over with an empty document
                if (!EditSaving.isEmpty(app, uri)) return@withContext R.string.create_exists
                val ok = try {
                    val bytes = if (kind == "docx") ua.hramota.doc.Templates.docx(java.util.Locale.getDefault().stripExtensions().toLanguageTag())
                        else ua.hramota.doc.Templates.xlsx(sheetName)
                    EditSaving.writeChecked(object : EditSaving.Io {
                        override fun read() = app.contentResolver.openInputStream(uri)
                        override fun write(mode: String) = app.contentResolver.openOutputStream(uri, mode)
                    }) { bytes.inputStream() }
                } catch (e: Exception) { false }
                if (!ok) { EditSaving.remove(app, uri); R.string.create_failed } else null
            }
            if (problem != null) { android.widget.Toast.makeText(this@MainActivity, getString(problem), android.widget.Toast.LENGTH_LONG).show(); return@launch }
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Recents.displayName(app, uri) }
            val intent = Intent(this@MainActivity, ViewerActivity.viewerClass(this@MainActivity)).setAction(Intent.ACTION_VIEW).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(ViewerActivity.EXTRA_NAME, name).putExtra(ViewerActivity.EXTRA_EDIT, true).putExtra(ViewerActivity.EXTRA_TOKEN, ViewerActivity.TOKEN)
            recents.add(uri, name)
            startActivity(intent)
        }
    }
    // Originals kept by saves that did not finish (Hramota stopped, or the file could not be put back)
    private var rescues by mutableStateOf<List<EditSaving.Rescue>>(emptyList())
    private var savingRescue: EditSaving.Rescue? = null
    // Which one was being saved, kept while the window is made again (the file picker open)
    private var savingRescuePath: String? = null
    private var confirmRescueDelete by mutableStateOf(false)
    // Any kind of file (Word or Excel): the name carries the extension
    private val saveRescue = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { target ->
        val r = savingRescue ?: savingRescuePath?.let { p -> EditSaving.Rescue.left(applicationContext).firstOrNull { it.path == p } }
        savingRescue = null; savingRescuePath = null
        if (target == null) return@registerForActivityResult
        if (r == null) {
            // Nothing to put in it: the empty file just made is removed
            val app = applicationContext
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { EditSaving.removeIfEmpty(app, target) }
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EditSaving.saveRescue(applicationContext, r, target) }
            android.widget.Toast.makeText(this@MainActivity, getString(if (ok) R.string.rescue_saved else R.string.rescue_not_saved), android.widget.Toast.LENGTH_LONG).show()
            if (ok) rescues = rescues.filter { it.path != r.path }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recents = Recents.get(applicationContext)
        vaultOn = ua.hramota.data.Vault.isOn(this)
        compareFirst = savedInstanceState?.getString(KEY_FIRST)?.let(Uri::parse)
        compareFirstName = savedInstanceState?.getString(KEY_FIRST_NAME)
        savingRescuePath = savedInstanceState?.getString(KEY_RESCUE)
        creating = savedInstanceState?.getBoolean(KEY_CREATING) == true
        choosing = savedInstanceState?.getBoolean(KEY_CHOOSING) ?: false
        comparing = savedInstanceState?.getBoolean(KEY_COMPARING) ?: false
        showSettings = savedInstanceState?.getBoolean(KEY_SETTINGS) ?: false
        applySecureScreen()
        if (savedInstanceState == null) openHanded(intent)
        setContent {
            AppTheme { settings ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val open by ua.hramota.data.Vault.unlocked.collectAsStateWithLifecycle()
                    if (vaultOn && !open) {
                        ua.hramota.ui.LockScreen(keyLost, onUnlock = { unlock() }, onStartOver = {
                            ua.hramota.data.Vault.reset(this@MainActivity)
                            keyLost = false; vaultOn = false
                        })
                        return@Surface
                    }
                    val files by recents.files.collectAsStateWithLifecycle()
                    val library = ua.hramota.data.Library.get(applicationContext)
                    val known by library.entries.collectAsStateWithLifecycle()
                    val shelves by library.shelves.collectAsStateWithLifecycle()
                    // The shelf holds only documents kept inside Hramota
                    val shelf = known.values.filter { it.kept }.sortedByDescending { it.openedAt }
                    // Recent files that open again: Hramota keeps lasting access to them, or a copy of them
                    val copies = known.values.filter { it.copy != null && it.uri != null }.associateBy { it.uri!! }
                    var granted by androidx.compose.runtime.remember { mutableStateOf<Set<String>?>(null) }
                    androidx.compose.runtime.LaunchedEffect(files) {
                        granted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet() } catch (e: Exception) { null }
                        }
                    }
                    val unreachable = granted?.let { g -> files.filter { it.uri !in g && it.uri !in copies }.map { it.uri }.toSet() } ?: emptySet()
                    BackHandler(showSettings) { showSettings = false }
                    if (creating) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { creating = false },
                        title = { androidx.compose.material3.Text(getString(R.string.create_new)) },
                        text = {
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = { creating = false; createDocx.launch(getString(R.string.new_document_name) + ".docx") }) { androidx.compose.material3.Text(getString(R.string.create_docx)) }
                                androidx.compose.material3.TextButton(onClick = { creating = false; createXlsx.launch(getString(R.string.new_sheet_file_name) + ".xlsx") }) { androidx.compose.material3.Text(getString(R.string.create_xlsx)) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { creating = false }) { androidx.compose.material3.Text(getString(R.string.cancel)) } },
                    )
                    // Looked for once Hramota is open (a sealed one needs the storage unlocked)
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        rescues = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            EditSaving.Rescue.left(applicationContext).filter { !it.sealed || ua.hramota.data.Vault.key() != null }
                        }
                    }
                    rescues.firstOrNull()?.let { r ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmRescueDelete = false; rescues = rescues - r },
                            title = { androidx.compose.material3.Text(getString(R.string.rescue_title)) },
                            text = { androidx.compose.material3.Text(getString(R.string.rescue_text, r.name.ifBlank { "?" })) },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = { savingRescue = r; savingRescuePath = r.path; saveRescue.launch(r.name.substringBeforeLast('.', r.name).ifBlank { "document" } + " (" + getString(R.string.rescue_suffix) + ")." + r.ext) }) {
                                    androidx.compose.material3.Text(getString(R.string.rescue_save))
                                }
                            },
                            dismissButton = {
                                // Deleting is asked again: it may be the only good copy of a damaged file
                                androidx.compose.material3.TextButton(onClick = { if (confirmRescueDelete) { confirmRescueDelete = false; r.delete(); rescues = rescues - r } else confirmRescueDelete = true }) {
                                    androidx.compose.material3.Text(getString(if (confirmRescueDelete) R.string.rescue_delete_sure else R.string.rescue_delete), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                    if (choosing && comparing) ua.hramota.ui.ComparePicker(
                        getString(if (compareFirst == null) R.string.compare_pick_first else R.string.compare_pick_second),
                        shelf, files,
                        onShelf = { e ->
                            choosing = false
                            // A kept copy (opened from its seal if need be), or where it came from
                            lifecycleScope.launch {
                                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { e.copy?.let { ua.hramota.data.Shelf.copyUri(this@MainActivity, it, e.title) } }
                                    ?: e.uri?.let(Uri::parse)
                                if (uri == null) choosing = true else chosen(uri, e.title)
                            }
                        },
                        onRecent = { r -> choosing = false; chosen(Uri.parse(r.uri), r.name) },
                        onOther = { choosing = false; pick.launch(arrayOf("*/*")) },
                        onDismiss = { choosing = false; comparing = false; compareFirst?.let { ua.hramota.data.Shelf.closeOpened(this@MainActivity, it) }; compareFirst = null },
                    )
                    if (backupDialog) ua.hramota.ui.BackupDialog(shelf, shelves, onCreate = { pw, ids ->
                        backupDialog = false
                        pendingBackup = pw to ids
                        // Held from now: Hramota does not lock while the file is being chosen and written
                        ua.hramota.data.Vault.hold()
                        saveBackup.launch(ua.hramota.data.Backup.suggestedName())
                    }, onDismiss = { backupDialog = false })
                    restoreUri?.let { u -> ua.hramota.ui.RestoreDialog(if (restoreWrong) restoreProblem else null, onRestore = { pw -> restore(u, pw) }, onDismiss = { restoreUri = null }) }
                    busy?.let { (t, d, n) -> ua.hramota.ui.BusyDialog(t, d, n) }
                    if (showSettings) SettingsScreen(
                        settings = settings,
                        version = appVersion(),
                        onChange = { Settings.get(this).update(it) },
                        onClearRecent = { recents.clear(); ua.hramota.data.Library.get(applicationContext).clearAll() },
                        onDeleteKept = { val app = applicationContext; lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { ua.hramota.data.Library.get(app).dropAllCopies() } },
                        onBack = { showSettings = false },
                        vault = vaultOn,
                        vaultPossible = ua.hramota.data.Vault.canUse(this),
                        onVault = { on -> setVault(on) },
                        onBackup = { backupDialog = true },
                        dictionaries = dictionaries,
                        onAddDictionary = { addDictionary.launch(arrayOf("*/*")) },
                        onRemoveDictionary = { id -> ua.hramota.data.Dictionaries.remove(this, id); refreshDictionaries() },
                        onRestore = { openBackup.launch(arrayOf("*/*")) },
                    ) else HomeScreen(
                        recents = files,
                        unreachable = unreachable,
                        comparePrompt = when {
                            !comparing -> null
                            compareFirst == null -> getString(R.string.compare_pick_first)
                            else -> getString(R.string.compare_pick_second)
                        },
                        onOpenFile = { pick.launch(arrayOf("*/*")) },
                        onCompare = { comparing = true; compareFirst = null; choosing = true },
                        onCreate = { creating = true },
                        // Where access was not kept (a file shared once by another app), its copy in Hramota opens instead
                        onOpenRecent = { r ->
                            val copy = copies[r.uri]
                            if (copy != null && granted?.contains(r.uri) == false) openShelf(copy)
                            else if (r.uri in unreachable) android.widget.Toast.makeText(this, getString(R.string.recent_no_access, r.name), android.widget.Toast.LENGTH_LONG).show()
                            else openViewer(Uri.parse(r.uri), r.name)
                        },
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

    private fun onBackupTarget(uri: Uri?) {
        val pending = pendingBackup
        pendingBackup = null
        if (pending == null) {
            // The window was made again while the file was chosen: the password is gone, so no half-made file stays
            val app = applicationContext
            if (uri != null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { EditSaving.removeIfEmpty(app, uri) }
            ua.hramota.data.Vault.release()
            return
        }
        val (pw, ids) = pending
        if (uri == null) { pw.fill(' '); ua.hramota.data.Vault.release(); return }
        busy = Triple(getString(R.string.backup_writing), 0, 0)
        lifecycleScope.launch {
            var wasEmpty = false
            var exists = false
            val reason = try {
                ua.hramota.data.Vault.work.withLock {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            wasEmpty = EditSaving.isEmpty(this@MainActivity, uri)
                            // A file chosen from those already there is not written over (an old backup, say)
                            if (!wasEmpty) { exists = true; throw java.io.IOException("Exists") }
                            val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("No file")
                            out.use { ua.hramota.data.Backup.write(this@MainActivity, it, pw, ids) { d, n -> busy = Triple(getString(R.string.backup_writing), d, n) } }
                            null
                        } catch (e: ua.hramota.data.Backup.Refused) { e.reason } catch (e: Exception) { ua.hramota.data.Backup.Reason.NO_SPACE } catch (e: OutOfMemoryError) { ua.hramota.data.Backup.Reason.NO_SPACE }
                    }
                }
            } finally { pw.fill(' '); ua.hramota.data.Vault.release() }
            // Only a file made for this backup is removed again (one chosen from those already there stays)
            if (reason != null && wasEmpty) { val app = applicationContext; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EditSaving.remove(app, uri) } }
            busy = null
            android.widget.Toast.makeText(this@MainActivity, getString(when {
                exists -> R.string.create_exists
                else -> when (reason) {
                null -> R.string.backup_written
                ua.hramota.data.Backup.Reason.LOCKED -> R.string.backup_locked
                else -> R.string.backup_failed
            } }), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDictionaries() {
        lifecycleScope.launch { dictionaries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ua.hramota.data.Dictionaries.list(this@MainActivity) } }
    }

    /** A dictionary from the chosen files (its parts, or one ZIP); kept in Hramota, used offline. */
    private fun onDictionaryFiles(uris: List<Uri>) {
        busy = Triple(getString(R.string.dict_adding), 0, 0)
        lifecycleScope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { ua.hramota.data.Dictionaries.add(this@MainActivity, uris) { Recents.displayName(this@MainActivity, it) } } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            }
            busy = null
            refreshDictionaries()
            android.widget.Toast.makeText(this@MainActivity, if (name != null) getString(R.string.dict_added, name) else getString(R.string.dict_failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun restore(uri: Uri, pw: CharArray) {
        busy = Triple(getString(R.string.backup_reading), 0, 0)
        lifecycleScope.launch {
            var reason: ua.hramota.data.Backup.Reason? = null
            val result = try {
                ua.hramota.data.Vault.work.withLock {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { ua.hramota.data.Vault.holding { contentResolver.openInputStream(uri)?.use { ua.hramota.data.Backup.restore(this@MainActivity, it, pw) } } }
                        catch (e: ua.hramota.data.Backup.Refused) { reason = e.reason; null }
                        catch (e: Exception) { reason = ua.hramota.data.Backup.Reason.WRONG_PASSWORD_OR_DAMAGED; null }
                        catch (e: OutOfMemoryError) { reason = ua.hramota.data.Backup.Reason.NO_SPACE; null }
                    }
                }
            } finally { pw.fill(' ') }
            busy = null
            if (result == null) { restoreProblem = reason ?: ua.hramota.data.Backup.Reason.WRONG_PASSWORD_OR_DAMAGED; restoreWrong = true; return@launch }
            restoreUri = null
            recents.reload()
            android.widget.Toast.makeText(this@MainActivity, getString(R.string.backup_restored, result.added, result.updated, result.documents), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        vaultOn = ua.hramota.data.Vault.isOn(this)
        applySecureScreen()
        refreshDictionaries()
        if (skipResume) { skipResume = false; return }
        // Coming to Hramota while locked: ask straight away
        if (vaultOn && ua.hramota.data.Vault.key() == null && !keyLost) unlock()
    }

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        skipResume = true
        lifecycleScope.launch {
            try {
                when (ua.hramota.data.Vault.unlock(this@MainActivity, getString(R.string.unlock_title), getString(R.string.unlock_subtitle))) {
                    ua.hramota.data.Vault.Outcome.OPEN -> recents.reload()
                    // No confirmation screen was shown: the next return to Hramota asks as usual
                    ua.hramota.data.Vault.Outcome.KEY_LOST -> { keyLost = true; skipResume = false }
                    ua.hramota.data.Vault.Outcome.NOT_CONFIRMED -> android.widget.Toast.makeText(this@MainActivity, getString(R.string.unlock_not_confirmed), android.widget.Toast.LENGTH_LONG).show()
                    ua.hramota.data.Vault.Outcome.CANCELLED -> Unit
                }
            } finally { unlocking = false }
        }
    }

    /** Turns locked storage on or off; everything kept is sealed or opened again, then a word on how it went. */
    private fun setVault(on: Boolean) {
        if (ua.hramota.data.Vault.work.isLocked) return
        lifecycleScope.launch {
            val ok = ua.hramota.data.Vault.work.withLock { try {
                if (on) ua.hramota.data.Vault.enable(this@MainActivity, getString(R.string.vault_title), getString(R.string.unlock_subtitle))
                else ua.hramota.data.Vault.disable(this@MainActivity)
            } catch (e: Exception) { false } }
            vaultOn = ua.hramota.data.Vault.isOn(this@MainActivity)
            applySecureScreen()
            recents.reload()
            android.widget.Toast.makeText(this@MainActivity, getString(if (!ok) R.string.vault_failed else if (on) R.string.vault_on_done else R.string.vault_off_done), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openHanded(intent)
    }

    /** A document opened from another app, handed over to open here (in Hramota's window). */
    private fun openHanded(handed: Intent?) {
        if (handed?.action != ACTION_OPEN || handed.data == null) return
        val open = Intent(this, ViewerInlineActivity::class.java).setAction(Intent.ACTION_VIEW).setData(handed.data)
            .addFlags(handed.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        handed.extras?.let { open.putExtras(it) }
        // Used once: not again when the window is made again
        setIntent(Intent(this, MainActivity::class.java))
        try { startActivity(open) } catch (e: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        compareFirst?.let { outState.putString(KEY_FIRST, it.toString()) }
        compareFirstName?.let { outState.putString(KEY_FIRST_NAME, it) }
        (savingRescue?.path ?: savingRescuePath)?.let { outState.putString(KEY_RESCUE, it) }
        outState.putBoolean(KEY_CHOOSING, choosing)
        outState.putBoolean(KEY_CREATING, creating)
        outState.putBoolean(KEY_COMPARING, comparing)
        outState.putBoolean(KEY_SETTINGS, showSettings)
    }

    private fun onPicked(uri: Uri?) {
        // Back from the file picker without a file: the list to choose from comes back
        if (uri == null) { if (comparing) choosing = true; return }
        // The file's app is asked for its name and lasting access off the main thread (a cloud app can be slow)
        val app = applicationContext
        lifecycleScope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Recents.displayName(app, uri).also { recents.add(uri, it) } }
            if (!comparing) openViewer(uri, name) else chosen(uri, name)
        }
    }

    /** A version chosen for comparing: the first one, then the second (from the list again). */
    private fun chosen(uri: Uri, name: String) {
        val first = compareFirst
        if (first == null) {
            compareFirst = uri
            compareFirstName = name
            choosing = true
        } else {
            comparing = false
            compareFirst = null
            startActivity(
                Intent(this, CompareActivity::class.java)
                    .putExtra(CompareActivity.EXTRA_OLDER, first.toString())
                    .putExtra(CompareActivity.EXTRA_NEWER, uri.toString())
                    .putExtra(CompareActivity.EXTRA_OLDER_NAME, compareFirstName ?: "")
                    .putExtra(CompareActivity.EXTRA_NEWER_NAME, name)
            )
            compareFirstName = null
        }
    }

    private fun openViewer(uri: Uri, name: String) {
        // Each file in its own window: the viewer is declared with documentLaunchMode="intoExisting"
        val intent = Intent(this, ViewerActivity.viewerClass(this)).setAction(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(ViewerActivity.EXTRA_NAME, name)
            .putExtra(ViewerActivity.EXTRA_TOKEN, ViewerActivity.TOKEN)
        try {
            startActivity(intent)
            recents.add(uri, name)
        } catch (e: SecurityException) {
            // A file opened once from another app ("Open with") gives access only that time:
            // from the list it cannot be opened again; it stays there, shown as such
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

    companion object {
        const val KEY_FIRST = "compare_first"
        const val KEY_FIRST_NAME = "compare_first_name"
        const val KEY_RESCUE = "rescue_saving"
        const val KEY_CREATING = "creating"
        /** A document handed over by the viewer (opened from another app), to open inside Hramota's window. */
        const val ACTION_OPEN = "ua.hramota.OPEN"
        const val KEY_CHOOSING = "compare_choosing"
        const val KEY_COMPARING = "comparing"
        const val KEY_SETTINGS = "settings"
    }
}
