package io.sonora.core.domain.equalizer

import kotlin.math.roundToInt

/** The equalizer's band layouts. Names are persisted: never rename one. */
enum class EqualizerMode(val bandCount: Int) {
    FOUR_BANDS(4),
    ELEVEN_BANDS(11),
    TWENTY_BANDS(20),
}

/**
 * The user's equalizer: on or off, the chosen [mode], the gains of every mode (each mode keeps its own curve, so
 * switching modes switches curves) and how boosts are kept below full scale. Gains are in dB, within ±[MAX_GAIN_DB],
 * in tenths of a decibel.
 *
 * With [automaticPreamp] on, the whole signal is lowered by the curve's highest boost; off, it keeps its level and a
 * limiter after the curve only reduces the peaks that would go over full scale.
 *
 * [gains] may come from an older store or be partial: every mode's list is normalised to its band count (missing
 * bands are 0 dB), so two settings with the same curves are equal however they were built.
 */
class EqualizerSettings(
    val enabled: Boolean = false,
    val mode: EqualizerMode = EqualizerMode.FOUR_BANDS,
    gains: Map<EqualizerMode, List<Float>> = emptyMap(),
    val automaticPreamp: Boolean = true,
) {
    private val gains: Map<EqualizerMode, List<Float>> = EqualizerMode.entries.associateWith { mode ->
        val stored = gains[mode].orEmpty()
        List(mode.bandCount) { band -> stored.getOrNull(band)?.let(::quantize) ?: 0f }
    }

    /** [mode]'s gains, one per band. */
    fun gainsFor(mode: EqualizerMode): List<Float> = gains.getValue(mode)

    /** The gains of the chosen mode. */
    val activeGains: List<Float> get() = gainsFor(mode)

    /** Whether the chosen mode's curve is flat (every band at 0 dB). */
    val isFlat: Boolean get() = activeGains.all { it == 0f }

    fun withEnabled(enabled: Boolean): EqualizerSettings = EqualizerSettings(enabled, mode, gains, automaticPreamp)

    fun withMode(mode: EqualizerMode): EqualizerSettings = EqualizerSettings(enabled, mode, gains, automaticPreamp)

    fun withAutomaticPreamp(automaticPreamp: Boolean): EqualizerSettings = EqualizerSettings(enabled, mode, gains, automaticPreamp)

    /** Sets [band] of [mode] to [gainDb], clamped and rounded; a band [mode] does not have changes nothing. */
    fun withGain(mode: EqualizerMode, band: Int, gainDb: Float): EqualizerSettings {
        if (band !in 0 until mode.bandCount) return this
        val updated = gainsFor(mode).toMutableList().apply { this[band] = quantize(gainDb) }
        return EqualizerSettings(enabled, this.mode, gains + (mode to updated), automaticPreamp)
    }

    /** Flattens [mode]'s curve; the other modes keep theirs. */
    fun reset(mode: EqualizerMode): EqualizerSettings = EqualizerSettings(enabled, this.mode, gains - mode, automaticPreamp)

    override fun equals(other: Any?): Boolean =
        other is EqualizerSettings && enabled == other.enabled && mode == other.mode && gains == other.gains &&
            automaticPreamp == other.automaticPreamp

    override fun hashCode(): Int =
        ((enabled.hashCode() * 31 + mode.hashCode()) * 31 + gains.hashCode()) * 31 + automaticPreamp.hashCode()

    override fun toString(): String = "EqualizerSettings(enabled=$enabled, mode=$mode, gains=$gains, automaticPreamp=$automaticPreamp)"

    companion object {
        const val MAX_GAIN_DB = 12f

        /** Clamps to ±[MAX_GAIN_DB] and rounds to a tenth of a decibel (never -0). */
        fun quantize(gainDb: Float): Float {
            if (gainDb.isNaN()) return 0f
            val tenths = (gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) * 10).roundToInt()
            return if (tenths == 0) 0f else tenths / 10f
        }
    }
}
