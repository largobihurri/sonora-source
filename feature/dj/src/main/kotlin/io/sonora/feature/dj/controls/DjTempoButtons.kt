package io.sonora.feature.dj.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.domain.playback.TempoRange
import io.sonora.feature.dj.R

/*
 * The CDJ tempo section's buttons, each standalone (the DJ layouts place them): TEMPO RANGE, MASTER TEMPO, the 0 %
 * reset and the ±0.1 BPM nudges. All are dark and translucent, as everything over Now Playing's artwork is.
 */

/** TEMPO RANGE: ±6 / ±10 / ±16 / Wide, a segmented control whose translucent thumb springs to the chosen [range]. */
@Composable
fun TempoRangeControl(range: TempoRange, onSelect: (TempoRange) -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val wide = stringResource(R.string.dj_tempo_range_wide)
    val title = stringResource(R.string.dj_tempo_range)
    SegmentedControl(
        options = TempoRange.entries,
        selected = range,
        label = { DjTempoText.rangeLabel(it, locale) ?: wide },
        onSelect = onSelect,
        modifier = modifier.semantics { contentDescription = title },
        textStyle = MaterialTheme.typography.labelMedium.merge(TabularFigures),
        // "Amplio" broke as "Am" / "plio" in its narrow segment: one line, shrinking to fit (D2.7b).
        singleLineLabels = true,
    )
}

/**
 * MASTER TEMPO: a translucent pill ([height], 44 dp by default) that keeps the key while the tempo moves. When [on], a small lamp and the label
 * light in the accent colour; off, the lamp is a dim dot. Both are reflected under the translucent surface.
 */
@Composable
fun MasterTempoPill(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, height: Dp = ButtonSize) {
    val state = stringResource(if (on) R.string.dj_state_on else R.string.dj_state_off)
    TranslucentPill(
        onClick = onToggle,
        modifier = modifier,
        height = height,
        onClickLabel = stringResource(R.string.dj_master_tempo_toggle),
        startPadding = 14.dp,
        darkTheme = true,
        tinted = false,
        contentModifier = Modifier.semantics { stateDescription = state },
    ) {
        val colors = LocalTranslucentContentColors.current
        val lamp = if (on) colors.accent else colors.label.copy(alpha = OffLampAlpha)
        Canvas(Modifier.size(LampSize).reflected()) { drawCircle(lamp) }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.dj_master_tempo),
            Modifier.reflected(),
            color = if (on) colors.accent else colors.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The tempo reset: a translucent pill ([height], 44 dp by default) reading "0 %" in the locale's style. At 0 % already ([atZero]) its label dims,
 * but it stays a button (a tap then changes nothing).
 */
@Composable
fun TempoResetPill(atZero: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier, height: Dp = ButtonSize) {
    val locale = LocalConfiguration.current.locales[0]
    val description = stringResource(R.string.dj_tempo_reset)
    TranslucentPill(
        onClick = onReset,
        modifier = modifier,
        height = height,
        onClickLabel = description,
        darkTheme = true,
        tinted = false,
        contentModifier = Modifier.semantics { contentDescription = description },
    ) {
        val label = LocalTranslucentContentColors.current.label
        Text(
            DjTempoText.zeroPercent(locale),
            Modifier.reflected(),
            color = if (atZero) label.copy(alpha = DimAlpha) else label,
            style = MaterialTheme.typography.labelLarge.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * A round translucent button ([size], 44 dp by default) nudging the tempo by 0.1 BPM, slower for a negative [direction], faster otherwise. It
 * needs the playing track's BPM: without it ([enabled] false) it dims and ignores taps. A press lights the translucent
 * surface instead of a ripple.
 */
@Composable
fun BpmNudgeButton(direction: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = ButtonSize) {
    val locale = LocalConfiguration.current.locales[0]
    val step = DjTempoText.nudgeStep(locale)
    val description = stringResource(if (direction < 0) R.string.dj_tempo_nudge_down else R.string.dj_tempo_nudge_up, step)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glow by animateFloatAsState(if (pressed) 1f else 0f, spring(), label = "nudgeGlow")
    TranslucentSurface(
        modifier.size(size),
        shape = CircleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
        wash = { PressWash.copy(alpha = PressWash.alpha * glow) },
    ) {
        val label = LocalTranslucentContentColors.current.label
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = description,
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                DjTempoText.nudgeLabel(direction, locale),
                color = if (enabled) label else label.copy(alpha = DisabledAlpha),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold).merge(TabularFigures),
                maxLines = 1,
            )
        }
    }
}

/** Tabular figures, so digits keep their width as values change. */
internal val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val ButtonSize = 44.dp
private val LampSize = 6.dp
private const val OffLampAlpha = 0.3f
private const val DimAlpha = 0.55f
private const val DisabledAlpha = 0.38f

/** The light a press lays over a nudge button's translucent surface. */
private val PressWash = Color.White.copy(alpha = 0.16f)
