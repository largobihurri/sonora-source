package io.sonora.core.domain.library

import io.sonora.core.domain.model.Track

/** How an album sounds, summarised for its header. */
sealed interface AudioQuality {
    /** Lossless above CD quality: shown as depth and rate, e.g. `24 bit/96 kHz`. */
    data class HiRes(val bits: Int, val sampleRateHz: Int) : AudioQuality

    /** Lossless at CD quality or below. */
    data object Lossless : AudioQuality

    /** Lossy at [kbps]; [approximate] when the songs do not share one bitrate (an average). */
    data class Bitrate(val kbps: Int, val approximate: Boolean) : AudioQuality
}

/** A release date as far as it is known. */
data class ReleaseDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    companion object {
        private val ISO = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$")

        /** `yyyy`, `yyyy-MM` or `yyyy-MM-dd` (as the scanner stores it); `null` for anything else. */
        fun parse(text: String?): ReleaseDate? {
            val match = text?.trim()?.let(ISO::find) ?: return null
            val month = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 }
            val day = match.groupValues[3].toIntOrNull()?.takeIf { month != null && it in 1..31 }
            return ReleaseDate(match.groupValues[1].toInt(), month, day)
        }
    }
}

/** What an album's header and footer show about it, from its songs. */
data class AlbumSummary(
    val format: String?,
    val year: Int?,
    val quality: AudioQuality?,
    val releaseDate: ReleaseDate?,
    val copyright: String?,
    val songCount: Int,
    val durationMs: Long,
) {
    companion object {
        /** CD quality; anything deeper or faster is shown as high resolution. */
        private const val CD_BITS = 16
        private const val CD_MAX_RATE_HZ = 48_000

        /** [tracks] of one album; [albumYear] is the album's own year, preferred for the header. */
        fun of(tracks: List<Track>, albumYear: Int?): AlbumSummary {
            val formats = tracks.map { formatOf(it) }
            val format = formats.filterNotNull().mostCommon()
            val main = tracks.filterIndexed { i, _ -> formats[i] == format }
            // Parsed first, so an unreadable value on most songs does not hide a valid date on the others.
            val date = tracks.mapNotNull { ReleaseDate.parse(it.releaseDate) }.mostCommon { it.precision }
            return AlbumSummary(
                format = format,
                year = albumYear ?: date?.year,
                quality = format?.let { quality(it, main) },
                releaseDate = date ?: albumYear?.let { ReleaseDate(it) },
                copyright = tracks.mapNotNull { it.copyright?.takeIf(String::isNotBlank) }.mostCommon(),
                songCount = tracks.size,
                durationMs = tracks.sumOf { it.durationMs.coerceAtLeast(0) },
            )
        }

        /** Whole minutes, rounded; at least one for any playable length. */
        fun minutes(durationMs: Long): Int = when {
            durationMs <= 0 -> 0
            else -> ((durationMs + 30_000) / 60_000).toInt().coerceAtLeast(1)
        }

        /** The scanner's codec, else what the MIME type tells (MP4 is ambiguous: AAC or ALAC). */
        private fun formatOf(track: Track): String? = AudioFormats.of(track.codec, track.mimeType)

        private fun quality(format: String, tracks: List<Track>): AudioQuality? {
            if (AudioFormats.isLossless(format)) {
                val (bits, rate) = tracks.mapNotNull { t -> t.bitsPerSample?.let { b -> t.sampleRateHz?.let { b to it } } }
                    .mostCommon() ?: return AudioQuality.Lossless
                return if (bits > CD_BITS || rate > CD_MAX_RATE_HZ) AudioQuality.HiRes(bits, rate) else AudioQuality.Lossless
            }
            val rates = tracks.mapNotNull { it.bitrateKbps?.takeIf { kbps -> kbps > 0 } }
            if (rates.isEmpty()) return null
            val common = rates.mostCommon()!!
            return if (rates.count { it == common } * 2 > rates.size) {
                AudioQuality.Bitrate(common, approximate = false)
            } else {
                AudioQuality.Bitrate(Math.round(rates.average()).toInt(), approximate = true)
            }
        }

        /** The most frequent value; ties go to the highest [rank] (none by default), then to the first seen. */
        private fun <T> List<T>.mostCommon(rank: (T) -> Int = { 0 }): T? {
            val counts = groupingBy { it }.eachCount()
            return counts.entries.maxWithOrNull(
                compareBy<Map.Entry<T, Int>> { it.value }.thenBy { rank(it.key) }.thenByDescending { indexOf(it.key) },
            )?.key
        }

        /** How many parts of the date are known beyond the year. */
        private val ReleaseDate.precision: Int get() = listOfNotNull(month, day).size
    }
}
