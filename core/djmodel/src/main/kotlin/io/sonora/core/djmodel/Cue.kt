package io.sonora.core.djmodel

/** Which of the two kinds of cue a [DjCue] is, as rekordbox's PCOB/PCO2 entries tell them apart. */
enum class CueKind { MEMORY, HOT }

/**
 * A colour of a cue: its [paletteIndex] in [CuePalette] (1..16, or 0 for a colour outside the palette) and its [rgb]
 * (`0xRRGGBB`, no alpha). The RGB is always there, so a cue written by a later palette still draws in its own colour.
 */
data class CueColor(val paletteIndex: Int, val rgb: Int) {
    init {
        require(paletteIndex in 0..CuePalette.SIZE) { "paletteIndex must be 0..${CuePalette.SIZE}: $paletteIndex" }
        require(rgb in 0..MAX_RGB) { "rgb must be 0x000000..0xFFFFFF: $rgb" }
    }

    companion object {
        /** A colour that is not in the palette. */
        fun custom(rgb: Int): CueColor = CueColor(0, rgb)

        private const val MAX_RGB = 0xFFFFFF
    }
}

/**
 * One cue point of a track, kept by the user: a hot cue (one of the eight pads A to H, [slot] 0..7) or a memory cue
 * (any number per track, no slot). Positions are in milliseconds of audio time, as rekordbox stores them in its
 * PCOB/PCO2 entries, as a [Double] so a quantized cue sits exactly on its beat. [loopEndMs] is `null` for a plain cue
 * and the end of the loop for a saved loop (the cue's position is the loop start), so saved loops are cues too.
 *
 * [id] is unique within a track: a hot cue's is derived from its slot ([hotId]), so setting a pad again replaces its
 * cue, and a memory cue's is chosen by whoever adds it.
 */
data class DjCue(
    val id: String,
    val kind: CueKind,
    val slot: Int?,
    val positionMs: Double,
    val loopEndMs: Double?,
    val color: CueColor,
    val name: String,
) {
    init {
        require(id.isNotBlank()) { "A cue needs an id" }
        when (kind) {
            CueKind.HOT -> require(slot != null && slot in 0 until HOT_SLOTS) { "A hot cue needs a pad 0..${HOT_SLOTS - 1}: $slot" }
            CueKind.MEMORY -> require(slot == null) { "A memory cue has no pad: $slot" }
        }
        require(positionMs.isFinite() && positionMs >= 0.0) { "positionMs must be finite and not negative: $positionMs" }
        require(loopEndMs == null || (loopEndMs.isFinite() && loopEndMs > positionMs)) { "A loop must end after it starts: $loopEndMs" }
        require(name.length <= MAX_NAME_LENGTH) { "A cue name has at most $MAX_NAME_LENGTH characters" }
    }

    /** Whether this cue is a saved loop. */
    val isLoop: Boolean get() = loopEndMs != null

    /** The pad letter A..H of a hot cue, `null` for a memory cue. */
    val label: Char? get() = slot?.let(::slotLabel)

    companion object {
        /** Hot cue pads: A to H. */
        const val HOT_SLOTS: Int = 8

        /** The longest name kept. */
        const val MAX_NAME_LENGTH: Int = 64

        /** The id of the hot cue on [slot]. */
        fun hotId(slot: Int): String = "hot-$slot"

        /** The letter of pad [slot]. */
        fun slotLabel(slot: Int): Char = 'A' + slot
    }
}
