package ua.hramota.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.appFonts
import ua.hramota.doc.slides.Deck
import ua.hramota.doc.slides.Frame
import ua.hramota.doc.slides.SlidePages
import ua.hramota.doc.slides.edit.PptxEdit
import java.io.InputStream

private const val EMU = 12700L

/**
 * Placing things on one slide: its picture as it will be saved, its shapes outlined. A touch chooses
 * a shape; dragging moves it, dragging its corner sizes it. A text box or a picture can be added, the
 * chosen shape taken out. Every change is a step to undo in the editor.
 */
@Composable
fun SlideLayoutScreen(state: SlideEditorState, slide: Int, source: () -> InputStream?, onDone: () -> Unit) {
    BackHandler { onDone() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = remember { java.util.Locale.getDefault().stripExtensions().toLanguageTag() }
    var deck by remember { mutableStateOf<Deck?>(null) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    // The chosen shape while it is being dragged (points): shown at once, saved when the finger lifts
    var live by remember { mutableStateOf<Frame?>(null) }
    val index = state.order.indexOf(slide)
    // The slide as it will be saved, drawn again after each change
    LaunchedEffect(state.paras, state.order, state.shapes) {
        delay(120)
        val d = state.preview(source)
        if (d == null) { failed = true; return@LaunchedEffect }
        deck = d
        picture = withContext(Dispatchers.Default) { SlidePages(d, appFonts(context)).render(index, 1600) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val d = deck ?: return@rememberLauncherForActivityResult
        scope.launch {
            val made = withContext(Dispatchers.IO) { cleanPicture { context.contentResolver.openInputStream(uri) } }
            if (made == null) { failed = true; return@launch }
            val (bytes, png, aspect) = made
            // Half the slide's width, in the middle, the picture's own proportions
            var w = d.width * 0.5f; var h = w * aspect
            if (h > d.height * 0.7f) { h = d.height * 0.7f; w = h / aspect }
            state.addPicture(slide, bytes, png, longArrayOf(((d.width - w) / 2 * EMU).toLong(), ((d.height - h) / 2 * EMU).toLong(), (w * EMU).toLong(), (h * EMU).toLong()))
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.slide_layout_title, index + 1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(8.dp))
            TextButton(onClick = { state.undo() }, enabled = state.canUndo) { Text("↶") }
            TextButton(onClick = { state.redo() }, enabled = state.canRedo) { Text("↷") }
            TextButton(onClick = onDone) { Text(stringResource(R.string.slide_layout_done)) }
        }
        val d = deck
        val frames = d?.slides?.getOrNull(index)?.frames.orEmpty()
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            if (d == null) {
                if (failed) Text(stringResource(R.string.slide_layout_failed)) else CircularProgressIndicator()
            } else BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(d.width / d.height)) {
                val density = LocalDensity.current
                // Pixels per point
                val k = with(density) { maxWidth.toPx() } / d.width
                val handle = with(density) { 28.dp.toPx() }
                picture?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                val outline = MaterialTheme.colorScheme.outline
                val accent = MaterialTheme.colorScheme.primary
                fun at(p: Offset) = frames.lastOrNull { f -> p.x in f.x * k..(f.x + f.w) * k && p.y in f.y * k..(f.y + f.h) * k }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(frames, k) { detectTapGestures { p -> selected = at(p)?.id } }
                        .pointerInput(frames, k, selected) {
                            var resize = false
                            detectDragGestures(
                                onDragStart = { p ->
                                    val chosen = frames.firstOrNull { it.id == selected }
                                    val corner = chosen?.let { Offset((it.x + it.w) * k, (it.y + it.h) * k) }
                                    resize = corner != null && (p - corner).getDistance() < handle * 1.5f
                                    val f = if (resize || chosen != null && at(p)?.id == chosen.id) chosen else at(p)
                                    selected = f?.id
                                    live = f
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val f = live ?: return@detectDragGestures
                                    val dx = drag.x / k; val dy = drag.y / k
                                    live = if (resize) f.copy(w = (f.w + dx).coerceAtLeast(12f), h = (f.h + dy).coerceAtLeast(12f))
                                    else f.copy(x = (f.x + dx).coerceIn(-f.w + 12f, d.width - 12f), y = (f.y + dy).coerceIn(-f.h + 12f, d.height - 12f))
                                },
                                onDragEnd = {
                                    val f = live ?: return@detectDragGestures
                                    live = null
                                    state.placeShape(PptxEdit.ShapeRef(slide, f.id), longArrayOf((f.x * EMU).toLong(), (f.y * EMU).toLong(), (f.w * EMU).toLong(), (f.h * EMU).toLong()))
                                },
                                onDragCancel = { live = null },
                            )
                        },
                ) {
                    for (f in frames) {
                        val shown = if (live?.id == f.id) live!! else f
                        val on = shown.id == selected
                        drawRect(if (on) accent else outline, Offset(shown.x * k, shown.y * k), Size(shown.w * k, shown.h * k), style = Stroke(if (on) 3.dp.toPx() else 1.dp.toPx()))
                        if (on) drawCircle(accent, handle / 2, Offset((shown.x + shown.w) * k, (shown.y + shown.h) * k))
                    }
                }
            }
        }
        Text(stringResource(R.string.slide_layout_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dd = deck ?: return@OutlinedButton
                state.addTextBox(slide, longArrayOf((dd.width * 0.2f * EMU).toLong(), (dd.height * 0.4f * EMU).toLong(), (dd.width * 0.6f * EMU).toLong(), (dd.height * 0.15f * EMU).toLong()), lang)
            }, enabled = deck != null) { Text(stringResource(R.string.slide_add_text)) }
            OutlinedButton(onClick = { pick.launch("image/*") }, enabled = deck != null) { Text(stringResource(R.string.slide_add_picture)) }
            OutlinedButton(onClick = {
                selected?.let { state.removeShape(PptxEdit.ShapeRef(slide, it)) }
                selected = null
            }, enabled = selected != null) { Text(stringResource(R.string.slide_remove_shape)) }
        }
    }
}

/**
 * A picture from the phone made fit for a slide: at most 2560 pixels across, written again as PNG (if it
 * has transparency) or JPEG, so nothing but the picture goes in (no place where a photo was taken, no
 * camera). Returns the bytes, whether PNG, and height divided by width; null if it is not a picture.
 */
internal fun cleanPicture(open: () -> InputStream?): Triple<ByteArray, Boolean, Float>? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (bmp == null) null else {
            val png = bmp.hasAlpha()
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
            val aspect = bmp.height.toFloat() / bmp.width
            bmp.recycle()
            Triple(out.toByteArray(), png, aspect)
        }
    }
} catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
