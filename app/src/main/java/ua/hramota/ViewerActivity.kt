package ua.hramota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.data.Library
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import ua.hramota.data.LibraryEntry
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.hramota.data.Recents
import ua.hramota.doc.CsvReader
import ua.hramota.doc.book.EpubReader
import ua.hramota.doc.book.Fb2Reader
import ua.hramota.doc.docx.WordBinaryReader
import ua.hramota.doc.sheet.XlsReader
import ua.hramota.doc.slides.PptxReader
import ua.hramota.doc.slides.PptReader
import ua.hramota.doc.slides.SlidePages
import ua.hramota.doc.slides.paragraphs
import ua.hramota.doc.sheet.XlsxReader
import ua.hramota.doc.EncryptedDocumentException
import ua.hramota.doc.Format
import ua.hramota.doc.PdfSource
import ua.hramota.pdf.PdfEngineUnavailableException
import ua.hramota.pdf.PdfPasswordException
import ua.hramota.pdf.PdfiumPages
import ua.hramota.doc.docx.DocxPages
import ua.hramota.doc.docx.DocxReader
import ua.hramota.ui.AppTheme
import ua.hramota.ui.applySecureScreen
import ua.hramota.ui.ViewerScreen
import ua.hramota.ui.ViewerState

/** One file in its own window (a separate card in Recents). */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data
        // Only documents handed over by address (content://); Hramota's own copies only when Hramota itself asks
        // An address with a "user@" part is refused: Android would ignore it and reach the same files
        if (uri == null || uri.scheme != "content" || uri.encodedAuthority?.contains('@') != false) { finish(); return }
        val ownCopy = ua.hramota.data.Shelf.isOwn(this, uri)
        if (ownCopy && intent.getStringExtra(EXTRA_TOKEN) != TOKEN) { finish(); return }
        // A copy kept inside Hramota is opened under the document's own name, not the copy's file name
        val name = (if (ownCopy) intent.getStringExtra(EXTRA_NAME) else null) ?: Recents.displayName(this, uri)
        labelTask(name)
        // Files opened from other apps ("Open with") also appear in the recent list
        if (savedInstanceState == null) Recents.get(applicationContext).add(uri, name)
        val format = formatOf(this, uri, name)
        val library = Library.get(applicationContext)
        val remember = ua.hramota.data.Settings.get(this).state.value.rememberRecent
        // The document as Hramota knows it (by content), found before it is shown
        var docId by mutableStateOf<String?>(null)
        var startPosition by mutableIntStateOf(0)
        // Taking the copy out of Hramota deletes it: asked first
        var confirmDrop by mutableStateOf<String?>(null)
        // A PDF whose notes are being written into a file: into this one, or a copy
        var savingNotes by mutableStateOf<PdfiumPages?>(null)
        // A DOCX being edited lives through turning the phone
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]

        applySecureScreen()
        setContent {
            AppTheme { settings ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val entries by library.entries.collectAsStateWithLifecycle()
                    val state by produceState<ViewerState>(ViewerState.Loading, uri) {
                        // An open PDF is closed if the window goes away before it is shown
                        var opened: java.io.Closeable? = null
                        // Locked storage closed: ask once; if declined, the file still opens, only nothing is remembered
                        if (ua.hramota.data.Vault.locked(this@ViewerActivity)) {
                            asking = true
                            try { ua.hramota.data.Vault.unlock(this@ViewerActivity, getString(R.string.unlock_title), getString(R.string.unlock_subtitle)) } finally { asking = false }
                        }
                        // The temporary copy of a sealed document is removed when this window goes
                        withContext(Dispatchers.IO) { Library.fingerprint(this@ViewerActivity, uri) }?.let { id ->
                            library.update(id) { e ->
                                when {
                                    e != null && ownCopy -> e.copy(openedAt = System.currentTimeMillis())
                                    e != null -> e.copy(title = name, uri = uri.toString(), openedAt = System.currentTimeMillis())
                                    remember -> LibraryEntry(id, name, format.label, uri.toString(), openedAt = System.currentTimeMillis())
                                    else -> null
                                }
                            }
                            startPosition = library.get(id)?.position ?: 0
                            docId = id
                            currentId = id
                        }
                        try {
                        value = withContext(Dispatchers.IO) {
                            try {
                                when {
                                    format == Format.PDF -> openPdf(uri) { opened = it }
                                    format == Format.DOCX -> {
                                        val doc = contentResolver.openInputStream(uri)?.use { DocxReader.read(it) } ?: return@withContext ViewerState.Failed
                                        val reading = doc.readingText()
                                        // The first page shows at once; the rest is laid out while reading
                                        val pages = DocxPages(doc, appFonts(applicationContext), background = true).also { opened = it }
                                        ViewerState.Paged(pages, reading, doc.inspection)
                                    }
                                    format == Format.DOC -> openDoc(uri) { opened = it }
                                    format == Format.XLSX -> ViewerState.Sheets(contentResolver.openInputStream(uri)?.use { XlsxReader.read(it) } ?: return@withContext ViewerState.Failed)
                                    format == Format.PPTX || format == Format.PPT -> {
                                        val deck = contentResolver.openInputStream(uri)?.use { if (format == Format.PPT) PptReader.read(it) else PptxReader.read(it) } ?: return@withContext ViewerState.Failed
                                        ViewerState.Paged(SlidePages(deck, appFonts(applicationContext)), deck.paragraphs { getString(R.string.slide_n, it) }, deck.inspection)
                                    }
                                    format == Format.EPUB || format == Format.FB2 -> ViewerState.Reader(
                                        contentResolver.openInputStream(uri)?.use { if (format == Format.EPUB) EpubReader.read(it) else Fb2Reader.read(it) } ?: return@withContext ViewerState.Failed,
                                    )
                                    format == Format.XLS -> ViewerState.Sheets(contentResolver.openInputStream(uri)?.use { XlsReader.read(it) } ?: return@withContext ViewerState.Failed)
                                    format == Format.CSV -> ViewerState.Table(
                                        contentResolver.openInputStream(uri)?.use { CsvReader.read(it) } ?: return@withContext ViewerState.Failed,
                                    )
                                    format.hasText -> ViewerState.Text(readText(this@ViewerActivity, uri, format), format)
                                    else -> ViewerState.Unsupported(format)
                                }
                            } catch (e: PdfPasswordException) {
                                ViewerState.Password
                            } catch (e: EncryptedDocumentException) {
                                ViewerState.Password
                            } catch (e: Exception) {
                                ViewerState.Failed
                            } catch (e: OutOfMemoryError) {
                                // A file too large or built to exhaust memory: say it cannot be opened
                                ViewerState.Failed
                            } catch (e: StackOverflowError) {
                                ViewerState.Failed
                            }
                        }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            opened?.close()
                            throw e
                        }
                    }
                    // A book goes on the shelf with a copy kept inside Hramota, and its cover picture as the cover
                    LaunchedEffect(state, docId) {
                        val book = (state as? ViewerState.Reader)?.book ?: return@LaunchedEffect
                        val id = docId ?: return@LaunchedEffect
                        val e = library.get(id) ?: return@LaunchedEffect
                        withContext(Dispatchers.IO) {
                            if (e.copy == null && !ownCopy) library.get(id)?.let { ua.hramota.data.Shelf.keepCopy(this@ViewerActivity, it) }
                            if (!ua.hramota.data.Shelf.hasCover(applicationContext, id)) {
                                val bytes = book.cover?.let { book.images[it] } ?: book.blocks.firstNotNullOfOrNull { (it as? ua.hramota.doc.book.BookBlock.Image)?.key?.let(book.images::get) }
                                // Decoded within limits (a picture can be built to exhaust memory)
                                bytes?.let { b -> ua.hramota.ui.decodeFitting(b, 480)?.asAndroidBitmap() }
                                    ?.let { ua.hramota.data.Shelf.saveCover(applicationContext, id, it) }
                            }
                        }
                    }
                    // A document just made opens ready to edit (once: not again when the window is made again)
                    LaunchedEffect(state) {
                        if (!intent.getBooleanExtra(EXTRA_EDIT, false) || held.autoEdited) return@LaunchedEffect
                        when {
                            format == Format.DOCX && state is ViewerState.Paged -> { held.autoEdited = true; startEditing(uri) }
                            format == Format.XLSX && state is ViewerState.Sheets -> { held.autoEdited = true; startSheetEditing(uri, (state as ViewerState.Sheets).workbook) }
                        }
                    }
                    // The first page becomes the document's cover on the shelf
                    LaunchedEffect(state, docId) {
                        val pages = (state as? ViewerState.Paged)?.pages ?: return@LaunchedEffect
                        val id = docId ?: return@LaunchedEffect
                        if (ua.hramota.data.Shelf.hasCover(applicationContext, id)) return@LaunchedEffect
                        pages.render(0, 360)?.let { img -> withContext(Dispatchers.IO) { ua.hramota.data.Shelf.saveCover(applicationContext, id, img.asAndroidBitmap()) } }
                    }
                    // The PDF engine holds the open file: release it when the window goes
                    DisposableEffect(state) {
                        onDispose { ((state as? ViewerState.Paged)?.pages as? java.io.Closeable)?.close() }
                    }
                    savingNotes?.let { pdf ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { savingNotes = null },
                            title = { androidx.compose.material3.Text(getString(R.string.pdf_save_notes)) },
                            text = { androidx.compose.material3.Text(getString(R.string.pdf_save_notes_hint)) },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    savingNotes = null
                                    docId?.let { id ->
                                        lifecycleScope.launch {
                                            val newId = withContext(Dispatchers.IO) { saveNotesInto(pdf, uri, id) }
                                            android.widget.Toast.makeText(this@ViewerActivity, getString(if (newId != null) R.string.pdf_notes_saved else R.string.pdf_notes_not_saved), android.widget.Toast.LENGTH_LONG).show()
                                            // The file is different now: opened again (a kept copy through a fresh temporary file),
                                            // so its new notes show and the next save builds on them
                                            if (newId != null) reopen(newId)
                                        }
                                    }
                                }) { androidx.compose.material3.Text(getString(R.string.pdf_into_file)) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    savingNotes = null
                                    pendingCopy = pdf
                                    saveCopy.launch(name.substringBeforeLast('.', name) + " " + getString(R.string.pdf_with_notes_suffix) + ".pdf")
                                }) { androidx.compose.material3.Text(getString(R.string.pdf_as_copy)) }
                            },
                        )
                    }
                    confirmDrop?.let { id ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmDrop = null },
                            text = { androidx.compose.material3.Text(getString(R.string.drop_confirm, name.substringBeforeLast('.', name))) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDrop = null; library.dropCopy(id) }) { androidx.compose.material3.Text(getString(R.string.drop_go), color = MaterialTheme.colorScheme.error) } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDrop = null }) { androidx.compose.material3.Text(getString(R.string.cancel)) } },
                        )
                    }
                    val editor = held.editor
                    // A save finished (perhaps while the window was being made again): said and acted on here
                    held.outcome?.let { o -> LaunchedEffect(o) { held.outcome = null; showOutcome(o, name) } }
                    held.slides?.let { slideEditor ->
                        val pages = (state as? ViewerState.Paged)?.pages
                        ua.hramota.ui.SlideEditorScreen(name, slideEditor, held.saving, onSave = { saveEdits(uri, docId, name) }, onClose = { held.slides = null },
                            thumbnail = pages?.let { pg -> { i: Int, w: Int -> pg.render(i, w) } }, aspect = pages?.let { 1f / it.aspect(0) } ?: (16f / 9f))
                        return@Surface
                    }
                    held.sheet?.let { sheetEditor ->
                        ua.hramota.ui.SheetEditorScreen(name, sheetEditor, held.saving, onSave = { saveEdits(uri, docId, name) }, onClose = { held.sheet = null })
                        return@Surface
                    }
                    if (editor != null) {
                        ua.hramota.ui.DocxEditorScreen(name, editor, held.saving,
                            onSave = { saveEdits(uri, docId, name) },
                            onClose = { held.editor = null },
                            author = settings.commentAuthor,
                            onAuthor = { who -> ua.hramota.data.Settings.get(this@ViewerActivity).update { it.copy(commentAuthor = who) } },
                            startWithComments = held.withComments)
                        return@Surface
                    }
                    ViewerScreen(
                        name, state, onBack = { finishAndRemoveTask() },
                        startInReading = settings.docxReading,
                        showInspector = settings.inspector,
                        bookmarks = docId?.let { entries[it]?.bookmarks }.orEmpty(),
                        onToggleBookmark = { page ->
                            docId?.let { id ->
                                if (library.get(id) == null) library.update(id) { LibraryEntry(id, name, format.label, uri.toString(), openedAt = System.currentTimeMillis()) }
                                library.toggleBookmark(id, page)
                            }
                        },
                        startPosition = startPosition,
                        onPosition = { position, total -> docId?.let { id -> library.setPosition(id, position, total) } },
                        menu = docId?.let { id ->
                            val e = entries[id]
                            listOfNotNull<Pair<String, () -> Unit>>(
                                // Word documents can be edited: text, bold and other marks, headings, lists
                                if (format == Format.DOCX && state is ViewerState.Paged) getString(R.string.edit_open) to { startEditing(uri); Unit } else null,
                                // Excel workbooks: cells and formulas
                                (state as? ViewerState.Sheets)?.takeIf { format == Format.XLSX }?.let { s -> getString(R.string.edit_open) to { startSheetEditing(uri, s.workbook); Unit } },
                                // Old Word and Excel files: kept as new ones, which can then be edited
                                if (format == Format.DOC && state is ViewerState.Paged) getString(R.string.save_as_docx) to { held.converting = true; saveAsDocx.launch(name.substringBeforeLast('.', name) + ".docx"); Unit } else null,
                                if (format == Format.XLS && state is ViewerState.Sheets) getString(R.string.save_as_xlsx) to { held.converting = true; saveAsXlsx.launch(name.substringBeforeLast('.', name) + ".xlsx"); Unit } else null,
                                // PowerPoint: the slides' text
                                if (format == Format.PPTX && state is ViewerState.Paged) getString(R.string.edit_open) to { startSlideEditing(uri); Unit } else null,
                                // Word comments: read, answered with new ones, removed (in the editing view)
                                (state as? ViewerState.Paged)?.inspection?.comments?.takeIf { format == Format.DOCX && it > 0 }?.let { n ->
                                    getString(R.string.comments_count, n) to { startEditing(uri, withComments = true); Unit }
                                },
                                // On the shelf = kept inside Hramota; taking it off keeps bookmarks and notes
                                if (e?.copy == null) getString(R.string.shelf_save) to {
                                    if (library.get(id) == null) library.update(id) { LibraryEntry(id, name, format.label, uri.toString(), openedAt = System.currentTimeMillis()) }
                                    lifecycleScope.launch {
                                        val ok = withContext(Dispatchers.IO) { library.get(id)?.let { ua.hramota.data.Shelf.keepCopy(this@ViewerActivity, it) } == true }
                                        android.widget.Toast.makeText(this@ViewerActivity, getString(if (ok) R.string.shelf_copy_kept else R.string.shelf_copy_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    Unit
                                } else if (!ownCopy) getString(R.string.shelf_drop_copy) to { confirmDrop = id; Unit } else null,
                                // Notes as text, sent where the person chooses
                                // A PDF's notes written into the file itself (or a copy), for other apps to see
                                ((state as? ViewerState.Paged)?.pages as? PdfiumPages)?.takeIf { e?.notes?.isNotEmpty() == true }?.let { pdf ->
                                    getString(R.string.pdf_save_notes) to { savingNotes = pdf; Unit }
                                },
                                (state as? ViewerState.Reader)?.book?.takeIf { e?.notes?.isNotEmpty() == true }?.let { book ->
                                    getString(R.string.notes_export) to {
                                        // Kept within what one message between apps can carry
                                        val text = ua.hramota.ui.notesText(book, name, e!!.notes).take(200_000)
                                        try {
                                            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(android.content.Intent.EXTRA_SUBJECT, book.title.ifEmpty { name }).putExtra(android.content.Intent.EXTRA_TEXT, text), null))
                                        } catch (_: Exception) {}
                                        Unit
                                    }
                                },
                            )
                        }.orEmpty(),
                        readSettings = settings,
                        notes = docId?.let { entries[it]?.notes }.orEmpty(),
                        onNote = { n ->
                            docId?.let { id ->
                                if (library.get(id) == null) library.update(id) { LibraryEntry(id, name, format.label, uri.toString(), openedAt = System.currentTimeMillis()) }
                                library.putNote(id, n)
                            }
                        },
                        onRemoveNote = { at -> docId?.let { library.removeNote(it, at) } },
                        onReadSettings = { change -> ua.hramota.data.Settings.get(this@ViewerActivity).update(change) },
                    )
                }
            }
        }
    }

    /**
     * Old Word pages laid out like DOCX. A file whose formatting cannot be read (Word 6/95, damaged
     * tables of formatting) still shows its text.
     */
    private fun openDoc(uri: android.net.Uri, onOpen: (java.io.Closeable) -> Unit): ViewerState = try {
        val doc = contentResolver.openInputStream(uri)?.use { WordBinaryReader.read(it) } ?: return ViewerState.Failed
        val reading = doc.readingText()
        val pages = DocxPages(doc, appFonts(applicationContext), background = true).also(onOpen)
        ViewerState.Paged(pages, reading, doc.inspection)
    } catch (e: EncryptedDocumentException) {
        throw e
    } catch (e: Exception) {
        ViewerState.Text(readText(this, uri, Format.DOC), Format.DOC)
    } catch (e: StackOverflowError) {
        ViewerState.Text(readText(this, uri, Format.DOC), Format.DOC)
    }

    /**
     * PDF through PDFium in an isolated process. Only if that process cannot start at all does it
     * fall back to the phone's own PDF engine; a file that crashes the engine is never retried here.
     */
    // The PDF waiting for the place of its copy with notes
    private var pendingCopy: PdfiumPages? = null
    private val saveCopy = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        val pdf = pendingCopy ?: return@registerForActivityResult
        pendingCopy = null
        if (target == null) return@registerForActivityResult
        val id = currentId ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val notes = Library.get(applicationContext).get(id)?.notes.orEmpty()
                    contentResolver.openFileDescriptor(target, "wt")?.use { pdf.saveNotes(notes, ::noteArgb, it) } == true
                } catch (e: Exception) { false }
            }
            if (!ok) try { android.provider.DocumentsContract.deleteDocument(contentResolver, target) } catch (_: Exception) {}
            android.widget.Toast.makeText(this@ViewerActivity, getString(if (ok) R.string.pdf_notes_saved else R.string.pdf_copy_not_saved), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private var currentId: String? = null

    /** Opens the DOCX for editing (off the main thread); a file that cannot be edited is said so. */
    private fun startEditing(uri: android.net.Uri, withComments: Boolean = false) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        held.withComments = withComments
        val app = applicationContext
        held.scope.launch {
            val edit = withContext(Dispatchers.IO) {
                try { app.contentResolver.openInputStream(uri)?.use { ua.hramota.doc.docx.edit.DocxEdit.open(it.buffered()) }?.let { ua.hramota.ui.EditorState(it) } }
                catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            }
            if (edit == null) android.widget.Toast.makeText(app, app.getString(R.string.edit_cannot_open), android.widget.Toast.LENGTH_LONG).show()
            else held.editor = edit
        }
    }

    /** Opens the PPTX for editing its text (off the main thread). */
    private fun startSlideEditing(uri: android.net.Uri) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        val app = applicationContext
        held.scope.launch {
            val edit = withContext(Dispatchers.IO) {
                try { app.contentResolver.openInputStream(uri)?.use { ua.hramota.doc.slides.edit.PptxEdit.open(it.buffered()) }?.let { ua.hramota.ui.SlideEditorState(it) } }
                catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            }
            if (edit == null) android.widget.Toast.makeText(app, app.getString(R.string.edit_cannot_open), android.widget.Toast.LENGTH_LONG).show()
            else held.slides = edit
        }
    }

    /** Opens the XLSX for editing (off the main thread), shown as the workbook already read. */
    private fun startSheetEditing(uri: android.net.Uri, workbook: ua.hramota.doc.sheet.Workbook) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        val app = applicationContext
        held.scope.launch {
            val edit = withContext(Dispatchers.IO) {
                try { app.contentResolver.openInputStream(uri)?.use { ua.hramota.doc.sheet.edit.XlsxEdit.open(it.buffered()) }?.let { ua.hramota.ui.SheetEditorState(it, workbook) } }
                catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            }
            if (edit == null) android.widget.Toast.makeText(app, app.getString(R.string.edit_cannot_open), android.widget.Toast.LENGTH_LONG).show()
            else held.sheet = edit
        }
    }

    /** Saves the edits off the main thread, in work that outlives the window (turning the phone); the outcome is shown when it is back. */
    private fun saveEdits(uri: android.net.Uri, id: String?, name: String) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        if (held.saving) return
        val maker = held.maker() ?: return
        held.saving = true
        val app = applicationContext
        // Locked storage stays open until the file is written (its temporary files would go with locking)
        ua.hramota.data.Vault.hold()
        held.scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { EditSaving.saveInto(app, maker, uri, id, name) }
            } catch (e: Exception) { EditSaving.Outcome(EditSaving.Result.FAILED) }
            finally { ua.hramota.data.Vault.release() }
            held.saving = false
            held.outcome = outcome
        }
    }

    /** What came of a save, once this window shows it. */
    private fun showOutcome(o: EditSaving.Outcome, name: String) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        fun say(id: Int) = android.widget.Toast.makeText(this, getString(id), android.widget.Toast.LENGTH_LONG).show()
        when (o.result) {
            EditSaving.Result.SAVED -> {
                say(R.string.edit_saved)
                held.editor = null; held.sheet = null; held.slides = null
                // Shown again from the saved file
                o.newId?.let { reopen(it) } ?: recreate()
            }
            EditSaving.Result.NOT_ALLOWED, EditSaving.Result.RESTORED, EditSaving.Result.BROKEN -> {
                say(when (o.result) { EditSaving.Result.BROKEN -> R.string.edit_original_broken; EditSaving.Result.RESTORED -> R.string.edit_not_written; else -> R.string.edit_save_copy_hint })
                held.copyPending = true
                held.copyReason = o.result
                val base = name.substringBeforeLast('.', name)
                when {
                    held.sheet != null -> saveSheetCopy.launch("$base.xlsx")
                    held.slides != null -> saveSlidesCopy.launch("$base.pptx")
                    else -> saveEditCopy.launch("$base.docx")
                }
            }
            EditSaving.Result.FAILED -> say(R.string.edit_not_saved)
            EditSaving.Result.LOCKED -> say(R.string.edit_locked_storage)
            EditSaving.Result.COPY_SAVED -> {
                say(if (held.copyReason == EditSaving.Result.NOT_ALLOWED) R.string.edit_saved_copy else R.string.edit_saved_copy_plain)
                held.editor = null; held.sheet = null; held.slides = null
                // The copy is opened in place of the original
                o.copy?.let { target ->
                    startActivity(android.content.Intent(this, ViewerActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(target)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                    finishAndRemoveTask()
                }
            }
        }
    }

    // A copy of the edits saved where the person chooses (the file itself could not be changed)
    private val saveEditCopy = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { target -> onCopyTarget(target) }
    private val saveAsDocx = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { target -> convertInto(target, "docx") }
    private val saveAsXlsx = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { target -> convertInto(target, "xlsx") }

    /** Writes this old Word or Excel file as a new DOCX or XLSX into [target], checks it opens, and opens it. */
    private fun convertInto(target: android.net.Uri?, kind: String) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        val waiting = held.converting
        held.converting = false
        val source = intent.data
        if (target == null || source == null) return
        if (!waiting) { try { android.provider.DocumentsContract.deleteDocument(contentResolver, target) } catch (_: Exception) {}; return }
        val app = applicationContext
        held.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val temp = java.io.File(java.io.File(app.cacheDir, "open").apply { mkdirs() }, "convert-${System.nanoTime()}.$kind")
                try {
                    temp.outputStream().buffered().use { out ->
                        app.contentResolver.openInputStream(source)?.use { input ->
                            if (kind == "docx") ua.hramota.doc.docx.DocxWriter.write(WordBinaryReader.read(input), out, java.util.Locale.getDefault().toLanguageTag())
                            else ua.hramota.doc.sheet.XlsxWriter.write(XlsReader.read(input), out)
                        } ?: return@withContext false
                    }
                    // The new file must open as a document before it is put in place
                    temp.inputStream().buffered().use { if (kind == "docx") DocxReader.read(it) else XlsxReader.read(it) }
                    EditSaving.writeChecked(object : EditSaving.Io {
                        override fun read() = app.contentResolver.openInputStream(target)
                        override fun write(mode: String) = app.contentResolver.openOutputStream(target, mode)
                    }) { temp.inputStream() }
                } catch (e: Exception) { false } catch (e: OutOfMemoryError) { false } finally { temp.delete() }
            }
            if (!ok) {
                try { android.provider.DocumentsContract.deleteDocument(app.contentResolver, target) } catch (_: Exception) {}
                android.widget.Toast.makeText(app, app.getString(R.string.convert_failed), android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            android.widget.Toast.makeText(app, app.getString(R.string.convert_done), android.widget.Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(app, ViewerActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(target)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val saveSlidesCopy = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.presentationml.presentation")) { target -> onCopyTarget(target) }
    private val saveSheetCopy = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { target -> onCopyTarget(target) }

    private fun onCopyTarget(target: android.net.Uri?) {
        val held = androidx.lifecycle.ViewModelProvider(this)[EditHolder::class.java]
        val maker = held.maker()
        val uri = intent.data
        val waiting = held.copyPending
        held.copyPending = false
        if (target == null) return
        // Nothing waiting (the edits are gone): the empty file just made is removed
        if (!waiting || maker == null || uri == null) {
            try { android.provider.DocumentsContract.deleteDocument(contentResolver, target) } catch (_: Exception) {}
            return
        }
        held.saving = true
        val app = applicationContext
        ua.hramota.data.Vault.hold()
        held.scope.launch {
            val outcome = try { withContext(Dispatchers.IO) { EditSaving.saveCopy(app, maker, uri, target) } } catch (e: Exception) { EditSaving.Outcome(EditSaving.Result.FAILED) }
            finally { ua.hramota.data.Vault.release() }
            held.saving = false
            held.outcome = outcome
        }
    }

    /**
     * Writes the notes into the PDF itself: the engine writes a copy with them to a private file, which
     * then replaces the file's content. The document now has other content, so its memory moves to the
     * new fingerprint, without the notes (they are in the file now, shown by any PDF app).
     */
    private suspend fun saveNotesInto(pdf: PdfiumPages, uri: android.net.Uri, id: String): String? {
        val library = Library.get(applicationContext)
        val entry = library.get(id) ?: return null
        // In the folder emptied when Hramota locks: a plain copy is never left behind
        val temp = java.io.File(java.io.File(cacheDir, "open").apply { mkdirs() }, "notes-${System.nanoTime()}.pdf")
        try {
            val written = android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
                .use { pdf.saveNotes(entry.notes, ::noteArgb, it) }
            if (!written || temp.length() == 0L) return null
            // A kept copy (possibly sealed) is replaced in Hramota's storage; another file through its app
            val copy = entry.copy?.let { java.io.File(it) }
            // The engine's output must begin with the document as it is, whatever is replaced
            val shownSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
            if (shownSize <= 0 || temp.length() <= shownSize) return null
            if (contentResolver.openInputStream(uri)?.use { a -> temp.inputStream().use { b -> sameStart(a, b, shownSize) } } != true) return null
            if (ua.hramota.data.Shelf.isOwn(this, uri) && copy != null) {
                if (!EditSaving.replaceKeptCopy(copy, temp)) return null
            } else {
                // Checked above to begin with the original: only the addition is written. Some apps that
                // hold files ignore "append" and keep only what was written: then the whole file is written
                contentResolver.openOutputStream(uri, "wa")?.use { out ->
                    temp.inputStream().use { input -> input.skip(shownSize); input.copyTo(out) }
                } ?: return null
                val now = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                val whole = now == temp.length() && contentResolver.openInputStream(uri)?.use { a -> temp.inputStream().use { b -> sameStart(a, b, now) } } == true
                if (!whole) {
                    contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } } ?: return null
                }
            }
            val newId = Library.fingerprint(this, android.net.Uri.fromFile(temp)) ?: return null
            library.update(newId) { entry.copy(id = newId, notes = emptyList()) }
            if (newId != id) library.update(id) { null }
            // The cover goes with the document
            java.io.File(Library.coversDir(this), "$id.png").takeIf { it.exists() }?.renameTo(java.io.File(Library.coversDir(this), "$newId.png"))
            java.io.File(Library.coversDir(this), "$id.png${ua.hramota.data.Vault.SUFFIX}").takeIf { it.exists() }?.let { it.delete() }
            return newId
        } catch (e: Exception) {
            return null
        } finally {
            temp.delete()
        }
    }

    private fun reopen(id: String) {
        val own = ua.hramota.data.Shelf.isOwn(this, intent.data)
        if (!own) { recreate(); return }
        val e = Library.get(applicationContext).get(id) ?: run { recreate(); return }
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { e.copy?.let { ua.hramota.data.Shelf.copyUri(this@ViewerActivity, it, e.title) } } ?: run { recreate(); return@launch }
            intent.data?.let { ua.hramota.data.Shelf.closeOpened(this@ViewerActivity, it) }
            startActivity(android.content.Intent(this@ViewerActivity, ViewerActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(fresh)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(EXTRA_NAME, e.title).putExtra(EXTRA_TOKEN, TOKEN))
            finishAndRemoveTask()
        }
    }

    /** True if [b] begins with the first [n] bytes of [a]. */
    private fun sameStart(a: java.io.InputStream, b: java.io.InputStream, n: Long): Boolean {
        val x = ByteArray(64 * 1024); val y = ByteArray(64 * 1024)
        fun fill(s: java.io.InputStream, buf: ByteArray, want: Int): Int {
            var k = 0
            while (k < want) { val r = s.read(buf, k, want - k); if (r < 0) break; k += r }
            return k
        }
        var left = n
        while (left > 0) {
            val want = minOf(left, x.size.toLong()).toInt()
            if (fill(a, x, want) != want || fill(b, y, want) != want) return false
            for (i in 0 until want) if (x[i] != y[i]) return false
            left -= want
        }
        return true
    }

    private fun noteArgb(color: Int): Int {
        val c = ua.hramota.ui.NOTE_COLORS[color.coerceIn(0, ua.hramota.ui.NOTE_COLORS.size - 1)]
        return android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    }

    private suspend fun openPdf(uri: android.net.Uri, onOpen: (java.io.Closeable) -> Unit): ViewerState {
        val fd = contentResolver.openFileDescriptor(uri, "r") ?: return ViewerState.Failed
        return try {
            val pages = PdfiumPages.open(this, fd) { contentResolver.openFileDescriptor(uri, "r") }.also(onOpen)
            ViewerState.Paged(pages, inspection = pages.inspection)
        } catch (e: PdfEngineUnavailableException) {
            val again = contentResolver.openFileDescriptor(uri, "r") ?: return ViewerState.Failed
            ViewerState.Paged(PdfSource.open(again).also(onOpen))
        }
    }

    // While a book is read in pages, the volume keys turn them (and make no sound)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val h = ua.hramota.ui.PageKeys.handler
        if (h != null && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            // At the first or last page the key changes the volume as usual
            if (h(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)) { keyTurned = keyCode; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var keyTurned = 0

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == keyTurned) { keyTurned = 0; return true }
        return super.onKeyUp(keyCode, event)
    }

    private var asking = false
    private var startedOnce = false

    override fun onStart() {
        super.onStart()
        // A copy kept under lock is not left on screen once Hramota has locked again
        if (ua.hramota.data.Shelf.isOwn(this, intent.data) && ua.hramota.data.Vault.locked(this)) { finishAndRemoveTask(); return }
        // Another document, after Hramota locked: ask again, so bookmarks and notes are not lost unseen
        if (!startedOnce) { startedOnce = true; return }
        if (ua.hramota.data.Vault.locked(this) && !asking) {
            asking = true
            lifecycleScope.launch {
                try { ua.hramota.data.Vault.unlock(this@ViewerActivity, getString(R.string.unlock_title), getString(R.string.unlock_subtitle)) } finally { asking = false }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A sealed copy opened for reading is plain only while it is shown
        val uri = intent.data
        if (isFinishing && uri != null) ua.hramota.data.Shelf.closeOpened(this, uri)
    }

    /** What is being edited, kept while the window is made again (the phone turned). */
    class EditHolder : androidx.lifecycle.ViewModel() {
        var editor by mutableStateOf<ua.hramota.ui.EditorState?>(null)
        var sheet by mutableStateOf<ua.hramota.ui.SheetEditorState?>(null)
        var slides by mutableStateOf<ua.hramota.ui.SlideEditorState?>(null)

        /** What saving needs from whichever editor is open (taken now: later typing is not part of it). */
        fun maker(): EditSaving.Maker? = editor?.let { EditSaving.docx(it.edit, it.paras) } ?: sheet?.maker() ?: slides?.maker()
        var saving by mutableStateOf(false)
        var outcome by mutableStateOf<EditSaving.Outcome?>(null)
        /** Why the edits went into a copy (what is said when it is saved). */
        var copyReason: EditSaving.Result? = null
        /** A new DOCX or XLSX is waiting for its place (the file picker is open). */
        var converting = false
        /** A new document was opened into editing already. */
        var autoEdited = false
        /** The editing view opens with the list of comments. */
        var withComments = false
        /** The edits wait for the place of their copy (the file picker is open). */
        var copyPending = false
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
        override fun onCleared() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    companion object {
        /** The document's name, for a copy kept inside Hramota (whose file is named by its fingerprint). */
        const val EXTRA_NAME = "ua.hramota.NAME"
        const val EXTRA_TOKEN = "ua.hramota.TOKEN"
        /** Open straight into editing (a document just made). */
        const val EXTRA_EDIT = "ua.hramota.EDIT"
        /** Known only inside this process: proves an own copy was opened by Hramota, not by another app. */
        val TOKEN: String = java.util.UUID.randomUUID().toString()
    }
}

