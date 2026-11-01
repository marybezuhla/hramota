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
import ua.hramota.data.LibraryEntry
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.hramota.data.Recents
import ua.hramota.doc.CsvReader
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
        val name = Recents.displayName(this, uri)
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
                            }
                        },
                        startPosition = startPosition,
                        onPosition = { position, total -> docId?.let { id -> library.setPosition(id, position, total) } },
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
}
