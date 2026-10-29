package io.sonora.feature.nowplaying

import androidx.annotation.StringRes
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.EmbeddedArtworkInfo
import io.sonora.core.domain.model.PcmEncoding
import io.sonora.core.domain.model.SampleFormat
import io.sonora.core.domain.model.TagInfo
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import java.time.ZoneId

/** One label/value line of the File Info sheet; [copyable] values can be selected and copied. */
data class InfoRow(val label: String, val value: String, val copyable: Boolean = false)

/** A grouped section of the File Info sheet, with an optional note under it. */
data class InfoSection(val title: String, val rows: List<InfoRow>, val footer: String? = null)

/** Copy and number formatting of the File Info sheet from string resources, kept pure so it is unit-tested. */
object FileInfoText {
    /**
     * Sections for [info]: Location, File, Audio, Playback (only with a [decoded] format) and Metadata. Empty values
     * are left out, and so are sections left without rows (Metadata stays, with a note, when the tags are unreadable or
     * there are none). Numbers and dates follow the language of [strings].
     */
    fun sections(
        info: TrackFileInfo,
        decoded: DecodedAudioFormat?,
        strings: Strings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<InfoSection> {
        val text = Builder(strings)
        val metadata = strings.get(R.string.file_info_section_metadata)
        return listOfNotNull(
            text.section(
                R.string.file_info_section_location,
                text.row(R.string.file_info_library_folder, info.location.libraryFolder),
                text.row(R.string.file_info_folder, info.location.relativePath),
                text.row(R.string.file_info_file_name, info.location.fileName),
                text.row(R.string.file_info_uri, info.location.contentUri)?.copy(copyable = true),
            ),
            text.section(
                R.string.file_info_section_file,
                text.row(R.string.file_info_format, info.file.container),
                text.row(R.string.file_info_size, info.file.sizeBytes?.let { LocaleFormats.fileSize(it, strings) }),
                text.row(R.string.file_info_modified, info.file.lastModifiedMs?.let { LocaleFormats.dateTime(it, zone, strings.locale) }),
            ),
            text.section(R.string.file_info_section_audio, *audioRows(info.audio, text)),
            decoded?.let {
                text.section(
                    R.string.file_info_section_playback,
                    *playbackRows(it, text),
                    footer = strings.get(R.string.file_info_playback_note),
                )
            },
            when (val tags = info.tags) {
                null -> InfoSection(metadata, emptyList(), footer = strings.get(R.string.file_info_tags_unavailable))
                else -> text.section(R.string.file_info_section_metadata, *metadataRows(tags, text))
                    ?: InfoSection(metadata, emptyList(), footer = strings.get(R.string.file_info_no_tags))
            },
        )
    }

    private fun audioRows(audio: AudioStreamInfo, text: Builder): Array<InfoRow?> {
        val strings = text.strings
        return arrayOf(
            text.row(R.string.file_info_codec, audio.codec),
            text.row(R.string.file_info_encoder, audio.encoder),
            text.row(R.string.file_info_sample_rate, audio.sampleRateHz?.let { sampleRate(it, strings) }),
            text.row(R.string.file_info_bit_depth, audio.sampleFormat?.let { sampleFormat(it, strings) }),
            text.row(R.string.file_info_channels, audio.channelCount?.let { channels(it, strings) }),
            text.row(R.string.file_info_bitrate, audio.bitrateKbps?.let { bitrate(it, audio.variableBitrate, strings) }),
            text.row(R.string.file_info_duration, audio.durationMs?.let(LocaleFormats::duration)),
        )
    }

    private fun playbackRows(decoded: DecodedAudioFormat, text: Builder): Array<InfoRow?> {
        val strings = text.strings
        val input = decoded.inputEncoding?.let { "PCM ${pcm(it, strings)}" } ?: decoded.inputMimeType?.let(::codecOfMimeType)
        val output = decoded.outputEncoding?.let { pcm(it, strings) }
        fun String.withRate(hz: Int?) = if (hz == null || hz <= 0) this else "$this · ${sampleRate(hz, strings)}"
        return arrayOf(
            text.row(R.string.file_info_decoder_input, input?.withRate(decoded.inputSampleRateHz)),
            text.row(R.string.file_info_output, output?.withRate(decoded.outputSampleRateHz)),
            text.row(R.string.file_info_channels, decoded.channelCount?.let { channels(it, strings) }),
        )
    }

    private fun metadataRows(tags: TagInfo, text: Builder): Array<InfoRow?> = arrayOf(
        text.row(R.string.file_info_title, tags.title),
        text.row(R.string.file_info_artist, tags.artist),
        text.row(R.string.file_info_album_artist, tags.albumArtist),
        text.row(R.string.file_info_album, tags.album),
        text.row(R.string.file_info_disc, position(tags.discNumber, tags.discTotal, text.strings)),
        text.row(R.string.file_info_track, position(tags.trackNumber, tags.trackTotal, text.strings)),
        text.row(R.string.file_info_year, tags.date),
        text.row(R.string.file_info_genre, tags.genre),
        text.row(R.string.file_info_composer, tags.composer),
        text.row(R.string.file_info_comment, tags.comment),
        // Tag field names (ReplayGain and the rest) are shown as stored, in no language.
        *tags.replayGain.map { rawRow(it.key, it.value) }.toTypedArray(),
        text.row(R.string.file_info_artwork, tags.artwork?.let { artwork(it, text.strings) }),
        *tags.otherFields.map { rawRow(it.key, it.value) }.toTypedArray(),
    )

    /** Builds titled sections and labelled rows from string resources. */
    private class Builder(val strings: Strings) {
        fun section(@StringRes title: Int, vararg rows: InfoRow?, footer: String? = null): InfoSection? =
            rows.filterNotNull().takeIf { it.isNotEmpty() }?.let { InfoSection(strings.get(title), it, footer) }

        fun row(@StringRes label: Int, value: String?): InfoRow? = rawRow(strings.get(label), value)
    }

    private fun rawRow(label: String, value: String?): InfoRow? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { InfoRow(label, it) }

    /** The line under the song's title in the panel header: `Artist · Album`, without blank parts; `null` if none. */
    fun headerSubtitle(track: Track): String? =
        listOf(track.artist, track.album).map(String::trim).filter(String::isNotEmpty).joinToString(" · ").ifEmpty { null }

    /** `44.1 kHz`, `48 kHz`, `22.05 kHz`, with the language's decimal separator. */
    fun sampleRate(hz: Int, strings: Strings): String =
        strings.get(R.string.file_info_khz, LocaleFormats.decimal(hz / 1000.0, maxFractionDigits = 3, locale = strings.locale))

    fun sampleFormat(format: SampleFormat, strings: Strings): String = when (format) {
        is SampleFormat.Integer -> strings.get(R.string.file_info_bits, format.bits)
        is SampleFormat.FloatingPoint -> strings.get(R.string.file_info_bits_float, format.bits)
        SampleFormat.Lossy -> strings.get(R.string.file_info_lossy)
    }

    fun channels(count: Int, strings: Strings): String = when (count) {
        1 -> strings.get(R.string.file_info_mono)
        2 -> strings.get(R.string.file_info_stereo)
        else -> strings.plural(R.plurals.file_info_channel_count, count, count)
    }

    fun bitrate(kbps: Int, variable: Boolean, strings: Strings): String {
        val rate = strings.get(R.string.file_info_kbps, LocaleFormats.integer(kbps, strings.locale))
        return if (variable) strings.get(R.string.file_info_vbr, rate) else rate
    }

    /** `3 of 12`, `3`, or `null` without a number. */
    fun position(number: Int?, total: Int?, strings: Strings): String? = when {
        number == null -> null
        total == null -> "$number"
        else -> strings.get(R.string.file_info_position, number, total)
    }

    fun pcm(encoding: PcmEncoding, strings: Strings): String {
        val kind = strings.get(if (encoding.isFloat) R.string.file_info_bits_float else R.string.file_info_bits_integer, encoding.bits)
        return if (encoding.isBigEndian) strings.get(R.string.file_info_big_endian, kind) else kind
    }

    private fun artwork(art: EmbeddedArtworkInfo, strings: Strings): String = listOfNotNull(
        // No-break spaces: "700 × 700" stays on one line, as does each number with its unit.
        if (art.width != null && art.height != null) "${art.width}\u00A0×\u00A0${art.height}" else null,
        art.mimeType?.substringAfter('/')?.uppercase()?.takeIf { it.isNotBlank() },
        LocaleFormats.fileSize(art.sizeBytes, strings),
    ).joinToString(" · ")

    private fun codecOfMimeType(mimeType: String): String = when (mimeType.lowercase()) {
        "audio/raw" -> "PCM"
        "audio/flac" -> "FLAC"
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/alac" -> "ALAC"
        "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"
        else -> mimeType
    }
}
