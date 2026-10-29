package io.sonora.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelActionRow
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.playback.PlaybackTempo
import java.util.Locale
import kotlin.math.abs

/*
 * Speed & Pitch (B6): a floating translucent panel opened from the "…" menu or from the badge Now Playing shows while
 * either is changed. One setting for the whole app; the player follows the store (see PlaybackTempoViewModel).
 */

/** The Speed & Pitch panel while it is open. Leaving Now Playing closes it, so it never reappears by itself. */
@Composable
internal fun PlaybackTempoPanelHost(viewModel: PlaybackTempoViewModel = hiltViewModel()) {
    val open by viewModel.panelOpen.collectAsStateWithLifecycle()
    val tempo by viewModel.tempo.collectAsStateWithLifecycle()
    if (open) PlaybackTempoPanel({ tempo }, viewModel)
    DisposableEffect(viewModel) {
        onDispose { viewModel.closePanel() }
    }
}

/** The "…" menu's Speed & Pitch item: opens the panel. */
@Composable
internal fun rememberOpenSpeedPitch(viewModel: PlaybackTempoViewModel = hiltViewModel()): () -> Unit = viewModel::openPanel

/**
 * A small translucent capsule with the speed and pitch ("1.25× · +2") while either differs from normal; a tap opens the
 * panel. Nothing is shown at normal speed and pitch.
 */
@Composable
internal fun PlaybackTempoBadge(modifier: Modifier = Modifier, viewModel: PlaybackTempoViewModel = hiltViewModel()) {
    val tempo by viewModel.tempo.collectAsStateWithLifecycle()
    if (tempo.isDefault) return
    val text = PlaybackTempoText.badge(tempo, currentLocale())
    val description = stringResource(R.string.speed_pitch_badge_description, text)
    TranslucentSurface(
        // The capsule is a 32 dp chip; its touch target still reaches the minimum size.
        modifier.minimumInteractiveComponentSize().height(BadgeHeight),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = true,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .clip(CapsuleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.speed_pitch_title), onClick = viewModel::openPanel)
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = LocalTranslucentContentColors.current.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private val BadgeHeight = 32.dp

@Composable
private fun PlaybackTempoPanel(tempo: () -> PlaybackTempo, viewModel: PlaybackTempoViewModel) {
    TranslucentPanel(stringResource(R.string.speed_pitch_title), viewModel::closePanel) {
        val current = tempo()
        val locale = currentLocale()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
            TranslucentPanelGroup(stringResource(R.string.speed_pitch_speed)) {
                TempoControl(
                    label = stringResource(R.string.speed_pitch_speed),
                    value = PlaybackTempoText.speed(current, locale),
                    position = current.speedPercent.toFloat(),
                    range = PlaybackTempo.MIN_SPEED_PERCENT.toFloat()..PlaybackTempo.MAX_SPEED_PERCENT.toFloat(),
                    steps = (PlaybackTempo.MAX_SPEED_PERCENT - PlaybackTempo.MIN_SPEED_PERCENT) / PlaybackTempo.SPEED_STEP_PERCENT - 1,
                    onMove = { viewModel.setSpeed(it / 100f) },
                    decrease = stringResource(R.string.speed_pitch_slower) to { viewModel.stepSpeed(-1) },
                    increase = stringResource(R.string.speed_pitch_faster) to { viewModel.stepSpeed(1) },
                )
            }
            TranslucentPanelGroup(stringResource(R.string.speed_pitch_pitch)) {
                TempoControl(
                    label = stringResource(R.string.speed_pitch_pitch),
                    value = pluralStringResource(
                        R.plurals.speed_pitch_semitones,
                        abs(current.semitones),
                        PlaybackTempoText.semitones(current.semitones),
                    ),
                    position = current.semitones.toFloat(),
                    range = PlaybackTempo.MIN_SEMITONES.toFloat()..PlaybackTempo.MAX_SEMITONES.toFloat(),
                    steps = PlaybackTempo.MAX_SEMITONES - PlaybackTempo.MIN_SEMITONES - 1,
                    onMove = viewModel::setSemitones,
                    decrease = stringResource(R.string.speed_pitch_lower) to { viewModel.stepPitch(-1) },
                    increase = stringResource(R.string.speed_pitch_higher) to { viewModel.stepPitch(1) },
                )
            }
            TranslucentPanelGroup(title = null) {
                TranslucentPanelActionRow(stringResource(R.string.speed_pitch_reset), SonoraIcons.Refresh, viewModel::reset)
            }
        }
    }
}

/** The live [value], then a slider on [range] in [steps] with a step button at each end. */
@Composable
private fun TempoControl(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onMove: (Float) -> Unit,
    decrease: Pair<String, () -> Unit>,
    increase: Pair<String, () -> Unit>,
) {
    val colors = LocalTranslucentContentColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            value,
            color = colors.label,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(MinusIcon, decrease.first, decrease.second)
            Slider(
                value = position,
                onValueChange = onMove,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = colors.label,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.label.copy(alpha = 0.2f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .semantics {
                        contentDescription = label
                        stateDescription = value
                    },
            )
            StepButton(SonoraIcons.Add, increase.first, increase.second)
        }
    }
}

/** A 44 dp round − or + button. */
@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = LocalTranslucentContentColors.current.label, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** A speedometer, for the "…" menu's Speed & Pitch row. */
internal val SpeedIcon: ImageVector by lazy {
    ImageVector.Builder("Speed", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19" +
                    "a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1a10,10 0,0 0,-0.27 -10.44zM10.59,15.41a2,2 0,0 0,2.83 0" +
                    "l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/** A minus sign, to match the design system's plus. */
private val MinusIcon: ImageVector by lazy {
    ImageVector.Builder("Minus", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes("M19,13H5v-2h14v2z"), fill = SolidColor(Color.Black))
        .build()
}
