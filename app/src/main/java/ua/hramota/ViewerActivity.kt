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
import ua.hramota.data.Bookmarks
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
        if (uri == null) { finish(); return }
        // A copy kept inside Hramota is opened under the document's own name, not the copy's file name
        val ownCopy = uri.authority == "$packageName.files"
        val name = (if (ownCopy) intent.getStringExtra(EXTRA_NAME) else null) ?: Recents.displayName(this, uri)
        labelTask(name)
        // Files opened from other apps ("Open with") also appear in the recent list
        if (savedInstanceState == null) Recents(applicationContext).add(uri, name)
        val format = formatOf(this, uri, name)
        val library = Library.get(applicationContext)
        val remember = ua.hramota.data.Settings.get(this).state.value.rememberRecent
        // The document as Hramota knows it (by content), found before it is shown
        var docId by mutableStateOf<String?>(null)
        var startPosition by mutableIntStateOf(0)

        applySecureScreen()
        setContent {
            AppTheme { settings ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val entries by library.entries.collectAsStateWithLifecycle()
                    val state by produceState<ViewerState>(ViewerState.Loading, uri) {
                        // An open PDF is closed if the window goes away before it is shown
                        var opened: java.io.Closeable? = null
                        withContext(Dispatchers.IO) { Library.fingerprint(this@ViewerActivity, uri) }?.let { id ->
                            val old = Bookmarks(applicationContext)
                            val oldMarks = old.pages(uri.toString())
                            library.update(id) { e ->
                                when {
                                    e != null && ownCopy -> e.copy(openedAt = System.currentTimeMillis())
                                    e != null -> e.copy(title = name, uri = uri.toString(), openedAt = System.currentTimeMillis(), bookmarks = e.bookmarks.ifEmpty { oldMarks })
                                    remember || oldMarks.isNotEmpty() -> LibraryEntry(id, name, format.label, uri.toString(), bookmarks = oldMarks, openedAt = System.currentTimeMillis())
                                    else -> null
                                }
                            }
                            // Bookmarks kept by address before are now kept with the document
                            if (oldMarks.isNotEmpty()) old.remove(uri.toString())
                            startPosition = library.get(id)?.position ?: 0
                            docId = id
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
                            if (!e.onShelf) library.update(id) { it?.copy(onShelf = true) }
                            if (e.copy == null && !ownCopy) library.get(id)?.let { ua.hramota.data.Shelf.keepCopy(this@ViewerActivity, it) }
                            if (!java.io.File(Library.coversDir(applicationContext), "$id.png").exists()) {
                                val bytes = book.cover?.let { book.images[it] } ?: book.blocks.firstNotNullOfOrNull { (it as? ua.hramota.doc.book.BookBlock.Image)?.key?.let(book.images::get) }
                                bytes?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleFor(b, 480) }) }
                                    ?.let { ua.hramota.data.Shelf.saveCover(applicationContext, id, it) }
                            }
                        }
                    }
                    // The first page becomes the document's cover on the shelf
                    LaunchedEffect(state, docId) {
                        val pages = (state as? ViewerState.Paged)?.pages ?: return@LaunchedEffect
                        val id = docId ?: return@LaunchedEffect
                        if (java.io.File(Library.coversDir(applicationContext), "$id.png").exists()) return@LaunchedEffect
                        pages.render(0, 360)?.let { img -> withContext(Dispatchers.IO) { ua.hramota.data.Shelf.saveCover(applicationContext, id, img.asAndroidBitmap()) } }
                    }
                    // The PDF engine holds the open file: release it when the window goes
                    DisposableEffect(state) {
                        onDispose { ((state as? ViewerState.Paged)?.pages as? java.io.Closeable)?.close() }
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
                                // A document with bookmarks goes on the shelf
                                if (library.get(id)?.bookmarks?.isNotEmpty() == true) library.update(id) { it?.copy(onShelf = true) }
                            }
                        },
                        startPosition = startPosition,
                        onPosition = { position, total -> docId?.let { id -> library.setPosition(id, position, total) } },
                        menu = docId?.let { id ->
                            val e = entries[id]
                            listOfNotNull<Pair<String, () -> Unit>>(
                                if (e?.onShelf == true) getString(R.string.shelf_remove) to { library.update(id) { it?.copy(onShelf = false) }; Unit }
                                else getString(R.string.shelf_add) to {
                                    library.update(id) { it?.copy(onShelf = true) ?: LibraryEntry(id, name, format.label, uri.toString(), onShelf = true, openedAt = System.currentTimeMillis()) }
                                    Unit
                                },
                                if (e?.copy == null) getString(R.string.shelf_keep_copy) to {
                                    if (library.get(id) == null) library.update(id) { LibraryEntry(id, name, format.label, uri.toString(), openedAt = System.currentTimeMillis()) }
                                    lifecycleScope.launch {
                                        val ok = withContext(Dispatchers.IO) { library.get(id)?.let { ua.hramota.data.Shelf.keepCopy(this@ViewerActivity, it) } == true }
                                        android.widget.Toast.makeText(this@ViewerActivity, getString(if (ok) R.string.shelf_copy_kept else R.string.shelf_copy_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    Unit
                                } else null,
                                // Notes as text, sent where the person chooses
                                (state as? ViewerState.Reader)?.book?.takeIf { e?.notes?.isNotEmpty() == true }?.let { book ->
                                    getString(R.string.notes_export) to {
                                        val text = ua.hramota.ui.notesText(book, name, e!!.notes)
                                        startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(android.content.Intent.EXTRA_SUBJECT, book.title.ifEmpty { name }).putExtra(android.content.Intent.EXTRA_TEXT, text), null))
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
            h(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (ua.hramota.ui.PageKeys.handler != null && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        /** The document's name, for a copy kept inside Hramota (whose file is named by its fingerprint). */
        const val EXTRA_NAME = "ua.hramota.NAME"
    }
}

/** How much to shrink a picture when decoding so its width is about [width] pixels. */
private fun sampleFor(bytes: ByteArray, width: Int): Int {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (o.outWidth / (s * 2) >= width) s *= 2
    return s
}
