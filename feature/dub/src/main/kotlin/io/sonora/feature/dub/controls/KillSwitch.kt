package io.sonora.feature.dub.controls

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.feature.dub.R
import kotlinx.coroutines.CancellationException

/**
 * A preamp way's kill switch ([KillGestureState]): a tap toggles the kill (latched), a hold is momentary (the way comes
 * back, or goes, only while held), and a cancelled touch restores the state before it. It acts the moment the finger
 * lands, with a haptic tick, and is lit while [killed]. For screen readers it is a switch named [description] whose
 * state is killed or on; a double tap toggles it.
 */
@Composable
internal fun KillSwitch(
    killed: Boolean,
    onKilledChange: (Boolean) -> Unit,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corner: Dp = 14.dp,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val latestKilled by rememberUpdatedState(killed)
    val change by rememberUpdatedState(onKilledChange)
    val gesture = remember { KillGestureState() }
    val haptics = LocalHapticFeedback.current
    val killedText = stringResource(R.string.dub_killed)
    val liveText = stringResource(R.string.dub_live)
    val lamp = MaterialTheme.colorScheme.error

    TranslucentSurface(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .clearAndSetSemantics {
                role = Role.Switch
                contentDescription = description
                stateDescription = if (killed) killedText else liveText
                toggleableState = ToggleableState(killed)
                if (enabled) onClick { onKilledChange(!killed); true } else disabled()
            },
        shape = RoundedCornerShape(corner),
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
        wash = { if (latestKilled) lamp.copy(alpha = LitWash) else Color.Transparent },
    ) {
        val colors = LocalTranslucentContentColors.current
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        change(gesture.down(down.uptimeMillis, latestKilled))
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        val result = try {
                            var ended: Boolean? = null
                            while (true) {
                                val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (pointer == null) {
                                    ended = gesture.cancel()
                                    break
                                }
                                if (pointer.changedToUpIgnoreConsumed()) {
                                    ended = if (pointer.isConsumed) gesture.cancel() else gesture.up(pointer.uptimeMillis)
                                    break
                                }
                                pointer.consume()
                            }
                            ended
                        } catch (e: CancellationException) {
                            gesture.cancel()?.let { change(it) }
                            throw e
                        }
                        result?.let { change(it) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (killed) Color.White else colors.label,
                style = style,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private const val LitWash = 0.7f
private const val DisabledAlpha = 0.4f
