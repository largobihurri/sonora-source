package io.sonora.feature.library.ui

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.library.AlbumSummary
import io.sonora.core.domain.library.AudioQuality
import io.sonora.core.domain.library.ReleaseDate
import io.sonora.feature.library.R
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Text of the album page: the metadata line under the artist and the footer under the songs. */
internal object AlbumDetailText {
    private const val SEPARATOR = " · "

    /** `FLAC · 2020 · 24 bit/96 kHz`: the parts that are known, `null` when none is. */
    fun metadataLine(summary: AlbumSummary, strings: Strings): String? = listOfNotNull(
        summary.format,
        summary.year?.toString(),
        summary.quality?.let { quality(it, strings) },
    ).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    /** `Rock · 2020` for the header beside the cover on a large screen: the genre and year that are known, `null` when none is. */
    fun genreYearLine(genre: String?, year: Int?): String? =
        listOfNotNull(genre, year?.toString()).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    /** `FLAC · 24 bit/96 kHz`: the metadata line without the year, which [genreYearLine] carries. */
    fun technicalLine(summary: AlbumSummary, strings: Strings): String? = listOfNotNull(
        summary.format,
        summary.quality?.let { quality(it, strings) },
    ).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    fun quality(quality: AudioQuality, strings: Strings): String = when (quality) {
        AudioQuality.Lossless -> strings.get(R.string.album_quality_lossless)
        is AudioQuality.HiRes -> strings.get(
            R.string.album_quality_hires,
            quality.bits,
            LocaleFormats.decimal(quality.sampleRateHz / 1000.0, 1, strings.locale),
        )
        is AudioQuality.Bitrate -> strings.get(
            if (quality.approximate) R.string.album_bitrate_approximate else R.string.album_bitrate,
            quality.kbps,
        )
    }

    /** Release date (full when known, else the year), song count and length, then the copyright when tagged. */
    fun footer(summary: AlbumSummary, strings: Strings): List<String> = listOfNotNull(
        summary.releaseDate?.let { releaseDate(it, strings.locale) },
        strings.get(
            R.string.playlist_summary,
            strings.plural(R.plurals.playlist_song_count, summary.songCount, summary.songCount),
            length(summary.durationMs, strings),
        ),
        summary.copyright,
    )

    /** `December 10, 2020` (the language's long date) for a full date; the year otherwise. */
    fun releaseDate(date: ReleaseDate, locale: Locale): String {
        if (date.month == null || date.day == null) return date.year.toString()
        return try {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
                .format(LocalDate.of(date.year, date.month!!, date.day!!))
        } catch (_: DateTimeException) {
            date.year.toString() // e.g. 31 February
        }
    }

    /** `4 minutes`, `1 hour`, `1 hour, 5 minutes`. */
    fun length(durationMs: Long, strings: Strings): String {
        val minutes = AlbumSummary.minutes(durationMs)
        val hours = minutes / 60
        val rest = minutes % 60
        val minutesText = strings.plural(R.plurals.playlist_minutes, rest, rest)
        if (hours == 0) return minutesText
        val hoursText = strings.plural(R.plurals.playlist_hours, hours, hours)
        return if (rest == 0) hoursText else strings.get(R.string.playlist_hours_minutes, hoursText, minutesText)
    }
}
