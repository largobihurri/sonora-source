package io.sonora.core.rekordbox.anlz

/** Which cue list a `PCOB`/`PCO2` section holds. */
enum class CueListType(val code: Int) { MEMORY(0), HOT(1) }

/**
 * The size of a quantised loop in beats, [numerator]/[denominator] (a 4-beat loop is 4/1, half a beat 1/2). Both are
 * powers of two and at most one of them is above 1, as rekordbox stores them.
 */
data class LoopSize(val numerator: Int, val denominator: Int) {
    init {
        require(numerator in 1..MAX_U16 && numerator.isPowerOfTwo()) { "numerator must be a power of two: $numerator" }
        require(denominator in 1..MAX_U16 && denominator.isPowerOfTwo()) { "denominator must be a power of two: $denominator" }
        require(numerator == 1 || denominator == 1) { "a loop is whole beats or a fraction of one: $numerator/$denominator" }
    }

    private fun Int.isPowerOfTwo(): Boolean = this > 0 && this and (this - 1) == 0
}

/**
 * One cue point or loop of a track as the players' cue lists hold it.
 *
 * [hotCue] is 0 for a memory cue and 1..8 for hot cue A..H. Times are whole milliseconds in rekordbox's time base;
 * [loopEndMs] makes the cue a loop (its [timeMs] is the loop start). [rgb] (`0xRRGGBB`) is the cue's colour, `null`
 * for none; the writer stores it as the explicit RGB of a hot cue next to the nearest [CueColors.hotCueCode] (or the
 * given [colorCode], for a cue read from a rekordbox export) and as the nearest [CueColors.memoryColorId] of a memory
 * cue. [comment] is the cue's name; [loopSize] the beat length of a loop that sits on the grid (`null` otherwise).
 */
data class AnlzCue(
    val hotCue: Int,
    val timeMs: Long,
    val loopEndMs: Long? = null,
    val rgb: Int? = null,
    val comment: String = "",
    val colorCode: Int? = null,
    val loopSize: LoopSize? = null,
) {
    init {
        require(hotCue in 0..MAX_HOT_CUE) { "hotCue must be 0..$MAX_HOT_CUE: $hotCue" }
        require(timeMs in 0 until NO_TIME) { "timeMs must fit 32 bits and not be the no-time marker: $timeMs" }
        require(loopEndMs == null || (loopEndMs > timeMs && loopEndMs < NO_TIME)) { "a loop must end after it starts: $loopEndMs" }
        require(rgb == null || rgb in 0..0xFFFFFF) { "rgb must be 0x000000..0xFFFFFF: $rgb" }
        require(colorCode == null || colorCode in 0..MAX_COLOR_CODE) { "colorCode must be 0..$MAX_COLOR_CODE: $colorCode" }
        require(loopSize == null || loopEndMs != null) { "only a loop has a loop size" }
        require(comment.length <= MAX_COMMENT_CHARS) { "a comment has at most $MAX_COMMENT_CHARS characters" }
    }

    val isLoop: Boolean get() = loopEndMs != null

    companion object {
        const val MAX_HOT_CUE: Int = 8

        /** The old lists (`PCOB`) of the nexus players hold hot cues A to C only. */
        const val MAX_LEGACY_HOT_CUE: Int = 3

        const val MAX_COLOR_CODE: Int = 62
        const val MAX_COMMENT_CHARS: Int = 64

        /** `0xFFFFFFFF`: the loop time of a cue that is no loop. */
        internal const val NO_TIME: Long = 0xFFFF_FFFFL
    }
}

/**
 * `PCOB`: a list of memory cues or of hot cues, in the form every nexus-era player reads (it holds hot cues A to C
 * only; the rest go in [ExtendedCueListSection]). Layout (Deep Symmetry, "Rekordbox Export Structure Analysis",
 * https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html, section `PCOB`, and crate-digger's
 * `rekordbox_anlz.ksy`, checked on a rekordbox 6 export): header `type`, a word of zeros, the number of cues and the
 * constant `0xFFFFFFFF` (its meaning is not published; every list rekordbox wrote that was looked at has it, memory
 * cues or none); then 56-byte `PCPT` entries. Hot cues are listed by number; memory cues by time, each entry naming its
 * neighbours in the sort keys (`ffff` for none), which is how the documented sequence reads.
 */
class CueListSection(val type: CueListType, cues: List<AnlzCue> = emptyList()) : AnlzSection("PCOB") {
    val cues: List<AnlzCue> = ordered(type, cues)

    init {
        this.cues.forEach {
            when (type) {
                CueListType.HOT -> require(it.hotCue in 1..AnlzCue.MAX_LEGACY_HOT_CUE) {
                    "the old hot cue list holds A to C, not ${it.hotCue}"
                }
                CueListType.MEMORY -> require(it.hotCue == 0) { "a memory list holds no hot cue: ${it.hotCue}" }
            }
        }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(type.code)
        writeShort(0) // unknown
        writeShort(cues.size)
        writeInt(-1) // memory count: 0xFFFFFFFF in the lists rekordbox writes
    }

    override fun body(): ByteArray = bigEndian {
        cues.forEachIndexed { index, cue ->
            val first = if (type == CueListType.MEMORY && index > 0) index - 1 else 0xFFFF
            val last = if (type == CueListType.MEMORY && index < cues.size - 1) index + 1 else 0xFFFF
            writeBytes("PCPT")
            writeInt(ENTRY_HEADER)
            writeInt(ENTRY_BYTES)
            writeInt(cue.hotCue)
            writeInt(0) // status: 0 for a cue or loop, 4 for the loop that is active
            writeInt(0x0001_0000)
            writeShort(first)
            writeShort(last)
            writeByte(if (cue.isLoop) TYPE_LOOP else TYPE_POSITION)
            write(CUE_CONSTANT)
            writeInt(cue.timeMs.toInt())
            writeInt((cue.loopEndMs ?: AnlzCue.NO_TIME).toInt())
            write(ByteArray(UNUSED_BYTES))
        }
    }

    private companion object {
        const val ENTRY_HEADER = 0x1C
        const val ENTRY_BYTES = 0x38
        const val UNUSED_BYTES = 16
    }
}

/**
 * `PCO2` (in `.EXT`): the extended cue list of the nexus 2 players: all eight hot cues, colours, comments and the size
 * of quantised loops. Layout from the same sources as [CueListSection] (`PCO2` and `PCP2`) and checked on rekordbox
 * 6 exports, whose entries are 88 bytes long with no comment: the fixed 44 bytes, the comment (UTF-16BE with its
 * terminator, its byte length in front), the colour code with the explicit RGB of a hot cue, and 40 bytes of zeros.
 */
class ExtendedCueListSection(val type: CueListType, cues: List<AnlzCue> = emptyList()) : AnlzSection("PCO2") {
    val cues: List<AnlzCue> = ordered(type, cues)

    init {
        this.cues.forEach {
            when (type) {
                CueListType.HOT -> require(it.hotCue in 1..AnlzCue.MAX_HOT_CUE) { "a hot list holds pads 1 to 8, not ${it.hotCue}" }
                CueListType.MEMORY -> require(it.hotCue == 0) { "a memory list holds no hot cue: ${it.hotCue}" }
            }
        }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(type.code)
        writeShort(cues.size)
        writeShort(0) // unknown
    }

    override fun body(): ByteArray = bigEndian {
        for (cue in cues) {
            val comment = if (cue.comment.isEmpty()) ByteArray(0) else "${cue.comment}\u0000".toByteArray(Charsets.UTF_16BE)
            writeBytes("PCP2")
            writeInt(ENTRY_HEADER)
            writeInt(FIXED_BYTES + comment.size + COLOR_BYTES + PADDING_BYTES)
            writeInt(cue.hotCue)
            writeByte(if (cue.isLoop) TYPE_LOOP else TYPE_POSITION)
            write(CUE_CONSTANT)
            writeInt(cue.timeMs.toInt())
            writeInt((cue.loopEndMs ?: AnlzCue.NO_TIME).toInt())
            // A memory cue or loop is coloured by its row of rekordbox's colour table; a hot cue by the colour code.
            writeByte(if (cue.hotCue == 0 && cue.rgb != null) CueColors.memoryColorId(cue.rgb) else 0)
            writeByte(1)
            write(ByteArray(SIX))
            writeShort(cue.loopSize?.numerator ?: 0)
            writeShort(cue.loopSize?.denominator ?: 0)
            writeInt(comment.size)
            write(comment)
            writeByte(cue.rgb?.let { cue.colorCode ?: CueColors.hotCueCode(it) } ?: 0)
            writeByte((cue.rgb ?: 0) shr 16 and 0xFF)
            writeByte((cue.rgb ?: 0) shr 8 and 0xFF)
            writeByte((cue.rgb ?: 0) and 0xFF)
            write(ByteArray(PADDING_BYTES))
        }
    }

    private companion object {
        const val ENTRY_HEADER = 0x10
        const val FIXED_BYTES = 0x2C
        const val COLOR_BYTES = 4
        const val PADDING_BYTES = 40
        const val SIX = 6
    }
}

private const val TYPE_POSITION = 1
private const val TYPE_LOOP = 2

/** The three bytes `0003e8` after a cue's type in every entry rekordbox writes. */
private val CUE_CONSTANT = byteArrayOf(0x00, 0x03, 0xE8.toByte())

/** Hot cues by number, memory cues by time (a stable sort, so equal times keep their order). */
private fun ordered(type: CueListType, cues: List<AnlzCue>): List<AnlzCue> = when (type) {
    CueListType.HOT -> cues.sortedBy { it.hotCue }
    CueListType.MEMORY -> cues.sortedBy { it.timeMs }
}
