package io.sonora.core.designsystem.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A tab's navigation stack as the app shell sees it: how many screens it holds ([depth]), whether Back reaches it
 * ([backEnabled]) and the way back to its first one ([popToRoot]), so tapping the tab that is already selected returns
 * to its root as on iOS. The outermost [PredictiveBackStack] in a tab registers itself through [LocalTabStack] (see
 * [TabStackRegistration]); stacks nested inside it do not.
 *
 * Only a stack still on screen is ever popped: each one registers while it is composed and unregisters when it leaves.
 * Should two outermost stacks be on screen in the same tab at once, the one that came last holds the tab; when it
 * leaves, the other holds it again at once, with what it last reported.
 */
@Stable
class TabStack {
    /** One registered stack: [pop] removes its last screen (its Back); it reports [depth] and [backEnabled]. */
    internal class RegisteredStack(val pop: () -> Unit) {
        var depth by mutableIntStateOf(1)
        var backEnabled by mutableStateOf(true)
    }

    private val registered = mutableStateListOf<RegisteredStack>()

    private val holder: RegisteredStack? get() = registered.lastOrNull()

    /** Screens on the stack holding the tab; 1 at its root, or when no stack is registered. */
    val depth: Int get() = holder?.depth ?: 1

    /**
     * Whether Back reaches the stack holding the tab; false while something above it (the Now Playing sheet) owns Back,
     * and then a tap leaves the stack as Back does.
     */
    val backEnabled: Boolean get() = holder?.backEnabled ?: true

    /** [stack] is on screen holding [depth] screens; a stack registers once, on its first report. */
    internal fun attach(stack: RegisteredStack, depth: Int, backEnabled: Boolean) {
        stack.depth = depth
        stack.backEnabled = backEnabled
        if (stack !in registered) registered += stack
    }

    /** [stack] left the screen; the other registered stacks stay. */
    internal fun detach(stack: RegisteredStack) {
        registered -= stack
    }

    /**
     * Pops every screen above the first one in a single change, which the stack shows as one Back from the top screen to
     * the root (see [StackMotion.change]); returns false when there is nothing to pop, or Back does not reach the stack.
     */
    fun popToRoot(): Boolean {
        val stack = holder ?: return false
        if (!stack.backEnabled || stack.depth <= 1) return false
        repeat(stack.depth - 1) { stack.pop() }
        stack.depth = 1 // the stack reports its new depth when it recomposes; until then a second tap pops nothing more
        return true
    }
}

/** The enclosing tab's [TabStack]; `null` outside the app shell and inside a tab's stack (nested stacks stay put). */
val LocalTabStack = staticCompositionLocalOf<TabStack?> { null }

/**
 * Registers the calling stack, holding [depth] screens and popped by [onPop] (its Back, enabled per [backEnabled]), with
 * the enclosing [LocalTabStack] while it is composed, then shows [content] with no tab stack, so stacks nested in it do
 * not register.
 */
@Composable
internal fun TabStackRegistration(depth: Int, backEnabled: Boolean, onPop: () -> Unit, content: @Composable () -> Unit) {
    val tab = LocalTabStack.current
    val latestPop by rememberUpdatedState(onPop)
    val stack = remember { TabStack.RegisteredStack(pop = { latestPop() }) }
    SideEffect { tab?.attach(stack, depth, backEnabled) }
    DisposableEffect(tab) { onDispose { tab?.detach(stack) } }
    CompositionLocalProvider(LocalTabStack provides null, content = content)
}
