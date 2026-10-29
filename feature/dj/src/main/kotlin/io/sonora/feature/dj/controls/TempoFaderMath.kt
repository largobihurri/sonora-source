package io.sonora.feature.dj.controls

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sonora.core.domain.playback.PlaybackTempo
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * The pure geometry of the [TempoFader]: where a tempo sits on the fader, the sticky 0 % detent, fine mode and the
 * thumb's travel. A fader fraction runs from -1 (the slowest of the tempo's range) through 0 (normal speed) to +1 (the
 * fastest), with the halves scaled apart as [PlaybackTempo.withFader] does.
 *
 * The detent works on a "raw" position, the finger's accumulated travel: within [DETENT_ZONE] of the centre the fader
 * holds at exactly 0, and beyond it the raw position maps onto the fader so that it leaves 0 without a jump and still
 * reaches both ends ([effectiveOf], inverted by [rawOf]).
 */
object TempoFaderMath {

    /** Half the width of the sticky 0 % zone, as a fraction of half the fader's travel. */
    const val DETENT_ZONE = 0.05

    /** The most fine mode divides the gain by (2, 4, then 8). */
    const val MAX_FINE_LEVEL = 3

    /** Where [tempo] sits on its range's fader: the inverse of [PlaybackTempo.withFader]. */
    fun fractionOf(tempo: PlaybackTempo): Double {
        val normal = PlaybackTempo.NORMAL_SPEED_BASIS_POINTS
        val bp = tempo.speedBasisPoints
        val range = tempo.range
        return if (bp >= normal) {
            (bp - normal).toDouble() / (range.maxBp - normal)
        } else {
            -(normal - bp).toDouble() / (normal - range.minBp)
        }
    }

    /** [tempo] with the fader at [fraction]: the model's own fader step and clamping ([PlaybackTempo.withFader]). */
    fun tempoAt(tempo: PlaybackTempo, fraction: Double): PlaybackTempo = tempo.withFader(fraction)

    /** The fader fraction of the [raw] position: 0 inside the detent [zone], continuous and reaching ±1 outside it. */
    fun effectiveOf(raw: Double, zone: Double = DETENT_ZONE): Double {
        val r = if (raw.isNaN()) 0.0 else raw.coerceIn(-1.0, 1.0)
        val distance = abs(r)
        return if (distance <= zone) 0.0 else sign(r) * (distance - zone) / (1.0 - zone)
    }

    /** The raw position showing [fraction]: the centre of the detent for 0, otherwise just as far beyond it. */
    fun rawOf(fraction: Double, zone: Double = DETENT_ZONE): Double {
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(-1.0, 1.0)
        return if (f == 0.0) 0.0 else sign(f) * (abs(f) * (1.0 - zone) + zone)
    }

    /**
     * Whether a move of the raw position from [fromRaw] to [toRaw] reaches 0 %: it enters the detent [zone] from
     * outside, or jumps across it in one move. Moves inside the zone or out of it do not.
     */
    fun hitsDetent(fromRaw: Double, toRaw: Double, zone: Double = DETENT_ZONE): Boolean {
        if (abs(fromRaw) <= zone) return false
        return abs(toRaw) <= zone || sign(toRaw) != sign(fromRaw)
    }

    /**
     * How much fine mode divides the gain by, for a finger [dyFromCentrePx] above or below the fader's centre line: 1
     * on the fader ([halfHeightPx] either side) and up to one [stepPx] beyond it, then 2, 4 and 8 for each further step.
     * Unusable input keeps full gain. The vertical fader passes the finger's distance to the left or right of its own
     * centre line and half its width.
     */
    fun fineDivisor(dyFromCentrePx: Float, halfHeightPx: Float, stepPx: Float): Int {
        if (!dyFromCentrePx.isFinite() || !(stepPx > 0f)) return 1
        val beyond = (abs(dyFromCentrePx) - halfHeightPx).coerceAtLeast(0f)
        val level = floor(beyond / stepPx).toInt().coerceIn(0, MAX_FINE_LEVEL)
        return 1 shl level
    }

    /**
     * The thumb's left edge for [fraction] on a track [trackWidthPx] wide: from 0 at -1 to the track minus the thumb
     * at +1 (right is faster), never outside the track.
     */
    fun thumbOffsetPx(fraction: Double, trackWidthPx: Float, thumbWidthPx: Float): Float {
        val travel = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(-1.0, 1.0)
        return ((f + 1.0) / 2.0 * travel).toFloat()
    }

    /**
     * The vertical fader's thumb top edge for [fraction] on a track [trackHeightPx] tall, in the Pioneer CDJ's direction: "-"
     * on top and "+" at the bottom, so from 0 at -1 (the slowest) to the track minus the thumb at +1 (the fastest), never
     * outside the track. It is the horizontal mapping turned a quarter, slower at the start and faster at the end.
     */
    fun verticalThumbOffsetPx(fraction: Double, trackHeightPx: Float, thumbHeightPx: Float): Float =
        thumbOffsetPx(fraction, trackHeightPx, thumbHeightPx)

    /** The y of the thumb's centre for [fraction]: where the track's marks stand (the same travel as the thumb's). */
    fun verticalMarkY(fraction: Double, trackHeightPx: Float, thumbHeightPx: Float): Float =
        verticalThumbOffsetPx(fraction, trackHeightPx, thumbHeightPx) + thumbHeightPx / 2f

    /**
     * The raw vertical fader move for a finger travel of [dyPx] (down is positive in screen coordinates): pulling down, towards
     * you as on a CDJ, speeds the tempo up, and half the thumb's [travelPx] is one whole half of the fader, divided by the
     * fine-mode [divisor].
     */
    fun verticalFractionDelta(dyPx: Float, travelPx: Float, divisor: Int): Double = fractionDelta(dyPx, travelPx, divisor)

    /**
     * The raw fader move for a finger travel of [dxPx] when the thumb can travel [travelPx]: half the travel is one
     * whole half of the fader (the finger and thumb move together at full gain), divided by the fine-mode [divisor].
     */
    fun fractionDelta(dxPx: Float, travelPx: Float, divisor: Int): Double {
        if (!(travelPx > 0f)) return 0.0
        return dxPx / (travelPx / 2.0) / divisor.coerceAtLeast(1)
    }
}

/**
 * One drag of the [TempoFader], relative like iOS sliders: it starts where the fader is ([start]), never where the
 * finger lands, and moves only by the finger's travel ([dragBy], already scaled by fine mode). Past an end it stops,
 * and turns back at once. Exactly one of [end] or [cancel] finishes it. [fraction] and [dragging] are snapshot state,
 * for the thumb to read in layout and draw.
 */
@Stable
class TempoFaderDrag(private val zone: Double = TempoFaderMath.DETENT_ZONE) {
    var dragging by mutableStateOf(false)
        private set
    private var raw by mutableDoubleStateOf(0.0)

    /** The fader fraction now shown, -1..1, exactly 0 within the detent. */
    val fraction: Double get() = TempoFaderMath.effectiveOf(raw, zone)

    /** Starts a drag from the fader's [fraction]. */
    fun start(fraction: Double) {
        raw = TempoFaderMath.rawOf(fraction, zone)
        dragging = true
    }

    /** Moves the fader by [delta] (raw); true when the move reached the 0 % detent, for a haptic tick. */
    fun dragBy(delta: Double): Boolean {
        if (!dragging || delta.isNaN()) return false
        val next = (raw + delta).coerceIn(-1.0, 1.0)
        val hit = TempoFaderMath.hitsDetent(raw, next, zone)
        raw = next
        return hit
    }

    /** Ends the drag: the fraction to store, or null when no drag was in progress. */
    fun end(): Double? {
        if (!dragging) return null
        dragging = false
        return fraction
    }

    /** Ends the drag without a value (a parent took the pointer). */
    fun cancel() {
        dragging = false
    }
}
