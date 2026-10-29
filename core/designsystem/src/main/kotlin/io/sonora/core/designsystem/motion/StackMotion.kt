package io.sonora.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One stack entry on stage; [stateKey] (position + entry) keys its saveable state. */
internal data class Layer<T>(val entry: T, val stateKey: String)

/** The two screens on stage while a push or pop runs. */
internal data class StackPair<T>(val top: Layer<T>, val under: Layer<T>, val edge: SwipeEdge)

internal fun <T> List<T>.layer(index: Int) = Layer(this[index], "$index:${this[index]}")

/**
 * The motion a stack change needs: [Push] (one screen added) runs [StackMotion.progress] 1 -> 0, [Pop] (back to a screen
 * below, one or several levels) 0 -> 1, [None] just switches.
 */
internal enum class StackAnimation { None, Push, Pop }

/**
 * State of [PredictiveBackStack], apart from the Compose UI so its rules are JVM-testable. [progress] is 0 when the
 * top screen of [pair] is in place and 1 when it is gone; the composable animates it, this class decides what is on
 * stage. Snapshot state, main thread only.
 */
internal class StackMotion<T : Any>(initial: List<T>) {
    /** The stack on screen; during an animated pop it is still the old one until [settle]. */
    var shown by mutableStateOf(initial)
        private set

    /** The screens of a running transition or back gesture; `null` at rest. */
    var pair by mutableStateOf<StackPair<T>?>(null)
        private set

    val progress = Animatable(0f)

    /**
     * The next stack change, a push or a pop, was already shown by a gesture, so it is not animated again. Consumed by
     * that change and cleared by [settle], so a gesture that changed nothing never swallows a later animation.
     */
    private var gestureShown = false

    /** What to draw, bottom first. */
    val layers: List<Layer<T>>
        get() = pair?.let { listOf(it.under, it.top) } ?: listOf(shown.layer(shown.lastIndex))

    /**
     * The stack became [target]: puts the transition on stage and returns the motion to run before [settle], or `null`
     * when [target] is already shown. [discard] receives the state keys of entries that left the stack.
     */
    suspend fun change(target: List<T>, reducedMotion: Boolean, discard: (String) -> Unit): StackAnimation? {
        val old = shown
        if (old == target) return null
        val pushed = target.size == old.size + 1 && target.subList(0, old.size) == old
        // Back to any screen below (a tap on the selected tab pops to its root): one pop from the top screen straight to
        // the target, as iOS does; the screens in between never show.
        val popped = target.isNotEmpty() && target.size < old.size && old.subList(0, target.size) == target
        val animate = !reducedMotion && !gestureShown
        gestureShown = false
        old.indices.filter { it >= target.size || old[it] != target[it] }.forEach { discard(old.layer(it).stateKey) }
        return when {
            pushed && animate -> {
                shown = target
                pair = StackPair(top = target.layer(target.lastIndex), under = old.layer(old.lastIndex), SwipeEdge.None)
                progress.snapTo(1f)
                StackAnimation.Push
            }
            popped && animate -> {
                pair = StackPair(top = old.layer(old.lastIndex), under = target.layer(target.lastIndex), SwipeEdge.None)
                progress.snapTo(0f)
                StackAnimation.Pop
            }
            else -> StackAnimation.None
        }
    }

    /** Ends any transition or gesture with [stack] on screen, at rest. */
    suspend fun settle(stack: List<T>) {
        shown = stack
        pair = null
        progress.snapTo(0f)
        gestureShown = false
    }

    /** Whether the screen with [stateKey] is in the stack on screen (a screen a push gesture only staged is not). */
    fun isOnStack(stateKey: String): Boolean = shown.indices.any { shown.layer(it).stateKey == stateKey }

    /**
     * A push gesture brought [entry] [entryProgress] of the way in (0 off the right edge, 1 in place) over the top of
     * [stack], before anything is pushed: it is staged under the state key the real push will give it, so it keeps what
     * it composed. Refuses, changing nothing, while [stack] is not the one on screen or another transition is on stage.
     */
    suspend fun pushMoved(stack: List<T>, entry: T, entryProgress: Float): Boolean {
        val key = "${stack.size}:$entry"
        val staged = pair
        if (stack != shown || (staged != null && (staged.top.stateKey != key || isOnStack(key)))) return false
        pair = StackPair(top = Layer(entry, key), under = stack.layer(stack.lastIndex), SwipeEdge.None)
        progress.snapTo(1f - entryProgress.coerceIn(0f, 1f))
        return true
    }

    /** The back gesture moved to [progress] from [edge]; the top of [stack] follows the finger over the one below. */
    suspend fun gestureMoved(stack: List<T>, progress: Float, edge: SwipeEdge) {
        pair = StackPair(top = stack.layer(stack.lastIndex), under = stack.layer(stack.lastIndex - 1), edge)
        this.progress.snapTo(progress)
    }

    /**
     * The committed gesture finished its motion (the top screen moved off, or a pushed one moved in); the stack change
     * it triggers next is not animated again.
     */
    fun gestureFinished() {
        gestureShown = true
    }
}

/**
 * Back-gesture state of the Now Playing sheet: [progress] shrinks and lowers it ([frame]). A committed gesture keeps
 * its frame so the exit slide continues from it; showing the sheet again ([reshown]) brings it back full size.
 */
class SheetBackMotion {
    val progress = Animatable(0f)

    val frame: SheetFrame get() = sheetFrame(progress.value)

    suspend fun moved(progress: Float) = this.progress.snapTo(progress)

    suspend fun reshown() = progress.snapTo(0f)
}
