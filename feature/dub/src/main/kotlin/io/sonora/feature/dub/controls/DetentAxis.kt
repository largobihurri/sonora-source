package io.sonora.feature.dub.controls

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.sign

/**
 * A 0..1 control axis with a sticky detent at [centre]: within [zone] either side of it a "raw" position (the finger's)
 * holds at exactly [centre], and beyond it the raw position is stretched so that the value leaves the detent without a
 * jump and still reaches 0 and 1 ([effectiveOf], inverted by [rawOf]). The fader's 0 dB and the XY pad's flat centre
 * are such detents. Unusable input reads as the centre.
 */
class DetentAxis(val centre: Double, val zone: Double) {
    init {
        require(zone > 0.0 && centre - zone > 0.0 && centre + zone < 1.0) { "The detent zone must sit inside 0..1" }
    }

    /** The value shown for the [raw] position. */
    fun effectiveOf(raw: Double): Double {
        if (raw.isNaN()) return centre
        val r = raw.coerceIn(0.0, 1.0)
        return when {
            abs(r - centre) <= zone + EPSILON -> centre
            r < centre -> r * centre / (centre - zone)
            else -> centre + (r - centre - zone) * (1.0 - centre) / (1.0 - centre - zone)
        }
    }

    /** The raw position that shows [value]: the centre for the centre, otherwise just as far beyond the zone. */
    fun rawOf(value: Double): Double {
        if (value.isNaN()) return centre
        val v = value.coerceIn(0.0, 1.0)
        return when {
            v == centre -> centre
            v < centre -> v * (centre - zone) / centre
            else -> centre + zone + (v - centre) * (1.0 - centre - zone) / (1.0 - centre)
        }
    }

    private companion object {
        /** Rounding slack, so a raw position spelled exactly at the zone's edge (0.75 - 0.05) counts as inside. */
        const val EPSILON = 1e-12
    }

    /** Whether a move of the raw position from [fromRaw] to [toRaw] reaches the detent: it enters the zone, or jumps it. */
    fun hitsDetent(fromRaw: Double, toRaw: Double): Boolean {
        if (fromRaw.isNaN() || toRaw.isNaN()) return false
        val from = fromRaw - centre
        val to = toRaw - centre
        if (abs(from) <= zone + EPSILON) return false
        return abs(to) <= zone + EPSILON || sign(to) != sign(from)
    }
}

/**
 * One relative drag on a [DetentAxis], like the capsule sliders: it starts where the control is ([start]), never where the
 * finger lands, and moves only by the finger's travel ([dragBy], in axis units). Exactly one of [end] or [cancel]
 * finishes it. [value] and [dragging] are snapshot state, so a control can read them in layout and draw.
 */
@Stable
class DetentDrag(private val axis: DetentAxis) {
    var dragging by mutableStateOf(false)
        private set
    private var raw by mutableDoubleStateOf(axis.centre)

    /** The value now shown, exactly the centre inside the detent. */
    val value: Double get() = axis.effectiveOf(raw)

    fun start(value: Double) {
        raw = axis.rawOf(value)
        dragging = true
    }

    /** Moves by [delta]; true when the move reached the detent, for one haptic tick. */
    fun dragBy(delta: Double): Boolean {
        if (!dragging || delta.isNaN()) return false
        val next = (raw + delta).coerceIn(0.0, 1.0)
        val hit = axis.hitsDetent(raw, next)
        raw = next
        return hit
    }

    /** Ends the drag: the value to keep, or null when no drag was in progress. */
    fun end(): Double? {
        if (!dragging) return null
        dragging = false
        return value
    }

    fun cancel() {
        dragging = false
    }
}
