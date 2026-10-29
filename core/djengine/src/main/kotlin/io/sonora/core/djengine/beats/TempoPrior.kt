package io.sonora.core.djengine.beats

import kotlin.math.exp
import kotlin.math.ln

/**
 * What the grid fitter believes about tempo before it hears the track: the range a DJ tempo lives in and the tempo
 * people hear most often, used to settle the octave ambiguity of a beat tracker (a track at 120 BPM can be tracked at
 * 60 or 240 BPM and the beats alone cannot tell).
 *
 * A measured tempo is folded by factors of two: [candidates] are its multiples inside `[minBpm, maxBpm]`, and when
 * more than one fits (any range wider than an octave has such tempos), [weight] says which is preferred: a log-normal
 * bump centred on [preferredBpm] and [preferenceWidthOctaves] wide, the shape of the tempo-preference curves in
 * Moelants and McKinney (2004), "Tempo perception and musical content" (a broad preference around 120 BPM).
 *
 * The range is explicit because the calibration harness tunes it: [DEFAULT] is 70..180 BPM, [REFERENCE_WIDE] is the
 * 70..210 BPM span the reference library covers.
 */
data class TempoPrior(
    val minBpm: Double,
    val maxBpm: Double,
    val preferredBpm: Double = 120.0,
    val preferenceWidthOctaves: Double = 0.6,
) {
    init {
        require(minBpm.isFinite() && minBpm > 0) { "minBpm must be positive: $minBpm" }
        require(maxBpm.isFinite() && maxBpm >= minBpm) { "maxBpm ($maxBpm) must not be below minBpm ($minBpm)" }
        require(preferredBpm.isFinite() && preferredBpm > 0) { "preferredBpm must be positive: $preferredBpm" }
        require(preferenceWidthOctaves.isFinite() && preferenceWidthOctaves > 0) {
            "preferenceWidthOctaves must be positive: $preferenceWidthOctaves"
        }
    }

    /** How much the prior likes [bpm]: 1.0 at [preferredBpm], falling with the distance in octaves; never zero. */
    fun weight(bpm: Double): Double {
        val octaves = log2(bpm / preferredBpm) / preferenceWidthOctaves
        return exp(-0.5 * octaves * octaves)
    }

    /** The multiples of [bpm] by a power of two that lie in the range, slowest first (possibly none). */
    fun candidates(bpm: Double): List<Double> {
        require(bpm.isFinite() && bpm > 0) { "bpm must be positive: $bpm" }
        var tempo = bpm
        while (tempo > minBpm * 2) tempo /= 2
        while (tempo < minBpm) tempo *= 2
        val out = ArrayList<Double>(2)
        // `tempo` is now the slowest multiple that is not below the range (or is above it when none fits).
        while (tempo <= maxBpm) {
            out += tempo
            tempo *= 2
        }
        return out
    }

    /** The multiple of [bpm] the prior prefers; when none is in the range, the one closest to it (in octaves). */
    fun fold(bpm: Double): Double {
        val inRange = candidates(bpm)
        if (inRange.isNotEmpty()) return inRange.maxByOrNull { weight(it) }!!
        var tempo = bpm
        while (tempo > minBpm) tempo /= 2
        // `tempo` is the fastest multiple not above the range's floor; its double is the slowest one above it.
        val below = tempo
        val above = tempo * 2
        return if (log2(minBpm / below) <= log2(above / maxBpm)) below else above
    }

    companion object {
        /** The default range, 70 to 180 BPM. */
        val DEFAULT: TempoPrior = TempoPrior(minBpm = 70.0, maxBpm = 180.0)

        /** The 70 to 210 BPM span of the reference library, for calibration. */
        val REFERENCE_WIDE: TempoPrior = TempoPrior(minBpm = 70.0, maxBpm = 210.0)

        private fun log2(x: Double): Double = ln(x) / LN2

        private val LN2 = ln(2.0)
    }
}
