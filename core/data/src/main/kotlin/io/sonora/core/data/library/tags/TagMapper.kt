package io.sonora.core.data.library.tags

import io.sonora.core.domain.library.TagText
import io.sonora.core.domain.library.TrackTags
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToLong

/** Tag and header values exactly as the tag library reports them, before any cleanup. */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val track: String? = null,
    val disc: String? = null,
    val compilation: String? = null,
    val durationSec: Double? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: String? = null,
    /** The JAudioTagger reader the file was read with (`mp3`, `flac`, `m4a`, `ogg`, `wav`, `aif`...). */
    val readerKey: String? = null,
    val encodingType: String? = null,
    val format: String? = null,
    val lossless: Boolean? = null,
    val bitrateKbps: Long? = null,
    val copyright: String? = null,
)

/** Turns [RawTags] into domain [TrackTags], treating blank, padded or garbled values as missing. */
object TagMapper {
    private val LEADING_NUMBER = Regex("^\\d+")
    private val TRUE_VALUES = setOf("1", "true", "yes")

    fun map(raw: RawTags): TrackTags = TrackTags(
        title = TagText.clean(raw.title),
        artist = TagText.clean(raw.artist),
        album = TagText.clean(raw.album),
        albumArtist = TagText.clean(raw.albumArtist),
        genre = TagText.clean(raw.genre),
        // "2001-05-03" and "2001" both carry the year; anything shorter is not one.
        year = leadingNumber(raw.year)?.takeIf { it in 1000..9999 },
        // "3/12" = track 3 of 12.
        trackNumber = leadingNumber(raw.track)?.takeIf { it > 0 },
        discNumber = leadingNumber(raw.disc)?.takeIf { it > 0 },
        isCompilation = TagText.clean(raw.compilation)?.lowercase() in TRUE_VALUES,
        durationMs = raw.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() } ?: 0L,
        sampleRateHz = raw.sampleRate?.takeIf { it > 0 },
        bitsPerSample = raw.bitsPerSample?.takeIf { it > 0 },
        channelCount = channelCount(raw.channels),
        codec = codec(raw),
        bitrateKbps = raw.bitrateKbps?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
        releaseDate = releaseDate(raw.year),
        copyright = TagText.clean(raw.copyright),
    )

    /** Short codec name from the reader that read the file and, for MP4, what its header says. */
    private fun codec(raw: RawTags): String? = when (raw.readerKey?.lowercase()) {
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "ogg" -> if ("opus" in raw.encodingType.orEmpty().lowercase()) "Opus" else "Vorbis"
        "m4a" -> {
            val text = "${raw.encodingType.orEmpty()} ${raw.format.orEmpty()}".lowercase()
            if ("alac" in text || "lossless" in text || raw.lossless == true) "ALAC" else "AAC"
        }
        "wav" -> "WAV"
        "aif", "aiff", "aifc" -> "AIFF"
        else -> null
    }

    private val DATE = Regex("^(\\d{4})(?:[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?)?")

    /** `yyyy`, `yyyy-MM` or `yyyy-MM-dd`: as much of a tagged date as forms a real date. */
    private fun releaseDate(value: String?): String? {
        val match = TagText.clean(value)?.let { DATE.find(it) } ?: return null
        val year = match.groupValues[1].toInt().takeIf { it in 1000..9999 } ?: return null
        val month = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 }
            ?: return year.toString()
        val day = match.groupValues[3].toIntOrNull()?.takeIf { it in 1..YearMonth.of(year, month).lengthOfMonth() }
        return buildString {
            append(String.format(Locale.ROOT, "%04d-%02d", year, month))
            if (day != null) append(String.format(Locale.ROOT, "-%02d", day))
        }
    }

    private fun leadingNumber(value: String?): Int? =
        TagText.clean(value)?.let { LEADING_NUMBER.find(it)?.value?.toIntOrNull() }

    /** Headers report either a count ("2") or an MP3 channel mode ("Joint Stereo", "Dual mono", "Mono"). */
    private fun channelCount(value: String?): Int? {
        val text = TagText.clean(value)?.lowercase() ?: return null
        text.toIntOrNull()?.let { return it.takeIf { count -> count > 0 } }
        return when {
            "dual" in text || "stereo" in text -> 2
            "mono" in text -> 1
            else -> null
        }
    }
}
