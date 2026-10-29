package io.sonora.feature.settings.duplicates

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.library.duplicates.DuplicateCopy
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateKind
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.feature.settings.R
import java.time.ZoneId

/** One field of the comparison, a value per copy; [differs] when the copies do not all share it. */
internal data class ComparisonRow(val label: String, val values: List<String>, val differs: Boolean)

/** The words of Settings › Duplicates, built from the copy in [Strings] so they are tested on the JVM. */
internal object DuplicatesText {

    /** How many copies a group has and why they are duplicates: "2 copies · Same song". */
    fun line(group: DuplicateGroup, strings: Strings): String = strings.get(
        R.string.duplicates_line,
        strings.plural(R.plurals.duplicates_copies, group.copies.size, group.copies.size),
        strings.get(if (group.kind == DuplicateKind.IDENTICAL) R.string.duplicates_kind_identical else R.string.duplicates_kind_same_song),
    )

    /** What the scan is doing or found; `null` before the first scan. */
    fun status(state: DuplicateScanState, strings: Strings): String? = when (state) {
        DuplicateScanState.NotScanned -> null
        is DuplicateScanState.Scanning -> if (state.total > 0) {
            strings.get(
                R.string.duplicates_checking,
                LocaleFormats.integer(state.checked, strings.locale),
                LocaleFormats.integer(state.total, strings.locale),
            )
        } else {
            strings.get(R.string.duplicates_looking)
        }
        is DuplicateScanState.Found -> if (state.groups.isEmpty()) {
            strings.get(R.string.duplicates_none)
        } else {
            strings.plural(R.plurals.duplicates_found, state.groups.size, state.groups.size)
        }
        DuplicateScanState.Failed -> strings.get(R.string.duplicates_failed)
    }

    /** Every compared field, a value per copy in the group's order (best first). */
    fun comparison(group: DuplicateGroup, zone: ZoneId, strings: Strings): List<ComparisonRow> {
        val unknown = strings.get(R.string.duplicates_value_unknown)
        val locale = strings.locale
        fun row(label: Int, value: (DuplicateCopy) -> String?): ComparisonRow {
            val values = group.copies.map { value(it) ?: unknown }
            return ComparisonRow(strings.get(label), values, differs = values.distinct().size > 1)
        }
        return listOf(
            row(R.string.duplicates_field_format) { it.format },
            row(R.string.duplicates_field_bitrate) { copy ->
                copy.bitrateKbps?.takeIf { it > 0 }?.let { strings.get(R.string.duplicates_value_kbps, LocaleFormats.integer(it, locale)) }
            },
            row(R.string.duplicates_field_sample_rate) { copy ->
                copy.sampleRateHz?.takeIf { it > 0 }?.let {
                    strings.get(R.string.duplicates_value_khz, LocaleFormats.decimal(it / 1_000.0, 1, locale))
                }
            },
            row(R.string.duplicates_field_bit_depth) { copy ->
                copy.bitsPerSample?.takeIf { it > 0 }?.let { strings.get(R.string.duplicates_value_bits, it) }
            },
            row(R.string.duplicates_field_channels) { copy ->
                when (val channels = copy.channelCount) {
                    null, 0 -> null
                    1 -> strings.get(R.string.duplicates_value_mono)
                    2 -> strings.get(R.string.duplicates_value_stereo)
                    else -> strings.plural(R.plurals.duplicates_value_channels, channels, channels)
                }
            },
            row(R.string.duplicates_field_duration) { copy -> copy.durationMs.takeIf { it > 0 }?.let(LocaleFormats::duration) },
            row(R.string.duplicates_field_size) { LocaleFormats.fileSize(it.sizeBytes, strings) },
            row(R.string.duplicates_field_folder) { it.folderPath },
            row(R.string.duplicates_field_file) { it.fileName },
            row(R.string.duplicates_field_tags) { strings.get(R.string.duplicates_value_tags, it.tagsFilled, DuplicateCopy.TAG_FIELDS) },
            row(R.string.duplicates_field_artwork) {
                strings.get(if (it.hasArtwork) R.string.duplicates_value_yes else R.string.duplicates_value_no)
            },
            row(R.string.duplicates_field_added) { copy ->
                copy.dateAddedSec.takeIf { it > 0 }?.let { LocaleFormats.dateTime(it * 1_000, zone, locale) }
            },
        )
    }

    fun notice(notice: DuplicatesNotice, strings: Strings): Pair<String, String> = when (notice) {
        is DuplicatesNotice.Deleted -> strings.get(R.string.duplicates_deleted_title) to
            strings.plural(R.plurals.duplicates_deleted_message, notice.copies, notice.copies)
        is DuplicatesNotice.Incomplete -> strings.get(R.string.duplicates_incomplete_title) to
            strings.plural(R.plurals.duplicates_incomplete_message, notice.failed, notice.failed)
        DuplicatesNotice.StorageRefused -> strings.get(R.string.duplicates_refused_title) to strings.get(R.string.duplicates_refused_message)
        DuplicatesNotice.Failed -> strings.get(R.string.duplicates_error_title) to strings.get(R.string.duplicates_error_message)
    }
}
