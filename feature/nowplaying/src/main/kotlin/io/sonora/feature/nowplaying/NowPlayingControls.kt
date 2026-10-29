package io.sonora.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.CapsuleSlider
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.theme.ColorMath
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.playback.AbLoop
import io.sonora.core.domain.model.RepeatMode

internal val Secondary = Color.White.copy(alpha = 0.6f)
private val TimeStyle = TextStyle(fontSize = 12.sp, color = Secondary, fontFeatureSettings = "tnum")

/** "m:ss" (or "h:mm:ss") formatting for the scrubber labels. */
internal fun formatTime(ms: Long): String = LocaleFormats.duration(ms)

/**
 * Position scrubber: continuous while dragging, seeks only on release; a cancelled drag does not seek. A [loop]'s points
 * show as markers that drag through [onLoopMove] (0 = A, 1 = B), and while its mode is on its control sits between the
 * times.
 */
@Composable
internal fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    loop: AbLoop = AbLoop(),
    onLoopTap: () -> Unit = {},
    onLoopMove: (index: Int, positionMs: Long) -> Unit = { _, _ -> },
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownMs = (fraction * duration).toLong()
    Column {
        CapsuleSlider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                onSeek((it * duration).toLong())
                dragFraction = null
            },
            onValueChangeCancelled = { dragFraction = null },
            contentDescription = stringResource(R.string.now_playing_position),
            stateDescription = stringResource(R.string.now_playing_position_state, formatTime(shownMs), formatTime(durationMs)),
            markers = loopMarkers(loop, durationMs),
            onMarkerMove = { index, value -> onLoopMove(index, (value * duration).toLong()) },
            onMarkerMoveFinished = { index, value -> onLoopMove(index, (value * duration).toLong()) },
            markerColor = SonoraTheme.accentOnDark,
            markerSpanColor = SonoraTheme.accentOnDark.copy(alpha = 0.35f),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shownMs), style = TimeStyle)
                Text("-" + formatTime(durationMs - shownMs), style = TimeStyle)
            }
            if (loop.enabled) AbLoopButton(loop.phase, onLoopTap)
        }
    }
}

/**
 * Volume slider bound to the system music stream. Writes while dragging (quantized by the controller); a
 * cancelled drag keeps the last value already written instead of committing another one. Tapping the quiet speaker
 * mutes; tapping the loud one raises the volume by a tenth. The speakers are [SpeakerSize] glyphs whose touch targets are
 * [speakerTouchSide] square when that is larger (a tablet's scaled-up controls, see [touchTargetSide]).
 */
@Composable
internal fun VolumeSlider(volume: Float, onVolume: (Float) -> Unit, speakerTouchSide: Dp = SpeakerSize) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val mute = stringResource(R.string.now_playing_mute)
        Speaker(SonoraIcons.SpeakerLow, mute, speakerTouchSide) { onVolume(0f) }
        Spacer(Modifier.width(10.dp))
        CapsuleSlider(
            value = dragValue ?: volume,
            onValueChange = {
                dragValue = it
                onVolume(it)
            },
            onValueChangeFinished = {
                onVolume(it)
                dragValue = null
            },
            onValueChangeCancelled = { dragValue = null },
            contentDescription = stringResource(R.string.now_playing_volume),
            stateDescription = stringResource(R.string.now_playing_volume_percent, ((dragValue ?: volume) * 100).toInt()),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Speaker(SonoraIcons.SpeakerHigh, stringResource(R.string.now_playing_volume_up), speakerTouchSide) {
            onVolume(louder(dragValue ?: volume))
        }
    }
}

/** The volume slider's speaker glyphs. */
private val SpeakerSize = 18.dp

/** A speaker glyph, tappable within a [touchSide] square when that is larger than the glyph. */
@Composable
private fun Speaker(icon: ImageVector, contentDescription: String, touchSide: Dp, onClick: () -> Unit) {
    val tap = Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
    if (touchSide > SpeakerSize) {
        Box(Modifier.size(touchSide).then(tap), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(SpeakerSize), tint = Secondary)
        }
    } else {
        Icon(icon, contentDescription, Modifier.size(SpeakerSize).then(tap), tint = Secondary)
    }
}

/** The volume one tap on the loud speaker sets: a tenth higher than [volume], at most the maximum. */
internal fun louder(volume: Float): Float = (volume + VOLUME_TAP_STEP).coerceAtMost(1f)

private const val VOLUME_TAP_STEP = 0.1f

/** Borderless glyph button with an iOS-like press-scale feedback. */
@Composable
internal fun GlyphButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, spring(dampingRatio = 0.5f), label = "press")
    Box(
        modifier
            .size(size + 16.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size), tint = tint)
    }
}

/**
 * Shuffle or repeat switch: accent tint while active; [badge] marks a sub-state (the "1" of Repeat One). The state is
 * spoken through [stateText].
 */
@Composable
internal fun ModeButton(
    icon: ImageVector,
    label: String,
    stateText: String,
    active: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val accent = SonoraTheme.accentOnDark // Now Playing is always on a dark backdrop
    Box {
        GlyphButton(
            icon,
            label,
            onClick,
            22.dp,
            Modifier.semantics { stateDescription = stateText },
            tint = if (active) accent else Secondary,
        )
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = Color(ColorMath.contentOn(accent.toArgb())), fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
            }
        }
    }
}

/**
 * Shuffle, previous, play/pause, next and repeat, in the Apple Music order; [button] marks each of them (0 to 4) for
 * gravity mode to fit one by one.
 */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    button: (Int) -> Modifier = { Modifier },
    /** The play button's side, which is the row's height (the DJ screen's compact densities use a smaller one). */
    playSize: Dp = PlayButtonSize,
    /** The previous and next glyphs' size; their touch targets are 16 dp larger. */
    glyphSize: Dp = 40.dp,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(button(0)) {
            ModeButton(
                SonoraIcons.Shuffle,
                stringResource(R.string.now_playing_shuffle),
                stringResource(if (shuffle) R.string.now_playing_on else R.string.now_playing_off),
                shuffle,
                onShuffle,
            )
        }
        GlyphButton(SonoraIcons.Previous, stringResource(R.string.now_playing_previous), onPrevious, glyphSize, button(1))
        PlayPauseButton(isPlaying, onToggle, button(2), playSize)
        GlyphButton(SonoraIcons.Next, stringResource(R.string.now_playing_next), onNext, glyphSize, button(3))
        Box(button(4)) {
            ModeButton(
                SonoraIcons.Repeat,
                stringResource(R.string.now_playing_repeat),
                stringResource(
                    when (repeat) {
                        RepeatMode.OFF -> R.string.now_playing_off
                        RepeatMode.ALL -> R.string.now_playing_repeat_all
                        RepeatMode.ONE -> R.string.now_playing_repeat_one
                    },
                ),
                active = repeat != RepeatMode.OFF,
                onClick = onRepeat,
                badge = "1".takeIf { repeat == RepeatMode.ONE },
            )
        }
    }
}

/**
 * Previous, play/pause and next only, centred: the iOS landscape transport. Shuffle and repeat stay one tap away in
 * the Up Next header. The DJ landscape header (D2.10) places a small one inline: a [modifier] that is not the full width, a
 * smaller [playSize] and [glyphSize] and a tighter [spacing].
 */
@Composable
internal fun CompactTransportRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Dp = PlayButtonSize,
    glyphSize: Dp = 40.dp,
    spacing: Dp = 28.dp,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(SonoraIcons.Previous, stringResource(R.string.now_playing_previous), onPrevious, glyphSize)
        PlayPauseButton(isPlaying, onToggle, size = playSize)
        GlyphButton(SonoraIcons.Next, stringResource(R.string.now_playing_next), onNext, glyphSize)
    }
}

/** The play button's side at full size, and the share of it its glyph takes (56 of 72 dp). */
private val PlayButtonSize = 72.dp
private const val PlayGlyphShare = 56f / 72f

/** The big play/pause glyph, morphing between the two with a springy scale. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = PlayButtonSize) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, spring(dampingRatio = 0.5f), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.6f), 0.4f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.4f) + fadeOut())
            },
            label = "playPause",
        ) { playing ->
            Icon(
                if (playing) SonoraIcons.Pause else SonoraIcons.Play,
                stringResource(if (playing) R.string.now_playing_pause else R.string.now_playing_play),
                Modifier.size(size * PlayGlyphShare),
                tint = Color.White,
            )
        }
    }
}
