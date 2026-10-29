package io.sonora.core.rekordbox.anlz

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The 3-band waveform a colour waveform is made from: four planes of one byte per column, 150 columns per second of
 * audio, the energy of the [low], [mid] and [high] bands and the overall [peak] (0..255 each). Arrays are copied in and
 * out.
 */
class BandPlanes(low: ByteArray, mid: ByteArray, high: ByteArray, peak: ByteArray) {
    private val lowColumns = low.copyOf()
    private val midColumns = mid.copyOf()
    private val highColumns = high.copyOf()
    private val peakColumns = peak.copyOf()

    init {
        require(midColumns.size == lowColumns.size && highColumns.size == lowColumns.size && peakColumns.size == lowColumns.size) {
            "all planes must have the same length"
        }
    }

    val columnCount: Int get() = lowColumns.size

    internal fun low(i: Int): Int = lowColumns[i].toInt() and 0xFF
    internal fun mid(i: Int): Int = midColumns[i].toInt() and 0xFF
    internal fun high(i: Int): Int = highColumns[i].toInt() and 0xFF
    internal fun peak(i: Int): Int = peakColumns[i].toInt() and 0xFF
}

/**
 * Builds the columns of the colour waveforms (`PWV4`, `PWV5`) and the 3-band waveforms (`PWV6`, `PWV7`) from
 * [BandPlanes]. The layouts are those of Deep Symmetry's analysis (see [ColorWaveformDetailSection]); the way rekordbox
 * turns the audio into column values is not published, so these follow what a rekordbox 6 export shows (a fit over 25
 * to 40 tracks, correlating `PWV5` with `PWV7` and `PWV4` with `PWAV`/`PWV6` of the same files):
 *
 * - `PWV5` red, green and blue are the low, mid and high bands as shares of the loudest of the three (the loudest is
 *   always 7), and the height is the overall level in 5 bits (r 0.97 against the blue detail's height).
 * - `PWV4` bytes 2 to 5 are the overall level and the low, mid and high bands in 0..127; byte 0 follows the level as
 *   `127 * sqrt(level / 127)` and byte 1 as `128 + (127 - level) / 2`, which is how they run in rekordbox's files
 *   (the leading two bytes feed the blue-and-white version of the preview).
 * - `PWV6`/`PWV7` hold the low, mid and high levels as they are (D1.20: byte 0 is low, 1 mid, 2 high).
 *
 * Only the nexus 2 and CDJ-3000 players draw these; the XDJ-700 skips them.
 */
object ColorWaveforms {
    const val PREVIEW_COLUMNS: Int = 1200
    private const val MAX_BAND = 255
    private const val MAX_LEVEL = 127
    private const val MAX_COLOUR = 7
    private const val MAX_HEIGHT = 31

    /** `PWV5` entries (2 bytes each, big-endian) for [columns] columns: the planes cut or padded with their last column. */
    fun detail(planes: BandPlanes, columns: Int): ByteArray {
        require(columns >= 0) { "columns must not be negative: $columns" }
        val out = ByteArray(columns * 2)
        for (i in 0 until columns) {
            val at = sourceIndex(planes, i)
            val value = if (at < 0) {
                0
            } else {
                val low = planes.low(at)
                val mid = planes.mid(at)
                val high = planes.high(at)
                val loudest = max(low, max(mid, high))
                val r = share(low, loudest)
                val g = share(mid, loudest)
                val b = share(high, loudest)
                (r shl 13) or (g shl 10) or (b shl 7) or (scale(planes.peak(at), MAX_HEIGHT) shl 2)
            }
            out[2 * i] = (value shr 8).toByte()
            out[2 * i + 1] = value.toByte()
        }
        return out
    }

    /** `PWV4` entries: [PREVIEW_COLUMNS] columns of 6 bytes, each the loudest of the stretch of audio it covers. */
    fun preview(planes: BandPlanes): ByteArray {
        val out = ByteArray(PREVIEW_COLUMNS * 6)
        forEachStretch(planes) { column, window ->
            val level = scale(window.peak, MAX_LEVEL)
            val base = column * 6
            out[base] = (MAX_LEVEL * sqrt(level / MAX_LEVEL.toDouble())).roundToInt().toByte()
            out[base + 1] = (128 + (MAX_LEVEL - level) / 2).toByte()
            out[base + 2] = level.toByte()
            out[base + 3] = scale(window.low, MAX_LEVEL).toByte()
            out[base + 4] = scale(window.mid, MAX_LEVEL).toByte()
            out[base + 5] = scale(window.high, MAX_LEVEL).toByte()
        }
        return out
    }

    /** `PWV7` entries: [columns] columns of low, mid and high as they are. */
    fun threeBandDetail(planes: BandPlanes, columns: Int): ByteArray {
        require(columns >= 0) { "columns must not be negative: $columns" }
        val out = ByteArray(columns * 3)
        for (i in 0 until columns) {
            val at = sourceIndex(planes, i)
            if (at < 0) continue
            out[3 * i] = planes.low(at).toByte()
            out[3 * i + 1] = planes.mid(at).toByte()
            out[3 * i + 2] = planes.high(at).toByte()
        }
        return out
    }

    /** `PWV6` entries: [PREVIEW_COLUMNS] columns of low, mid and high, the loudest of each stretch. */
    fun threeBandPreview(planes: BandPlanes): ByteArray {
        val out = ByteArray(PREVIEW_COLUMNS * 3)
        forEachStretch(planes) { column, window ->
            out[3 * column] = window.low.toByte()
            out[3 * column + 1] = window.mid.toByte()
            out[3 * column + 2] = window.high.toByte()
        }
        return out
    }

    /** The plane column that stands for output column [i]: the same one, the last when the planes are shorter, -1 when empty. */
    private fun sourceIndex(planes: BandPlanes, i: Int): Int = if (planes.columnCount == 0) -1 else min(i, planes.columnCount - 1)

    private class Stretch(val low: Int, val mid: Int, val high: Int, val peak: Int)

    /** Calls [block] for each of the [PREVIEW_COLUMNS] preview columns with the loudest values of its stretch of detail columns. */
    private inline fun forEachStretch(planes: BandPlanes, block: (Int, Stretch) -> Unit) {
        val n = planes.columnCount
        for (column in 0 until PREVIEW_COLUMNS) {
            if (n == 0) {
                block(column, Stretch(0, 0, 0, 0))
                continue
            }
            val start = (column.toLong() * n / PREVIEW_COLUMNS).toInt().coerceAtMost(n - 1)
            val end = ((column + 1).toLong() * n / PREVIEW_COLUMNS).toInt().coerceIn(start + 1, n)
            var low = 0
            var mid = 0
            var high = 0
            var peak = 0
            for (i in start until end) {
                low = max(low, planes.low(i))
                mid = max(mid, planes.mid(i))
                high = max(high, planes.high(i))
                peak = max(peak, planes.peak(i))
            }
            block(column, Stretch(low, mid, high, peak))
        }
    }

    /** [value] (0..255) as 0..[top], half up. */
    private fun scale(value: Int, top: Int): Int = (value * top + MAX_BAND / 2) / MAX_BAND

    /** [value] as a share of [loudest] in 0..7, half up; 0 when everything is silent. */
    private fun share(value: Int, loudest: Int): Int = if (loudest == 0) 0 else (value * MAX_COLOUR * 2 + loudest) / (loudest * 2)
}

/**
 * `PWV5` (in `.EXT`): the colour detail, one 16-bit entry per column and 150 a second (as many as `PWV3` has): 3 bits
 * red, 3 green, 3 blue, 5 of height, two unused. [entries] is the columns' bytes, big-endian, as built by
 * [ColorWaveforms.detail]. Layout: Deep Symmetry, "Rekordbox Export Structure Analysis", `PWV5`; the header's last word
 * `0x00960305` is the constant rekordbox writes.
 */
class ColorWaveformDetailSection(entries: ByteArray) : AnlzSection("PWV5") {
    private val entries = entries.copyOf()

    init {
        require(this.entries.size % 2 == 0) { "entries are 2 bytes each: ${this.entries.size}" }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(2)
        writeInt(entries.size / 2)
        writeInt(0x0096_0305)
    }

    override fun body(): ByteArray = entries.copyOf()
}

/** `PWV4` (in `.EXT`): the colour preview, [ColorWaveforms.PREVIEW_COLUMNS] columns of 6 bytes, built by [ColorWaveforms.preview]. */
class ColorWaveformPreviewSection(columns: ByteArray) : AnlzSection("PWV4") {
    private val columns = columns.copyOf()

    init {
        require(this.columns.size == ColorWaveforms.PREVIEW_COLUMNS * 6) { "preview must have 1200 columns of 6 bytes: ${columns.size}" }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(6)
        writeInt(ColorWaveforms.PREVIEW_COLUMNS)
        writeInt(0)
    }

    override fun body(): ByteArray = columns.copyOf()
}

/**
 * `PWV7` (in `.2EX`, read by the CDJ-3000): the 3-band detail, 3 bytes (low, mid, high) per column, 150 a second.
 * Layout: Deep Symmetry's analysis, `PWV7` (the band order there is mid, high, low; the D1.20 calibration against
 * rekordbox exports found low, mid, high on 150 of 150 tracks).
 */
class ThreeBandDetailSection(entries: ByteArray) : AnlzSection("PWV7") {
    private val entries = entries.copyOf()

    init {
        require(this.entries.size % 3 == 0) { "entries are 3 bytes each: ${this.entries.size}" }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(3)
        writeInt(entries.size / 3)
        writeInt(0x0096_0000)
    }

    override fun body(): ByteArray = entries.copyOf()
}

/** `PWV6` (in `.2EX`): the 3-band preview, [ColorWaveforms.PREVIEW_COLUMNS] columns of low, mid, high; no trailing header word. */
class ThreeBandPreviewSection(columns: ByteArray) : AnlzSection("PWV6") {
    private val columns = columns.copyOf()

    init {
        require(this.columns.size == ColorWaveforms.PREVIEW_COLUMNS * 3) { "preview must have 1200 columns of 3 bytes: ${columns.size}" }
    }

    override fun header(): ByteArray = bigEndian {
        writeInt(3)
        writeInt(ColorWaveforms.PREVIEW_COLUMNS)
    }

    override fun body(): ByteArray = columns.copyOf()
}

/**
 * `PWVC` (in `.2EX`): the gains of the three bands, which rekordbox writes per track (80, 88 and 119 in the demo
 * export, 89, 97 and 107 in another library). Its layout is not published; this repeats the demo's values.
 */
data object WaveformGainSection : AnlzSection("PWVC") {
    override fun header(): ByteArray = ByteArray(2)

    override fun body(): ByteArray = bigEndian {
        writeShort(80)
        writeShort(88)
        writeShort(119)
    }
}
