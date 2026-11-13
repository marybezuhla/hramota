package ua.hramota.social

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.ocr.Reflow
import ua.hramota.ocr.TextRecognizer
import ua.hramota.ui.AppTheme
import ua.hramota.ui.TopBar
import ua.hramota.ui.applySecureScreen
import java.io.File
import java.io.FileOutputStream

/** Pictures being got ready for sharing, in Hramota's temporary folder (emptied when the screen closes). */
object Pictures {
    fun dir(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    /** Writes [bitmaps] as PNG files and opens the screen to hide parts, describe and share them. */
    fun open(context: Context, bitmaps: List<Bitmap>, alts: List<String> = emptyList(), name: String = "hramota") {
        val d = dir(context)
        d.listFiles()?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        val files = bitmaps.mapIndexed { i, b ->
            File(d, "${safe(name)}-${stamp}-${i + 1}.png").also { f -> FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        context.startActivity(Intent(context, PicturesActivity::class.java)
            .putExtra(PicturesActivity.EXTRA_FILES, files.map { it.path }.toTypedArray())
            .putExtra(PicturesActivity.EXTRA_ALTS, alts.toTypedArray()))
    }

    fun safe(s: String) = s.replace(Regex("[^\\p{L}0-9 _-]"), "").trim().take(40).ifEmpty { "hramota" }
}

/** A quotation card: the passage, where it is from, the shape and the look; then hidden parts, a description, sharing. */
class QuoteCardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(); super.onCreate(savedInstanceState); applySecureScreen()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { QuoteCardScreen(text, source) { finish() } } } }
    }

    companion object {
        const val EXTRA_TEXT = "text"; const val EXTRA_SOURCE = "source"
        fun intent(context: Context, text: String, source: String) = Intent(context, QuoteCardActivity::class.java)
            .putExtra(EXTRA_TEXT, text.take(5000)).putExtra(EXTRA_SOURCE, source.take(200))
    }
}

@Composable
internal fun QuoteCardScreen(start: String, startSource: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var quote by rememberSaveable { mutableStateOf(start.trim().take(Cards.MAX_CHARS)) }
    var source by rememberSaveable { mutableStateOf(startSource) }
    var shape by rememberSaveable { mutableStateOf(CardShape.PORTRAIT) }
    var look by rememberSaveable { mutableStateOf(Cards.LOOKS[0].key) }
    val chosen = Cards.LOOKS.first { it.key == look }
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, quote, source, shape, look) {
        value = withContext(Dispatchers.Default) { Cards.render(quote, source, shape, chosen).let { b -> Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true).also { if (it !== b) b.recycle() }.asImageBitmap() } }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.social_card), null, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                preview?.let { Image(it, contentDescription = Cards.alt(quote, source, stringResource(R.string.social_quote_word)), modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(shape.w.toFloat() / shape.h).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) }
                    ?: CircularProgressIndicator()
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in CardShape.entries) FilterChip(shape == s, { shape = s }, label = { Text(s.label) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in Cards.LOOKS) Box(
                    Modifier.size(40.dp).border(if (l.key == look) 3.dp else 1.dp, if (l.key == look) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(3.dp).clickable { look = l.key },
                ) { Canvas(Modifier.fillMaxSize()) { drawRect(Color(l.top)); drawCircle(Color(l.accent), size.minDimension / 5) } }
            }
            OutlinedTextField(quote, { quote = it.take(Cards.MAX_CHARS) }, label = { Text(stringResource(R.string.social_quote)) }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${quote.length}/${Cards.MAX_CHARS}") })
            OutlinedTextField(source, { source = it.replace("\n", " ").take(200) }, label = { Text(stringResource(R.string.social_source)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = {
            val b = Cards.render(quote, source, shape, chosen)
            Pictures.open(context, listOf(b), listOf(Cards.alt(quote, source, context.getString(R.string.social_quote_word))), context.getString(R.string.social_card))
            b.recycle()
        }, enabled = quote.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.social_next)) }
    }
}

/** Counting characters for a network and cutting a long text into a thread of posts. */
class ThreadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(); super.onCreate(savedInstanceState); applySecureScreen()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ThreadScreen(text) { finish() } } } }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        fun intent(context: Context, text: String) = Intent(context, ThreadActivity::class.java).putExtra(EXTRA_TEXT, text.take(100_000))
    }
}

@Composable
internal fun ThreadScreen(start: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(start) }
    var platform by rememberSaveable { mutableStateOf("x") }
    var numbered by rememberSaveable { mutableStateOf(true) }
    val p = SocialText.PLATFORMS.first { it.key == platform }
    val parts = remember(text, platform, numbered) { SocialText.thread(text, p, numbered) }
    val length = remember(text, platform) { SocialText.length(text.trim(), p) }
    fun copy(s: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("post", s))
        Toast.makeText(context, context.getString(R.string.scan_copied), Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.social_thread), null, onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pl in SocialText.PLATFORMS) FilterChip(platform == pl.key, { platform = pl.key }, label = { Text(pl.name) })
                }
            }
            item {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.social_text)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    supportingText = { Text(stringResource(R.string.social_count, length, p.limit), color = if (length > p.limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.social_numbered), modifier = Modifier.weight(1f))
                    Switch(numbered, { numbered = it })
                }
                if (parts.size > 1) Text(stringResource(R.string.social_parts, parts.size), style = MaterialTheme.typography.titleSmall)
            }
            itemsIndexed(parts) { i, s ->
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1} · ${SocialText.length(s, p)}/${p.limit}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { copy(s) }) { Text(stringResource(R.string.copy)) }
                            TextButton(onClick = { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), null)) } }) { Text(stringResource(R.string.share)) }
                        }
                    }
                }
            }
        }
    }
}

/** Pictures ready to go: hide parts of them, describe them (from the text on them), share or save. */
class PicturesActivity : ComponentActivity() {
    class Holder : ViewModel() {
        val files = mutableStateListOf<String>()
        val alts = mutableStateListOf<String>()
        var started = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(); super.onCreate(savedInstanceState); applySecureScreen()
        TextRecognizer.get(this).hold()
        val held = ViewModelProvider(this)[Holder::class.java]
        if (!held.started) {
            held.started = true
            val files = intent.getStringArrayExtra(EXTRA_FILES).orEmpty().filter { File(it).exists() && File(it).parentFile?.canonicalPath == Pictures.dir(this).canonicalPath }
            val picked = intent.getStringExtra(EXTRA_PICK) != null
            held.files.addAll(files)
            val alts = intent.getStringArrayExtra(EXTRA_ALTS).orEmpty()
            held.alts.addAll(files.indices.map { alts.getOrElse(it) { "" } })
            if (files.isEmpty() && !picked) { finish(); return }
        }
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PicturesScreen(held, intent.getStringExtra(EXTRA_PICK) != null) { finish() } } } }
    }

    override fun onDestroy() {
        super.onDestroy()
        val r = TextRecognizer.get(this)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { r.letGo() }
        if (isFinishing) Pictures.dir(this).listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val EXTRA_FILES = "files"; const val EXTRA_ALTS = "alts"
        /** Opened to pick a picture from the phone first. */
        const val EXTRA_PICK = "pick"
        fun pick(context: Context) = Intent(context, PicturesActivity::class.java).putExtra(EXTRA_PICK, "1")
    }
}

@Composable
internal fun PicturesScreen(held: PicturesActivity.Holder, pickFirst: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<Int?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { if (held.files.isEmpty()) onBack(); return@rememberLauncherForActivityResult }
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                // Written anew from its pixels: the camera's metadata (place, time, phone) does not come along
                val (bytes, _, _) = ua.hramota.ui.cleanPicture { context.contentResolver.openInputStream(uri) } ?: return@withContext null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                File(Pictures.dir(context), "picture-${System.currentTimeMillis()}.png").also { out -> FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
            }
            if (f == null) Toast.makeText(context, context.getString(R.string.scan_picture_failed), Toast.LENGTH_SHORT).show()
            else { held.files += f.path; held.alts += "" }
        }
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    if (pickFirst && !asked && held.files.isEmpty()) { asked = true; androidx.compose.runtime.LaunchedEffect(Unit) { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    val saveAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    for (path in held.files) {
                        val f = File(path)
                        val doc = DocumentsContract.createDocument(context.contentResolver, parent, "image/png", f.name.removeSuffix(".png")) ?: error("no file")
                        context.contentResolver.openOutputStream(doc, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }
                }.isSuccess
            }
            Toast.makeText(context, context.getString(if (ok) R.string.scan_saved else R.string.scan_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
    fun uris() = held.files.map { FileProvider.getUriForFile(context, context.packageName + ".files", File(it)) }
    fun describe(i: Int) {
        busy = i
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = BitmapFactory.decodeFile(held.files[i]) ?: return@runCatching ""
                    try { Reflow.paragraphs(TextRecognizer.get(context).recognize(bmp)).joinToString(" ") } finally { bmp.recycle() }
                }.getOrDefault("")
            }
            busy = null
            if (text.isBlank()) Toast.makeText(context, context.getString(R.string.scan_nothing), Toast.LENGTH_SHORT).show()
            else held.alts[i] = context.getString(R.string.social_alt_from_text, text.take(900))
        }
    }

    editing?.let { i -> HideEditor(held.files[i], onDone = { now -> if (now != null) held.files[i] = now; editing = null }); return }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.social_pictures), null, onBack = onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(held.files, key = { _, f -> f }) { i, path ->
                // A new picture each time it is changed (hidden parts): the file name changes with it
                val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bmp?.let { Image(it, contentDescription = held.alts.getOrNull(i)?.ifBlank { null }, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = i }) { Text(stringResource(R.string.social_hide)) }
                        OutlinedButton(onClick = { describe(i) }, enabled = busy == null) { Text(stringResource(R.string.social_describe)) }
                        if (busy == i) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    OutlinedTextField(held.alts.getOrElse(i) { "" }, { held.alts[i] = it }, label = { Text(stringResource(R.string.social_alt)) }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { if (held.alts.getOrElse(i) { "" }.isNotBlank()) TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("alt", held.alts[i]))
                            Toast.makeText(context, context.getString(R.string.scan_copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy)) } })
                }
            }
            item { TextButton(onClick = { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.social_add_picture)) } }
            item { Text(stringResource(R.string.social_alt_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { saveAll.launch(null) }, enabled = held.files.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.social_save)) }
            Button(onClick = {
                val list = uris()
                val send = if (list.size == 1) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, list[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(list))
                // The description goes along as text (where the app takes it)
                held.alts.filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
                send.clipData = ClipData.newRawUri("", list[0]).apply { list.drop(1).forEach { addItem(ClipData.Item(it)) } }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }, enabled = held.files.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share)) }
        }
    }
}

/**
 * Hiding parts of one picture: boxes drawn by finger, or offered where phone numbers, e-mail
 * addresses and card numbers were found in its text; painted over for good on "Done".
 */
@Composable
internal fun HideEditor(path: String, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    if (bitmap == null) { onDone(null); return }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val boxes = remember { mutableStateListOf<Rect>() }
    var black by remember { mutableStateOf(true) }
    var finding by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.social_hide), null, onBack = { onDone(null) })
        Text(stringResource(R.string.social_hide_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val k = minOf(constraints.maxWidth.toFloat() / bitmap.width, constraints.maxHeight.toFloat() / bitmap.height)
            val w = bitmap.width * k; val h = bitmap.height * k
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(k) {
                    detectDragGestures(
                        onDragStart = { drag = it to it },
                        onDrag = { change, _ -> drag = drag?.let { it.first to change.position } },
                        onDragEnd = {
                            drag?.let { (a, b) ->
                                val r = Rect((minOf(a.x, b.x) / k).toInt(), (minOf(a.y, b.y) / k).toInt(), (maxOf(a.x, b.x) / k).toInt(), (maxOf(a.y, b.y) / k).toInt())
                                if (r.width() > 4 && r.height() > 4) boxes += r
                            }
                            drag = null
                        },
                    )
                }
                .pointerInput(k) {
                    // A tap on a box takes it away
                    detectTapGestures { p -> boxes.firstOrNull { it.contains((p.x / k).toInt(), (p.y / k).toInt()) }?.let { boxes.remove(it) } }
                }) {
                Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val fill = if (black) Color.Black else Color.White
                    for (b in boxes) {
                        drawRect(fill.copy(alpha = 0.85f), Offset(b.left * k, b.top * k), Size(b.width() * k, b.height() * k))
                        drawRect(Color(0xFFE53935), Offset(b.left * k, b.top * k), Size(b.width() * k, b.height() * k), style = Stroke(2f))
                    }
                    drag?.let { (a, b) -> drawRect(Color(0xFFE53935), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = Stroke(3f)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                finding = true
                scope.launch {
                    val found = withContext(Dispatchers.Default) { runCatching { Redact.find(TextRecognizer.get(context).recognize(bitmap)) }.getOrDefault(emptyList()) }
                    finding = false
                    found.forEach { f -> if (boxes.none { it == f.box }) boxes += f.box }
                    Toast.makeText(context, context.getString(R.string.social_found, found.size), Toast.LENGTH_SHORT).show()
                }
            }, enabled = !finding) { Text(stringResource(R.string.social_find_private)) }
            if (finding) CircularProgressIndicator(Modifier.size(24.dp))
            FilterChip(black, { black = true }, label = { Text(stringResource(R.string.social_black)) })
            FilterChip(!black, { black = false }, label = { Text(stringResource(R.string.social_white)) })
            TextButton(onClick = { if (boxes.isNotEmpty()) boxes.removeAt(boxes.size - 1) }, enabled = boxes.isNotEmpty()) { Text(stringResource(R.string.edit_undo)) }
        }
        Button(onClick = {
            if (boxes.isEmpty()) { onDone(null); return@Button }
            val list = boxes.toList()
            scope.launch {
                val now = withContext(Dispatchers.IO) {
                    // A new picture from the painted pixels; the original is deleted: what was under the boxes exists nowhere any more
                    val out = Redact.apply(bitmap, list, if (black) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    val f = File(path).let { File(it.parentFile, it.name.removeSuffix(".png").replace(Regex("-h\\d+$"), "") + "-h" + System.currentTimeMillis() + ".png") }
                    FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out.recycle()
                    File(path).delete()
                    f.path
                }
                onDone(now)
            }
        }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text(stringResource(R.string.social_hide_done, boxes.size)) }
    }
}
