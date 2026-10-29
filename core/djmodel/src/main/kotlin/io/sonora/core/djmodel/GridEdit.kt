package io.sonora.core.djmodel

/**
 * The user's correction of one track's beat grid, kept apart from the analysed grid (an edit never rewrites the
 * analysis, and deleting it brings the analysed grid back): one constant tempo of [bpmHundredths] hundredths of a BPM
 * (BPM to two decimals, like rekordbox, stored as the exact integer so 128.00 is never 127.99999) and one downbeat at
 * [anchorMs], the time of a beat 1 of a bar. Every other beat and bar start follows from those two numbers, in both
 * directions, over the whole track.
 *
 * The operations of [GridEditor] keep [anchorMs] canonical: the first downbeat at or after the start of the track
 * (`0 <= anchorMs < 4 beats`), which is also the point a BPM change pivots on, as in rekordbox. Any finite anchor is
 * accepted here, so a value written by another version still reads; [GridEdits.apply] handles it all the same.
 */
data class GridEdit(val bpmHundredths: Int, val anchorMs: Double) {
    init {
        require(bpmHundredths in MIN_BPM_HUNDREDTHS..MAX_BPM_HUNDREDTHS) {
            "bpmHundredths must be $MIN_BPM_HUNDREDTHS..$MAX_BPM_HUNDREDTHS: $bpmHundredths"
        }
        require(anchorMs.isFinite()) { "anchorMs must be finite: $anchorMs" }
    }

    /** The tempo in BPM (two decimals at most). */
    val bpm: Double get() = bpmHundredths / HUNDREDTHS

    /** The time between two beats. */
    val beatLengthMs: Double get() = MS_PER_MINUTE_HUNDREDTHS / bpmHundredths

    companion object {
        /** The slowest tempo an edit may set: 20.00 BPM. */
        const val MIN_BPM_HUNDREDTHS: Int = 2_000

        /** The fastest tempo an edit may set: 400.00 BPM. */
        const val MAX_BPM_HUNDREDTHS: Int = 40_000

        /** The tempo a grid made from nothing starts at: 120.00 BPM. */
        const val DEFAULT_BPM_HUNDREDTHS: Int = 12_000

        private const val HUNDREDTHS = 100.0
        private const val MS_PER_MINUTE_HUNDREDTHS = 6_000_000.0
    }
}
