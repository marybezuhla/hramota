package ua.hramota.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import ua.hramota.R

/** The undo arrow (Material Symbols' shape, Apache 2.0); redo is its mirror. */
private val UndoArrow: ImageVector by lazy {
    ImageVector.Builder("Undo", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes("M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Undo and redo in an editor's title bar: the same two buttons in every editor. */
@Composable
fun UndoRedo(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = canUndo) { Icon(UndoArrow, contentDescription = stringResource(R.string.edit_undo)) }
    IconButton(onClick = onRedo, enabled = canRedo) { Icon(UndoArrow, contentDescription = stringResource(R.string.edit_redo), modifier = Modifier.scale(scaleX = -1f, scaleY = 1f)) }
}

/** Undo in a phone's tool row, where the thumb reaches. */
@Composable
fun UndoTool(enabled: Boolean, onUndo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = enabled) { Icon(UndoArrow, contentDescription = stringResource(R.string.edit_undo)) }
}
