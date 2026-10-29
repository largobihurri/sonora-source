package io.sonora.core.domain.playback

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow

/**
 * The tempo fader's reach, as on a CDJ's TEMPO RANGE button: the speed may move this far either side of normal. The
 * ranges are expressed as absolute speeds in basis points (1 bp = 0.01 %; 10 000 is normal speed), so [WIDE], which is
 * not symmetric, is just another pair of bounds.
 *
 * @property code A short stable code for storage ("6", "10", "16", "wide"); never change an existing one.
 * @property minBp The slowest speed of this range, in basis points.
 * @property maxBp The fastest speed of this range, in basis points.
 * @property faderStepBp The finest step the tempo fader moves in, in basis points (finer for the narrower ranges).
 */
enum class TempoRange(val code: String, val minBp: Int, val maxBp: Int, val faderStepBp: Int) {
    /** Plus or minus 6 %, in 0.02 % steps. */
    SIX("6", 9_400, 10_600, 2),

    /** Plus or minus 10 %, in 0.05 % steps. */
    TEN("10", 9_000, 11_000, 5),

    /** Plus or minus 16 %, in 0.05 % steps. */
    SIXTEEN("16", 8_400, 11_600, 5),

    /** From minus 50 % to plus 100 %, in 0.5 % steps: the whole playable speed span. */
    WIDE("wide", 5_000, 20_000, 50);

    /** Whether [speedBasisPoints] lies inside this range (both ends included). */
    operator fun contains(speedBasisPoints: Int): Boolean = speedBasisPoints in minBp..maxBp

    companion object {
        /** The range stored under [code], or null for an unknown or missing code. */
        fun fromCode(code: String?): TempoRange? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How fast and how high the music plays, set independently, modelled on a DJ player's tempo section. One setting for
 * the whole app (not per song), kept across restarts.
 *
 * The speed is held in basis points ([speedBasisPoints]: 10 000 is normal, one point is 0.01 %), from
 * [MIN_SPEED_BASIS_POINTS] (0.5×) to [MAX_SPEED_BASIS_POINTS] (2×) and always inside the chosen [range], so a tempo
 * fader built on [range] can never show a speed it cannot reach. The pitch is in equal-tempered semitones from
 * [MIN_SEMITONES] to [MAX_SEMITONES] (an octave either way). With [masterTempo] on, a speed change keeps the pitch (the
 * default); off, the pitch follows the speed like a turntable. Values that do not fit cannot be built: the `with…`,
 * `step…`, `nudge…` and `restored` functions snap and clamp.
 *
 * The older panel's API ([withSpeed], [stepSpeed], [speedPercent], [restored]) is kept on its 5 % grid; it widens the
 * [range] when a speed would not fit.
 */
data class PlaybackTempo(
    val speedBasisPoints: Int = NORMAL_SPEED_BASIS_POINTS,
    val semitones: Int = 0,
    val masterTempo: Boolean = true,
    val range: TempoRange = TempoRange.TEN,
) {

    init {
        require(speedBasisPoints in MIN_SPEED_BASIS_POINTS..MAX_SPEED_BASIS_POINTS) {
            "Speed $speedBasisPoints bp is out of bounds"
        }
        require(speedBasisPoints in range) { "Speed $speedBasisPoints bp is outside the range ${range.code}" }
        require(semitones in MIN_SEMITONES..MAX_SEMITONES) { "Pitch $semitones semitones is out of range" }
    }

    /** The playback speed as a factor: 1 is normal. */
    val speed: Float get() = speedBasisPoints / BASIS_POINTS_PER_UNIT.toFloat()

    /** How far the speed is from normal, in percent: +1.25 is 1.25 % faster, -3 is 3 % slower. */
    val tempoPercent: Double get() = (speedBasisPoints - NORMAL_SPEED_BASIS_POINTS) / 100.0

    /** The speed in whole percent (rounded towards zero), for the older panel; 100 is normal. */
    val speedPercent: Int get() = speedBasisPoints / 100

    /**
     * The pitch as a frequency factor: 1 is unchanged, 2 an octave up. With [masterTempo] it is the semitone shift
     * alone; without, the speed multiplies it too.
     */
    val pitchFactor: Float
        get() {
            val shift = 2.0.pow(semitones / 12.0)
            return (if (masterTempo) shift else shift * speedBasisPoints / BASIS_POINTS_PER_UNIT).toFloat()
        }

    /** Normal speed and pitch: the music plays as recorded (the range and Master Tempo make no difference then). */
    val isDefault: Boolean get() = speedBasisPoints == NORMAL_SPEED_BASIS_POINTS && semitones == 0

    /** The tempo of a track that is [trackBpm] at its own speed, as shown: that times the speed, to 0.1 BPM. */
    fun displayedBpm(trackBpm: Double): Double = (trackBpm * speedBasisPoints / 1_000.0).roundToLong() / 10.0

    /** This tempo [percent] off normal (+ faster), snapped to one basis point and clamped to the [range]. */
    fun withTempoPercent(percent: Double): PlaybackTempo {
        val points = if (percent.isNaN()) NORMAL_SPEED_BASIS_POINTS.toDouble() else NORMAL_SPEED_BASIS_POINTS + percent * 100.0
        return copy(speedBasisPoints = points.coerceIn(range.minBp.toDouble(), range.maxBp.toDouble()).roundToInt())
    }

    /**
     * This tempo with the fader at [fraction], from -1 (slowest of the [range]) through 0 (normal speed) to +1
     * (fastest); out of bounds clamps and NaN is the centre. The two halves scale separately so a range that is not
     * symmetric ([TempoRange.WIDE]) still has 0 % at the centre, and the result moves in the range's fader step.
     */
    fun withFader(fraction: Double): PlaybackTempo {
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(-1.0, 1.0)
        val span = if (f >= 0.0) range.maxBp - NORMAL_SPEED_BASIS_POINTS else NORMAL_SPEED_BASIS_POINTS - range.minBp
        val step = range.faderStepBp
        val offset = (f * span / step).roundToInt() * step
        return copy(speedBasisPoints = (NORMAL_SPEED_BASIS_POINTS + offset).coerceIn(range.minBp, range.maxBp))
    }

    /**
     * This tempo nudged by [deltaBpm] on a track of [trackBpm]. The target is the tempo as displayed (to 0.1 BPM)
     * plus [deltaBpm], and the speed is the nearest basis point that reaches it, so repeated nudges land on the
     * displayed tenths and never drift. An unusable [trackBpm] or [deltaBpm] changes nothing.
     */
    fun nudgeBpm(trackBpm: Double, deltaBpm: Double): PlaybackTempo {
        if (!(trackBpm > 0.0) || trackBpm.isInfinite() || deltaBpm.isNaN()) return this
        val points = (displayedBpm(trackBpm) + deltaBpm) / trackBpm * BASIS_POINTS_PER_UNIT
        return copy(speedBasisPoints = points.coerceIn(range.minBp.toDouble(), range.maxBp.toDouble()).roundToInt())
    }

    /** This tempo with the fader reach [range]; a speed outside it moves to its nearest end. */
    fun withRange(range: TempoRange): PlaybackTempo =
        copy(speedBasisPoints = speedBasisPoints.coerceIn(range.minBp, range.maxBp), range = range)

    /** This tempo with Master Tempo (keep the pitch when the speed changes) [on] or off. */
    fun withMasterTempo(on: Boolean): PlaybackTempo = copy(masterTempo = on)

    /** Back to normal speed (0 %), keeping the range, Master Tempo and the pitch. */
    fun resetTempo(): PlaybackTempo = copy(speedBasisPoints = NORMAL_SPEED_BASIS_POINTS)

    /**
     * This tempo at [speed] (a factor), snapped to the nearest 0.05× step and clamped to half and double; NaN reads as
     * normal speed. The [range] widens if the speed does not fit it.
     */
    fun withSpeed(speed: Float): PlaybackTempo {
        val steps = if (speed.isNaN()) NORMAL_SPEED_PERCENT / SPEED_STEP_PERCENT else (speed * 100f / SPEED_STEP_PERCENT).roundToInt()
        return atSpeed(steps * SPEED_STEP_PERCENT * 100)
    }

    /** This tempo [steps] speed steps (0.05× each) faster, or slower when negative, stopping at the ends. */
    fun stepSpeed(steps: Int): PlaybackTempo = atSpeed(speedBasisPoints + steps * SPEED_STEP_PERCENT * 100)

    /** This tempo at [semitones] of pitch, clamped. */
    fun withSemitones(semitones: Int): PlaybackTempo = copy(semitones = semitones.coerceIn(MIN_SEMITONES, MAX_SEMITONES))

    /** This tempo [steps] semitones higher, or lower when negative, stopping at the ends. */
    fun stepPitch(steps: Int): PlaybackTempo = withSemitones(semitones + steps)

    private fun atSpeed(basisPoints: Int): PlaybackTempo {
        val clamped = basisPoints.coerceIn(MIN_SPEED_BASIS_POINTS, MAX_SPEED_BASIS_POINTS)
        return copy(speedBasisPoints = clamped, range = fittingRange(clamped, preferred = range))
    }

    companion object {
        const val NORMAL_SPEED_BASIS_POINTS = 10_000
        const val MIN_SPEED_BASIS_POINTS = 5_000
        const val MAX_SPEED_BASIS_POINTS = 20_000
        private const val BASIS_POINTS_PER_UNIT = 10_000.0

        /** The fastest speed as a factor (2×); every [TempoRange] stays at or below it. */
        const val MAX_SPEED = 2.0

        const val NORMAL_SPEED_PERCENT = 100
        const val MIN_SPEED_PERCENT = 50
        const val MAX_SPEED_PERCENT = 200
        const val SPEED_STEP_PERCENT = 5
        const val MIN_SEMITONES = -12
        const val MAX_SEMITONES = 12

        /** Normal speed and pitch. */
        val DEFAULT = PlaybackTempo()

        /**
         * A tempo at [percent] of normal speed (100 is normal), clamped to half and double, in the range [fittingRange]
         * picks. For code that thinks in whole percent.
         */
        fun ofPercent(percent: Int, semitones: Int = 0, masterTempo: Boolean = true): PlaybackTempo =
            DEFAULT.atSpeed(percent.coerceIn(0, MAX_SPEED_PERCENT) * 100).withSemitones(semitones).withMasterTempo(masterTempo)

        /**
         * The range to show for a stored speed of [speedBasisPoints]: [preferred] if it holds the speed, otherwise the
         * narrowest of +-10 %, +-16 % and wide that does.
         */
        fun fittingRange(speedBasisPoints: Int, preferred: TempoRange = TempoRange.TEN): TempoRange =
            if (speedBasisPoints in preferred) {
                preferred
            } else {
                listOf(TempoRange.TEN, TempoRange.SIXTEEN).firstOrNull { speedBasisPoints in it } ?: TempoRange.WIDE
            }

        /** The frequency factor of [semitones] in equal temperament: 2^(semitones / 12). */
        fun pitchFactorOf(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()

        /** The semitone nearest to [pitchFactor], clamped to the range; anything not above 0 is the lowest. */
        fun semitonesOf(pitchFactor: Float): Int {
            if (pitchFactor.isNaN() || pitchFactor <= 0f) return MIN_SEMITONES
            return (12.0 * log2(pitchFactor.toDouble())).roundToInt().coerceIn(MIN_SEMITONES, MAX_SEMITONES)
        }

        /**
         * A stored tempo in whole percent, snapped onto the 5 % grid and clamped, so an edited or older store never
         * fails to load. Master Tempo is on, and the range is the narrowest of +-10 %, +-16 % and wide that holds it.
         */
        fun restored(speedPercent: Int, semitones: Int): PlaybackTempo =
            DEFAULT.withSpeed(speedPercent / 100f).withSemitones(semitones)
    }
}

/** Where the [PlaybackTempo] setting is kept; the player and Now Playing both follow it. */
interface PlaybackTempoRepository {
    /** The current setting ([PlaybackTempo.DEFAULT] if never set); re-emits on every change. */
    val tempo: Flow<PlaybackTempo>

    /**
     * Shows [tempo] as the current setting without storing it (null clears it): for a fader being dragged, whose every
     * move should be heard but only its release written. Held in memory, for this process only, and it wins over the
     * stored setting until it is cleared, which a successful [update] does.
     */
    fun preview(tempo: PlaybackTempo?)

    /** Atomically replaces the setting with [transform] applied to the current one, and stores the result. */
    suspend fun update(transform: (PlaybackTempo) -> PlaybackTempo)
}
