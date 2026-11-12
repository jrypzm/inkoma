package inkoma.ui.components

import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

/** Click with desktop semantics: left click = [onClick]; right click or long press = [onLongClick] (select). */
fun Modifier.inkomaClickable(enabled: Boolean = true, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this
        .onClick(enabled = enabled, matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = onLongClick)
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
