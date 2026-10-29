package io.sonora.feature.dj

import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.playback.PlaybackTempo
import java.util.Locale

/**
 * What the DJ readout shows for the playing track: the tempo it plays at ([bpm], `null` for a track without a beat
 * grid), how far the fader is from normal ([tempoPercent], + faster) and the [key] as it sounds (`null` when none was
 * detected).
 */
data class DjReadout(val bpm: Double?, val tempoPercent: Double, val key: MusicalKey?) {

    /** [bpm] with one decimal in [locale] (`129.6`), or `null` without one. */
    fun bpmText(locale: Locale = Locale.getDefault()): String? = bpm?.let { String.format(locale, "%.1f", it) }

    /** [tempoPercent] signed with two decimals in [locale] (`+1.25`, `-3.00`); normal speed is `0.00`. */
    fun tempoPercentText(locale: Locale = Locale.getDefault()): String =
        if (tempoPercent == 0.0) String.format(locale, "%.2f", 0.0) else String.format(locale, "%+.2f", tempoPercent)

    companion object {
        /**
         * The readout for [analysis] played at [tempo] with the playhead at [positionMs]. The tempo is the grid's (the user's edit when there is one) at that
         * position (a variable-tempo track has several) times the speed, to 0.1 BPM ([PlaybackTempo.displayedBpm]); the
         * key is the detected one moved by the pitch the player makes: [KeyShift] of [PlaybackTempo.pitchFactor], so it
         * only moves with Master Tempo off or with semitones set.
         */
        fun of(analysis: DjAnalysis, tempo: PlaybackTempo, positionMs: Long): DjReadout =
            DjTrackReadout.of(analysis, positionMs).at(tempo)
    }
}

/**
 * What the readout knows of the track alone, with no tempo in it: the [gridBpm] at the playhead (the grid's, the user's
 * edit when there is one; `null` without a beat grid) and the [key] as detected. The DJ screen's state carries this, so a
 * drag of the tempo fader, which changes the tempo with every step, changes nothing outside the tempo controls; they
 * apply their own tempo with [at].
 */
data class DjTrackReadout(val gridBpm: Double?, val key: MusicalKey?) {

    /** The readout of this track played at [tempo]: the tempo to 0.1 BPM ([PlaybackTempo.displayedBpm]) and the key as the pitch moves it. */
    fun at(tempo: PlaybackTempo): DjReadout = DjReadout(
        bpm = gridBpm?.let(tempo::displayedBpm),
        tempoPercent = tempo.tempoPercent,
        key = key?.let { KeyShift.shifted(it, tempo.pitchFactor.toDouble()) },
    )

    companion object {
        /** The track readout of [analysis] with the playhead at [positionMs] (a variable-tempo track has several tempos). */
        fun of(analysis: DjAnalysis, positionMs: Long): DjTrackReadout = DjTrackReadout(
            gridBpm = analysis.effectiveGrid?.bpmAt(positionMs.toDouble()),
            key = analysis.key?.key,
        )
    }
}
