package ua.hramota.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import ua.hramota.R
import ua.hramota.data.Vault
import ua.hramota.ink.InkCanvas
import ua.hramota.ink.InkJson
import ua.hramota.ink.InkState
import ua.hramota.ink.Stroke
import ua.hramota.pdf.PageRect
import ua.hramota.pdf.PdfField
import ua.hramota.pdf.PdfSignature
import kotlin.math.max
import kotlin.math.min

/** One's own signature, kept to sign again (sealed with locked storage on). */
object SavedSignature {
    private const val FILE = "signature.json"
    fun load(context: android.content.Context): List<Stroke>? = try {
        Vault.readSmall(context, FILE)?.let { InkJson.readStrokes(JSONObject(String(it)).getJSONArray("strokes")) }
    } catch (e: Exception) { null }
    fun save(context: android.content.Context, strokes: List<Stroke>) = try {
        Vault.writeSmall(context, FILE, JSONObject().put("strokes", InkJson.strokes(strokes)).toString().toByteArray()); true
    } catch (e: Exception) { false }
    fun forget(context: android.content.Context) { Vault.deleteIn(context.filesDir, FILE) }

    /** The signature as a picture (transparent around it, dark blue ink) at most [maxSide] px, and its BGRA bytes for the PDF. */
    fun picture(strokes: List<Stroke>, maxSide: Int = 600): Triple<Int, Int, ByteArray>? {
        val pts = strokes.flatMap { it.points }
        if (pts.isEmpty()) return null
        val pad = 6f
        val x0 = pts.minOf { it.x } - pad; val y0 = pts.minOf { it.y } - pad
        val w = pts.maxOf { it.x } + pad - x0; val h = pts.maxOf { it.y } + pad - y0
        val k = min(4f, maxSide / max(w, h))
        val bw = max(1, (w * k).toInt()); val bh = max(1, (h * k).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-x0 * k, -y0 * k)
        ua.hramota.notebook.NoteExport.drawInk(c, strokes.map { it.copy(color = 0xFF1B2A6B.toInt()) }, k)
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        bmp.recycle()
        val out = ByteArray(bw * bh * 4)
        for (i in px.indices) {
            val p = px[i]
            out[i * 4] = (p and 0xFF).toByte(); out[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((p shr 16) and 0xFF).toByte(); out[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
        }
        return Triple(bw, bh, out)
    }
}

/**
 * Filling in a PDF form and signing it: the fields in a list (as the form names them), a signature
 * drawn by finger or stylus and put on a page. A filled copy is saved; the original stays as it is.
 */
@Composable
fun PdfFormScreen(
    fields: List<PdfField>,
    pageCount: Int,
    aspect: (Int) -> Float,
    render: suspend (Int, Int) -> ImageBitmap?,
    saving: Boolean,
    onClose: () -> Unit,
    onSave: (values: Map<String, String>, signature: PdfSignature?) -> Unit,
) {
    val context = LocalContext.current
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.name, it.value) } } }
    var drawing by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<Stroke>?>(null) }
    var placing by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf<Pair<Int, PageRect>?>(null) }
    val editable = fields.filter { !it.readOnly }
    if (drawing) SignaturePad(onDismiss = { drawing = false }) { s -> drawing = false; strokes = s; placing = true }
    if (placing) strokes?.let { s ->
        SignaturePlacement(s, pageCount, aspect, render, start = placed, onDismiss = { placing = false }) { page, rect -> placing = false; placed = page to rect }
        return
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        TopBar(stringResource(R.string.form_title), if (fields.isEmpty()) null else stringResource(R.string.form_fields, editable.size), onBack = onClose)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fields.isEmpty()) item { Text(stringResource(R.string.form_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(editable, key = { it.name }) { f -> FieldRow(f, values[f.name] ?: "") { values[f.name] = it } }
            item {
                HorizontalDivider()
                Text(stringResource(R.string.sign_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.sign_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { drawing = true }) { Text(stringResource(if (strokes == null) R.string.sign_draw else R.string.sign_redraw)) }
                    if (strokes == null) SavedSignature.load(context)?.let { saved -> OutlinedButton(onClick = { strokes = saved; placing = true }) { Text(stringResource(R.string.sign_use_saved)) } }
                    if (strokes != null) OutlinedButton(onClick = { placing = true }) { Text(stringResource(R.string.sign_move)) }
                    if (strokes != null) TextButton(onClick = { strokes = null; placed = null }) { Text(stringResource(R.string.note_remove), color = MaterialTheme.colorScheme.error) }
                }
                placed?.let { (p, _) -> Text(stringResource(R.string.sign_on_page, p + 1), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = {
            val changed = values.filter { (k, v) -> fields.firstOrNull { it.name == k }?.value != v }
            val sig = strokes?.let { s -> placed?.let { (page, rect) -> SavedSignature.picture(s)?.let { (w, h, bytes) -> PdfSignature(page, rect, w, h, bytes) } } }
            onSave(changed, sig)
        }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(if (saving) R.string.edit_saving else R.string.form_save)) }
    }
}

@Composable
private fun FieldRow(f: PdfField, value: String, onChange: (String) -> Unit) {
    when (f.kind) {
        "check" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(value == "1", { onChange(if (it) "1" else "0") })
            Text(f.label)
        }
        "radio" -> Column {
            Text(f.label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in f.options) FilterChip(value == o, { onChange(o) }, label = { Text(o) })
            }
        }
        "choice", "list" -> Column {
            var open by remember { mutableStateOf(false) }
            Text(f.label, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { open = true }) { Text(value.ifEmpty { "—" }) }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    for (o in f.options) DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onChange(o) })
                }
            }
        }
        else -> OutlinedTextField(value, onChange, label = { Text(f.label) }, singleLine = !f.multiline, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SignaturePad(onDismiss: () -> Unit, onDone: (List<Stroke>) -> Unit) {
    val context = LocalContext.current
    val ink = remember { InkState().apply { width = 2.6f; color = 0xFF1B2A6B.toInt() } }
    var keep by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_draw)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))) {
                    InkCanvas(ink, Modifier.fillMaxSize()) {
                        drawLine(Color(0x552F5597), androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.78f), androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.78f), 2f)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it })
                    Text(stringResource(R.string.sign_keep), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { val s = ink.strokes.toList(); if (keep) SavedSignature.save(context, s); onDone(s) }, enabled = !ink.isEmpty) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { ink.clear() }) { Text(stringResource(R.string.ink_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Where the signature goes: a page, a tap where it is to stand, and its size. */
@Composable
private fun SignaturePlacement(strokes: List<Stroke>, pageCount: Int, aspect: (Int) -> Float, render: suspend (Int, Int) -> ImageBitmap?, start: Pair<Int, PageRect>?, onDismiss: () -> Unit, onDone: (Int, PageRect) -> Unit) {
    var page by remember { mutableStateOf(start?.first ?: (pageCount - 1).coerceAtLeast(0)) }
    // The signature's width as a part of the page's width; its centre on the page
    var size by remember { mutableStateOf(start?.second?.let { it.right - it.left } ?: 0.3f) }
    var center by remember { mutableStateOf(start?.second?.let { ((it.left + it.right) / 2) to ((it.top + it.bottom) / 2) } ?: (0.7f to 0.85f)) }
    val pic = remember(strokes) { SavedSignature.picture(strokes, 400) }
    val sig = remember(pic) {
        pic?.let { (w, h, bgra) ->
            val px = IntArray(w * h) { i -> ((bgra[i * 4 + 3].toInt() and 0xFF) shl 24) or ((bgra[i * 4 + 2].toInt() and 0xFF) shl 16) or ((bgra[i * 4 + 1].toInt() and 0xFF) shl 8) or (bgra[i * 4].toInt() and 0xFF) }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    val ratio = pic?.let { it.second.toFloat() / it.first } ?: 0.35f
    val pageAspect = aspect(page).takeIf { it > 0f } ?: 1.414f
    val image by produceState<ImageBitmap?>(null, page) { value = render(page, 1000) }
    fun rect(): PageRect {
        val w = size; val h = size * ratio / pageAspect
        val l = (center.first - w / 2).coerceIn(0f, 1f - w); val t = (center.second - h / 2).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return PageRect(l, t, l + w, t + h)
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.sign_place), stringResource(R.string.sign_on_page, page + 1), onBack = onDismiss) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("‹") }
            TextButton(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page < pageCount - 1) { Text("›") }
        }
        Text(stringResource(R.string.sign_place_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val w = min(maxWidth.value, maxHeight.value / pageAspect)
            Box(Modifier.size(w.dp, (w * pageAspect).dp).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .pointerInput(page) { detectTapGestures { p -> center = (p.x / this.size.width) to (p.y / this.size.height) } }) {
                image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                val r = rect()
                val density = LocalDensity.current
                sig?.let {
                    Image(it, contentDescription = stringResource(R.string.sign_title), modifier = Modifier
                        .offset { IntOffset((r.left * w * density.density).toInt(), (r.top * w * pageAspect * density.density).toInt()) }
                        .size(((r.right - r.left) * w).dp, ((r.bottom - r.top) * w * pageAspect).dp)
                        .border(1.dp, Color(0xFFE53935)))
                }
            }
        }
        Text(stringResource(R.string.sign_size), modifier = Modifier.padding(horizontal = 16.dp))
        Slider(size, { size = it }, valueRange = 0.1f..0.6f, modifier = Modifier.padding(horizontal = 16.dp))
        Button(onClick = { onDone(page, rect()) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.ok)) }
    }
}

