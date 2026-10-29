package io.sonora.core.djmodel

import io.sonora.core.domain.analysis.BeatGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * One stretch of a [DjBeatGrid] with a steady tempo: from [startMs] (which is a beat) every `60000 / bpm` ms, with that
 * first beat at position [beatInBarAtStart] (1..4, 1 = downbeat) of its bar.
 *
 * [barInPhraseAtStart] (1..4, default 1) says where the phrases of [DjBeatGrid.BARS_PER_PHRASE] bars fall: it is the
 * position in its phrase of the first downbeat of the grid, which is the first downbeat at or after the start of the
 * track (the first segment's beats run back to it). Only the first segment's value is read: the count goes on over the
 * later segments, as rekordbox and CDJs count bars from the start of the grid. The default is the analysed grid's
 * rule, a phrase from the first downbeat; a user's grid edit sets it from where they put the phrase start. It is
 * not part of the SDJ1 payload (the analysed grid is always the default).
 */
class TempoSegment(val startMs: Double, val bpm: Double, val beatInBarAtStart: Int, val barInPhraseAtStart: Int = 1) {
    init {
        require(startMs.isFinite() && startMs >= 0) { "startMs must be zero or more: $startMs" }
        require(bpm.isFinite() && bpm > 0) { "bpm must be positive: $bpm" }
        require(beatInBarAtStart in 1..BeatGrid.BEATS_PER_BAR) {
            "beatInBarAtStart must be 1..${BeatGrid.BEATS_PER_BAR}: $beatInBarAtStart"
        }
        require(barInPhraseAtStart in 1..DjBeatGrid.BARS_PER_PHRASE) {
            "barInPhraseAtStart must be 1..${DjBeatGrid.BARS_PER_PHRASE}: $barInPhraseAtStart"
        }
    }

    /** Time between two beats of this segment. */
    val beatLengthMs: Double get() = MS_PER_MINUTE / bpm

    override fun equals(other: Any?): Boolean = other is TempoSegment &&
        startMs == other.startMs && bpm == other.bpm && beatInBarAtStart == other.beatInBarAtStart &&
        barInPhraseAtStart == other.barInPhraseAtStart

    override fun hashCode(): Int =
        ((startMs.hashCode() * 31 + bpm.hashCode()) * 31 + beatInBarAtStart) * 31 + barInPhraseAtStart

    override fun toString(): String =
        "TempoSegment(startMs=$startMs, bpm=$bpm, beatInBarAtStart=$beatInBarAtStart, barInPhraseAtStart=$barInPhraseAtStart)"
}

/** One beat of a [DjBeatGrid]: its time from the start of the track, its position in the bar (1..4) and the tempo there. */
data class GridBeat(val timeMs: Double, val beatInBar: Int, val bpm: Double)

/**
 * The beat grid of a track as a list of tempo [segments]: one segment for a constant tempo, several for a track whose
 * tempo changes (a per-beat grid is a grid of many short segments).
 *
 * Invariants, checked on construction: there is at least one segment; the starts strictly increase; and every segment
 * after the first starts on a beat of the previous one (within [BOUNDARY_TOLERANCE_MS]), so the beat times stay one
 * continuous series. The bar position is not tied across a boundary: each segment says where its first beat sits in
 * its bar, which is how a grid with a bar-length change is expressed.
 *
 * The first segment's beats also run backwards to the start of the track, as [BeatGrid.spanning] does, so a grid
 * anchored at 1300 ms and 120 BPM reports beats at 300, 800, 1300 ms and on.
 */
class DjBeatGrid(val segments: List<TempoSegment>) {

    init {
        require(segments.isNotEmpty()) { "A beat grid needs at least one segment" }
        for (i in 1 until segments.size) {
            val previous = segments[i - 1]
            val next = segments[i]
            require(next.startMs > previous.startMs) {
                "Segment starts must increase: ${previous.startMs} then ${next.startMs}"
            }
            val beatsBetween = ((next.startMs - previous.startMs) / previous.beatLengthMs).roundToLong()
            val offBy = abs(next.startMs - previous.startMs - beatsBetween * previous.beatLengthMs)
            require(beatsBetween >= 1 && offBy <= BOUNDARY_TOLERANCE_MS) {
                "Segment ${next.startMs} ms does not start on a beat of the previous one (off by $offBy ms)"
            }
        }
    }

    /**
     * For each of [beats] (the beats of this grid from its start, as [beats] lists them, or a prefix of them), whether
     * it is the first beat of a phrase of [BARS_PER_PHRASE] bars: a downbeat whose bar is the first of its phrase,
     * counting bars from the first downbeat (the first segment's [TempoSegment.barInPhraseAtStart]) and on across the
     * tempo segments.
     */
    fun phraseStarts(beats: List<GridBeat>): BooleanArray {
        val firstBar = segments.first().barInPhraseAtStart - 1
        var downbeats = 0
        return BooleanArray(beats.size) { i ->
            beats[i].beatInBar == 1 && (firstBar + downbeats++) % BARS_PER_PHRASE == 0
        }
    }

    /** `true` when the whole track has one tempo. */
    val isConstant: Boolean get() = segments.size == 1

    /** The tempo at [timeMs]: the segment that holds it; the first segment's tempo before the grid starts. */
    fun bpmAt(timeMs: Double): Double = segments[segmentIndexAt(timeMs)].bpm

    /** Every beat in `[0, durationMs)`, in order. */
    fun beats(durationMs: Double): List<GridBeat> {
        val out = ArrayList<GridBeat>()
        for (i in segments.indices) {
            val segment = segments[i]
            val length = segment.beatLengthMs
            val firstIndex = if (i == 0) ceil(-segment.startMs / length - EPSILON).toLong().coerceAtMost(0) else 0L
            val next = segments.getOrNull(i + 1)
            // Whole beats up to the next segment's first beat, which that segment reports itself.
            val endIndex = next?.let { ((it.startMs - segment.startMs) / length).roundToLong() } ?: Long.MAX_VALUE
            var k = firstIndex
            while (k < endIndex) {
                val time = segment.startMs + k * length
                if (time >= durationMs) break
                out += GridBeat(time, Math.floorMod(segment.beatInBarAtStart - 1 + k, BeatGrid.BEATS_PER_BAR.toLong()).toInt() + 1, segment.bpm)
                k++
            }
        }
        return out
    }

    /**
     * The same grid as the plain [BeatGrid] (one tempo, [BeatGrid.spanning] over `[0, durationMs)`), or `null` when the
     * tempo changes.
     */
    fun toConstantBeatGrid(durationMs: Double): BeatGrid? {
        if (!isConstant) return null
        val segment = segments.single()
        return BeatGrid.spanning(segment.bpm, segment.startMs, segment.beatInBarAtStart, durationMs)
    }

    private fun segmentIndexAt(timeMs: Double): Int {
        var index = 0
        for (i in 1 until segments.size) {
            if (timeMs >= segments[i].startMs) index = i else break
        }
        return index
    }

    override fun equals(other: Any?): Boolean = other is DjBeatGrid && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "DjBeatGrid(${segments.size} segments)"

    companion object {
        /** How far a segment's start may sit from a beat of the previous segment. */
        const val BOUNDARY_TOLERANCE_MS: Double = 0.001

        /** Bars in a phrase. */
        const val BARS_PER_PHRASE: Int = 4
        private const val EPSILON = 1e-9
    }
}

private const val MS_PER_MINUTE = 60_000.0
