package io.sonora.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.sonora.core.domain.playback.PlaybackBandLevels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * The Apple Music "now playing" glyph: thin rounded bars standing on a common baseline, in [color]. While [animating]
 * they bounce, each at its own pace; otherwise they rest at a fixed pose (paused). Drawn in the draw phase only, so the
 * bouncing never recomposes the row it sits in.
 *
 * With [levels], playing bars show the music instead: bass, kick, mids and highs, each bar's height and colour
 * following its band's level ([NowPlayingBarsLevels]). The bars watch the levels only while playing and the screen is
 * started. Until the music's levels arrive (the song is still loading) they wait flat at the bottom rather than
 * bouncing, so they only ever move with the music.
 */
@Composable
fun NowPlayingBars(
    animating: Boolean,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
    levels: PlaybackBandLevels? = null,
) {
    if (levels != null && animating) {
        LifecycleStartEffect(levels) {
            val watching = levels.watch()
            onStopOrDispose { watching.close() }
        }
    }
    val live = remember(levels) { levels?.let(::LiveBars) }
    // Paused, no transition runs at all.
    val phase = if (animating) {
        rememberInfiniteTransition(label = "nowPlayingBars").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(NowPlayingBarsMotion.CYCLE_MS, easing = LinearEasing)),
            label = "nowPlayingBarsPhase",
        )
    } else {
        null
    }
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(width = 14.dp, height = 12.dp).then(described)) {
        val count = NowPlayingBarsMotion.BAR_COUNT
        val barWidth = size.width / (count + (count - 1) * GAP_RATIO)
        val step = barWidth * (1f + GAP_RATIO)
        val corner = CornerRadius(barWidth / 2f)
        // Read on every draw, the phase is also the frame clock that redraws the live levels while playing.
        val at = phase?.value ?: 0f
        val meter = live?.takeIf { animating && it.update(System.nanoTime()) }
        if (meter == null) live?.cool()
        for (bar in 0 until count) {
            val fraction = NowPlayingBarsLevels.barHeight(bar, meter?.height(bar), metered = live != null, phase = at, animating = animating)
            val height = size.height * fraction
            val barColor = meter?.color(bar, color) ?: color
            drawRoundRect(barColor, Offset(bar * step, size.height - height), Size(barWidth, height), corner)
        }
    }
}

/** The bars' view of the live levels: the latest levels read, and each bar's colour heat easing towards its level. */
private class LiveBars(private val source: PlaybackBandLevels) {
    private val levels = FloatArray(PlaybackBandLevels.BAND_COUNT)
    private val heat = FloatArray(PlaybackBandLevels.BAND_COUNT)
    private var lastNanos = 0L

    /** Reads the levels heard now; false when there are none. */
    fun update(nowNanos: Long): Boolean {
        if (!source.read(levels)) return false
        val elapsed = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, 1f)
        lastNanos = nowNanos
        for (bar in heat.indices) heat[bar] = NowPlayingBarsLevels.approach(heat[bar], NowPlayingBarsLevels.heat(levels[bar]), elapsed)
        return true
    }

    /** No live levels: the next ones start from the plain colour. */
    fun cool() {
        heat.fill(0f)
        lastNanos = 0L
    }

    fun height(bar: Int): Float = NowPlayingBarsLevels.height(levels[bar])

    fun color(bar: Int, base: Color): Color = NowPlayingBarsLevels.color(base, heat[bar])
}

/**
 * How live band levels look on the bars, VU-meter style. Levels are the meter's dBFS (AES-17 RMS, a full-scale sine
 * at 0 dB); the constants are meant to be tuned by eye on real music. Pure, so it is unit-tested.
 *
 * - **Height**: [FLOOR_DB]..0 dB maps linearly onto [NowPlayingBarsMotion.MIN_HEIGHT]..1 (quieter stays at the minimum).
 * - **Colour**: the bar's own colour below [ORANGE_DB], [ORANGE] from there, [RED] from [RED_DB]. RMS levels keep loud
 *   masters out of permanent red: they peak around -3 to -6 dB in their loudest band. The colour eases between them
 *   over about [HEAT_SECONDS] instead of flickering.
 */
object NowPlayingBarsLevels {
    const val FLOOR_DB = -48f
    const val ORANGE_DB = -9f
    const val RED_DB = -3f

    /** Time constant of the colour's change between tiers. */
    const val HEAT_SECONDS = 0.08f

    val ORANGE = Color(0xFFFF9F0A)
    val RED = Color(0xFFFF453A)

    /** The height (fraction of the glyph) of a bar at [db]. */
    fun height(db: Float): Float {
        val position = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        return NowPlayingBarsMotion.MIN_HEIGHT + (1f - NowPlayingBarsMotion.MIN_HEIGHT) * position
    }

    /**
     * A bar's height: its [live] level when there is one; flat at the bottom while a [metered] bar plays without levels
     * yet; otherwise the glyph's own motion at [phase].
     */
    fun barHeight(bar: Int, live: Float?, metered: Boolean, phase: Float, animating: Boolean): Float = when {
        live != null -> live
        metered && animating -> NowPlayingBarsMotion.MIN_HEIGHT
        else -> NowPlayingBarsMotion.height(bar, phase, animating)
    }

    /** The colour tier a level calls for: 0 the bar's colour, 1 orange, 2 red. */
    fun heat(db: Float): Float = when {
        db >= RED_DB -> 2f
        db >= ORANGE_DB -> 1f
        else -> 0f
    }

    /** [current] heat eased towards [target] over [elapsedSeconds]. */
    fun approach(current: Float, target: Float, elapsedSeconds: Float): Float =
        target + (current - target) * exp(-elapsedSeconds / HEAT_SECONDS)

    /** [base] at heat 0, [ORANGE] at 1, [RED] at 2, blended in between. */
    fun color(base: Color, heat: Float): Color = when {
        heat <= 0f -> base
        heat < 1f -> lerp(base, ORANGE, heat)
        heat == 1f -> ORANGE
        heat < 2f -> lerp(ORANGE, RED, heat - 1f)
        else -> RED
    }
}

/**
 * The bars' heights, as fractions of the glyph's height. Playing, bar `i` follows a cosine that completes
 * [CYCLES]`[i]` bounces per [CYCLE_MS] from its own starting point, so the bars never move in step and the cycle loops
 * seamlessly. Pure, so it is unit-tested.
 */
object NowPlayingBarsMotion {
    const val BAR_COUNT = 4
    const val CYCLE_MS = 3_000
    const val MIN_HEIGHT = 0.2f

    /** The paused pose: low and uneven, so it still reads as the same glyph. */
    val RESTING = listOf(0.4f, 0.7f, 0.5f, 0.3f)

    private val CYCLES = intArrayOf(4, 5, 3, 6)
    private val OFFSETS = floatArrayOf(0f, 0.3f, 0.6f, 0.15f)

    /** The height of [bar] at [phase] (0..1 through the cycle), or its resting height when not [animating]. */
    fun height(bar: Int, phase: Float, animating: Boolean): Float {
        if (!animating) return RESTING[bar]
        val wave = 0.5 - 0.5 * cos(2.0 * PI * (CYCLES[bar] * phase + OFFSETS[bar]))
        return (MIN_HEIGHT + (1f - MIN_HEIGHT) * wave).toFloat()
    }

    fun heights(phase: Float, animating: Boolean): List<Float> = List(BAR_COUNT) { height(it, phase, animating) }
}

private const val GAP_RATIO = 0.6f
