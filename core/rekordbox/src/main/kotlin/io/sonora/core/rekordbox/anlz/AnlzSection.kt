package io.sonora.core.rekordbox.anlz

import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One tagged section of a rekordbox analysis file (`ANLZ0000.DAT`/`.EXT`), as the players read it.
 *
 * On disk every section starts with its [fourCc], a big-endian `len_header` (12 plus the size of [header]) and a
 * big-endian `len_tag` (`len_header` plus the size of [body]); [AnlzEncoder] writes that common part, each subclass
 * only its own header fields and body. New sections (colour waveforms `PWV4`/`PWV5`, `PQT2`, cues) are added as new
 * subclasses without touching the encoder.
 */
sealed class AnlzSection(val fourCc: String) {

    /** Section-specific header fields after `fourcc`, `len_header` and `len_tag`, big-endian. */
    internal abstract fun header(): ByteArray

    /** Section payload after the header, big-endian. */
    internal abstract fun body(): ByteArray
}

/**
 * `PPTH`: the track's audio file as the player finds it on the stick, e.g. `/Contents/Artist/Album/file.aiff`; the
 * same string as the `file_path` of the track in `export.pdb`. Stored as UTF-16BE with a terminating NUL.
 */
class PathSection(val path: String) : AnlzSection("PPTH") {
    init {
        require(path.startsWith("/")) { "path must start at the stick root: $path" }
    }

    private val encoded = "$path\u0000".toByteArray(Charsets.UTF_16BE)

    override fun header(): ByteArray = bigEndian { writeInt(encoded.size) }

    override fun body(): ByteArray = encoded.copyOf()
}

/**
 * `PVBR`: the seek index rekordbox writes for VBR files. All zeros, as rekordbox writes it for WAV and AIFF, where the
 * players seek by sample position.
 */
data object VbrSection : AnlzSection("PVBR") {
    private const val HEADER_BYTES = 4
    private const val BODY_BYTES = 1604 // 1620-byte section minus its 16-byte header

    override fun header(): ByteArray = ByteArray(HEADER_BYTES)

    override fun body(): ByteArray = ByteArray(BODY_BYTES)
}

/** `PQTZ`: the beat grid, one entry per beat of the whole track. */
class BeatGridSection(beats: List<AnlzBeat>) : AnlzSection("PQTZ") {
    val beats: List<AnlzBeat> = beats.toList()

    override fun header(): ByteArray = bigEndian {
        writeInt(0)
        writeInt(UNKNOWN)
        writeInt(beats.size)
    }

    override fun body(): ByteArray = bigEndian {
        for (beat in beats) {
            writeShort(beat.beatInBar)
            writeShort(beat.tempo)
            writeInt(beat.timeMs.toInt())
        }
    }

    companion object {
        /** Constant rekordbox writes after the leading zero; meaning unknown. */
        private const val UNKNOWN = 0x0008_0000

        /**
         * Every beat of [grid], at its tempo, with times rounded to the nearest millisecond (half up). The tempo is
         * checked even when the grid has no beats, so no grid with an unstorable tempo is ever accepted.
         */
        fun from(grid: BeatGrid): BeatGridSection {
            require(grid.bpm.isFinite() && grid.bpm > 0) { "bpm must be positive: ${grid.bpm}" }
            val tempo = (grid.bpm * 100).roundToInt()
            require(tempo > 0) { "bpm too low to store: ${grid.bpm}" }
            require(tempo <= MAX_U16) { "bpm too high to store: ${grid.bpm}" }
            return BeatGridSection(grid.beats().map { AnlzBeat(it.beatInBar, tempo, it.timeMs.roundToLong()) })
        }
    }
}

/** One `PQTZ` entry: position in the bar (1..4), tempo in hundredths of a BPM and time from the start in ms. */
data class AnlzBeat(val beatInBar: Int, val tempo: Int, val timeMs: Long) {
    init {
        require(beatInBar in 1..BeatGrid.BEATS_PER_BAR) { "beatInBar must be 1..${BeatGrid.BEATS_PER_BAR}: $beatInBar" }
        require(tempo in 0..MAX_U16) { "tempo must fit 16 bits: $tempo" }
        require(timeMs in 0..MAX_U32) { "timeMs must fit 32 bits: $timeMs" }
    }
}

/** `PWAV`: the whole-track blue preview, [BlueWaveform.PREVIEW_COLUMNS] columns packed as [BlueWaveform.packColumn]. */
class WaveformPreviewSection(columns: ByteArray) : AnlzSection("PWAV") {
    private val columns = columns.copyOf()

    init {
        require(this.columns.size == BlueWaveform.PREVIEW_COLUMNS) { "preview must have 400 columns: ${columns.size}" }
    }

    override fun header(): ByteArray = previewHeader(columns.size)

    override fun body(): ByteArray = columns.copyOf()
}

/** `PWV2`: the tiny browser preview, [BlueWaveform.TINY_PREVIEW_COLUMNS] heights 0..15. */
class TinyWaveformPreviewSection(columns: ByteArray) : AnlzSection("PWV2") {
    private val columns = columns.copyOf()

    init {
        require(this.columns.size == BlueWaveform.TINY_PREVIEW_COLUMNS) {
            "tiny preview must have 100 columns: ${columns.size}"
        }
    }

    override fun header(): ByteArray = previewHeader(columns.size)

    override fun body(): ByteArray = columns.copyOf()
}

/** `PWV3` (in `.EXT`): the scrolling blue detail, 150 columns per second packed as [BlueWaveform.packColumn]. */
class WaveformDetailSection(columns: ByteArray) : AnlzSection("PWV3") {
    private val columns = columns.copyOf()

    override fun header(): ByteArray = bigEndian {
        writeInt(1) // bytes per column
        writeInt(columns.size)
        writeInt(UNKNOWN)
    }

    override fun body(): ByteArray = columns.copyOf()

    private companion object {
        /** Constant rekordbox writes here; `0x96` = 150, the columns per second. */
        const val UNKNOWN = 0x0096_0000
    }
}

internal const val MAX_U16: Int = 0xFFFF
internal const val MAX_U32: Long = 0xFFFF_FFFFL

/** `PWAV`/`PWV2` header: column count, then a constant rekordbox always writes (meaning unknown). */
private fun previewHeader(columns: Int): ByteArray = bigEndian {
    writeInt(columns)
    writeInt(0x0001_0000)
}

/** Bytes written by [block] in big-endian order (the order [DataOutputStream] always uses). */
internal inline fun bigEndian(block: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
