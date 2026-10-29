package io.sonora.feature.cdjexport.export

import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.djmodel.loop.LoopSizes
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.core.rekordbox.anlz.AnlzCue
import io.sonora.core.rekordbox.anlz.AnlzTrack
import io.sonora.core.rekordbox.anlz.BandPlanes
import io.sonora.core.rekordbox.anlz.LoopSize
import io.sonora.core.rekordbox.pdb.DeviceFileType
import io.sonora.core.rekordbox.timing.RekordboxTimeBase
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What Sonora's DJ mode knows about one track: its [analysis] (the grid edit included) and its [cues]. */
class DjTrackExport(val analysis: DjAnalysis, val cues: List<DjCue>)

/**
 * The audio file as it will sit on the stick, for the time base: its [format], the frames of [encoderDelayFrames] the
 * DJ analysis' decoder dropped from its start (0 for a file that states none, and for every file the export converts:
 * Sonora's converter trims exactly what the analysis trims, so the converted AIFF starts where the analysis does) and
 * its [sampleRateHz]. See [RekordboxTimeBase].
 */
data class StickAudio(val format: DeviceFileType, val encoderDelayFrames: Int, val sampleRateHz: Int)

/**
 * Turns the DJ analysis of a track into the content of its analysis files: the `PQTZ` beats of the grid in force (the
 * user's edit when there is one, one entry per beat with its own tempo, bar numbers from the grid), the cues and loops
 * of the cue lists and the 3-band waveform of the colour waveforms. Every time goes from Sonora's analysis time base
 * into rekordbox's ([RekordboxTimeBase]), rounded to whole milliseconds. Without DJ data the track is the one the
 * ordinary analysis describes, as before.
 */
object DjAnlzMapper {

    /** The loop sizes rekordbox stores for a quantised loop must match the loop within this share of a beat. */
    private const val LOOP_SIZE_TOLERANCE = 0.01

    /** The track for the analysis files of the audio at [filePath]; [base] is the ordinary analysis, [dj] the DJ data if any. */
    fun anlzTrack(filePath: String, base: TrackAnalysis, dj: DjTrackExport?, target: StickAudio): AnlzTrack {
        val plain = AnlzTrack(filePath, base.beatGrid, base.waveform)
        if (dj == null) return plain
        val analysis = dj.analysis
        val offset = RekordboxTimeBase.offsetMs(target.format, target.encoderDelayFrames, target.sampleRateHz)
        return plain.copy(
            beats = analysis.effectiveGrid?.let { beats(it, analysis.durationMs.toDouble(), offset) },
            cues = cues(dj.cues, analysis, offset),
            bands = bands(analysis.waveform),
        )
    }

    /** The tempo the library lists: that of the first beat of the grid in force, else the ordinary analysis'. */
    fun tempoBpm(base: TrackAnalysis, dj: DjTrackExport?): Double =
        dj?.analysis?.effectiveGrid?.segments?.first()?.bpm ?: base.bpm

    /** The key the library lists: the DJ analysis' when it found one, else the ordinary analysis'. */
    fun key(base: TrackAnalysis, dj: DjTrackExport?): MusicalKey? = dj?.analysis?.key?.key ?: base.key

    private fun beats(grid: DjBeatGrid, durationMs: Double, offsetMs: Double): List<AnlzBeat> =
        grid.beats(durationMs).map {
            AnlzBeat(
                beatInBar = it.beatInBar,
                tempo = (it.bpm * 100).roundToInt().coerceIn(1, 0xFFFF),
                timeMs = (it.timeMs + offsetMs).roundToLong().coerceAtLeast(0),
            )
        }

    private fun cues(cues: List<DjCue>, analysis: DjAnalysis, offsetMs: Double): List<AnlzCue> {
        val quantizer = analysis.effectiveGrid?.let { LoopQuantizer(it, analysis.durationMs.toDouble()) }
        return cues.mapNotNull { cue ->
            val start = (cue.positionMs + offsetMs).roundToLong()
            if (start < 0) return@mapNotNull null
            val end = cue.loopEndMs?.let { (it + offsetMs).roundToLong() }?.takeIf { it > start }
            AnlzCue(
                hotCue = if (cue.kind == CueKind.HOT) cue.slot!! + 1 else 0,
                timeMs = start,
                loopEndMs = end,
                rgb = cue.color.rgb,
                comment = cue.name.take(AnlzCue.MAX_COMMENT_CHARS),
                loopSize = if (end != null) loopSize(quantizer, cue) else null,
            )
        }
    }

    /** The cue's loop as a number of beats rekordbox has a name for, or `null` (off the grid, or an odd length). */
    private fun loopSize(quantizer: LoopQuantizer?, cue: DjCue): LoopSize? {
        val endMs = cue.loopEndMs ?: return null
        if (quantizer == null) return null
        val beats = quantizer.beatPositionAt(endMs) - quantizer.beatPositionAt(cue.positionMs)
        val match = LoopSizes.BEATS.firstOrNull { abs(beats - it) <= it * LOOP_SIZE_TOLERANCE } ?: return null
        return if (match >= 1.0) LoopSize(match.roundToInt(), 1) else LoopSize(1, (1 / match).roundToInt())
    }

    private fun bands(waveform: BandWaveform): BandPlanes? =
        if (waveform.columnCount == 0) null else BandPlanes(waveform.low(), waveform.mid(), waveform.high(), waveform.peak())
}
