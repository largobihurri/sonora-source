package io.sonora.core.domain.analysis

/**
 * The monochrome ("blue") waveforms of a track, as the Pioneer players without colour waveforms (XDJ-700, XDJ-1000,
 * CDJ-2000NXS) show them: a scrolling [detail] waveform, a whole-track [preview] and a [tinyPreview] for the browser.
 *
 * Every array holds one byte per column, already in the players' packing, so caching and encoding are plain copies:
 * - [detail] and [preview] columns pack a height 0..[MAX_HEIGHT] in the low 5 bits and a whiteness 0..[MAX_WHITENESS]
 *   (how much of the sound is above the bass; drawn as a paler blue) in the high 3 bits, see [packColumn];
 * - [tinyPreview] columns hold a height 0..[MAX_TINY_HEIGHT] in the low 4 bits and nothing else.
 *
 * [detail] has [DETAIL_COLUMNS_PER_SECOND] columns per second of audio ([detailColumnCount]); [preview] always has
 * [PREVIEW_COLUMNS] and [tinyPreview] [TINY_PREVIEW_COLUMNS] columns spread over the whole track.
 *
 * Arrays are copied in and out, so an instance never changes; equality compares the contents.
 */
class BlueWaveform(detail: ByteArray, preview: ByteArray, tinyPreview: ByteArray) {

    private val detailColumns: ByteArray = detail.copyOf()
    private val previewColumns: ByteArray = preview.copyOf()
    private val tinyPreviewColumns: ByteArray = tinyPreview.copyOf()

    init {
        require(previewColumns.size == PREVIEW_COLUMNS) {
            "preview must have $PREVIEW_COLUMNS columns: ${previewColumns.size}"
        }
        require(tinyPreviewColumns.size == TINY_PREVIEW_COLUMNS) {
            "tinyPreview must have $TINY_PREVIEW_COLUMNS columns: ${tinyPreviewColumns.size}"
        }
        require(tinyPreviewColumns.all { it in 0..MAX_TINY_HEIGHT }) { "tinyPreview heights must be 0..$MAX_TINY_HEIGHT" }
    }

    /** Number of [detail] columns, without copying them. */
    val detailSize: Int get() = detailColumns.size

    /** A copy of the detail columns ([DETAIL_COLUMNS_PER_SECOND] per second, packed as [packColumn]). */
    fun detail(): ByteArray = detailColumns.copyOf()

    /** A copy of the [PREVIEW_COLUMNS] whole-track columns, packed as [packColumn]. */
    fun preview(): ByteArray = previewColumns.copyOf()

    /** A copy of the [TINY_PREVIEW_COLUMNS] whole-track heights (0..[MAX_TINY_HEIGHT]). */
    fun tinyPreview(): ByteArray = tinyPreviewColumns.copyOf()

    override fun equals(other: Any?): Boolean = other is BlueWaveform &&
        detailColumns.contentEquals(other.detailColumns) &&
        previewColumns.contentEquals(other.previewColumns) &&
        tinyPreviewColumns.contentEquals(other.tinyPreviewColumns)

    override fun hashCode(): Int =
        (detailColumns.contentHashCode() * 31 + previewColumns.contentHashCode()) * 31 +
            tinyPreviewColumns.contentHashCode()

    override fun toString(): String = "BlueWaveform(detail=${detailColumns.size} columns)"

    companion object {
        const val DETAIL_COLUMNS_PER_SECOND: Int = 150
        const val PREVIEW_COLUMNS: Int = 400
        const val TINY_PREVIEW_COLUMNS: Int = 100
        const val MAX_HEIGHT: Int = 31
        const val MAX_WHITENESS: Int = 7
        const val MAX_TINY_HEIGHT: Int = 15
        private const val HEIGHT_BITS = 5

        /** A [detail] or [preview] column: [height] (0..31) in the low 5 bits, [whiteness] (0..7) in the high 3. */
        fun packColumn(height: Int, whiteness: Int): Byte {
            require(height in 0..MAX_HEIGHT) { "height must be 0..$MAX_HEIGHT: $height" }
            require(whiteness in 0..MAX_WHITENESS) { "whiteness must be 0..$MAX_WHITENESS: $whiteness" }
            return (whiteness shl HEIGHT_BITS or height).toByte()
        }

        /** The height (0..31) of a [detail] or [preview] column. */
        fun heightOf(column: Byte): Int = column.toInt() and MAX_HEIGHT

        /** The whiteness (0..7) of a [detail] or [preview] column. */
        fun whitenessOf(column: Byte): Int = (column.toInt() and 0xFF) ushr HEIGHT_BITS

        /**
         * Number of [detail] columns for [totalFrames] frames at [sampleRateHz]: column `i` starts at frame
         * `floor(i * sampleRateHz / 150)`, and every column that starts inside the track counts, so a partial last
         * column is kept (`ceil(totalFrames * 150 / sampleRateHz)`).
         */
        fun detailColumnCount(totalFrames: Long, sampleRateHz: Int): Int {
            require(totalFrames >= 0) { "totalFrames must not be negative: $totalFrames" }
            require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
            val rate = sampleRateHz.toLong()
            return Math.toIntExact((totalFrames * DETAIL_COLUMNS_PER_SECOND + rate - 1) / rate)
        }

        /** First frame of detail column [column] at [sampleRateHz]. */
        fun detailColumnStartFrame(column: Long, sampleRateHz: Int): Long =
            column * sampleRateHz / DETAIL_COLUMNS_PER_SECOND
    }
}
