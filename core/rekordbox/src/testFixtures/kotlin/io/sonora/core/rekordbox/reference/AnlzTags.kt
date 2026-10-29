package io.sonora.core.rekordbox.reference

import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.core.rekordbox.anlz.CueListType

/**
 * A parsed ANLZ file: the raw layer ([raw]: header and sections as stored) and the [tags], one per section in file
 * order. Unknown sections are kept as [UnknownTag], so nothing in the file is dropped.
 */
class AnlzFile(val raw: RawAnlz, val tags: List<AnlzTag>) {
    val header: ByteArray get() = raw.header

    /** The tags whose four-character code is [fourCc], in file order. */
    fun all(fourCc: String): List<AnlzTag> = tags.filter { it.fourCc == fourCc }

    /** The one tag [fourCc]; fails when there is none or several. */
    fun single(fourCc: String): AnlzTag = all(fourCc).single()

    /** The tags of type [T], in file order. */
    inline fun <reified T : AnlzTag> filter(): List<T> = tags.filterIsInstance<T>()

    /** The first tag of type [T], or null. */
    inline fun <reified T : AnlzTag> first(): T? = tags.firstOrNull { it is T } as T?

    /** The audio file path of the `PPTH` section, or null when the file has none. */
    val path: String? get() = first<PathTag>()?.path
}

/** One section of an ANLZ file, parsed. [raw] is the section exactly as stored (common header included). */
sealed class AnlzTag(val raw: RawSection) {
    val fourCc: String get() = raw.fourCc
}

/** `PPTH`: the audio file as the player finds it on the stick (UTF-16BE, trailing NUL removed). */
class PathTag(raw: RawSection, val path: String) : AnlzTag(raw)

/**
 * `PQTZ`: the beat grid, one [beats] entry per beat. [unknown1] and [unknown2] are the two header words whose meaning
 * is not known (the second is usually `0x00080000`).
 */
class BeatGridTag(raw: RawSection, val unknown1: Long, val unknown2: Long, val beats: List<AnlzBeat>) : AnlzTag(raw)

/** One of the two anchors of a `PQT2` header: beat in bar, tempo in hundredths of a BPM and time in ms. */
data class GridAnchor(val beatInBar: Int, val tempoHundredths: Int, val timeMs: Long)

/**
 * `PQT2` (`.EXT`, nexus 2 and CDJ-3000 players): the compact beat grid. Its 56-byte header holds [version] (observed
 * `0x01000002`/`0x02000002`), two [anchors] (in the files seen, the first and the last beat, each with its tempo and
 * time) and three words of unknown meaning; [entries] has one 16-bit word per beat whose meaning is not published (it
 * is 937 on every beat of the constant-tempo track of the one export checked, so it is no beat number; pyrekordbox
 * models the two bytes as beat and unknown, which does not fit that file). Layout from pyrekordbox's `PQT2` structure
 * (MIT) and checked against a rekordbox export; Deep Symmetry's analysis does not document this tag.
 */
class ExtendedBeatGridTag(
    raw: RawSection,
    val version: Long,
    val anchors: List<GridAnchor>,
    val unknown1: Long,
    val unknown2: Long,
    val unknown3: Long,
    val entries: List<Int>,
) : AnlzTag(raw)

/**
 * `PWAV` (400 columns, [isTiny] false) or `PWV2` (100 columns, [isTiny] true): the monochrome whole-track previews.
 * Per column byte of `PWAV`, the five low bits are the height (0..31) and the three high bits the whiteness; `PWV2`
 * uses only the four low bits (height 0..15). [unknown] is the header word after the length (always `0x00010000`).
 */
class WaveformPreviewTag(raw: RawSection, val columns: ByteArray, val unknown: Long) : AnlzTag(raw) {
    val isTiny: Boolean get() = fourCc == "PWV2"

    fun height(column: Int): Int = (columns[column].toInt() and 0xFF) and if (isTiny) 0x0F else 0x1F

    fun whiteness(column: Int): Int = if (isTiny) 0 else (columns[column].toInt() and 0xFF) ushr 5
}

/**
 * `PWV3` (`.EXT`): the scrolling monochrome detail, one byte per column, 150 columns per second, packed like `PWAV`.
 * [unknown] is the header word after the entry count (always `0x00960000`).
 */
class WaveformDetailTag(raw: RawSection, val columns: ByteArray, val unknown: Long) : AnlzTag(raw) {
    fun height(column: Int): Int = (columns[column].toInt() and 0xFF) and 0x1F

    fun whiteness(column: Int): Int = (columns[column].toInt() and 0xFF) ushr 5
}

/**
 * `PWV4` (`.EXT`): the colour preview, [entryBytes] (6) bytes per column, 1200 columns. Per column, as far as the
 * public analysis goes: channels 0 and 1 feed the whiteness of the blue version, channel 2 is the energy in the lower
 * half of the spectrum, channel 3 the lower third, channel 4 the middle and channel 5 the top. [data] holds the
 * columns back to back.
 */
class ColorPreviewTag(raw: RawSection, val entryBytes: Int, val unknown: Long, val data: ByteArray) : AnlzTag(raw) {
    val columnCount: Int get() = if (entryBytes == 0) 0 else data.size / entryBytes

    /** Byte [channel] (0 until [entryBytes]) of [column] as 0..255. */
    fun channel(column: Int, channel: Int): Int {
        require(channel in 0 until entryBytes) { "channel out of range: $channel" }
        return data[column * entryBytes + channel].toInt() and 0xFF
    }
}

/**
 * `PWV5` (`.EXT`): the colour detail, one big-endian 16-bit [entries] value per column (150 per second): 3 bits red,
 * 3 bits green, 3 bits blue and 5 bits height from the top, the two low bits unused.
 */
class ColorDetailTag(raw: RawSection, val unknown: Long, val entries: IntArray) : AnlzTag(raw) {
    fun red(column: Int): Int = (entries[column] ushr 13) and 0x7

    fun green(column: Int): Int = (entries[column] ushr 10) and 0x7

    fun blue(column: Int): Int = (entries[column] ushr 7) and 0x7

    fun height(column: Int): Int = (entries[column] ushr 2) and 0x1F
}

/**
 * `PWV6` (preview, 1200 columns, [isDetail] false) or `PWV7` (detail, 150 columns per second, [isDetail] true), both
 * in the `.2EX` file of the CDJ-3000: [entryBytes] (3) bytes per column holding the heights of the low, mid and high
 * frequency bands, in that order (byte 0, 1, 2). [unknown] is the header word `PWV7` has after the entry count (null
 * for `PWV6`).
 *
 * The order was disputed between sources (this reader first said mid, high, low). The D1.20 calibration settled it on
 * a real rekordbox export: against our own low/mid/high bands, the low, mid, high reading wins on 150 of 150 tracks
 * (mean Pearson r 0.790 for PWV7 and 0.878 for PWV6, against 0.279 and 0.400 for mid, high, low).
 */
class ThreeBandWaveformTag(raw: RawSection, val entryBytes: Int, val unknown: Long?, val data: ByteArray) :
    AnlzTag(raw) {
    val isDetail: Boolean get() = fourCc == "PWV7"
    val columnCount: Int get() = if (entryBytes == 0) 0 else data.size / entryBytes

    fun low(column: Int): Int = data[column * entryBytes].toInt() and 0xFF

    fun mid(column: Int): Int = data[column * entryBytes + 1].toInt() and 0xFF

    fun high(column: Int): Int = data[column * entryBytes + 2].toInt() and 0xFF
}

/**
 * One `PCPT` entry of a `PCOB` list: [hotCue] 0 for a memory cue, else the hot cue number (A is 1); [status] 0 for a
 * plain cue or loop and 4 for an active loop; [orderFirst]/[orderLast] sort the cues; [typeCode] 1 for a position and
 * 2 for a loop; [loopTimeMs] is the loop's end in ms, null when the entry stores none (`0xFFFFFFFF`, as plain cues do).
 */
data class Cue(
    val hotCue: Int,
    val status: Int,
    val orderFirst: Int,
    val orderLast: Int,
    val typeCode: Int,
    val timeMs: Long,
    val loopTimeMs: Long?,
) {
    val isLoop: Boolean get() = typeCode == CUE_TYPE_LOOP
}

/**
 * `PCOB`: a list of memory cues or of hot cues ([type]) with their [cues]. [unknown] and [memoryCount] are header
 * fields whose meaning is not known (`memoryCount` is `0xFFFFFFFF` in the empty lists rekordbox writes).
 */
class CueListTag(raw: RawSection, val type: CueListType, val unknown: Int, val memoryCount: Long, val cues: List<Cue>) :
    AnlzTag(raw)

/**
 * One `PCP2` entry of a `PCO2` list. [colorId] indexes rekordbox's colour table for memory cues and loops; the loop
 * size of a quantised loop is [loopNumerator]/[loopDenominator] beats (0/0 otherwise). [comment] is empty when the
 * entry has none. [colorCode], [red], [green] and [blue] describe a hot cue's colour and are null in the incomplete
 * entries rekordbox sometimes writes (cut before the colour).
 */
data class ExtendedCue(
    val hotCue: Int,
    val typeCode: Int,
    val timeMs: Long,
    val loopTimeMs: Long?,
    val colorId: Int,
    val loopNumerator: Int,
    val loopDenominator: Int,
    val comment: String,
    val colorCode: Int?,
    val red: Int?,
    val green: Int?,
    val blue: Int?,
) {
    val isLoop: Boolean get() = typeCode == CUE_TYPE_LOOP
}

/** `PCO2` (`.EXT`): the extended cue list (colours, comments, hot cues past C) of the nexus 2 and later players. */
class ExtendedCueListTag(raw: RawSection, val type: CueListType, val unknown: Int, val cues: List<ExtendedCue>) :
    AnlzTag(raw)

/**
 * One phrase of a `PSSI` song structure: its 1-based [index], the [beat] it starts at and its [kind], whose meaning
 * depends on the tag's mood (see [SongStructureTag.mood]); [k1], [k2] and [k3] tell the variants of a high-mood
 * phrase apart; [b], [beat2], [beat3] and [beat4] are extra beat numbers of "Up 3" phrases; a non-zero [fill] says a
 * fill-in starts at [beatFill].
 */
data class Phrase(
    val index: Int,
    val beat: Int,
    val kind: Int,
    val k1: Int,
    val k2: Int,
    val k3: Int,
    val b: Int,
    val beat2: Int,
    val beat3: Int,
    val beat4: Int,
    val fill: Int,
    val beatFill: Int,
)

/**
 * `PSSI` (`.EXT`, rekordbox 6 and later): the song structure. [mood] is 1 (high), 2 (mid) or 3 (low); [endBeat] the
 * beat where the last phrase ends; [bank] the lighting bank (0..8, see the analysis for the names). [wasMasked] says
 * the file stored the tag XOR-masked (as rekordbox 6 exports do); [unmasked] is the whole section with the mask
 * removed. See [SongStructureMask].
 */
class SongStructureTag(
    raw: RawSection,
    val wasMasked: Boolean,
    val mood: Int,
    val endBeat: Int,
    val bank: Int,
    val phrases: List<Phrase>,
    val unmasked: ByteArray,
) : AnlzTag(raw)

/** A section this reader does not know; [raw] has every byte of it. */
class UnknownTag(raw: RawSection) : AnlzTag(raw)

internal const val CUE_TYPE_LOOP = 2
