package io.sonora.feature.dub.controls

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.feature.dub.R
import kotlinx.coroutines.CancellationException

/**
 * The state of one [DubPad], hoisted so the screen can release or latch it (the performance state resets every time
 * the mode opens). It wraps the pure [PadGestureState] and publishes [state] as snapshot state; every change of
 * whether the pad is engaged goes to the pad's `onEngagedChange` with the event's uptime in milliseconds.
 */
@Stable
class DubPadState(doubleTapWindowMs: Long = PadGestureState.DOUBLE_TAP_WINDOW_MS) {
    private val machine = PadGestureState(doubleTapWindowMs)

    var state by mutableStateOf(PadState.OFF)
        private set

    internal var onEngagedChange: (engaged: Boolean, uptimeMillis: Long) -> Unit = { _, _ -> }

    internal fun down(uptimeMillis: Long) = publish(machine.down(uptimeMillis), uptimeMillis)

    internal fun up(uptimeMillis: Long) = publish(machine.up(uptimeMillis), uptimeMillis)

    internal fun cancel(uptimeMillis: Long) = publish(machine.cancel(), uptimeMillis)

    internal fun releaseMomentary(uptimeMillis: Long) = publish(machine.releaseMomentary(), uptimeMillis)

    fun latch(uptimeMillis: Long = SystemClock.uptimeMillis()) = publish(machine.latch(), uptimeMillis)

    fun release(uptimeMillis: Long = SystemClock.uptimeMillis()) = publish(machine.release(), uptimeMillis)

    private fun publish(changed: Boolean, uptimeMillis: Long) {
        state = machine.state
        if (changed) onEngagedChange(machine.engaged, uptimeMillis)
    }
}

@Composable
fun rememberDubPadState(): DubPadState = remember { DubPadState() }

/**
 * A DUB performance pad (THROW, CUT, DROP, FREEZE, RUNAWAY): momentary by default, latched by a double tap
 * ([PadGestureState] has the rules). It engages the moment a finger lands, with a haptic tick, and reports every edge
 * through [onEngagedChange] with the event's uptime (the THROW compensation starts from it). Each pad reads its own
 * pointer with `requireUnconsumed = false`, so two pads held with two thumbs, plus a finger on the XY pad, all work; a
 * cancelled pointer (the system took it) releases, the screen stopping releases a held pad and keeps a latched one,
 * and the pad excludes the system back gesture so an edge swipe never steals a hold. A latch shows a padlock as well
 * as the wash, never colour alone. For screen readers it is a button whose state is held, latched or off, with "Latch"
 * and "Release" actions (a double tap toggles the latch). [enabled] false (the stage is bypassed) releases it.
 */
@Composable
fun DubPad(
    label: String,
    onEngagedChange: (engaged: Boolean, uptimeMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
    state: DubPadState = rememberDubPadState(),
    enabled: Boolean = true,
    minSize: Dp = PadMinSize,
) {
    val report by rememberUpdatedState(onEngagedChange)
    SideEffect { state.onEngagedChange = { engaged, time -> report(engaged, time) } }
    val haptics = LocalHapticFeedback.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.releaseMomentary(SystemClock.uptimeMillis()) }
    LaunchedEffect(enabled) { if (!enabled) state.release() }

    val padState = state.state
    val stateText = stringResource(
        when (padState) {
            PadState.HELD -> R.string.dub_pad_held
            PadState.LATCHED -> R.string.dub_pad_latched
            PadState.OFF -> R.string.dub_pad_off
        },
    )
    val latchLabel = stringResource(R.string.dub_pad_latch)
    val releaseLabel = stringResource(R.string.dub_pad_release)
    val accent = SonoraTheme.accentOnDark
    val shape = RoundedCornerShape(PadCorner)

    TranslucentSurface(
        modifier
            .sizeIn(minWidth = minSize, minHeight = minSize)
            .systemGestureExclusion()
            .alpha(if (enabled) 1f else DisabledAlpha)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = stateText
                if (enabled) {
                    val latched = padState == PadState.LATCHED
                    onClick(if (latched) releaseLabel else latchLabel) {
                        if (latched) state.release() else state.latch()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(latchLabel) { state.latch(); true },
                        CustomAccessibilityAction(releaseLabel) { state.release(); true },
                    )
                } else {
                    disabled()
                }
            },
        shape = shape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
        wash = { if (state.state != PadState.OFF) accent.copy(alpha = EngagedWash) else Color.Transparent },
    ) {
        val colors = LocalTranslucentContentColors.current
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, state) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        state.down(down.uptimeMillis)
                        if (state.state != PadState.OFF) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    state.cancel(event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis())
                                    break
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    // A consumed up is a cancellation (a parent or the system took the pointer).
                                    if (change.isConsumed) state.cancel(change.uptimeMillis) else state.up(change.uptimeMillis)
                                    break
                                }
                                // Keep the finger's moves to the pad, so a page swipe never steals a hold.
                                change.consume()
                            }
                        } catch (e: CancellationException) {
                            state.cancel(SystemClock.uptimeMillis())
                            throw e
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = colors.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (padState == PadState.LATCHED) {
                Icon(
                    SonoraIcons.Lock,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.align(Alignment.TopEnd).padding(LatchGlyphInset).size(LatchGlyphSize),
                )
            }
        }
    }
}

/** The smallest pad (COMPACT density, design §4); COMFORTABLE passes 96 dp. */
val PadMinSize = 72.dp
private val PadCorner = 20.dp
private val LatchGlyphSize = 14.dp
private val LatchGlyphInset = 8.dp
private const val EngagedWash = 0.45f
private const val DisabledAlpha = 0.4f
