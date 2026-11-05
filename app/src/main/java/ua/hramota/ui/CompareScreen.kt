package ua.hramota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ua.hramota.R
import ua.hramota.diff.Block
import ua.hramota.diff.Comparison
import ua.hramota.diff.Segment

/** Comparison of two versions; null while it is being worked out, or when a file failed. */
sealed interface CompareState {
    data object Loading : CompareState
    data object Failed : CompareState
    data class Ready(val comparison: Comparison) : CompareState
}

@Composable
fun CompareScreen(olderName: String, newerName: String, state: CompareState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(title = stringResource(R.string.compare_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NameLine(stringResource(R.string.compare_older), olderName)
            NameLine(stringResource(R.string.compare_newer), newerName)
        }
        when (state) {
            CompareState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            CompareState.Failed -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.open_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is CompareState.Ready -> Result(state.comparison, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NameLine(label: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // At least as wide as the label needs on one line (never «Попере-дня» with a large font)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
            modifier = Modifier.widthIn(min = 88.dp).padding(end = 8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun Result(c: Comparison, modifier: Modifier) {
    val colors = LocalDiffColors.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var position by remember(c) { mutableIntStateOf(0) }
    // The list has one header row before the paragraphs
    fun go(to: Int) {
        if (c.changes.isEmpty()) return
        position = to.coerceIn(0, c.changes.size - 1)
        scope.launch { list.animateScrollToItem(c.changes[position] + 1) }
    }

    Column(modifier) {
        // On more lines when they do not fit on one (each label stays whole)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val edited = c.blocks.count { it is Block.Changed }
            val added = c.blocks.count { it is Block.Added }
            val removed = c.blocks.count { it is Block.Removed }
            Legend("${stringResource(R.string.legend_edited)} $edited", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, strike = false)
            Spacer(Modifier.width(8.dp))
            Legend("${stringResource(R.string.legend_added)} $added", colors.addedBg, colors.addedText, strike = false)
            Spacer(Modifier.width(8.dp))
            Legend("${stringResource(R.string.legend_removed)} $removed", colors.removedBg, colors.removedText, strike = true)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                item {
                    if (c.coarse) Text(
                        stringResource(R.string.compare_too_big),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                    )
                    if (c.changes.isEmpty()) Text(stringResource(R.string.compare_no_changes), modifier = Modifier.padding(top = 16.dp))
                }
                itemsIndexed(c.blocks) { index, block ->
                    val focused = c.changes.isNotEmpty() && c.changes[position] == index
                    BlockText(block, focused)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.compare_changes, c.changes.size, c.changes.size), style = MaterialTheme.typography.titleSmall)
                if (c.changes.isNotEmpty()) Text(
                    stringResource(R.string.compare_position, position + 1, c.changes.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { go(position - 1) }, enabled = position > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.compare_prev))
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { go(position + 1) }, enabled = position < c.changes.size - 1) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.compare_next))
            }
        }
    }
}

@Composable
private fun Legend(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, strike: Boolean) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
        maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun BlockText(block: Block, focused: Boolean) {
    val colors = LocalDiffColors.current
    val added = SpanStyle(background = colors.addedBg, color = colors.addedText)
    val removed = SpanStyle(background = colors.removedBg, color = colors.removedText, textDecoration = TextDecoration.LineThrough)
    val text: AnnotatedString = when (block) {
        is Block.Same -> AnnotatedString(block.text)
        is Block.Added -> buildAnnotatedString { withStyle(added) { append(block.text) } }
        is Block.Removed -> buildAnnotatedString { withStyle(removed) { append(block.text) } }
        is Block.Changed -> buildAnnotatedString {
            block.segments.forEach { s ->
                when (s.kind) {
                    Segment.Kind.SAME -> append(s.text)
                    Segment.Kind.ADDED -> withStyle(added) { append(s.text) }
                    Segment.Kind.REMOVED -> withStyle(removed) { append(s.text) }
                }
            }
        }
    }
    val changed = block !is Block.Same
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // A bar at the edge marks changed paragraphs; the current one is stronger
        Box(
            Modifier.padding(end = 10.dp).size(width = 3.dp, height = 24.dp)
                .background(
                    when {
                        focused -> MaterialTheme.colorScheme.primary
                        changed -> MaterialTheme.colorScheme.outline
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    },
                    RoundedCornerShape(2.dp),
                ),
        )
        Text(
            if (text.isEmpty()) AnnotatedString(" ") else text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            modifier = Modifier.weight(1f).then(
                if (focused) Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)).padding(horizontal = 6.dp, vertical = 2.dp)
                else Modifier
            ),
        )
    }
}
