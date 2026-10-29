package io.sonora.feature.dub.controls

import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlin.math.pow

/** Where the XY filter pad stands: [x] 0..1 (0.5 is the flat detent), [y] 0..1 (0 the least resonance, 1 the most). */
@Immutable
data class XyPosition(val x: Float, val y: Float)

/** What the X axis does: a low-pass on the left, nothing at the detent, a high-pass on the right. */
enum class XyFilterMode { LOW_PASS, FLAT, HIGH_PASS }

/** The filter an [XyPosition] asks for; [cutoffHz] is 0 when [mode] is [XyFilterMode.FLAT] (a true bypass). */
@Immutable
data class XyFilterValue(val mode: XyFilterMode, val cutoffHz: Float, val q: Float)

/** One finger sample on the pad: the new [position], and whether the move reached the flat detent (one haptic tick). */
@Immutable
data class XyTouch(val position: XyPosition, val hitsDetent: Boolean)

/**
 * The pure mapping of the XY sweep filter (design §2 "XY filter"). X runs from a low-pass on the left edge (200 Hz)
 * through a flat detent with true bypass at the centre to a high-pass on the right edge (6 kHz); each side is a log
 * map from fully open at the detent (20 kHz low-pass, 20 Hz high-pass) to its edge. Y is the resonance, Q 0.7-8 on a
 * log map. The finger is absolute (the crosshair sits under it); the detent is [DETENT_ZONE] wide either side of the
 * centre, in finger units, and stretched like [DetentAxis] so both edges stay reachable. On lift the pad glides to
 * [Centre] over [GLIDE_MS] unless LOCK is on.
 */
object XyMapping {
    const val LOW_PASS_EDGE_HZ = 200f
    const val HIGH_PASS_EDGE_HZ = 6_000f
    const val OPEN_LOW_PASS_HZ = 20_000f
    const val OPEN_HIGH_PASS_HZ = 20f
    const val MIN_Q = 0.7f
    const val MAX_Q = 8f

    /** Half the width of the flat detent, as a part of the pad's width. */
    const val DETENT_ZONE = 0.04f

    /** How long the pad takes to glide back to the centre after a lift. */
    const val GLIDE_MS = 150

    /** The rest position: flat, with the resonance half way (where the "Centre" action and a glide put it). */
    val Centre = XyPosition(0.5f, 0.5f)

    val axis = DetentAxis(centre = 0.5, zone = DETENT_ZONE.toDouble())

    /** The filter at [position]; a non-finite X reads as flat. */
    fun valueAt(position: XyPosition): XyFilterValue {
        val q = qAt(position.y)
        val x = position.x
        return when {
            !x.isFinite() || x == 0.5f -> XyFilterValue(XyFilterMode.FLAT, 0f, q)
            x < 0.5f -> XyFilterValue(XyFilterMode.LOW_PASS, logLerp(OPEN_LOW_PASS_HZ, LOW_PASS_EDGE_HZ, side(x)), q)
            else -> XyFilterValue(XyFilterMode.HIGH_PASS, logLerp(OPEN_HIGH_PASS_HZ, HIGH_PASS_EDGE_HZ, side(x)), q)
        }
    }

    /** The resonance at [y]. */
    fun qAt(y: Float): Float = logLerp(MIN_Q, MAX_Q, if (y.isFinite()) y.coerceIn(0f, 1f) else 0f)

    /** The y showing resonance [q]: the inverse of [qAt]. */
    fun yOfQ(q: Float): Float {
        if (!q.isFinite()) return 0f
        return (ln(q.coerceIn(MIN_Q, MAX_Q) / MIN_Q) / ln(MAX_Q / MIN_Q)).coerceIn(0f, 1f)
    }

    /** The x showing [mode] at [cutoffHz]: the inverse of [valueAt]'s X. */
    fun xOf(mode: XyFilterMode, cutoffHz: Float): Float {
        val hz = if (cutoffHz.isFinite()) cutoffHz else return 0.5f
        return when (mode) {
            XyFilterMode.FLAT -> 0.5f
            XyFilterMode.LOW_PASS -> 0.5f - 0.5f * logFraction(OPEN_LOW_PASS_HZ, LOW_PASS_EDGE_HZ, hz)
            XyFilterMode.HIGH_PASS -> 0.5f + 0.5f * logFraction(OPEN_HIGH_PASS_HZ, HIGH_PASS_EDGE_HZ, hz)
        }
    }

    /**
     * The pad at the finger's [rawX], [rawY] (0..1 of the pad, y up), coming from [current]: inside the detent X is
     * exactly flat, and [XyTouch.hitsDetent] is true only for the move that reaches it. A non-finite coordinate keeps
     * the current one.
     */
    fun touch(current: XyPosition, rawX: Float, rawY: Float): XyTouch {
        val fromRaw = axis.rawOf(current.x.toDouble())
        val toRaw = if (rawX.isFinite()) rawX.coerceIn(0f, 1f).toDouble() else fromRaw
        val x = if (rawX.isFinite()) axis.effectiveOf(toRaw).toFloat() else current.x
        val y = if (rawY.isFinite()) rawY.coerceIn(0f, 1f) else current.y
        return XyTouch(XyPosition(x, y), axis.hitsDetent(fromRaw, toRaw))
    }

    /** Where the pad glides after the finger lifts: the centre, or nowhere (null) while LOCK holds it. */
    fun liftTarget(locked: Boolean): XyPosition? = if (locked) null else Centre

    /** The glide from [from] to [to] at [fraction] 0..1 of [GLIDE_MS], easing out; lands exactly on [to]. */
    fun glide(from: XyPosition, to: XyPosition, fraction: Float): XyPosition {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        if (f >= 1f) return to
        if (f <= 0f) return from
        val eased = 1f - (1f - f).pow(3)
        return XyPosition(from.x + (to.x - from.x) * eased, from.y + (to.y - from.y) * eased)
    }

    /** How far [x] is from the detent towards its edge, 0..1. */
    private fun side(x: Float): Float = (kotlin.math.abs(x - 0.5f) / 0.5f).coerceIn(0f, 1f)

    private fun logLerp(from: Float, to: Float, t: Float): Float =
        (from.toDouble() * (to.toDouble() / from).pow(t.toDouble())).toFloat()

    private fun logFraction(from: Float, to: Float, value: Float): Float {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val v = value.coerceIn(lo, hi)
        return (ln(v.toDouble() / from) / ln(to.toDouble() / from)).toFloat().coerceIn(0f, 1f)
    }
}
