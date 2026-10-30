package io.sonora.feature.dj

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentPill

/**
 * What the DJ waveform panel says while the song has no waveform to show, or none worth seeing (D2.11): waiting, analysing
 * with its percentage and a thin bar, paused by the phone's heat, the waveform being rebuilt, a failure with its reason and
 * Try again. All of them sit centred in the panel, on its own dark translucent surface.
 *
 * The bar moves in the draw phase only: the fill follows [DjStateCardText.percent] through an animated value read in
 * [drawBehind], and a soft highlight sweeps over it from an infinite transition read there too, so neither recomposes
 * the card. A paused bar stands still and dim; a system without animations shows it still too.
 *
 * [compact] is the look for a short waveform (the landscape layouts, about 120 dp): a smaller percentage, tighter gaps,
 * a one line title, a two line hint and a 36 dp button, so the whole card fits.
 */
@Composable
internal fun BoxScope.DjStateCard(panel: DjTimelinePanel, onRetry: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val title = stringResource(DjStateCardText.title(panel))
    val hint = stringResource(DjStateCardText.hint(panel))
    val percent = DjStateCardText.percent(panel)
    val action = DjStateCardText.action(panel)
    val paused = DjStateCardText.paused(panel)
    Column(
        modifier
            .align(Alignment.Center)
            .widthIn(max = CardMaxWidth)
            .padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (percent != null && panel is DjTimelinePanel.Analysing) {
            // The big number is the one thing to read from across the room; the bar and the title carry the meaning.
            Text(
                percentText(percent),
                Modifier.clearAndSetSemantics { },
                style = (if (compact) CompactPercentStyle else PercentStyle).copy(color = Color.White.copy(alpha = if (paused) DimAlpha else 1f)),
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        }
        Text(
            title,
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = TitleStyle,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            hint,
            style = HintStyle,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (panel is DjTimelinePanel.Analysing || panel is DjTimelinePanel.WaveformMissing || panel == DjTimelinePanel.Waiting) {
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            ProgressBar(percent, paused)
        }
        if (action != null) {
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            val description = stringResource(requireNotNull(DjStateCardText.actionDescription(panel)))
            TranslucentPill(
                label = stringResource(action),
                onClick = onRetry,
                height = if (compact) CompactButtonHeight else ButtonHeight,
                style = ButtonStyle,
                darkTheme = true,
                tinted = false,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/**
 * The strip along the bottom of the waveform for a track without a steady beat (D2.11): the waveform stays visible above
 * it, [DjStateCardText.title] says why there is no grid and Analyse again asks for a new analysis. The hint, which says
 * what can still be done, is announced with the title.
 */
@Composable
internal fun BoxScope.DjNoBeatStrip(onAnalyseAgain: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val panel = DjTimelinePanel.NoBeatGrid
    val title = stringResource(DjStateCardText.title(panel))
    val hint = stringResource(DjStateCardText.hint(panel))
    val description = stringResource(requireNotNull(DjStateCardText.actionDescription(panel)))
    Row(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp)
            .clip(StripShape)
            .background(Color.Black.copy(alpha = StripAlpha))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics {
                heading()
                stateDescription = hint
            },
            style = StripTitleStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        TranslucentPill(
            label = stringResource(requireNotNull(DjStateCardText.action(panel))),
            onClick = onAnalyseAgain,
            height = if (compact) CompactButtonHeight else ButtonHeight,
            style = ButtonStyle,
            darkTheme = true,
            tinted = false,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/**
 * The thin bar under the title: filled to [percent] (an indeterminate sweep alone while it is `null`), with a highlight
 * running over it unless [paused]. Everything that changes between frames is read inside [drawBehind].
 */
@Composable
private fun ProgressBar(percent: Int?, paused: Boolean) {
    val description = stringResource(R.string.dj_state_progress_description)
    val percentLabel = percent?.let { percentText(it) }
    val shown by animateFloatAsState((percent ?: 0) / FULL, tween(FillTweenMs, easing = LinearEasing), label = "analysis fill")
    val sweep = if (paused) null else rememberInfiniteTransition(label = "analysis sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SweepMs, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val shape = remember { RoundedCornerShape(BarHeight / 2) }
    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = BarMaxWidth)
            .height(BarHeight)
            .clip(shape)
            .background(Color.White.copy(alpha = TrackAlpha))
            .semantics {
                contentDescription = description
                if (percent != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(percent / FULL, 0f..1f)
                    stateDescription = percentLabel.orEmpty()
                }
            }
            .drawBehind {
                val corner = CornerRadius(size.height / 2)
                val fillAlpha = if (paused) DimAlpha else 1f
                if (percent != null) {
                    drawRoundRect(Color.White.copy(alpha = FillAlpha * fillAlpha), size = Size(size.width * shown, size.height), cornerRadius = corner)
                }
                val position = sweep?.value ?: return@drawBehind
                // A soft highlight, a third of the bar wide, running left to right; over the fill, or alone while indeterminate.
                val width = size.width * SweepWidth
                val left = (size.width + width) * position - width
                drawRoundRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = SweepAlpha), Color.Transparent),
                        startX = left,
                        endX = left + width,
                    ),
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                    cornerRadius = corner,
                )
            },
    )
}

private val StripShape = RoundedCornerShape(16.dp)
private const val StripAlpha = 0.55f
private val StripTitleStyle = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

private val CardMaxWidth = 300.dp
private val BarMaxWidth = 220.dp
private val BarHeight = 4.dp
private val ButtonHeight = 44.dp
private val CompactButtonHeight = 36.dp

private const val FULL = 100f
private const val TrackAlpha = 0.14f
private const val FillAlpha = 0.85f
private const val DimAlpha = 0.45f
private const val SweepAlpha = 0.5f
private const val SweepWidth = 0.33f
private const val FillTweenMs = 600
private const val SweepMs = 1_600

private val PercentStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")
private val CompactPercentStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")

/** Lines of balanced length, so a wrapped title or hint never leaves one word alone ("…la forma de / onda."). */
private val BalancedLines = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)
private val TitleStyle =
    TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, lineBreak = BalancedLines)
private val HintStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = 0.6f), lineBreak = BalancedLines)
private val ButtonStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

/** The percent as the locale writes it ("42%", "42 %", Basque "% 42"). */
@Composable
internal fun percentText(percent: Int): String =
    java.text.NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(percent / 100.0)
