package inkoma.ui.reader

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import inkoma.Inkoma

/** Reader actions that can be bound to keys (Settings › Reader › Keyboard shortcuts). */
enum class ReaderAction(val label: String, val defaults: List<Key>) {
    PAGE_RIGHT("Page right (next in LTR, previous in RTL)", listOf(Key.DirectionRight)),
    PAGE_LEFT("Page left (previous in LTR, next in RTL)", listOf(Key.DirectionLeft)),
    NEXT_PAGE("Next page", listOf(Key.DirectionDown, Key.PageDown, Key.Spacebar)),
    PREVIOUS_PAGE("Previous page", listOf(Key.DirectionUp, Key.PageUp)),
    FIRST_PAGE("First page", listOf(Key.MoveHome)),
    LAST_PAGE("Last page", listOf(Key.MoveEnd)),
    NEXT_CHAPTER("Next chapter", listOf(Key.N)),
    PREVIOUS_CHAPTER("Previous chapter", listOf(Key.P)),
    TOGGLE_MENU("Show/hide menu", listOf(Key.M)),
    FULLSCREEN("Toggle fullscreen", listOf(Key.F, Key.F11)),
    BOOKMARK("Bookmark chapter", listOf(Key.B)),
}

/** Bindings stored as "ACTION=keycode,keycode;ACTION=…" in the reader_key_bindings preference. */
object KeyBindings {
    fun parse(raw: String): Map<ReaderAction, List<Key>> {
        val custom = raw.split(';').mapNotNull { entry ->
            val (name, codes) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val action = ReaderAction.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val keys = codes.split(',').mapNotNull { it.toLongOrNull() }.map { Key(it) }
            // Unparseable entries fall back to the defaults instead of leaving the action unbound.
            if (keys.isEmpty()) null else action to keys
        }.toMap()
        return ReaderAction.entries.associateWith { custom[it] ?: it.defaults }
    }

    fun serialize(bindings: Map<ReaderAction, List<Key>>): String =
        bindings.filter { (action, keys) -> keys != action.defaults }
            .entries.joinToString(";") { (action, keys) -> action.name + "=" + keys.joinToString(",") { it.keyCode.toString() } }

    fun current(): Map<ReaderAction, List<Key>> = parse(Inkoma.preferences.keyBindings.get())

    fun actionFor(key: Key, bindings: Map<ReaderAction, List<Key>>): ReaderAction? =
        bindings.entries.firstOrNull { key in it.value }?.key

    /** Human-readable key name, e.g. "Right", "Page Down", "F11". */
    fun keyName(key: Key): String = runCatching {
        java.awt.event.KeyEvent.getKeyText(key.nativeKeyCode)
    }.getOrDefault(key.toString())
}
