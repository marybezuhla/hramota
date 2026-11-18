package ua.hramota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A tablet (or a phone turned with room enough): the editors are laid out as office programs on a computer. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** One tab of a ribbon: its name and its groups of tools. */
class RibbonTab(val title: String, val content: @Composable RowScope.() -> Unit)

/**
 * The tools of an office program on a computer: tabs ("Home", "Insert"…) above, under them the
 * chosen tab's tools in named groups. Tools that cannot be used on this file are shown greyed.
 */
@Composable
fun Ribbon(tabs: List<RibbonTab>, key: String) {
    var tab by rememberSaveable(key) { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((i, t) in tabs.withIndex()) {
                    val on = i == tab.coerceIn(0, tabs.size - 1)
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .selectable(on, role = Role.Tab) { tab = i }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t.title, style = MaterialTheme.typography.labelLarge, color = if (on) colors.primary else colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                            Box(Modifier.height(3.dp).widthIn(min = 28.dp).background(if (on) colors.primary else colors.surfaceContainer, RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainerLow).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) { tabs.getOrNull(tab)?.content?.invoke(this) }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

/** A named group of tools on a ribbon, a line after it. */
@Composable
fun RibbonGroup(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        VerticalDivider(Modifier.height(56.dp).padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A tool on a ribbon: a sign above, its name under it (both read as one, the name aloud). One that
 * [toggles] says whether it is on.
 */
@Composable
fun RibbonButton(
    glyph: String, label: String, enabled: Boolean = true, on: Boolean = false,
    style: TextStyle = TextStyle(), toggles: Boolean = false, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when { !enabled -> colors.onSurface.copy(alpha = 0.38f); on -> colors.onSecondaryContainer; else -> colors.onSurface }
    val click = if (toggles) Modifier.toggleable(on, enabled, Role.Checkbox) { onClick() } else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Column(
        Modifier.widthIn(min = 52.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) colors.secondaryContainer else colors.surfaceContainerLow)
            .then(click).padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium.merge(style), color = tint, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
    }
}

/** The line along the bottom of an office program: what the document holds, where one is in it. */
@Composable
fun StatusBar(start: String, end: String? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(start, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                end?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

/** What a keyboard's shortcuts do in an editor (the ones a computer's office programs have). */
class EditorKeys(
    val undo: () -> Unit, val redo: () -> Unit, val save: () -> Unit, val find: () -> Unit,
    val bold: (() -> Unit)? = null, val italic: (() -> Unit)? = null, val underline: (() -> Unit)? = null,
)

/** Ctrl+Z, Ctrl+Y (Ctrl+Shift+Z), Ctrl+S, Ctrl+F, Ctrl+B, Ctrl+I, Ctrl+U from a keyboard attached to the tablet. */
fun Modifier.editorKeys(keys: EditorKeys): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
    val act: (() -> Unit)? = when (e.key) {
        Key.Z -> if (e.isShiftPressed) keys.redo else keys.undo
        Key.Y -> keys.redo
        Key.S -> keys.save
        Key.F -> keys.find
        Key.B -> keys.bold
        Key.I -> keys.italic
        Key.U -> keys.underline
        else -> null
    }
    act?.invoke()
    act != null
}

/** A word count in the person's language ("1 234"). */
fun grouped(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)
