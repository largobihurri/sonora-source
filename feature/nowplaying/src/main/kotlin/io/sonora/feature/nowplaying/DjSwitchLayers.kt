package io.sonora.feature.nowplaying

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.motion.prefersReducedMotion

/**
 * The state of a [DjSwitch]: the layers it composes ([SwitchPlan]) and the progress of the swap. The plan is a plain field,
 * moved on by the composition that sees a new target and by the swap's end; only [version] is state, bumped when the plan
 * changes without the caller's inputs changing (the swap ending), so the switch recomposes then and not otherwise. The
 * progress is state read only while drawing, so a swap never recomposes anything.
 */
@Stable
internal class SwitchHost<T>(first: T, key: Any) {
    var plan: SwitchPlan<T> = SwitchPlan.of(first, key)
        private set

    /** 0 to 1 through the swap; 1 when none runs. Read in the layers' draw blocks only. */
    var progress by mutableFloatStateOf(1f)

    var version by mutableIntStateOf(0)
        private set

    /** The screen asks for [value] under [key]: a new key starts a swap, unless it is not [animate]d. */
    fun target(value: T, key: Any, animate: Boolean) {
        var next = plan.retarget(value, key)
        if (!animate) next = next.settled()
        if (next === plan || next == plan) return
        if (next.target.id != plan.target.id) progress = if (next.transitioning) 0f else 1f
        plan = next
    }

    /** The swap is over. */
    fun finish() {
        if (!plan.transitioning) return
        plan = plan.settled()
        progress = 1f
        version++
    }
}

/**
 * Shows [content] for [target], and swaps it for another over [DJ_SWITCH_MILLIS] ms when [key] says the target is a
 * different arrangement (the DJ on/off transition, D2.10): both are composed for the length of the swap, the outgoing one
 * fading out and shrinking a little while the incoming one fades in and settles ([switchFrame]), then only the new one. On a
 * large window the swap is calm: the layers only fade, and the clock starts after a few frames in which the incoming layer is
 * drawn nearly clear ([DJ_SWITCH_WARM_FRAMES]).
 *
 * - The outgoing layer keeps the value it was composed with (it is told it is `leaving`, so it can freeze what the state
 *   behind it drops at once), reads as [ArrangementRole.Hidden] (no marquee, no per-frame waveform) and is hidden from
 *   TalkBack; a transparent layer over both takes the touches meanwhile, so nothing is pressed half way.
 * - The composition of the layer that stays is kept: the player, the waveform and the playback behind them do not restart.
 * - The swap is drawn by layer properties read in the draw phase, so it recomposes nothing; settled, the switch has no
 *   layer properties, no animation and no state read, and asks for no frame.
 * - It runs at the system animator scale (the clock of the animation scales it) and is skipped when animations are removed.
 */
@Composable
internal fun <T> DjSwitch(
    target: T,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (value: T, leaving: Boolean) -> Unit,
) {
    val host = remember { SwitchHost(target, key(target)) }
    host.version // read: the end of a swap recomposes
    val reduced = LocalContext.current.prefersReducedMotion()
    // A large window (T13): the layers only fade (no scale or shift), and the swap's clock starts once the incoming layer has
    // been drawn, nearly clear, for a few frames, so its first, costly frames are not part of the fade.
    val calm = LocalWindowClass.current.isLarge
    host.target(target, key(target), animate = !reduced)
    val plan = host.plan
    LaunchedEffect(host, plan.target.id) {
        if (!plan.transitioning) return@LaunchedEffect
        if (calm) repeat(DJ_SWITCH_WARM_FRAMES) { withFrameNanos { } }
        animate(0f, 1f, animationSpec = tween(DJ_SWITCH_MILLIS, easing = LinearEasing)) { value, _ -> host.progress = value }
        host.finish()
    }
    val outer = LocalArrangementRole.current
    Box(modifier.fillMaxSize(), propagateMinConstraints = true) {
        for (layer in plan.layers) {
            key(layer.id) {
                val role = plan.roleOf(layer)
                val current = rememberUpdatedState(role)
                val layerRole = remember(outer) { { if (current.value == SwitchRole.Outgoing) ArrangementRole.Hidden else outer() } }
                val layered = when (role) {
                    SwitchRole.Settled -> Modifier
                    SwitchRole.Incoming -> Modifier.swapLayer(host, role, calm)
                    SwitchRole.Outgoing -> Modifier.swapLayer(host, role, calm).clearAndSetSemantics { }
                }
                Box(Modifier.fillMaxSize().then(layered), propagateMinConstraints = true) {
                    CompositionLocalProvider(LocalArrangementRole provides layerRole) {
                        content(layer.value, role == SwitchRole.Outgoing)
                    }
                }
            }
        }
        if (plan.transitioning) Box(Modifier.fillMaxSize().then(TouchEater))
    }
}

/**
 * Draws the layer through [switchFrame] at the swap's progress, read in the draw phase. A [calm] swap (large windows) only
 * fades: a layer that scales or moves made every translucent surface in it redraw on every frame, about 80 ms a frame on the Pad 6
 * (86 % janky frames over the swap, 30 % with only the fade).
 */
private fun Modifier.swapLayer(host: SwitchHost<*>, role: SwitchRole, calm: Boolean): Modifier = graphicsLayer {
    val frame = switchFrame(role, host.progress, calm)
    alpha = swapAlpha(role, host.progress, calm)
    if (!calm) {
        scaleX = frame.scale
        scaleY = frame.scale
        translationY = frame.shift * size.height
    }
}

/** Takes every touch before the layers under it see it (a swap lasts 300 ms; nothing is pressed half way). */
private val TouchEater: Modifier = Modifier.pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
