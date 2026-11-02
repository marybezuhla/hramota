package ua.hramota.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.hramota.R
import ua.hramota.data.Article
import ua.hramota.data.Dictionaries

/** What the dictionaries kept in Hramota say about a word; looked up on this phone only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(word: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // The first word of what was selected
    val query = word.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    val result by produceState<List<Article>?>(null, query) {
        value = withContext(Dispatchers.IO) { try { Dictionaries.lookUp(context, query) } catch (e: Exception) { emptyList() } catch (e: OutOfMemoryError) { emptyList() } }
    }
    val none by produceState(false, query) { value = withContext(Dispatchers.IO) { Dictionaries.list(context).isEmpty() } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(query, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Literata))
            val list = result
            when {
                none -> Text(stringResource(R.string.dict_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.dict_not_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> SelectionContainer {
                    LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(list) { a ->
                            Column {
                                Text(a.dictionary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(a.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                                Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
