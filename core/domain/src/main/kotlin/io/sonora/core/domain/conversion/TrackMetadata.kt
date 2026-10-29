package io.sonora.core.domain.conversion

import io.sonora.core.domain.library.TagText

/**
 * Text fields a conversion carries over from the source, independent of the source's tag format (ID3, Vorbis
 * comments, MP4 atoms, RIFF INFO) and of the output's. Each output container decides how to store them.
 */
enum class MetadataTextField {
    TITLE,
    ARTIST,
    ALBUM_ARTIST,
    ALBUM,
    GENRE,
    COMPOSER,
    BPM,
    INITIAL_KEY,
    ISRC,
    LABEL,
    CONDUCTOR,
    REMIXER,
    LYRICIST,
    ORIGINAL_ARTIST,
    ORIGINAL_ALBUM,
    ORIGINAL_YEAR,
    GROUPING,
    SUBTITLE,
    COPYRIGHT,
    LANGUAGE,
    MEDIA,
    TITLE_SORT,
    ARTIST_SORT,
    ALBUM_SORT,
    ALBUM_ARTIST_SORT,
    COMPOSER_SORT,
    COMPILATION,
}

/** A free-form field (ID3 `TXXX`, a custom Vorbis comment, an MP4 `----` atom): ReplayGain, MusicBrainz ids, … */
data class UserTextField(val description: String, val value: String)

/** The front cover as stored in the output: [bytes] are a JPEG or PNG of [width] x [height] pixels. */
class CoverArt(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean = other is CoverArt && mimeType == other.mimeType &&
        width == other.width && height == other.height && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (mimeType.hashCode() * 31 + width) * 31 + height + 31 * bytes.contentHashCode()

    override fun toString(): String = "CoverArt($mimeType, ${width}x$height, ${bytes.size} bytes)"
}

/**
 * The tags and front cover of a source file, ready to be written into a converted file. Values are cleaned
 * ([TagText.clean]) and never blank; multiple values of one field are already joined ([MetadataValues.join]).
 * Technical fields that describe the source's encoding (encoder, length, gapless data) are not part of it.
 */
data class TrackMetadata(
    val text: Map<MetadataTextField, String> = emptyMap(),
    /** Release date as tagged: `yyyy`, `yyyy-MM-dd` or any text starting with a year. */
    val date: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val comment: String? = null,
    val lyrics: String? = null,
    /** At most one entry per description, compared ignoring case. */
    val userText: List<UserTextField> = emptyList(),
    val cover: CoverArt? = null,
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && date == null && trackNumber == null && trackTotal == null && discNumber == null &&
            discTotal == null && comment == null && lyrics == null && userText.isEmpty() && cover == null
}

/** Value rules shared by every tag format. */
object MetadataValues {
    /**
     * Joining rule for fields with several values (repeated Vorbis `ARTIST`/`GENRE` comments, ID3v2.4 null-separated
     * lists, several MP4 values): each value cleaned, blanks and case-insensitive repeats dropped, the rest joined in
     * source order with `"; "`. ID3v2.3 has no multi-value text frames, and `/` (its traditional separator) would
     * split names such as "AC/DC"; `;` reads well on a player display. `null` when nothing is left.
     */
    fun join(values: List<String?>): String? = values
        .mapNotNull(TagText::clean)
        .distinctBy { it.lowercase() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(SEPARATOR)

    const val SEPARATOR = "; "

    /** The positive number at the start of [value] (`"07"`, `"3/12"`), else `null`. */
    fun leadingNumber(value: String?): Int? =
        value?.trim()?.let { LEADING_NUMBER.find(it)?.value }?.toIntOrNull()?.takeIf { it > 0 }

    private val LEADING_NUMBER = Regex("^\\d{1,9}")
}

/**
 * Front cover policy for DJ players: Pioneer CDJ/XDJ units show embedded JPEG/PNG artwork but choke on very large
 * pictures, so a cover is kept byte for byte only while it is a JPEG or PNG of at most [MAX_ORIGINAL_SIDE] pixels a
 * side and [MAX_ORIGINAL_BYTES]; otherwise it is re-encoded as a JPEG ([REENCODE_JPEG_QUALITY]) that fits
 * [REENCODE_MAX_SIDE] x [REENCODE_MAX_SIDE]. (A rekordbox export makes its own artwork thumbnails.)
 */
object CoverArtRule {
    const val MAX_ORIGINAL_SIDE = 1500
    const val MAX_ORIGINAL_BYTES = 2 * 1024 * 1024
    const val REENCODE_MAX_SIDE = 1000
    const val REENCODE_JPEG_QUALITY = 90

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"

    /** `image/jpeg` or `image/png` from the file signature; `null` for any other or unknown format. */
    fun mimeTypeOf(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
        bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> PNG
        else -> null
    }

    fun keepsOriginal(mimeType: String?, width: Int, height: Int, sizeBytes: Int): Boolean =
        (mimeType == JPEG || mimeType == PNG) && width in 1..MAX_ORIGINAL_SIDE && height in 1..MAX_ORIGINAL_SIDE &&
            sizeBytes <= MAX_ORIGINAL_BYTES

    /** Size of a re-encoded cover: [width] x [height] scaled down (never up) to fit the re-encode box. */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        val longest = maxOf(width, height)
        if (longest <= REENCODE_MAX_SIDE) return width to height
        val scale = REENCODE_MAX_SIDE.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
