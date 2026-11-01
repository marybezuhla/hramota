package ua.hramota

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.data.Recents
import ua.hramota.diff.Diff
import ua.hramota.ui.CompareScreen
import ua.hramota.ui.CompareState
import ua.hramota.ui.AppTheme
import ua.hramota.ui.applySecureScreen

/** Two versions of a document: what was added, removed or changed, paragraph by paragraph. */
class CompareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val older = intent.getStringExtra(EXTRA_OLDER)?.let(Uri::parse)
        val newer = intent.getStringExtra(EXTRA_NEWER)?.let(Uri::parse)
        if (older == null || newer == null) { finish(); return }
        val olderName = Recents.displayName(this, older)
        val newerName = Recents.displayName(this, newer)
        labelTask("$olderName ↔ $newerName")

        applySecureScreen()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by produceState<CompareState>(CompareState.Loading, older, newer) {
                        value = withContext(Dispatchers.Default) {
                            try {
                                val a = formatOf(this@CompareActivity, older, olderName)
                                val b = formatOf(this@CompareActivity, newer, newerName)
                                if (!a.hasText || !b.hasText) return@withContext CompareState.Failed
                                val first = readText(this@CompareActivity, older, a).map { it.text }
                                val second = readText(this@CompareActivity, newer, b).map { it.text }
                                CompareState.Ready(Diff.compare(first, second))
                            } catch (e: Exception) {
                                CompareState.Failed
                            } catch (e: OutOfMemoryError) {
                                CompareState.Failed
                            } catch (e: StackOverflowError) {
                                CompareState.Failed
                            }
                        }
                    }
                    CompareScreen(olderName, newerName, state, onBack = { finishAndRemoveTask() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_OLDER = "older"
        const val EXTRA_NEWER = "newer"
    }
}
