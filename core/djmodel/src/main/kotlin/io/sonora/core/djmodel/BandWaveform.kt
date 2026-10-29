package io.sonora.core.djmodel

/**
 * The coloured 3-band waveform of a track, in the shape of the CDJ colour waveform: per column the energy of the
 * [low], [mid] and [high] bands and the overall [peak], each one byte (0..255, an unsigned level).
 *
 * The four planes have the same length: [COLUMNS_PER_SECOND] columns per second of audio. [version] identifies the
 * algorithm (crossovers, scaling) that produced them. Arrays are copied in and out, so an instance never changes;
 * equality compares the contents.
 */
class BandWaveform(low: ByteArray, mid: ByteArray, high: ByteArray, peak: ByteArray, val version: Int) {

    private val lowColumns: ByteArray = low.copyOf()
    private val midColumns: ByteArray = mid.copyOf()
    private val highColumns: ByteArray = high.copyOf()
    private val peakColumns: ByteArray = peak.copyOf()

    init {
        require(version >= 1) { "version must be positive: $version" }
        require(midColumns.size == lowColumns.size && highColumns.size == lowColumns.size && peakColumns.size == lowColumns.size) {
            "All planes must have the same length: low ${lowColumns.size}, mid ${midColumns.size}, " +
                "high ${highColumns.size}, peak ${peakColumns.size}"
        }
    }

    /** Number of columns in each plane. */
    val columnCount: Int get() = lowColumns.size

    /** Length of the audio the columns cover. */
    val durationMs: Double get() = columnCount * 1000.0 / COLUMNS_PER_SECOND

    fun low(): ByteArray = lowColumns.copyOf()

    fun mid(): ByteArray = midColumns.copyOf()

    fun high(): ByteArray = highColumns.copyOf()

    fun peak(): ByteArray = peakColumns.copyOf()

    override fun equals(other: Any?): Boolean = other is BandWaveform &&
        version == other.version &&
        lowColumns.contentEquals(other.lowColumns) &&
        midColumns.contentEquals(other.midColumns) &&
        highColumns.contentEquals(other.highColumns) &&
        peakColumns.contentEquals(other.peakColumns)

    override fun hashCode(): Int {
        var h = version
        h = h * 31 + lowColumns.contentHashCode()
        h = h * 31 + midColumns.contentHashCode()
        h = h * 31 + highColumns.contentHashCode()
        h = h * 31 + peakColumns.contentHashCode()
        return h
    }

    override fun toString(): String = "BandWaveform(version=$version, $columnCount columns)"

    companion object {
        const val COLUMNS_PER_SECOND: Int = 150

        /**
         * The placeholder of an analysis whose waveform has not been computed (one restored from a backup, which
         * never carries waveforms): no columns. See [DjAnalysis.waveformMissing].
         */
        val MISSING: BandWaveform = BandWaveform(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), version = 1)
    }
}
