package io.sonora.feature.dub.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.feature.dub.R

/**
 * The four beat-phase LEDs: [lit] (0..3, from [DubIndicators.beatLed]) is on, the first one brighter as the downbeat;
 * null lights none. With [reducedMotion] they stay still: none follows the beat. They redraw only when [lit] changes.
 */
@Composable
fun BeatLeds(lit: Int?, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val shown = if (reducedMotion) null else lit
    val title = stringResource(R.string.dub_beat_leds)
    val state = if (shown == null) {
        stringResource(R.string.dub_beat_none)
    } else {
        stringResource(R.string.dub_beat_of, shown + 1, DubIndicators.BEATS_PER_BAR)
    }
    val accent = SonoraTheme.accentOnDark
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = title
            stateDescription = state
        },
        horizontalArrangement = Arrangement.spacedBy(LedGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DubIndicators.BEATS_PER_BAR) { index ->
            val on = index == shown
            val colour = when {
                on && index == 0 -> Color.White
                on -> accent
                else -> Color.White.copy(alpha = LedOffAlpha)
            }
            Box(Modifier.size(LedSize).background(colour, CircleShape))
        }
    }
}

/**
 * The master peak meter: a horizontal row of segments filled to [peak] (linear, 1 = full scale) on a decibel scale
 * ([DubIndicators.peakFraction]), the top segments warning. [peak] is read while drawing, so the meter redraws only when
 * the value it reads changes, never recomposes for it.
 */
@Composable
fun PeakMeter(peak: () -> Float, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.dub_peak_meter)
    val warn = MaterialTheme.colorScheme.error
    val accent = SonoraTheme.accentOnDark
    Canvas(
        modifier
            .fillMaxWidth()
            .height(MeterHeight)
            .clearAndSetSemantics { contentDescription = title },
    ) {
        val fraction = DubIndicators.peakFraction(peak())
        val gap = SegmentGap.toPx()
        val segment = (size.width - gap * (Segments - 1)) / Segments
        val litCount = (fraction * Segments + 0.5f).toInt()
        val radius = CornerRadius(segment / 4f, segment / 4f)
        for (i in 0 until Segments) {
            val colour = when {
                i >= litCount -> Color.White.copy(alpha = LedOffAlpha)
                i >= Segments - WarnSegments -> warn
                i >= Segments - CautionSegments -> Color(CautionArgb)
                else -> accent
            }
            drawRoundRect(colour, Offset(i * (segment + gap), 0f), Size(segment, size.height), radius)
        }
    }
}

/** The limiter LED: lit while the output limiter takes more than 1 dB off ([DubIndicators.limiterLit]). */
@Composable
fun LimiterLed(lit: Boolean, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.dub_limiter)
    val state = stringResource(if (lit) R.string.dub_limiter_on else R.string.dub_limiter_off)
    val warn = MaterialTheme.colorScheme.error
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = title
            stateDescription = state
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(LedSize).background(if (lit) warn else Color.White.copy(alpha = LedOffAlpha), CircleShape))
        Text(
            stringResource(R.string.dub_limiter_short),
            color = Color.White.copy(alpha = if (lit) 1f else LabelOffAlpha),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The banner saying why the DUB stage passes the audio through untouched ([DubBypassReason]); nothing for a null
 * [reason]. Screen readers announce it when it appears or changes.
 */
@Composable
fun BypassBanner(reason: DubBypassReason?, modifier: Modifier = Modifier) {
    if (reason == null) return
    val message = stringResource(reason.message)
    TranslucentSurface(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
    ) {
        val colors = LocalTranslucentContentColors.current
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SonoraIcons.Info, contentDescription = null, tint = colors.label, modifier = Modifier.size(18.dp))
            Box(Modifier.width(10.dp))
            Text(message, color = colors.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val LedSize = 8.dp
private val LedGap = 6.dp
private val MeterHeight = 6.dp
private val SegmentGap = 2.dp
private const val Segments = 24
private const val WarnSegments = 2
private const val CautionSegments = 6
private const val CautionArgb = 0xFFFFCC00
private const val LedOffAlpha = 0.2f
private const val LabelOffAlpha = 0.5f
