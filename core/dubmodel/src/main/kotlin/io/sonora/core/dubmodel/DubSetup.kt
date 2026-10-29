package io.sonora.core.dubmodel

import kotlin.math.log10
import kotlin.math.pow

/** The echo time as a note value, locked to the beat: frames = sampleRate · 60 / bpm · [beats]. */
enum class DubDivision(val numerator: Int, val denominator: Int) {
    SIXTEENTH(1, 16),
    EIGHTH(1, 8),
    DOTTED_EIGHTH(3, 16),
    QUARTER(1, 4),
    DOTTED_QUARTER(3, 8),
    HALF(1, 2),
    ;

    /** The division as a fraction of a whole note (four beats). */
    val wholeNotes: Double get() = numerator.toDouble() / denominator

    /** The division in beats (quarter notes). */
    val beats: Double get() = wholeNotes * 4
}

/** How the pads answer a touch (Settings › DUB Mode › Pads). */
enum class PadBehaviour {
    /** Down engages, up releases; a second down within the double-tap window latches. */
    HOLD_DOUBLE_TAP_LATCH,

    /** Each tap turns the pad on or off. */
    TOGGLE,
}

/** The five ways of the preamp, low to high, with their default band corners. */
enum class PreampWay(val defaultHighPassHz: Float, val defaultLowPassHz: Float) {
    SUB(WaySetup.MIN_CORNER_HZ, 80f),
    BASS(80f, 250f),
    LOW_MID(250f, 1_200f),
    HIGH_MID(1_200f, 5_000f),
    TOP(5_000f, WaySetup.MAX_CORNER_HZ),
}

/**
 * One way of the preamp: a band between [highPassHz] and [lowPassHz] (a corner at its range end is off) and a fader
 * [gain] (linear: 0 is -inf dB, 1 is the 0 dB detent, [MAX_GAIN] is +6 dB). The kill switch is performance state and
 * is not part of the setup.
 */
data class WaySetup(
    val highPassHz: Float,
    val lowPassHz: Float,
    val gain: Float = UNITY_GAIN,
) {
    /** The high-pass corner at the bottom of its range: no high-pass at all. */
    val highPassOff: Boolean get() = highPassHz <= MIN_CORNER_HZ

    /** The low-pass corner at the top of its range: no low-pass at all. */
    val lowPassOff: Boolean get() = lowPassHz >= MAX_CORNER_HZ

    /** The fader in dB: -inf at gain 0, 0 at the detent, +6 at the top. */
    val levelDb: Float get() = if (gain > 0f) 20f * log10(gain) else Float.NEGATIVE_INFINITY

    /** The high-pass moved to [hz], kept inside the range and not above the low-pass. */
    fun withHighPass(hz: Float): WaySetup =
        if (hz.isNaN()) this else copy(highPassHz = hz.coerceIn(MIN_CORNER_HZ, lowPassHz.coerceIn(MIN_CORNER_HZ, MAX_CORNER_HZ)))

    /** The low-pass moved to [hz], kept inside the range and not below the high-pass. */
    fun withLowPass(hz: Float): WaySetup =
        if (hz.isNaN()) this else copy(lowPassHz = hz.coerceIn(highPassHz.coerceIn(MIN_CORNER_HZ, MAX_CORNER_HZ), MAX_CORNER_HZ))

    /** The fader set to [db] (-inf mutes the way), kept at or below +6 dB. */
    fun withLevelDb(db: Float): WaySetup = copy(
        gain = when {
            db.isNaN() -> UNITY_GAIN
            db == Float.NEGATIVE_INFINITY -> 0f
            else -> 10f.pow(db.coerceAtMost(MAX_LEVEL_DB) / 20f).coerceIn(0f, MAX_GAIN)
        },
    )

    /**
     * This way inside its valid ranges: corners in [MIN_CORNER_HZ]..[MAX_CORNER_HZ] with the high-pass not above the
     * low-pass (swapped when they cross), gain in 0..[MAX_GAIN]; a value that is not a number takes [fallback]'s.
     */
    fun clamped(fallback: WaySetup): WaySetup {
        val high = highPassHz.orIfNaN(fallback.highPassHz).coerceIn(MIN_CORNER_HZ, MAX_CORNER_HZ)
        val low = lowPassHz.orIfNaN(fallback.lowPassHz).coerceIn(MIN_CORNER_HZ, MAX_CORNER_HZ)
        return WaySetup(
            highPassHz = minOf(high, low),
            lowPassHz = maxOf(high, low),
            gain = gain.orIfNaN(fallback.gain).coerceIn(0f, MAX_GAIN),
        )
    }

    companion object {
        const val MIN_CORNER_HZ: Float = 20f
        const val MAX_CORNER_HZ: Float = 20_000f
        const val UNITY_GAIN: Float = 1f
        const val MAX_LEVEL_DB: Float = 6f

        /** +6 dB as a linear gain. */
        val MAX_GAIN: Float = 10f.pow(MAX_LEVEL_DB / 20f)

        /** The default corners of [way] at unity gain. */
        fun defaultFor(way: PreampWay): WaySetup = WaySetup(way.defaultHighPassHz, way.defaultLowPassHz)
    }
}

/** The five ways of the preamp, in [PreampWay] order. */
data class PreampSetup(val ways: List<WaySetup> = PreampWay.entries.map(WaySetup::defaultFor)) {

    operator fun get(way: PreampWay): WaySetup = ways.getOrElse(way.ordinal) { WaySetup.defaultFor(way) }

    /** This setup with [way] replaced by [setup]. */
    fun with(way: PreampWay, setup: WaySetup): PreampSetup =
        PreampSetup(PreampWay.entries.map { if (it == way) setup else this[it] })

    /** Exactly five ways, each clamped; a missing way takes its default. */
    fun clamped(): PreampSetup = PreampSetup(PreampWay.entries.map { this[it].clamped(WaySetup.defaultFor(it)) })
}

/**
 * The stored DUB effect setup: the echo [division], [feedback] (0..[MAX_FEEDBACK]; higher only through FREEZE or
 * RUNAWAY) and [returnLevel] (0..1), the XY filter [lock], the [padBehaviour], the ways the DROP pad kills
 * ([dropWays]) and the five-way [preamp] (corners and fader levels). Performance state (latches, the filter position,
 * freeze, the preamp kills) is reset every time the mode opens and never stored.
 */
data class DubSetup(
    val division: DubDivision = DubDivision.QUARTER,
    val feedback: Float = DEFAULT_FEEDBACK,
    val returnLevel: Float = DEFAULT_RETURN,
    val lock: Boolean = false,
    val padBehaviour: PadBehaviour = PadBehaviour.HOLD_DOUBLE_TAP_LATCH,
    val dropWays: Set<PreampWay> = DEFAULT_DROP_WAYS,
    val preamp: PreampSetup = PreampSetup(),
) {
    /** This setup inside its valid ranges; a value that is not a number takes its default. */
    fun clamped(): DubSetup = copy(
        feedback = feedback.orIfNaN(DEFAULT_FEEDBACK).coerceIn(0f, MAX_FEEDBACK),
        returnLevel = returnLevel.orIfNaN(DEFAULT_RETURN).coerceIn(0f, MAX_RETURN),
        preamp = preamp.clamped(),
    )

    companion object {
        const val DEFAULT_FEEDBACK: Float = 0.55f
        const val DEFAULT_RETURN: Float = 0.80f
        const val MAX_FEEDBACK: Float = 0.95f
        const val MAX_RETURN: Float = 1f
        val DEFAULT_DROP_WAYS: Set<PreampWay> = setOf(PreampWay.SUB, PreampWay.BASS)
    }
}

private fun Float.orIfNaN(fallback: Float): Float = if (isNaN()) fallback else this
