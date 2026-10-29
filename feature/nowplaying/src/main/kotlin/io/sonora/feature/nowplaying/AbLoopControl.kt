package io.sonora.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.playback.AbLoop

/** The A-B loop's set points as fractions of [durationMs], A first; none without a duration. */
internal fun loopMarkers(loop: AbLoop, durationMs: Long): List<Float> {
    if (durationMs <= 0) return emptyList()
    return listOfNotNull(loop.startMs, loop.endMs).map { (it.toFloat() / durationMs).coerceIn(0f, 1f) }
}

/** The position scrubber with the player's A-B loop: its markers on the track and, while the mode is on, its control. */
@Composable
internal fun PlayerScrubber(durationMs: Long, viewModel: NowPlayingViewModel) {
    val loop by viewModel.abLoop.collectAsStateWithLifecycle()
    // Read here, not by the screen: the scrubber alone recomposes with the player's four position ticks a second.
    val positionMs by viewModel.position.collectAsStateWithLifecycle()
    Scrubber(
        positionMs = positionMs,
        durationMs = durationMs,
        onSeek = viewModel::seekTo,
        loop = loop,
        onLoopTap = viewModel::tapAbLoop,
        onLoopMove = viewModel::moveLoopPoint,
    )
}

/**
 * The A-B loop's translucent capsule, centred on the scrubber's time row: its label says what the next tap does (set A, set
 * B, or clear the loop, accented while looping). It is a 44 dp text capsule over the artwork (elevation 0, untinted,
 * always dark), laid out with no height of its own, so turning the mode on never moves the controls around it.
 */
@Composable
internal fun AbLoopButton(phase: AbLoop.Phase, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(
        when (phase) {
            AbLoop.Phase.AWAITING_B -> R.string.now_playing_ab_loop_set_b
            AbLoop.Phase.LOOPING -> R.string.now_playing_ab_loop_clear
            else -> R.string.now_playing_ab_loop_set_a
        },
    )
    val label = when (phase) {
        AbLoop.Phase.AWAITING_B -> "B"
        AbLoop.Phase.LOOPING -> "A–B"
        else -> "A"
    }
    TranslucentSurface(
        modifier
            .layout { measurable, constraints ->
                val pill = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
                layout(pill.width, 0) { pill.place(0, -pill.height / 2) }
            }
            .height(44.dp)
            .widthIn(min = 56.dp),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
    ) {
        val colors = LocalTranslucentContentColors.current
        Box(
            Modifier.fillMaxHeight().clip(CapsuleShape)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onTap)
                .semantics { contentDescription = description }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (phase == AbLoop.Phase.LOOPING) colors.accent else colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
