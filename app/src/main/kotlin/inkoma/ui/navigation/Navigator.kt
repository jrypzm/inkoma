package inkoma.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A destination. Like Voyager screens in Mihon, an instance lives as long as it is on the back stack,
 * so state held in it survives navigating forward and back.
 */
abstract class Screen {
    /** Unique key used to keep this screen's saveable UI state (scroll positions…) while it is on the stack. */
    val stateKey: String = java.util.UUID.randomUUID().toString()

    /** Scope cancelled when the screen is popped (Voyager's screenModelScope). */
    val screenScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Composable
    abstract fun Content()

    open fun onDispose() {
        screenScope.cancel()
    }
}

class Navigator(root: Screen) {
    val stack = mutableStateListOf(root)

    /** Called when a screen leaves the stack for good (used to drop its saved UI state). */
    var onRemoved: (Screen) -> Unit = {}

    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (!canPop) return false
        stack.removeAt(stack.lastIndex).also { onRemoved(it) }.onDispose()
        return true
    }

    fun replace(screen: Screen) {
        stack.removeAt(stack.lastIndex).also { onRemoved(it) }.onDispose()
        stack.add(screen)
    }

    fun popUntilRoot() {
        while (canPop) pop()
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
