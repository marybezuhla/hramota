package ua.hramota.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.doc.DocxWriter
import ua.hramota.doc.WBlock
import ua.hramota.ui.AppTheme
import ua.hramota.ui.TopBar
import ua.hramota.ui.applySecureScreen
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** One scanned page: its picture (straightened, in the scanner's own folder) and the text read from it. */
class ScanPage(val id: Long, val file: File, val width: Int, val height: Int) {
    var lines by mutableStateOf<List<OcrLine>>(emptyList())
    /** The text as the person corrected it (paragraphs separated by empty lines). */
    var text by mutableStateOf("")
    var reading by mutableStateOf(true)
    var failed by mutableStateOf(false)
}

/**
 * The scanner: pages photographed with the phone's own camera app (Hramota has no camera
 * permission) or picked from photos; their text read on the phone. Saved as a Word document, a PDF
 * with the text under the pictures (it can be searched and copied), copied or shared. The pictures
 * stay in Hramota's temporary folder only while the scanner is open.
 */
class ScanActivity : ComponentActivity() {
    class Holder : ViewModel() {
        val pages = mutableStateListOf<ScanPage>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reading = Mutex()
        var shot: Uri? = null
        override fun onCleared() { scope.cancel() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        TextRecognizer.get(this).hold()
        applySecureScreen()
        val held = ViewModelProvider(this)[Holder::class.java]
        // A scanner opened afresh: nothing left from before
        if (savedInstanceState == null) Scans.dir(this).listFiles()?.forEach { it.delete() }
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ScanScreen(held, onBack = { finish() }) } } }
    }

    override fun onDestroy() {
        super.onDestroy()
        val recognizer = TextRecognizer.get(this)
        CoroutineScope(Dispatchers.Default).launch { recognizer.letGo() }
        if (isFinishing) Scans.dir(this).listFiles()?.forEach { it.delete() }
    }
}

internal object Scans {
    private val ids = AtomicLong(System.currentTimeMillis())
    fun dir(context: Context) = File(context.cacheDir, "scan").apply { mkdirs() }
    fun newShot(context: Context): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", File(dir(context), "shot-${ids.incrementAndGet()}.jpg"))
    fun shotFile(context: Context, uri: Uri): File? = uri.lastPathSegment?.let { File(dir(context), it.substringAfterLast('/')) }?.takeIf { it.exists() }

    /** The largest side a page is kept at: enough for small print, not more than the reader needs. */
    private const val MAX_SIDE = 3000

    /** A picture as a page: turned upright (as the camera wrote it), scaled down, kept as JPEG. */
    fun page(context: Context, uri: Uri): ScanPage? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val orientation = try { cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1 } catch (e: Exception) { 1 }
        val m = Matrix()
        when (orientation) {
            3 -> m.postRotate(180f); 6 -> m.postRotate(90f); 8 -> m.postRotate(270f)
            2 -> m.postScale(-1f, 1f); 4 -> m.postScale(1f, -1f)
        }
        val k = min(1f, MAX_SIDE.toFloat() / max(raw.width, raw.height))
        if (k < 1f) m.postScale(k, k)
        val bmp = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        return save(context, bmp)
    }

    fun save(context: Context, bmp: Bitmap): ScanPage {
        val id = ids.incrementAndGet()
        val f = File(dir(context), "page-$id.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val page = ScanPage(id, f, bmp.width, bmp.height)
        bmp.recycle()
        return page
    }

    /** The page turned a quarter to the right (a photo taken sideways), as a new page to read again. */
    fun turned(context: Context, p: ScanPage): ScanPage? {
        val bmp = BitmapFactory.decodeFile(p.file.path) ?: return null
        val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(90f) }, true)
        if (t !== bmp) bmp.recycle()
        p.file.delete()
        return save(context, t)
    }

    fun thumbnail(p: ScanPage): Bitmap? = try {
        var s = 1
        while (max(p.width, p.height) / (s * 2) >= 480) s *= 2
        BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }

    /** All pages' text: pages one after another, as corrected. */
    fun allText(pages: List<ScanPage>) = pages.joinToString("\n\n") { it.text.trim() }.trim()

    fun docx(pages: List<ScanPage>, withPictures: Boolean, lang: String): ByteArray {
        val blocks = ArrayList<WBlock>()
        pages.forEachIndexed { i, p ->
            if (i > 0) blocks += WBlock.PageBreak
            if (withPictures) {
                val bytes = p.file.readBytes()
                blocks += WBlock.Picture(bytes, false, p.width, p.height, p.text.take(200))
            }
            for (para in p.text.split(Regex("\n\\s*\n"))) if (para.isNotBlank()) blocks += WBlock.Para(para.trim().replace(Regex("\\s*\n\\s*"), " "))
        }
        return DocxWriter.write(blocks, lang)
    }

    /**
     * A PDF of the pages as photographed, each word's text laid under its picture (all but invisible):
     * the PDF can be searched and its text copied. Corrected text no longer matches the words found
     * on the picture, so for a page whose text was changed the corrected text is laid in line by line.
     */
    fun pdf(pages: List<ScanPage>, out: java.io.OutputStream) {
        val doc = PdfDocument()
        try {
            // A4 at 72 points per inch; the photo fitted in, its proportions kept
            val pw = 595; val ph = 842
            val hidden = Paint().apply { color = android.graphics.Color.argb(1, 0, 0, 0); isAntiAlias = true }
            pages.forEachIndexed { i, p ->
                val landscape = p.width > p.height
                val (w, h) = if (landscape) ph to pw else pw to ph
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = page.canvas
                val k = min(w.toFloat() / p.width, h.toFloat() / p.height)
                val ox = (w - p.width * k) / 2; val oy = (h - p.height * k) / 2
                val bmp = BitmapFactory.decodeFile(p.file.path)
                if (bmp != null) {
                    c.drawBitmap(bmp, null, android.graphics.RectF(ox, oy, ox + p.width * k, oy + p.height * k), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                }
                val original = Reflow.paragraphs(p.lines).joinToString("\n\n")
                if (p.text.trim() == original.trim()) {
                    for (l in p.lines) for (word in l.words) fit(c, hidden, word.text, ox + word.box.left * k, oy + word.box.top * k, word.box.width() * k, word.box.height() * k)
                } else {
                    // Corrected: the text as lines from the top of the page, in small letters
                    hidden.textScaleX = 1f; hidden.textSize = 8f
                    var y = oy + 10f
                    for (line in p.text.lines()) { c.drawText(line, ox + 4f, y, hidden); y += 9f }
                }
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    private fun fit(c: android.graphics.Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        if (text.isBlank() || w <= 0f || h <= 0f) return
        paint.textScaleX = 1f
        paint.textSize = h * 0.8f
        val natural = paint.measureText(text)
        if (natural > 0f) paint.textScaleX = (w / natural).coerceIn(0.2f, 5f)
        c.drawText(text, x, y + h * 0.8f, paint)
    }
}

@Composable
private fun ScanScreen(held: ScanActivity.Holder, onBack: () -> Unit) {
    val context = LocalContext.current
    val pages = held.pages
    var withPictures by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun read(p: ScanPage) {
        p.reading = true; p.failed = false
        held.scope.launch {
            try {
                // One page at a time: the models take much memory
                val lines = held.reading.withLock {
                    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p.file.path) } ?: throw java.io.IOException("no picture")
                    try { TextRecognizer.get(context).recognize(bmp) } finally { bmp.recycle() }
                }
                p.lines = lines
                p.text = Reflow.paragraphs(lines).joinToString("\n\n")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                p.failed = true
            } finally { p.reading = false }
        }
    }
    fun add(uris: List<Uri>, deleteAfter: Boolean) {
        if (uris.isEmpty()) return
        held.scope.launch {
            for (u in uris) {
                val p = withContext(Dispatchers.IO) { try { Scans.page(context, u) } catch (e: Throwable) { null } finally { if (deleteAfter) Scans.shotFile(context, u)?.delete() } }
                if (p == null) { Toast.makeText(context, context.getString(R.string.scan_picture_failed), Toast.LENGTH_SHORT).show(); continue }
                pages += p
                read(p)
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> add(uris, false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = held.shot; held.shot = null
        if (u != null) { if (ok) add(listOf(u), true) else Scans.shotFile(context, u)?.delete() }
    }
    fun shoot() {
        val u = Scans.newShot(context)
        held.shot = u
        try { camera.launch(u) } catch (e: android.content.ActivityNotFoundException) {
            held.shot = null
            Toast.makeText(context, context.getString(R.string.scan_no_camera), Toast.LENGTH_LONG).show()
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    val lang = remember { java.util.Locale.getDefault().toLanguageTag() }
    fun saved(ok: Boolean) = Toast.makeText(context, context.getString(if (ok) R.string.scan_saved else R.string.scan_save_failed), Toast.LENGTH_SHORT).show()
    fun write(uri: Uri?, what: (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        busy = true
        held.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { context.contentResolver.openOutputStream(uri, "wt")!!.use { what(it) }; true } catch (e: Throwable) {
                    // Nothing half-written is left behind
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    false
                }
            }
            busy = false
            saved(ok)
        }
    }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) { uri ->
        val snapshot = pages.toList()
        write(uri) { it.write(Scans.docx(snapshot, withPictures, lang)) }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val snapshot = pages.toList()
        write(uri) { Scans.pdf(snapshot, it) }
    }
    val name = remember { context.getString(R.string.scan_file_name) + " " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.ROOT).format(java.util.Date()) }
    val ready = pages.isNotEmpty() && pages.none { it.reading } && !busy

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.scan), if (pages.isEmpty()) null else stringResource(R.string.scan_pages, pages.size), onBack = onBack) {
            Box {
                IconButton(onClick = { menu = true }, enabled = ready) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.scan_copy)) }, onClick = {
                        menu = false
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.scan), Scans.allText(pages)))
                        Toast.makeText(context, context.getString(R.string.scan_copied), Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.scan_share)) }, onClick = {
                        menu = false
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Scans.allText(pages))
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(if (withPictures) R.string.scan_docx_text_only else R.string.scan_docx_with_pictures)) }, onClick = { menu = false; withPictures = !withPictures })
                }
            }
        }
        if (pages.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { shoot() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_camera)) }
                FilledTonalButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_photos)) }
                Text(stringResource(R.string.scan_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, p -> PageRow(i, p, held, onRead = { read(it) }) }
                item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shoot() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_camera_more)) }
                        OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_photos_more)) }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy || pages.any { it.reading }) CircularProgressIndicator(Modifier.size(24.dp))
                Button(onClick = { saveDocx.launch("$name.docx") }, enabled = ready, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_save_docx)) }
                Button(onClick = { savePdf.launch("$name.pdf") }, enabled = ready, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_save_pdf)) }
            }
        }
    }
}

@Composable
private fun PageRow(i: Int, p: ScanPage, held: ScanActivity.Holder, onRead: (ScanPage) -> Unit) {
    val context = LocalContext.current
    val thumb = remember(p.id) { Scans.thumbnail(p)?.asImageBitmap() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_page, i + 1), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                held.scope.launch {
                    val t = withContext(Dispatchers.IO) { Scans.turned(context, p) } ?: return@launch
                    val at = held.pages.indexOf(p)
                    if (at >= 0) { held.pages[at] = t; onRead(t) }
                }
            }, enabled = !p.reading) { Text(stringResource(R.string.scan_turn)) }
            TextButton(onClick = { held.pages.remove(p); p.file.delete() }, enabled = !p.reading) { Text(stringResource(R.string.scan_remove), color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (thumb != null) Image(thumb, contentDescription = stringResource(R.string.scan_page, i + 1), contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp, 128.dp))
            Box(Modifier.weight(1f)) {
                when {
                    p.reading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp)); Text(stringResource(R.string.scan_reading))
                    }
                    p.failed -> Column {
                        Text(stringResource(R.string.scan_read_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onRead(p) }) { Text(stringResource(R.string.scan_again)) }
                    }
                    else -> OutlinedTextField(
                        p.text, { p.text = it },
                        placeholder = { Text(stringResource(R.string.scan_nothing)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp),
                    )
                }
            }
        }
    }
}
