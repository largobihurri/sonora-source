package io.sonora.core.designsystem.motion

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lets the top screen of the enclosing [PredictiveBackStack] be pulled down to close it, as Back does (album and
 * artist pages pulled by their header); `null` where there is nothing to go back to.
 */
val LocalStackPull = compositionLocalOf<StackPull?> { null }

/** A pull on the top screen of a [PredictiveBackStack]; see [LocalStackPull]. */
@Stable
class StackPull internal constructor(private val moved: (Float) -> Unit, private val released: (Boolean) -> Unit) {
    /** The screen follows the finger down to [progress] (share of the height), the previous one showing beneath. */
    fun move(progress: Float) = moved(progress)

    /** The finger lifted: [close] slides the screen the rest of the way off and pops it; otherwise it springs back. */
    fun release(close: Boolean) = released(close)
}

/**
 * Lets the first screen of the enclosing [PredictiveBackStack] drag the next screen in with the finger before it is
 * pushed (the library's rows dragged by their chevron); `null` while the stack is not at rest on its first screen, Back is
 * held above it, or the stack takes no pushes.
 */
val LocalStackPush = compositionLocalOf<StackPush?> { null }

/** A push driven by the finger over the first screen of a [PredictiveBackStack]; see [LocalStackPush]. */
@Stable
class StackPush internal constructor(
    private val moved: (Any, Float) -> Unit,
    private val released: (Any, Boolean) -> Unit,
) {
    /**
     * [destination] follows the finger in from the right to [progress] (0 off the right edge, 1 in place) over the
     * current screen, which slides aside dimmed; nothing is pushed yet.
     */
    fun move(destination: Any, progress: Float) = moved(destination, progress)

    /** The finger lifted: [commit] slides [destination] the rest of the way in and pushes it; otherwise it slides back. */
    fun release(destination: Any, commit: Boolean) = released(destination, commit)
}

/** iOS navigation curve (close to UIKit's push/pop timing). */
private val StackEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

/**
 * iOS-style navigation stack. Shows the last entry of [stack]; pushes slide the new screen in from the right
 * over the parallaxed previous one, pops reverse that. The system back gesture drives the pop with the finger
 * (Android predictive back): the top screen follows the swipe edge, the previous one shows dimmed underneath;
 * a committed gesture finishes the motion then calls [onPop], a cancelled one springs back.
 *
 * A screen may also be pulled down to close it through [LocalStackPull], over the previous one in the same way. With
 * [onPush] set, the first screen at rest may drag the next one in with the finger through [LocalStackPush]; a committed
 * drag finishes the motion then calls [onPush], which must add that entry.
 *
 * Each entry keeps its saveable state (scroll position) while it is covered. [onPop] must remove the last
 * entry. With "Remove animations" on, screens change without motion. The rules live in [StackMotion].
 *
 * The outermost stack of an app-shell tab registers with its [LocalTabStack] while it is composed, so tapping the
 * selected tab pops it back to its first screen (all pops at once, shown as one Back) as long as [backEnabled] lets Back
 * reach it; stacks nested in its screens do not register (see [TabStackRegistration]).
 */
@Composable
fun <T : Any> PredictiveBackStack(
    stack: List<T>,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    onPush: ((T) -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val target = stack.toList()
    val motion = remember { StackMotion(target) }
    val latest by rememberUpdatedState(target)
    val latestPop by rememberUpdatedState(onPop)
    val latestPush by rememberUpdatedState(onPush)
    val pull = remember(motion) {
        StackPull(
            moved = { progress ->
                val stack = latest
                if (stack.size > 1 && !context.prefersReducedMotion()) scope.launch { motion.gestureMoved(stack, progress, SwipeEdge.Top) }
            },
            released = { close ->
                scope.launch {
                    val pulled = motion.pair?.edge == SwipeEdge.Top
                    if (close) {
                        if (pulled) {
                            motion.progress.animateTo(1f, tween(remainingDurationMillis(motion.progress.value), easing = StackEasing))
                            motion.gestureFinished()
                        }
                        latestPop()
                    } else if (pulled) {
                        motion.progress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
                        motion.settle(latest)
                    }
                }
            },
        )
    }

    val pushHook = rememberStackPush(motion, scope, holder) { latest to latestPush }
    val push = if (onPush != null) pushHook else null

    LaunchedEffect(target) {
        when (motion.change(target, context.prefersReducedMotion(), holder::removeState)) {
            null -> return@LaunchedEffect
            StackAnimation.Push -> motion.progress.animateTo(0f, tween(STACK_DURATION_MILLIS, easing = StackEasing))
            StackAnimation.Pop -> motion.progress.animateTo(1f, tween(STACK_DURATION_MILLIS, easing = StackEasing))
            StackAnimation.None -> Unit
        }
        motion.settle(target)
    }

    // Keyed on the real stack so a back press during a running transition never falls through to the system.
    PredictiveBackHandler(enabled = backEnabled && target.size > 1) { events ->
        val reduced = context.prefersReducedMotion()
        val current = latest
        try {
            events.collect { event ->
                if (!reduced) motion.gestureMoved(current, event.progress, swipeEdgeOf(event.swipeEdge))
            }
        } catch (e: CancellationException) {
            scope.launch {
                motion.progress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
                motion.settle(latest) // a gesture may have interrupted a transition before it settled
            }
            throw e
        }
        if (motion.pair != null) {
            motion.progress.animateTo(1f, tween(remainingDurationMillis(motion.progress.value), easing = StackEasing))
            motion.gestureFinished()
        }
        onPop()
    }

    // A tab tap pops to the root only what Back would pop: nothing while Back is held above the stack.
    TabStackRegistration(target.size, backEnabled, onPop) {
        Box(modifier.fillMaxSize()) {
            val onStage = motion.pair
            for (layer in motion.layers) {
                key(layer.stateKey) {
                    val isTop = onStage == null || layer == onStage.top
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (onStage != null) {
                                    val frame = stackFrame(motion.progress.value, onStage.edge)
                                    val offset = if (isTop) frame.topOffset else frame.underOffset
                                    if (frame.vertical) translationY = size.height * offset else translationX = size.width * offset
                                }
                            }
                            .background(MaterialTheme.colorScheme.background)
                            .drawWithContent {
                                drawContent()
                                if (onStage != null && !isTop) {
                                    drawRect(Color.Black.copy(alpha = stackFrame(motion.progress.value, onStage.edge).scrimAlpha))
                                }
                            },
                    ) {
                        val canPull = isTop && backEnabled && motion.shown.size > 1
                        // Only the first screen at rest: Back is off there, so it and the push never share the stage.
                        val canPush = onStage == null && backEnabled && push != null && motion.shown.size == 1
                        CompositionLocalProvider(
                            LocalStackPull provides if (canPull) pull else null,
                            LocalStackPush provides if (canPush) push else null,
                        ) {
                            holder.SaveableStateProvider(layer.stateKey) { content(layer.entry) }
                        }
                        // A screen a push gesture only staged is not on the stack yet: its own actions must wait.
                        if (!isTop || !motion.isOnStack(layer.stateKey)) TouchBlocker(Modifier.matchParentSize())
                    }
                }
            }
        }
    }
}

/**
 * The finger-driven push of [motion]'s first screen. [current] gives the stack and the push callback of the latest
 * composition. A gesture owns the stage from its first move to its release; the page it staged keeps no saved state
 * unless it really was pushed, even when the motion is interrupted.
 */
@Composable
private fun <T : Any> rememberStackPush(
    motion: StackMotion<T>,
    scope: CoroutineScope,
    holder: SaveableStateHolder,
    current: () -> Pair<List<T>, ((T) -> Unit)?>,
): StackPush {
    val context = LocalContext.current
    val latest by rememberUpdatedState(current)
    return remember(motion) {
        var pending: Any? = null // the destination of the gesture that owns the stage
        var followed = false // whether the screen followed the finger (not with animations removed)
        fun dropUnpushed(key: String) {
            if (!motion.isOnStack(key)) holder.removeState(key)
        }
        StackPush(
            moved = { destination, progress ->
                if (pending == null) {
                    pending = destination
                    followed = !context.prefersReducedMotion()
                }
                if (pending == destination && followed) {
                    @Suppress("UNCHECKED_CAST")
                    scope.launch { motion.pushMoved(latest().first, destination as T, progress) }
                }
            },
            released = released@{ destination, commit ->
                if (pending != destination) return@released
                pending = null
                val wasFollowed = followed
                scope.launch {
                    @Suppress("UNCHECKED_CAST")
                    val entry = destination as T
                    val staged = motion.pair?.top?.takeIf { it.entry == destination && !motion.isOnStack(it.stateKey) }
                    when {
                        commit && staged != null -> {
                            try {
                                // A push runs 1 -> 0, so the share still to go is the current progress.
                                motion.progress.animateTo(
                                    0f,
                                    tween(remainingDurationMillis(1f - motion.progress.value), easing = StackEasing),
                                )
                            } catch (e: CancellationException) {
                                dropUnpushed(staged.stateKey)
                                throw e
                            }
                            motion.gestureFinished()
                            latest().second?.invoke(entry)
                        }
                        // With animations removed nothing was staged: the page just switches in.
                        commit && !wasFollowed -> latest().second?.invoke(entry)
                        // Another stack change took the stage meanwhile: that one stands.
                        commit -> Unit
                        staged != null -> {
                            try {
                                motion.progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 500f))
                            } finally {
                                dropUnpushed(staged.stateKey)
                            }
                            motion.settle(latest().first)
                        }
                    }
                }
            },
        )
    }
}

/** Swallows touches on the covered screen while a transition runs, as UIKit does. */
@Composable
private fun TouchBlocker(modifier: Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        },
    )
}
