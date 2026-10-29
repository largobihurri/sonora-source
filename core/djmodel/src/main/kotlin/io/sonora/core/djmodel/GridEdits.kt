package io.sonora.core.djmodel

import io.sonora.core.domain.analysis.BeatGrid
import kotlin.math.floor
import kotlin.math.roundToInt

/** What a [GridEdit] does to an analysed grid; see [GridEditor] for the operations that make edits. */
object GridEdits {

    /** A phrase is four bars: sixteen beats. */
    private const val BEATS_PER_PHRASE = BeatGrid.BEATS_PER_BAR * DjBeatGrid.BARS_PER_PHRASE

    /** Anchors are kept to a microsecond, so repeated nudges never pile up floating point dust. */
    private const val ANCHOR_RESOLUTION_PER_MS = 1_000.0

    /**
     * The grid the user sees and hears against: [analysisGrid] itself while there is no [edit], else the constant grid
     * the edit describes (also for a track whose analysis found no steady beat, `analysisGrid == null`). The grid's
     * first beat is the first one at or after the start of the track, so it covers the track in both directions from
     * the anchor. The anchor is the first bar of a phrase: the grid's [TempoSegment.barInPhraseAtStart] says which bar
     * of its phrase the first downbeat of the track is.
     */
    fun apply(analysisGrid: DjBeatGrid?, edit: GridEdit?): DjBeatGrid? {
        if (edit == null) return analysisGrid
        val length = edit.beatLengthMs
        // The beat index (relative to the anchor, which is index 0) of the first beat at or after 0 ms.
        var first = Math.ceil(-edit.anchorMs / length).toLong()
        var start = edit.anchorMs + first * length
        if (start < 0.0) {
            first++
            start += length
        }
        val beatsPerBar = BeatGrid.BEATS_PER_BAR.toLong()
        val beatInBar = Math.floorMod(first, beatsPerBar).toInt() + 1
        // The first downbeat of the track (the first beat index at or after `first` that is a multiple of a bar), and
        // which bar of its phrase it is, counting bars from the anchor, which is bar 1 of a phrase.
        val firstDownbeat = Math.floorDiv(first + beatsPerBar - 1, beatsPerBar)
        val barInPhrase = Math.floorMod(firstDownbeat, DjBeatGrid.BARS_PER_PHRASE.toLong()).toInt() + 1
        return DjBeatGrid(listOf(TempoSegment(start, edit.bpm, beatInBar, barInPhrase)))
    }

    /**
     * [anchorMs] moved by whole phrases of [beatLengthMs] into the first phrase of the track: `0 <= result < 16 beats`.
     * Moving by whole phrases changes neither the bar lines nor the phrase lines, so the anchor keeps both.
     */
    fun canonicalAnchorMs(anchorMs: Double, beatLengthMs: Double): Double {
        val phrase = BEATS_PER_PHRASE * beatLengthMs
        val reduced = anchorMs - floor(anchorMs / phrase) * phrase
        val rounded = Math.rint(reduced * ANCHOR_RESOLUTION_PER_MS) / ANCHOR_RESOLUTION_PER_MS
        return if (rounded >= phrase || rounded < 0.0) 0.0 else rounded
    }

    /** Whether halving [bpmHundredths] stays in the range an edit may have. */
    fun canHalve(bpmHundredths: Int): Boolean = halved(bpmHundredths) >= GridEdit.MIN_BPM_HUNDREDTHS

    /** Whether doubling [bpmHundredths] stays in the range an edit may have. */
    fun canDouble(bpmHundredths: Int): Boolean = bpmHundredths.toLong() * 2 <= GridEdit.MAX_BPM_HUNDREDTHS

    internal fun halved(bpmHundredths: Int): Int = (bpmHundredths + 1) / 2
}

/**
 * The operations of the beat grid editor on one track whose analysed grid is [analysisGrid] (`null` when the analysis
 * found no steady beat) and which lasts [durationMs] (0 when unknown).
 *
 * Every operation takes the current [GridEdit] (`null` while the track is unedited, which then starts from the
 * analysed grid) and the [positionMs] of the playhead, which only matters when it has to pick a tempo or a downbeat
 * out of a grid whose tempo changes. It returns the new edit, whose anchor is canonical (see [GridEdit]); the caller
 * stores it, and a reset is the caller deleting the edit. An edit is one constant tempo, so editing a track whose
 * analysed tempo changes replaces its tempo changes with the tempo at the playhead.
 *
 * A tempo change pivots on the anchor, the first bar start of the track, as rekordbox's does: beats far from it move
 * the most.
 */
class GridEditor(private val analysisGrid: DjBeatGrid?, private val durationMs: Double) {

    /** The grid [edit] gives, or the analysed one. */
    fun effective(edit: GridEdit?): DjBeatGrid? = GridEdits.apply(analysisGrid, edit)

    /**
     * Makes the beat of the current grid nearest to [positionMs] the first beat of a bar, and that bar the first of a
     * phrase. A track without any grid has no beat to snap to, so the bar starts exactly at [positionMs].
     */
    fun setDownbeatAt(edit: GridEdit?, positionMs: Double): GridEdit {
        val grid = effective(edit)
        val tempo = edit?.bpmHundredths ?: tempoAt(grid, positionMs)
        val length = beatLengthOf(tempo)
        val beat = grid?.let { nearestBeatMs(it, positionMs) } ?: positionMs
        return GridEdit(tempo, GridEdits.canonicalAnchorMs(beat, length))
    }

    /**
     * Moves the bar starts, and the phrase with them, by [beats] whole beats (negative: earlier). The phrase start wraps
     * within the phrase; see [shiftBars] to move only the phrase.
     */
    fun shiftBeats(edit: GridEdit?, positionMs: Double, beats: Int): GridEdit {
        val base = edit ?: seed(positionMs)
        return moved(base, beats * base.beatLengthMs)
    }

    /**
     * Moves the phrase start by [bars] whole bars (negative: earlier) without moving a single bar line: only which
     * downbeat starts a phrase changes. The phrase start wraps within the phrase.
     */
    fun shiftBars(edit: GridEdit?, positionMs: Double, bars: Int): GridEdit {
        val base = edit ?: seed(positionMs)
        return moved(base, bars * BeatGrid.BEATS_PER_BAR * base.beatLengthMs)
    }

    /** Moves every beat by [deltaMs] (negative: earlier). */
    fun nudge(edit: GridEdit?, positionMs: Double, deltaMs: Double): GridEdit = moved(edit ?: seed(positionMs), deltaMs)

    /** Sets the tempo, keeping the first downbeat where it is; [bpmHundredths] is limited to the range of an edit. */
    fun setBpm(edit: GridEdit?, positionMs: Double, bpmHundredths: Int): GridEdit {
        val base = edit ?: seed(positionMs)
        val tempo = bpmHundredths.coerceIn(GridEdit.MIN_BPM_HUNDREDTHS, GridEdit.MAX_BPM_HUNDREDTHS)
        return GridEdit(tempo, GridEdits.canonicalAnchorMs(base.anchorMs, beatLengthOf(tempo)))
    }

    /** Changes the tempo by [deltaHundredths] hundredths of a BPM, keeping the first downbeat where it is. */
    fun adjustBpm(edit: GridEdit?, positionMs: Double, deltaHundredths: Int): GridEdit {
        val base = edit ?: seed(positionMs)
        return setBpm(base, positionMs, (base.bpmHundredths.toLong() + deltaHundredths).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    /** Halves the tempo (a track analysed at double time); see [GridEdits.canHalve]. */
    fun halve(edit: GridEdit?, positionMs: Double): GridEdit {
        val base = edit ?: seed(positionMs)
        return setBpm(base, positionMs, GridEdits.halved(base.bpmHundredths))
    }

    /** Doubles the tempo (a track analysed at half time); see [GridEdits.canDouble]. */
    fun double(edit: GridEdit?, positionMs: Double): GridEdit {
        val base = edit ?: seed(positionMs)
        return setBpm(base, positionMs, (base.bpmHundredths.toLong() * 2).coerceAtMost(GridEdit.MAX_BPM_HUNDREDTHS.toLong()).toInt())
    }

    private fun moved(base: GridEdit, deltaMs: Double): GridEdit =
        GridEdit(base.bpmHundredths, GridEdits.canonicalAnchorMs(base.anchorMs + deltaMs, base.beatLengthMs))

    /**
     * The edit that reproduces the analysed grid (the starting point of the first change): its tempo to the hundredth,
     * its bar lines and its phrases, so the anchor is the start of the phrase of the first downbeat of the track (the
     * first downbeat itself for an analysed grid, which starts a phrase there). For a grid whose tempo changes, the tempo
     * at [positionMs] and the phrase start nearest to it; for no grid at all, [GridEdit.DEFAULT_BPM_HUNDREDTHS] from the
     * start of the track.
     */
    private fun seed(positionMs: Double): GridEdit {
        val grid = analysisGrid ?: return GridEdit(GridEdit.DEFAULT_BPM_HUNDREDTHS, 0.0)
        val tempo = tempoAt(grid, positionMs)
        val length = beatLengthOf(tempo)
        val anchor = if (grid.isConstant) {
            val segment = grid.segments.single()
            // Beats from the segment's first one to the next bar start (0 when it is a beat 1 itself).
            val toBarStart = Math.floorMod(BeatGrid.BEATS_PER_BAR - (segment.beatInBarAtStart - 1), BeatGrid.BEATS_PER_BAR)
            // The grid runs back to the start of the track: the first downbeat there is the first one at or after 0 ms.
            var firstDownbeat = segment.startMs + toBarStart * segment.beatLengthMs
            val bar = BeatGrid.BEATS_PER_BAR * segment.beatLengthMs
            if (firstDownbeat >= bar) firstDownbeat -= floor(firstDownbeat / bar) * bar
            // That bar is bar `barInPhraseAtStart` of its phrase: the phrase started that many bars before.
            firstDownbeat - (segment.barInPhraseAtStart - 1) * bar
        } else {
            nearestPhraseStartMs(grid, positionMs)
        }
        return GridEdit(tempo, GridEdits.canonicalAnchorMs(anchor, length))
    }

    private fun tempoAt(grid: DjBeatGrid?, positionMs: Double): Int =
        if (grid == null) {
            GridEdit.DEFAULT_BPM_HUNDREDTHS
        } else {
            (grid.bpmAt(positionMs) * HUNDREDTHS).roundToInt().coerceIn(GridEdit.MIN_BPM_HUNDREDTHS, GridEdit.MAX_BPM_HUNDREDTHS)
        }

    private fun beatLengthOf(bpmHundredths: Int): Double = MS_PER_MINUTE_HUNDREDTHS / bpmHundredths

    /** The time of the beat of [grid] nearest to [positionMs] inside the track (a tie goes to the later beat). */
    private fun nearestBeatMs(grid: DjBeatGrid, positionMs: Double): Double? {
        if (grid.isConstant) {
            val segment = grid.segments.single()
            val length = segment.beatLengthMs
            var beat = segment.startMs + floor((positionMs - segment.startMs) / length + HALF) * length
            while (beat < 0.0) beat += length
            // The very end of a track is not a place for a bar start.
            while (durationMs > 0 && beat >= durationMs && beat - length >= 0.0) beat -= length
            return beat
        }
        return nearest(grid.beats(listingEnd(positionMs)).map { it.timeMs }, positionMs)
    }

    private fun nearestPhraseStartMs(grid: DjBeatGrid, positionMs: Double): Double {
        val beats = grid.beats(listingEnd(positionMs))
        val flags = grid.phraseStarts(beats)
        val starts = beats.filterIndexed { i, _ -> flags[i] }.map { it.timeMs }
        return nearest(starts.ifEmpty { beats.map { it.timeMs } }, positionMs) ?: 0.0
    }

    /** Where the beats of a grid that changes tempo are listed to: the track's end, or a while past the playhead if unknown. */
    private fun listingEnd(positionMs: Double): Double = if (durationMs > 0) durationMs else positionMs + UNKNOWN_DURATION_LOOKAHEAD_MS

    private fun nearest(times: List<Double>, positionMs: Double): Double? {
        if (times.isEmpty()) return null
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < positionMs) low = mid + 1 else high = mid
        }
        // `low` is the first beat at or after the playhead; its predecessor is the only other candidate.
        val after = times[low]
        val before = times.getOrNull(low - 1) ?: return after
        return if (positionMs - before < after - positionMs) before else after
    }

    private companion object {
        const val HUNDREDTHS = 100.0
        const val HALF = 0.5
        const val MS_PER_MINUTE_HUNDREDTHS = 6_000_000.0

        /** With no duration known, a grid that changes tempo lists its beats this far past the playhead: ten minutes. */
        const val UNKNOWN_DURATION_LOOKAHEAD_MS = 600_000.0
    }
}
