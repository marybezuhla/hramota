package ua.hramota.ink

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.hramota.R
import ua.hramota.data.Vault
import ua.hramota.ui.AppTheme
import ua.hramota.ui.TopBar
import ua.hramota.ui.applySecureScreen
import java.io.File

/**
 * Work on one's samples that outlives the screen (turning the phone, say): changes to the file one at a
 * time and in the order asked, and fitting recognition, which stops when the storage locks.
 */
object InkWork {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val files = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val fits = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /** The sentences written so far (null until read). */
    val written = kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)

    sealed interface Fit {
        data object Idle : Fit
        data class Running(val done: Int, val all: Int, val stopping: Boolean = false) : Fit
        data class Done(val tuning: InkTuning.Tuning) : Fit
        data object Failed : Fit
    }
    val fit = kotlinx.coroutines.flow.MutableStateFlow<Fit>(Fit.Idle)
    private var fitJob: kotlinx.coroutines.Job? = null

    /** Does [change] to the file after every change asked before it; then the count is read again. False on failure. */
    fun change(context: Context, change: (Context) -> Unit, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        files.launch {
            val ok = runCatching { change(app) }.isSuccess
            written.value = runCatching { MyInk.samples(app).map { it.optString("text") } }.getOrDefault(written.value ?: emptyList())
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun load(context: Context) = change(context, {})

    /** Starts fitting unless a fit is still under way (one stopped is waited for). */
    @Synchronized fun startFit(context: Context) {
        if (fitJob?.isCompleted == false) return
        val app = context.applicationContext
        fit.value = Fit.Running(0, 0)
        fitJob = fits.launch {
            // The samples are not read on while the storage is locked
            val job = coroutineContext[kotlinx.coroutines.Job]!!
            val watch = if (Vault.isOn(app)) fits.launch { Vault.unlocked.first { !it }; job.cancel() } else null
            val result = try {
                Fit.Done(InkText.fit(app) { d, a -> fit.value = Fit.Running(d, a) })
            } catch (e: kotlinx.coroutines.CancellationException) { Fit.Idle } catch (e: Throwable) { Fit.Failed }
            watch?.cancel()
            fit.value = result
        }
    }

    /** Stops the fit; a new one can start once it has stopped. */
    @Synchronized fun stopFit() {
        val j = fitJob ?: return
        if (j.isCompleted) return
        (fit.value as? Fit.Running)?.let { fit.value = it.copy(stopping = true) }
        j.cancel()
    }

    /** The result has been seen. */
    fun seen() { if (fit.value is Fit.Done || fit.value is Fit.Failed) fit.value = Fit.Idle }
}

/**
 * Samples of the person's own handwriting, kept only on this phone (sealed with locked storage on).
 * Hramota's recognition adapts to them; they leave the phone only as a file the person saves.
 */
object MyInk {
    const val FILE = "ink-samples.jsonl"

    @Synchronized fun samples(context: Context): List<JSONObject> {
        val bytes = Vault.readSmall(context, FILE) ?: return emptyList()
        return String(bytes).lineSequence().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
    }

    @Synchronized fun add(context: Context, sample: JSONObject) {
        val before = Vault.readSmall(context, FILE) ?: ByteArray(0)
        Vault.writeSmall(context, FILE, before + (sample.toString() + "\n").toByteArray())
    }

    /** Takes out the last sample (written by mistake). */
    @Synchronized fun dropLast(context: Context) {
        val lines = samples(context)
        if (lines.isEmpty()) return
        Vault.writeSmall(context, FILE, lines.dropLast(1).joinToString("") { it.toString() + "\n" }.toByteArray())
    }

    @Synchronized fun deleteAll(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, FILE + Vault.SUFFIX).delete()
        // What was fitted to the samples goes with them
        InkTuning.forget(context)
    }

    /** The sentences to write, from the app (comments and empty lines left out). */
    fun prompts(context: Context): List<String> = try {
        context.assets.open("ink/practice_uk.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    } catch (e: Exception) { emptyList() }

    /** The next sentence: the first not yet written, then round again. */
    fun next(prompts: List<String>, written: List<String>): Int {
        if (prompts.isEmpty()) return -1
        val done = written.groupingBy { it }.eachCount()
        val least = prompts.minOf { done[it] ?: 0 }
        return prompts.indexOfFirst { (done[it] ?: 0) == least }
    }

    /** Enough samples for recognition to adapt to (one pass over every letter and then some). */
    const val ENOUGH = 40
}

/** «Мій почерк»: the person writes the sentences shown; recognition learns their hand from these. */
class MyHandwritingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Locked storage closed: the samples cannot be read or added to
        if (Vault.locked(this)) { Toast.makeText(this, getString(R.string.delete_kept_locked), Toast.LENGTH_LONG).show(); finish(); return }
        applySecureScreen()
        setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MyHandwritingScreen(onBack = { finish() }) } } }
    }

    override fun onStart() {
        super.onStart()
        // Hramota locked while away: the samples are not shown again without unlocking
        if (Vault.locked(this)) finish()
    }
}

@Composable
fun MyHandwritingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompts = remember { MyInk.prompts(context) }
    val written by InkWork.written.collectAsState()
    LaunchedEffect(Unit) { InkWork.load(context) }
    var skipped by rememberSaveable { mutableIntStateOf(0) }
    val index = written?.let { w -> MyInk.next(prompts, w).let { if (it < 0) -1 else (it + skipped) % prompts.size } } ?: -1
    val ink = remember { InkState() }
    var size by remember { mutableStateOf(0f to 0f) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    // Fitting recognition to the samples: how far along, then what came of it (kept while the phone turns)
    val fit by InkWork.fit.collectAsState()
    if (fit is InkWork.Fit.Failed) LaunchedEffect(fit) { Toast.makeText(context, context.getString(R.string.ink_fit_failed), Toast.LENGTH_SHORT).show(); InkWork.seen() }
    (fit as? InkWork.Fit.Done)?.tuning?.let { t ->
        AlertDialog(
            onDismissRequest = { InkWork.seen() },
            title = { Text(stringResource(R.string.ink_fit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v) in t.scores) Text(stringResource(R.string.ink_fit_score, stringResource(when (k) {
                        "r1" -> R.string.ink_way_r1; "r1-upright" -> R.string.ink_way_r1_upright; "r2" -> R.string.ink_way_r2; else -> R.string.ink_way_r2_upright
                    }), Math.round(v * 100)))
                    Text(stringResource(R.string.ink_fit_chosen, stringResource(when {
                        t.engine == "r1" && t.slant == 0f -> R.string.ink_way_r1; t.engine == "r1" -> R.string.ink_way_r1_upright
                        t.slant == 0f -> R.string.ink_way_r2; else -> R.string.ink_way_r2_upright
                    })), style = MaterialTheme.typography.titleSmall)
                    if (!Rukopys.available(context)) Text(stringResource(R.string.ink_fit_no_r2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { InkWork.seen() }) { Text(stringResource(R.string.ok)) } },
        )
    }
    (fit as? InkWork.Fit.Running)?.let { r ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.ink_fit_title)) },
            text = { Text(stringResource(R.string.ink_fit_progress, r.done, r.all)) }, confirmButton = {},
            dismissButton = { TextButton(onClick = { InkWork.stopFit() }, enabled = !r.stopping) { Text(stringResource(R.string.cancel)) } })
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                ua.hramota.EditSaving.writeNew(context, uri) { out -> MyInk.samples(context).forEach { out.write((it.toString() + "\n").toByteArray()) } }
            }
            Toast.makeText(context, context.getString(when (r) {
                ua.hramota.EditSaving.New.DONE -> R.string.ink_saved; ua.hramota.EditSaving.New.EXISTS -> R.string.create_exists; else -> R.string.ink_save_failed
            }), Toast.LENGTH_SHORT).show()
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.ink_delete_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; InkWork.stopFit(); InkWork.change(context, { MyInk.deleteAll(it) }); ink.clear() }) { Text(stringResource(R.string.ink_delete_go), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text(stringResource(R.string.ink_title)) },
        text = { Text(stringResource(R.string.ink_about)) },
        confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.insp_close)) } },
    )
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(stringResource(R.string.ink_title), written?.let { stringResource(R.string.ink_count, it.size, MyInk.ENOUGH) }, onBack = onBack) {
            TextButton(onClick = { about = true }) { Text("?") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ink_undo_last)) }, enabled = !written.isNullOrEmpty(), onClick = {
                        menu = false
                        InkWork.change(context, { MyInk.dropLast(it) }) { ok -> if (!ok) Toast.makeText(context, context.getString(R.string.ink_save_failed), Toast.LENGTH_SHORT).show() }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ink_fit)) }, enabled = (written?.size ?: 0) >= 3 && fit !is InkWork.Fit.Running, onClick = {
                        menu = false
                        InkWork.startFit(context)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ink_export)) }, enabled = !written.isNullOrEmpty(), onClick = { menu = false; export.launch("my-handwriting.jsonl") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ink_delete_all), color = MaterialTheme.colorScheme.error) }, enabled = !written.isNullOrEmpty(), onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        val prompt = prompts.getOrNull(index)
        Text(
            prompt ?: "",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Text(stringResource(R.string.ink_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        val line = MaterialTheme.colorScheme.outlineVariant
        val density = LocalDensity.current.density
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).border(BorderStroke(1.dp, line), RoundedCornerShape(12.dp))) {
            size = maxWidth.value to maxHeight.value
            InkCanvas(ink, Modifier.fillMaxSize()) {
                // Lines as in a school notebook, 14 mm apart or so
                val step = 56f * density
                var y = step
                while (y < this.size.height) { drawLine(Color(0x332F5597), Offset(0f, y), Offset(this.size.width, y), 1f * density); y += step }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ink.undo() }, enabled = !ink.isEmpty) { Text(stringResource(R.string.ink_undo)) }
            OutlinedButton(onClick = { ink.clear() }, enabled = !ink.isEmpty) { Text(stringResource(R.string.ink_clear)) }
            OutlinedButton(onClick = { ink.clear(); skipped++ }) { Text(stringResource(R.string.ink_skip)) }
            Button(onClick = {
                val p = prompt ?: return@Button
                val sample = InkJson.sample(p, ink.strokes.toList(), size.first, size.second)
                ink.clear(); skipped = 0
                InkWork.change(context, { MyInk.add(it, sample) }) { ok -> if (!ok) Toast.makeText(context, context.getString(R.string.ink_save_failed), Toast.LENGTH_SHORT).show() }
            }, enabled = !ink.isEmpty && prompt != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ink_next)) }
        }
    }
}
