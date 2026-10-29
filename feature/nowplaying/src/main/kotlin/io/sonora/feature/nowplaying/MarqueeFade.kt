package io.sonora.feature.nowplaying

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A scrolling title or artist fades softly on both edges instead of being cut hard (L9). `basicMarquee` does not expose
 * where it is in its cycle, so the fade follows a clock that replays the marquee's timing: it is given explicit delay,
 * spacing and velocity (below) and its cycle is the marquee's own, a rest of `MARQUEE_DELAY_MILLIS` at the start and
 * then one pass of `travel / velocity`, repeated. The clock restarts whenever the marquee's target (text width plus
 * gap) changes, as the marquee's animation does. A frame or two of difference between the two starts moves the left
 * fade by a pixel or so, which no one can see. Only the draw phase reads the clock, so nothing recomposes per frame.
 */

/** The marquee's rest before every pass: the first one and the ones between repeats (the library default). */
internal const val MARQUEE_DELAY_MILLIS = 1200

/** The gap between the end of the text and its repeat, as a fraction of the container (the library default). */
internal const val MARQUEE_SPACING_FRACTION = 1f / 3f

private val MarqueeVelocity = 30.dp

/** How wide each edge fades while the text scrolls. */
private val FadeWidth = 20.dp

/** How far the marquee moves the text in one pass: the text and the gap after it, so the copy lands where it began. */
internal fun marqueeTravelPx(contentPx: Float, containerPx: Float): Float =
    contentPx + (containerPx * MARQUEE_SPACING_FRACTION).roundToInt()

/**
 * How far the marquee has moved the text [elapsedMillis] after it started: it rests for [delayMillis], moves
 * [travelPx] at [velocityPxPerSecond] (the library rounds the pass up to a whole millisecond), then starts over.
 */
internal fun marqueeOffsetPx(elapsedMillis: Long, travelPx: Float, velocityPxPerSecond: Float, delayMillis: Int = MARQUEE_DELAY_MILLIS): Float {
    if (elapsedMillis < 0 || travelPx <= 0f || velocityPxPerSecond <= 0f) return 0f
    val passMillis = ceil(travelPx / velocityPxPerSecond * 1000f).toLong()
    val phase = elapsedMillis % (delayMillis + passMillis)
    if (phase < delayMillis) return 0f
    return min(travelPx, velocityPxPerSecond * (phase - delayMillis) / 1000f)
}

/** The width of the fade on each edge, in px. */
internal data class EdgeFade(val leftPx: Float, val rightPx: Float) {
    /** Where the left fade ends, as a fraction of a [widthPx] container. */
    fun leftStop(widthPx: Float): Float = if (widthPx <= 0f) 0f else leftPx / widthPx

    /** Where the right fade starts, as a fraction of a [widthPx] container. */
    fun rightStop(widthPx: Float): Float = if (widthPx <= 0f) 1f else 1f - rightPx / widthPx
}

/**
 * The fades for text [offsetPx] into a pass of [travelPx] in a [containerPx] container. Text that fits has none.
 * Otherwise the right edge always fades, since there is more text beyond it. The left edge fades only as far as the
 * text has moved, and again only as far as it still has to go before the repeat settles at the start: at rest the
 * text starts at the container's edge with no fade, aligned with the line below it, and the fade neither pops in when
 * the text sets off nor out when it arrives. Neither fade exceeds [fadePx] or half the container.
 */
internal fun marqueeEdgeFade(overflowing: Boolean, offsetPx: Float, travelPx: Float, containerPx: Float, fadePx: Float): EdgeFade {
    if (!overflowing) return EdgeFade(0f, 0f)
    val limit = min(fadePx, containerPx / 2f)
    val left = min(limit, min(offsetPx, travelPx - offsetPx))
    return EdgeFade(leftPx = max(0f, left), rightPx = max(0f, limit))
}

/**
 * One line of text that scrolls when it is wider than its container, fading softly at both edges meanwhile. With
 * [animationMode] `WhileFocused` and no focus the text stands still, so only the right edge fades while it overflows.
 */
@Composable
internal fun FadingMarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    animationMode: MarqueeAnimationMode,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val density = LocalDensity.current
    var contentPx by remember { mutableIntStateOf(0) }
    var containerPx by remember { mutableIntStateOf(0) }
    val elapsedMillis = remember { mutableLongStateOf(0L) }
    val overflowing = contentPx > containerPx
    val standsStill = animationMode == MarqueeAnimationMode.WhileFocused
    val travelPx = marqueeTravelPx(contentPx.toFloat(), containerPx.toFloat())
    val velocityPx = with(density) { MarqueeVelocity.toPx() }
    val fadePx = with(density) { FadeWidth.toPx() }
    LaunchedEffect(overflowing && !standsStill, travelPx) {
        elapsedMillis.longValue = 0L
        if (overflowing && !standsStill) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { elapsedMillis.longValue = it - start }
        }
    }
    val fade = if (overflowing) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val offset = if (standsStill) 0f else marqueeOffsetPx(elapsedMillis.longValue, travelPx, velocityPx)
                val edges = marqueeEdgeFade(true, offset, travelPx, size.width, fadePx)
                // Opaque between the stops, transparent beyond them: DstIn keeps the text only where the mask is.
                val mask = Brush.horizontalGradient(
                    0f to (if (edges.leftPx > 0f) Color.Transparent else Color.Black),
                    edges.leftStop(size.width) to Color.Black,
                    edges.rightStop(size.width) to Color.Black,
                    1f to Color.Transparent,
                )
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
    } else {
        Modifier
    }
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        modifier = modifier
            .onSizeChanged { containerPx = it.width }
            .then(fade)
            .basicMarquee(
                animationMode = animationMode,
                repeatDelayMillis = MARQUEE_DELAY_MILLIS,
                initialDelayMillis = MARQUEE_DELAY_MILLIS,
                spacing = MarqueeSpacing.fractionOfContainer(MARQUEE_SPACING_FRACTION),
                velocity = MarqueeVelocity,
            )
            // Inside the marquee, which measures its content without a width limit: this is the text's own width.
            .onSizeChanged { contentPx = it.width },
    )
}
