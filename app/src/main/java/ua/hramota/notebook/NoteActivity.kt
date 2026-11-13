package ua.hramota.notebook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.data.Settings
import ua.hramota.data.Vault
import ua.hramota.ink.InkText
import ua.hramota.ui.AppTheme
import ua.hramota.ui.TopBar
import ua.hramota.ui.applySecureScreen
import java.io.File

/** One note, open for reading and writing; saved as it changes. */
class NoteActivity : ComponentActivity() {
    class Holder : ViewModel() {
        var state by mutableStateOf<NoteState?>(null)
        /** A locked note waits for the phone's lock to be given. */
        var askLock by mutableStateOf(false)
        var missing by mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val saving = Mutex()
        var saved = -1
        override fun onCleared() { scope.cancel() }
    }

    private lateinit var held: Holder

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Vault.locked(this)) { Toast.makeText(this, getString(R.string.delete_kept_locked), Toast.LENGTH_LONG).show(); finish(); return }
        applySecureScreen()
        held = ViewModelProvider(this)[Holder::class.java]
        val id = intent.getStringExtra(EXTRA_ID)
        if (held.state == null && id != null) {
            val note = Notebook.read(this, id)
            if (note == null) held.missing = true
            else if (note.meta.locked) {
                held.askLock = true
                lifecycleScope.launch {
                    if (Vault.confirm(this@NoteActivity, getString(R.string.note_locked_title), note.meta.title)) { held.state = NoteState(note.meta, note.blocks); held.askLock = false }
                    else finish()
                }
            } else held.state = NoteState(note.meta, note.blocks)
        }
        if (id == null) { finish(); return }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s = held.state
                    when {
                        held.missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.note_missing)) }
                        s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> NoteScreen(s, held, onBack = { finish() })
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::held.isInitialized) saveNow(held, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::held.isInitialized) held.state?.let { s ->
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { held.saving.withLock { runCatching { Notebook.dropUnusedPictures(app, s.note()) } } }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        fun intent(context: Context, id: String) = Intent(context, NoteActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** Writes the note if it changed since it was last written. */
private fun saveNow(held: NoteActivity.Holder, context: Context) {
    val s = held.state ?: return
    if (s.version == held.saved) return
    val note = s.note().let { it.copy(meta = it.meta.copy(updated = System.currentTimeMillis())) }
    val v = s.version
    val app = context.applicationContext
    held.scope.launch(Dispatchers.IO + NonCancellable) {
        held.saving.withLock {
            try { Notebook.write(app, note); held.saved = v } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(R.string.note_not_saved), Toast.LENGTH_LONG).show() }
            }
        }
    }
}

@Composable
private fun NoteScreen(state: NoteState, held: NoteActivity.Holder, onBack: () -> Unit) {
    val context = LocalContext.current
    // Saved a moment after each change, and when the note is left
    LaunchedEffect(state.version) { if (state.version != held.saved) { delay(700); saveNow(held, context) } }
    // Handwriting read in the background, for search and "to text"
    LaunchedEffect(state.version) {
        delay(1500)
        for (b in state.blocks.filterIsInstance<NBlock.Ink>()) {
            val hash = b.strokes.hashCode()
            if (b.readFor == hash) continue
            val text = if (b.strokes.isEmpty()) "" else try { withContext(Dispatchers.Default) { InkText.read(context, b.strokes) } } catch (e: Throwable) { continue }
            state.recognized(b.id, hash, text)
        }
    }
    BackHandler { saveNow(held, context); onBack() }
    var menu by remember { mutableStateOf(false) }
    var paperAsk by remember { mutableStateOf(false) }
    var bookAsk by remember { mutableStateOf(false) }
    val lang = remember { java.util.Locale.getDefault().toLanguageTag() }
    val name = state.meta.title.ifBlank { context.getString(R.string.note_untitled) }.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(80)

    fun export(kind: String, out: java.io.OutputStream) {
        val note = state.note()
        when (kind) {
            "docx" -> out.write(NoteExport.docx(context, note, lang))
            "pdf" -> NoteExport.pdf(context, note, out)
            "md" -> out.write(NoteExport.markdown(note).toByteArray())
            "png" -> out.write(NoteExport.png(context, note))
        }
    }
    val mime = mapOf("docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "pdf" to "application/pdf", "md" to "text/markdown", "png" to "image/png")
    var saveKind by remember { mutableStateOf("pdf") }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = saveKind
        held.scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { export(kind, it) } }.isSuccess }
            Toast.makeText(context, context.getString(if (ok) R.string.scan_saved else R.string.scan_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
    fun share(kind: String) {
        held.scope.launch {
            // Into the folder of plain copies that the viewer also uses; cleared with it
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "open").apply { mkdirs() }
                    File(dir, "$name.$kind").also { f -> f.outputStream().use { export(kind, it) } }
                }.getOrNull()
            } ?: return@launch
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).setType(mime[kind]).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        held.scope.launch {
            val added = withContext(Dispatchers.IO) {
                val (bytes, png, _) = ua.hramota.ui.cleanPicture { context.contentResolver.openInputStream(uri) } ?: return@withContext null
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val file = runCatching { Notebook.putPicture(context, state.meta.id, bytes, png) }.getOrNull() ?: return@withContext null
                NBlock.Picture(state.newId(), file, o.outWidth, o.outHeight)
            }
            if (added == null) Toast.makeText(context, context.getString(R.string.slide_picture_failed), Toast.LENGTH_SHORT).show() else state.insert(added)
        }
    }
    val books by Notebook.books(context).collectAsState()
    val reading by ua.hramota.speech.ReadAloud.state.collectAsState()
    val settings by Settings.get(context).state.collectAsState()

    if (paperAsk) AlertDialog(
        onDismissRequest = { paperAsk = false },
        title = { Text(stringResource(R.string.note_paper)) },
        text = {
            Column {
                for ((k, l) in listOf("plain" to R.string.note_paper_plain, "lines" to R.string.note_paper_lines, "grid" to R.string.note_paper_grid, "dots" to R.string.note_paper_dots))
                    TextButton(onClick = { paperAsk = false; state.metaChanged(state.meta.copy(paper = k)) }) { Text((if (state.meta.paper == k) "● " else "○ ") + stringResource(l)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { paperAsk = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (bookAsk) AlertDialog(
        onDismissRequest = { bookAsk = false },
        title = { Text(stringResource(R.string.note_move_to)) },
        text = {
            Column {
                TextButton(onClick = { bookAsk = false; state.metaChanged(state.meta.copy(book = null)) }) { Text((if (state.meta.book == null) "● " else "○ ") + stringResource(R.string.notebook_none)) }
                for (b in books) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(Color(b.color), CircleShape))
                    TextButton(onClick = { bookAsk = false; state.metaChanged(state.meta.copy(book = b.id)) }) { Text((if (state.meta.book == b.id) "● " else "○ ") + b.title) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { bookAsk = false }) { Text(stringResource(R.string.cancel)) } },
    )

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val book = books.firstOrNull { it.id == state.meta.book }
        TopBar(book?.title ?: stringResource(R.string.notebook), null, onBack = { saveNow(held, context); onBack() }) {
            TextButton(onClick = { state.metaChanged(state.meta.copy(starred = !state.meta.starred)) }) { Text(if (state.meta.starred) "★" else "☆") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(if (state.meta.pinned) R.string.note_unpin else R.string.note_pin)) }, onClick = { menu = false; state.metaChanged(state.meta.copy(pinned = !state.meta.pinned)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.note_move_to)) }, onClick = { menu = false; bookAsk = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.note_paper)) }, onClick = { menu = false; paperAsk = true })
                    DropdownMenuItem(text = { Text(stringResource(if (state.meta.locked) R.string.note_unlock else R.string.note_lock)) }, enabled = Vault.canUse(context), onClick = { menu = false; state.metaChanged(state.meta.copy(locked = !state.meta.locked)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.read_aloud)) }, onClick = {
                        menu = false
                        val paras = Notebook.plainText(state.blocks).split('\n').filter { it.isNotBlank() }.let { if (state.meta.title.isNotBlank()) listOf(state.meta.title) + it else it }
                        if (paras.isNotEmpty()) ua.hramota.speech.ReadAloud.start(context, ua.hramota.speech.ReadAloud.Document("note:" + state.meta.id, state.meta.title.ifBlank { context.getString(R.string.note_untitled) }, { paras.size }, { i -> paras.getOrElse(i) { "" } }), 0, 0, settings.readSpeed, settings.readVoice)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.social_card)) }, onClick = {
                        menu = false
                        val first = state.blocks.filterIsInstance<NBlock.Text>().map { it.text }.filter { it.isNotBlank() }.joinToString("\n").take(ua.hramota.social.Cards.MAX_CHARS)
                        context.startActivity(ua.hramota.social.QuoteCardActivity.intent(context, first, state.meta.title))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.social_thread)) }, onClick = {
                        menu = false
                        context.startActivity(ua.hramota.social.ThreadActivity.intent(context, Notebook.plainText(state.blocks.filterIsInstance<NBlock.Text>())))
                    })
                    for ((k, l) in listOf("pdf" to R.string.note_share_pdf, "docx" to R.string.note_share_docx, "md" to R.string.note_share_md, "png" to R.string.note_share_png))
                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { menu = false; saveNow(held, context); share(k) })
                    for ((k, l) in listOf("pdf" to R.string.note_save_pdf, "docx" to R.string.note_save_docx, "md" to R.string.note_save_md))
                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { menu = false; saveKind = k; saveAs.launch("$name.$k") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.note_to_bin), color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        saveNow(held, context)
                        val id = state.meta.id
                        held.scope.launch { withContext(Dispatchers.IO) { held.saving.withLock { runCatching { Notebook.toBin(context, id) } } }; onBack() }
                    })
                }
            }
        }
        NoteEditor(state, Modifier.weight(1f), onPickPicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        if (reading.document == "note:" + state.meta.id) ua.hramota.ui.ReadAloudBar(reading, onBookmark = {}, onSettings = {})
    }
}

