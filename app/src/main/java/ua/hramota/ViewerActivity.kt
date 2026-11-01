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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.data.Recents
import ua.hramota.doc.Format
import ua.hramota.doc.PdfSource
import ua.hramota.ui.HramotaTheme
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

        setContent {
            HramotaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by produceState<ViewerState>(ViewerState.Loading, uri) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                when {
                                    format == Format.PDF -> {
                                        val fd = contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ViewerState.Failed
                                        ViewerState.Pdf(PdfSource.open(fd))
                                    }
                                    format.hasText -> ViewerState.Text(readText(this@ViewerActivity, uri, format), format)
                                    else -> ViewerState.Unsupported(format)
                                }
                            } catch (e: Exception) {
                                ViewerState.Failed
                            }
                        }
                    }
                    // The PDF engine holds the open file: release it when the window goes
                    DisposableEffect(state) {
                        onDispose { ((state as? ViewerState.Pdf)?.pages as? PdfSource)?.close() }
                    }
                    ViewerScreen(name, state, onBack = { finishAndRemoveTask() })
                }
            }
        }
    }
}
