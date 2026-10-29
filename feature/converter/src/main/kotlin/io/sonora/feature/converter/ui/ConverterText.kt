package io.sonora.feature.converter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchStatus
import io.sonora.feature.converter.ConverterNotice
import io.sonora.feature.converter.ConverterRules
import io.sonora.feature.converter.FormatChoice
import io.sonora.feature.converter.R
import java.text.NumberFormat
import java.time.ZoneId

// User-facing copy of the converter, from string resources.

@Composable
internal fun modeLabel(mode: PerformanceMode): String = stringResource(
    when (mode) {
        PerformanceMode.LOW_RESOURCES -> R.string.converter_mode_low
        PerformanceMode.NORMAL -> R.string.converter_mode_normal
        PerformanceMode.HIGH_PERFORMANCE -> R.string.converter_mode_high
    },
)

@Composable
internal fun modeDescription(mode: PerformanceMode): String = stringResource(
    when (mode) {
        PerformanceMode.LOW_RESOURCES -> R.string.converter_mode_low_description
        PerformanceMode.NORMAL -> R.string.converter_mode_normal_description
        PerformanceMode.HIGH_PERFORMANCE -> R.string.converter_mode_high_description
    },
)

@Composable
internal fun sectionTitle(section: HistorySection): String = stringResource(
    when (section) {
        HistorySection.TODAY -> R.string.converter_section_today
        HistorySection.YESTERDAY -> R.string.converter_section_yesterday
        HistorySection.LAST_7_DAYS -> R.string.converter_section_last_7_days
        HistorySection.LAST_30_DAYS -> R.string.converter_section_last_30_days
        HistorySection.OLDER -> R.string.converter_section_older
    },
)

/** A format's name in the picker; format names are not translated. */
internal fun formatChoiceName(choice: FormatChoice): String =
    choice.format?.displayName ?: ConversionProfiles.XDJ_700.displayName

/** What a target writes, in the app's language: "FLAC · 24-bit · Up to 48 kHz", "MP3 · VBR V2". */
@Composable
internal fun targetSummary(target: OutputTarget): String = when (target) {
    OutputTarget.Xdj700 -> stringResource(R.string.converter_profile_summary_xdj_700)
    is OutputTarget.Lossless -> stringResource(
        R.string.converter_format_summary_lossless,
        target.format.displayName,
        target.bitDepth,
        target.sampleRateCapHz?.let { stringResource(R.string.converter_format_summary_rate_cap, kilohertz(it)) }
            ?: stringResource(R.string.converter_format_summary_rate_keep),
    )
    is OutputTarget.Aac -> stringResource(R.string.converter_format_summary_bitrate, target.format.displayName, target.bitrateKbps)
    is OutputTarget.Opus -> stringResource(R.string.converter_format_summary_bitrate, target.format.displayName, target.bitrateKbps)
    is OutputTarget.Mp3 -> when (val mode = target.mode) {
        is Mp3Mode.Cbr -> stringResource(R.string.converter_format_summary_mp3_cbr, mode.bitrateKbps)
        is Mp3Mode.Vbr -> stringResource(R.string.converter_format_summary_mp3_vbr, mode.quality)
    }
}

/** A target in one line for rows: the XDJ-700 by its name, any other by what it writes. */
@Composable
internal fun targetLabel(target: OutputTarget): String =
    if (target == OutputTarget.Xdj700) ConversionProfiles.XDJ_700.displayName else targetSummary(target)

/** The output a stored profile id names ([targetLabel]); the watch's and unknown ones by [ConverterRules.profileName]. */
@Composable
internal fun profileLabel(profileId: String): String =
    OutputTargets.parse(profileId)?.let { targetLabel(it) } ?: ConverterRules.profileName(profileId)

/** "44.1", "48" in the app's language ("44,1" in Castellano and Euskara). */
@Composable
internal fun kilohertz(hz: Int): String =
    NumberFormat.getNumberInstance(currentLocale()).apply { maximumFractionDigits = 1 }.format(hz / 1_000.0)

/** Short status for history rows and the batch detail. */
@Composable
internal fun statusLabel(status: BatchStatus): String = stringResource(
    when (status) {
        BatchStatus.QUEUED -> R.string.converter_status_queued
        BatchStatus.RUNNING -> R.string.converter_status_running
        BatchStatus.COMPLETED -> R.string.converter_status_completed
        BatchStatus.CANCELLED -> R.string.converter_status_cancelled
        BatchStatus.TIMED_OUT -> R.string.converter_status_timed_out
        BatchStatus.FAILED -> R.string.converter_status_failed
    },
)

/** Title of a finished batch's summary. */
@Composable
internal fun summaryTitle(status: BatchStatus): String = stringResource(
    when (status) {
        BatchStatus.CANCELLED -> R.string.converter_batch_cancelled
        BatchStatus.TIMED_OUT -> R.string.converter_batch_timed_out
        BatchStatus.FAILED -> R.string.converter_batch_failed
        else -> R.string.converter_batch_completed
    },
)

@Composable
internal fun itemStateLabel(state: BatchItemState): String = stringResource(
    when (state) {
        BatchItemState.PENDING -> R.string.converter_item_pending
        BatchItemState.RUNNING -> R.string.converter_item_running
        BatchItemState.DONE -> R.string.converter_item_done
        BatchItemState.FAILED -> R.string.converter_item_failed
        BatchItemState.SKIPPED -> R.string.converter_item_skipped
        BatchItemState.REPLACED_ORIGINAL -> R.string.converter_item_replaced
    },
)

/** "12 converted · 1 failed" (failures only when there are some). */
@Composable
internal fun countsLine(counts: BatchCounts): String {
    val converted = pluralStringResource(R.plurals.converter_converted_count, counts.converted, counts.converted)
    if (counts.failed == 0) return converted
    return "$converted · ${pluralStringResource(R.plurals.converter_failed_count, counts.failed, counts.failed)}"
}

/** When a batch ran: the time alone in the Today and Yesterday sections, else date and time, in the app's language. */
@Composable
internal fun batchTime(createdAtMs: Long, section: HistorySection? = null): String {
    val locale = currentLocale()
    val timeOnly = section == HistorySection.TODAY || section == HistorySection.YESTERDAY
    val zone = ZoneId.systemDefault()
    return if (timeOnly) LocaleFormats.time(createdAtMs, zone, locale) else LocaleFormats.dateTime(createdAtMs, zone, locale)
}

/** Title and message of an alert. */
internal fun noticeText(notice: ConverterNotice): Pair<Int, Int> = when (notice) {
    ConverterNotice.NO_OUTPUT_FOLDER -> R.string.converter_notice_no_output_title to R.string.converter_notice_no_output_message
    ConverterNotice.NO_AUDIO_FILES -> R.string.converter_notice_no_audio_title to R.string.converter_notice_no_audio_message
    ConverterNotice.START_FAILED -> R.string.converter_notice_start_failed_title to R.string.converter_notice_start_failed_message
    ConverterNotice.OUTPUT_FOLDER_FAILED ->
        R.string.converter_notice_output_failed_title to R.string.converter_notice_output_failed_message
    ConverterNotice.PICKER_MISSING ->
        R.string.converter_notice_picker_missing_title to R.string.converter_notice_picker_missing_message
    ConverterNotice.CANCEL_FAILED ->
        R.string.converter_notice_cancel_failed_title to R.string.converter_notice_cancel_failed_message
    ConverterNotice.DELETE_FAILED ->
        R.string.converter_notice_delete_failed_title to R.string.converter_notice_delete_failed_message
    ConverterNotice.PLAY_FAILED -> R.string.converter_notice_play_failed_title to R.string.converter_notice_play_failed_message
}
