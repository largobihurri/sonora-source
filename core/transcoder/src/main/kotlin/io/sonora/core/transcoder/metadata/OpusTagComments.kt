package io.sonora.core.transcoder.metadata

import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.CoverArtRule
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.transcoder.ogg.FlacPicture
import io.sonora.core.transcoder.ogg.VorbisComment
import kotlin.math.roundToInt

/** Shrinks a cover for a small screen; Android's bitmap codecs implement it, tests use doubles. */
fun interface CoverScaler {
    /**
     * [cover] as a JPEG whose sides do not exceed [maxSidePx], or `null` when the image cannot be decoded. Never
     * enlarges.
     */
    fun scale(cover: CoverArt, maxSidePx: Int): CoverArt?
}

/**
 * The Vorbis comments of an Ogg Opus file for a source's [TrackMetadata]: the fields a player lists (title, artist,
 * album, album artist, track and disc numbers, date, genre), `R128_TRACK_GAIN` when the source carries a track gain,
 * and the front cover as `METADATA_BLOCK_PICTURE` (RFC 7845 section 5.2), downscaled to a watch-sized JPEG.
 */
object OpusTagComments {

    /** Covers are shrunk to this many pixels on their longer side: sharp on a watch, small in the file. */
    const val MAX_COVER_SIDE = 300

    /** ReplayGain 2 aims at -18 LUFS and the R128 comment at -23 LUFS: the same track needs 5 dB less gain. */
    private const val REPLAYGAIN_TO_R128_DB = 5.0
    private const val REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    private val GAIN = Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE)

    fun from(metadata: TrackMetadata, scaler: CoverScaler): List<VorbisComment> {
        val comments = ArrayList<VorbisComment>()
        fun add(name: String, value: String?) {
            if (!value.isNullOrBlank()) comments += VorbisComment(name, value)
        }
        add("TITLE", metadata.text[MetadataTextField.TITLE])
        add("ARTIST", metadata.text[MetadataTextField.ARTIST])
        add("ALBUM", metadata.text[MetadataTextField.ALBUM])
        add("ALBUMARTIST", metadata.text[MetadataTextField.ALBUM_ARTIST])
        add("TRACKNUMBER", metadata.trackNumber?.toString())
        add("TRACKTOTAL", metadata.trackTotal?.toString())
        add("DISCNUMBER", metadata.discNumber?.toString())
        add("DISCTOTAL", metadata.discTotal?.toString())
        add("DATE", metadata.date)
        add("GENRE", metadata.text[MetadataTextField.GENRE])
        add("R128_TRACK_GAIN", r128TrackGain(metadata))
        metadata.cover?.let { cover -> picture(cover, scaler)?.let { comments += it } }
        return comments
    }

    /** The source's track gain as the Q7.8 dB integer of `R128_TRACK_GAIN`; `null` when it has none to convert. */
    private fun r128TrackGain(metadata: TrackMetadata): String? {
        val text = metadata.userText.firstOrNull { it.description.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true) }?.value
        val db = text?.let { GAIN.matchEntire(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() } ?: return null
        val q78 = ((db - REPLAYGAIN_TO_R128_DB) * 256).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return q78.toString()
    }

    private fun picture(cover: CoverArt, scaler: CoverScaler): VorbisComment? {
        val small = cover.mimeType == CoverArtRule.JPEG && maxOf(cover.width, cover.height) <= MAX_COVER_SIDE
        val ready = if (small) cover else runCatching { scaler.scale(cover, MAX_COVER_SIDE) }.getOrNull()
        return ready?.let { FlacPicture.comment(it.bytes, it.mimeType, it.width, it.height) }
    }
}
