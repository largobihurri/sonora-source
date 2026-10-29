package io.sonora.core.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange

/**
 * The keys of the tempo store and how they read back into a [PlaybackTempo].
 *
 * Version 1 stored only [SPEED_PERCENT] (whole percent) and [SEMITONES]. Version 2 adds the exact speed in basis
 * points, Master Tempo and the fader range, and every write also keeps [SPEED_PERCENT] in step, so an older build (a
 * downgrade) still plays the right speed. The legacy key is therefore also a witness: when a backup written by an
 * older build is restored over the new keys, or an older build writes after a newer one, the legacy percent no longer
 * matches the basis points, and it wins.
 *
 * Key names are persisted and travel in settings backups: never change them, and none may contain a word
 * [io.sonora.core.domain.backup.BackupSettingsPolicy] leaves out.
 */
internal object TempoKeys {
    const val SPEED_PERCENT_NAME = "tempo.speed_percent"
    const val SPEED_BASIS_POINTS_NAME = "tempo.speed_basis_points"
    const val MASTER_TEMPO_NAME = "tempo.master_tempo"
    const val RANGE_NAME = "tempo.range"
    const val SEMITONES_NAME = "tempo.semitones"

    val SPEED_PERCENT = intPreferencesKey(SPEED_PERCENT_NAME)
    val SPEED_BASIS_POINTS = intPreferencesKey(SPEED_BASIS_POINTS_NAME)
    val MASTER_TEMPO = booleanPreferencesKey(MASTER_TEMPO_NAME)
    val RANGE = stringPreferencesKey(RANGE_NAME)
    val SEMITONES = intPreferencesKey(SEMITONES_NAME)

    /** The legacy whole-percent key for [speedBasisPoints]: percent rounded half up, inside what an older build reads. */
    fun legacyPercentOf(speedBasisPoints: Int): Int =
        ((speedBasisPoints.toLong() + 50) / 100)
            .coerceIn(PlaybackTempo.MIN_SPEED_PERCENT.toLong(), PlaybackTempo.MAX_SPEED_PERCENT.toLong()).toInt()

    /**
     * The tempo the stored values describe; any of them may be missing.
     *
     * The speed is [speedBasisPoints] when it is there and [legacyPercent] agrees with it (or is missing); otherwise
     * the legacy percent times 100, exactly (not snapped to a grid), because something older wrote it last. Either
     * way it is clamped to the playable span. A [rangeCode] that is unknown, missing or too narrow for the speed gives
     * [PlaybackTempo.fittingRange]; Master Tempo defaults on. (An old build that writes the very percent the basis
     * points already round to cannot be told apart; the basis points stay.)
     */
    fun read(
        legacyPercent: Int?,
        speedBasisPoints: Int?,
        masterTempo: Boolean?,
        rangeCode: String?,
        semitones: Int? = null,
    ): PlaybackTempo {
        val basisPoints = when {
            speedBasisPoints != null && (legacyPercent == null || legacyPercent == legacyPercentOf(speedBasisPoints)) -> speedBasisPoints.toLong()
            legacyPercent != null -> legacyPercent.toLong() * 100
            else -> PlaybackTempo.NORMAL_SPEED_BASIS_POINTS.toLong()
        }.coerceIn(PlaybackTempo.MIN_SPEED_BASIS_POINTS.toLong(), PlaybackTempo.MAX_SPEED_BASIS_POINTS.toLong()).toInt()
        val range = PlaybackTempo.fittingRange(basisPoints, preferred = TempoRange.fromCode(rangeCode) ?: TempoRange.TEN)
        return PlaybackTempo(basisPoints, 0, masterTempo ?: true, range).withSemitones(semitones ?: 0)
    }
}
