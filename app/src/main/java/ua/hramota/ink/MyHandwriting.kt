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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.hramota.R
import ua.hramota.data.Vault
import ua.hramota.ui.AppTheme
import ua.hramota.ui.TopBar
import ua.hramota.ui.applySecureScreen
import java.io.File

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
}

@Composable
fun MyHandwritingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompts = remember { MyInk.prompts(context) }
    var written by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { written = withContext(Dispatchers.IO) { runCatching { MyInk.samples(context).map { it.optString("text") } }.getOrDefault(emptyList()) } }
    var skipped by rememberSaveable { mutableIntStateOf(0) }
    val index = written?.let { w -> MyInk.next(prompts, w).let { if (it < 0) -1 else (it + skipped) % prompts.size } } ?: -1
    val ink = remember { InkState() }
    var size by remember { mutableStateOf(0f to 0f) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> MyInk.samples(context).forEach { out.write((it.toString() + "\n").toByteArray()) } } }.isSuccess
            }
            Toast.makeText(context, context.getString(if (ok) R.string.ink_saved else R.string.ink_save_failed), Toast.LENGTH_SHORT).show()
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.ink_delete_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; MyInk.deleteAll(context); written = emptyList(); ink.clear() }) { Text(stringResource(R.string.ink_delete_go), color = MaterialTheme.colorScheme.error) } },
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
                        menu = false; MyInk.dropLast(context); written = written?.dropLast(1)
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
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { MyInk.add(context, sample) }.isSuccess }
                    if (ok) written = (written ?: emptyList()) + p
                    else Toast.makeText(context, context.getString(R.string.ink_save_failed), Toast.LENGTH_SHORT).show()
                }
            }, enabled = !ink.isEmpty && prompt != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ink_next)) }
        }
    }
}
