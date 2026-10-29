package io.sonora.feature.cdjexport

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.rekordbox.manifest.ManifestFormatException
import io.sonora.feature.cdjexport.export.EjectState
import io.sonora.feature.cdjexport.export.ExportJob
import io.sonora.feature.cdjexport.export.ExportPhase
import io.sonora.feature.cdjexport.export.ExportResult
import io.sonora.feature.cdjexport.export.StickLibrary
import io.sonora.feature.cdjexport.export.TrackFailure
import java.util.Locale

// Copy of the "Export to Rekordbox" section and its notification from string resources, kept pure so it is
// unit-tested in every language. File, stick and playlist names are shown as they are.

/** What [profile] takes, for messages: "AIFF 16-bit up to 48 kHz". */
internal fun profileSummary(profile: DeviceProfile, strings: Strings): String = strings.get(
    R.string.cdj_profile_summary,
    profile.acceptedContainers.joinToString("/") { containerName(it, strings) },
    profile.acceptedBitDepths.sorted().joinToString("/") { strings.get(R.string.cdj_bits, it) },
    khz(profile.maxSampleRateHz, strings.locale),
)

/** The intro under the section header. */
internal fun exportIntro(profile: DeviceProfile, strings: Strings): String =
    strings.get(R.string.cdj_export_intro, profile.displayName, profileSummary(profile, strings))

/** Under the intro: Sonora is independent of the players' makers and names their trademarks only for compatibility. */
internal fun notAffiliatedNotice(strings: Strings): String = strings.get(R.string.cdj_not_affiliated)

internal fun containerName(container: AudioContainer, strings: Strings): String = when (container) {
    AudioContainer.M4A -> strings.get(R.string.cdj_container_m4a)
    AudioContainer.UNKNOWN -> strings.get(R.string.cdj_container_unknown)
    else -> container.displayName
}

/** Why a picked file is not listed for export. */
internal fun RejectReason.message(profile: DeviceProfile, strings: Strings): String = when (this) {
    RejectReason.UnreadableFile -> strings.get(R.string.cdj_unreadable_file)
    RejectReason.UnreadableFolder -> strings.get(R.string.cdj_unreadable_folder)
    is RejectReason.Undecodable ->
        strings.get(R.string.cdj_undecodable, containerName(container, strings), profile.displayName)
}

/** "3 files will be converted to AIFF 16-bit up to 48 kHz on export." */
internal fun conversionNotice(count: Int, profile: DeviceProfile, strings: Strings): String =
    strings.plural(R.plurals.cdj_will_convert, count, count, profileSummary(profile, strings))

/** A connected volume by its name and size: "SONORA, 16.0 GB" (the name alone when the size is unknown). */
internal fun volumeSummary(volume: RemovableVolume, strings: Strings): String = volume.totalBytes
    ?.let { strings.get(R.string.cdj_stick_summary, volume.name, formatCapacity(it, strings.locale)) }
    ?: volume.name

/** Where "Eject stick" is, for the line under its button; null while no eject was asked for. */
internal fun ejectMessage(state: EjectState, strings: Strings): String? = when (state) {
    EjectState.Idle -> null
    is EjectState.OpenScreen -> strings.get(R.string.cdj_eject_opening)
    is EjectState.Waiting -> when {
        state.screen == null -> strings.get(R.string.cdj_eject_no_screen)
        state.target != null -> strings.get(R.string.cdj_eject_waiting, state.target.name)
        else -> strings.get(R.string.cdj_eject_waiting_unnamed)
    }
    is EjectState.SafeToRemove ->
        state.name?.let { strings.get(R.string.cdj_eject_safe, it) } ?: strings.get(R.string.cdj_eject_safe_unnamed)
}

internal fun StickProblem.message(strings: Strings): String = strings.get(
    when (this) {
        StickProblem.NO_ACCESS -> R.string.cdj_stick_no_access
        StickProblem.NOT_A_STICK -> R.string.cdj_not_a_stick
        StickProblem.UNUSABLE -> R.string.cdj_stick_unusable
    },
)

internal fun ExportNotice.message(strings: Strings): String = when (this) {
    ExportNotice.ANOTHER_EXPORT_RUNNING -> strings.get(R.string.cdj_another_export_running)
}

/** "Not enough space: the export needs about 2.0 GB and the stick has 1.0 GB free." */
internal fun spaceProblemText(neededBytes: Long, freeBytes: Long, strings: Strings): String = strings.get(
    R.string.cdj_space_problem,
    formatCapacity(neededBytes, strings.locale),
    formatCapacity(freeBytes, strings.locale),
)

/** "AIFF 16-bit · 44.1 kHz · 35.2 MB". */
internal fun trackDetail(file: SourceFile, strings: Strings): String = listOfNotNull(
    containerName(file.audio.container, strings) +
        (file.audio.bitsPerSample?.let { " " + strings.get(R.string.cdj_bits, it) } ?: ""),
    file.audio.sampleRateHz?.let { strings.get(R.string.cdj_khz, khz(it, strings.locale)) },
    formatCapacity(file.sizeBytes, strings.locale),
).joinToString(" · ")

internal fun StickLibrary.description(strings: Strings): String = when (this) {
    StickLibrary.Empty -> strings.get(R.string.cdj_library_empty)
    is StickLibrary.Sonora -> strings.plural(R.plurals.cdj_library_sonora, trackCount, trackCount)
    StickLibrary.Foreign -> strings.get(R.string.cdj_library_foreign)
    is StickLibrary.Unreadable -> if (cause is ManifestFormatException.NewerVersion) {
        strings.get(R.string.cdj_library_newer)
    } else {
        strings.get(R.string.cdj_library_damaged)
    }
}

/** The text of the replace confirmation. */
internal fun StickLibrary.replaceWarning(strings: Strings): String = strings.get(
    if (this is StickLibrary.Unreadable) R.string.cdj_replace_warning_unreadable else R.string.cdj_replace_warning_foreign,
)

internal fun exportProgressText(step: ExportStep.Running, strings: Strings): String {
    val progress = step.progress ?: return strings.get(R.string.cdj_starting)
    if (step.cancelling) return strings.get(R.string.cdj_cancelling)
    val position = progress.trackIndex + 1
    val count = progress.trackCount
    return when (progress.phase) {
        ExportPhase.CONVERTING -> strings.get(R.string.cdj_progress_converting, position, count, progress.trackName)
        ExportPhase.PREPARING -> strings.get(R.string.cdj_progress_preparing, position, count, progress.trackName)
        ExportPhase.ANALYSING -> strings.get(R.string.cdj_progress_analysing, position, count, progress.trackName)
        ExportPhase.COPYING -> strings.get(R.string.cdj_progress_copying, position, count, progress.trackName)
        ExportPhase.WRITING_LIBRARY -> strings.get(R.string.cdj_progress_writing_library)
    }
}

/** The export notification's title: the stick it exports to. */
internal fun exportNotificationTitle(job: ExportJob, strings: Strings): String =
    (job as? ExportJob.Running)?.let { strings.get(R.string.cdj_notification_title, it.stickName) }
        ?: strings.get(R.string.cdj_notification_title_default)

/** The export notification's line: the track it is on, or what happens before and after. */
internal fun exportNotificationText(job: ExportJob, strings: Strings): String {
    val running = job as? ExportJob.Running ?: return strings.get(R.string.cdj_finishing)
    val progress = running.progress
    return when {
        running.cancelling -> strings.get(R.string.cdj_cancelling)
        progress == null -> strings.get(R.string.cdj_starting)
        else -> strings.get(
            R.string.cdj_notification_track,
            minOf(progress.trackIndex + 1, progress.trackCount),
            progress.trackCount,
            progress.trackName.ifEmpty { strings.get(R.string.cdj_notification_writing_library) },
        )
    }
}

internal fun TrackFailure.message(strings: Strings): String = strings.get(
    when (this) {
        TrackFailure.UNREADABLE -> R.string.cdj_failure_unreadable
        TrackFailure.NOT_ANALYSABLE -> R.string.cdj_failure_not_analysable
        TrackFailure.NOT_CONVERTIBLE -> R.string.cdj_failure_not_convertible
        TrackFailure.CHANGED_DURING_EXPORT -> R.string.cdj_failure_changed
        TrackFailure.UNEXPECTED -> R.string.cdj_failure_unexpected
    },
)

/** The result screen: a [title], a [message] and the files that were skipped or failed, with why. */
data class ExportSummary(
    val title: String,
    val message: String,
    val isError: Boolean,
    val skipped: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

internal fun summarize(result: ExportResult, strings: Strings): ExportSummary = when (result) {
    is ExportResult.Completed -> completed(result, strings)
    is ExportResult.NotEnoughSpace -> ExportSummary(
        strings.get(R.string.cdj_summary_no_space_title),
        strings.get(
            R.string.cdj_summary_no_space_message,
            formatCapacity(result.neededBytes, strings.locale),
            formatCapacity(result.freeBytes, strings.locale),
        ),
        isError = true,
    )
    is ExportResult.NeedsConfirmation -> ExportSummary(
        strings.get(R.string.cdj_summary_nothing),
        strings.get(R.string.cdj_summary_needs_confirmation),
        isError = true,
    )
    is ExportResult.Failed -> ExportSummary(
        strings.get(R.string.cdj_summary_failed),
        strings.get(
            when {
                result.libraryChanged -> R.string.cdj_summary_failed_library_changed
                result.cause is StickIoException -> R.string.cdj_summary_failed_stick
                else -> R.string.cdj_summary_failed_unexpected
            },
        ),
        isError = true,
    )
    ExportResult.Cancelled -> ExportSummary(
        strings.get(R.string.cdj_summary_cancelled),
        strings.get(R.string.cdj_summary_cancelled_message),
        isError = false,
    )
}

private fun completed(result: ExportResult.Completed, strings: Strings): ExportSummary {
    val added = result.exported.size
    val held = result.libraryTrackCount
    val title = when {
        added > 0 && result.failed.isEmpty() -> R.string.cdj_summary_finished
        added > 0 -> R.string.cdj_summary_finished_problems
        result.libraryWritten && result.playlist != null ->
            if (result.playlistCreated) R.string.cdj_summary_playlist_added else R.string.cdj_summary_playlist_updated
        result.libraryWritten -> R.string.cdj_summary_library_completed
        else -> R.string.cdj_summary_nothing
    }
    val sentences = when {
        // Nothing new, but an earlier export's interrupted library was rewritten.
        added == 0 && result.playlist == null -> listOf(strings.plural(R.plurals.cdj_summary_completed_library, held, held))
        // Only tracks already on the stick, joining the playlist.
        added == 0 -> listOf(
            strings.plural(R.plurals.cdj_summary_joined, result.playlistAdded, result.playlistAdded, result.playlist!!),
            strings.plural(R.plurals.cdj_summary_holds, held, held),
        )
        else -> listOf(
            result.playlist?.let { strings.plural(R.plurals.cdj_summary_added_to_playlist, added, added, it) }
                ?: strings.plural(R.plurals.cdj_summary_added, added, added),
            strings.plural(R.plurals.cdj_summary_now_holds, held, held),
        )
    }
    val message = if (result.libraryWritten) {
        (sentences + strings.get(R.string.cdj_summary_eject)).joinToString(" ")
    } else {
        strings.get(R.string.cdj_summary_nothing_added)
    }
    return ExportSummary(
        title = strings.get(title),
        message = message,
        isError = added == 0 && !result.libraryWritten,
        skipped = result.duplicates.map { strings.get(R.string.cdj_summary_duplicate, it.name) },
        failed = result.failed.map { strings.get(R.string.cdj_name_reason, it.name, it.reason.message(strings)) },
    )
}

/** The playlist name offered by default: "Sonora 2026-10-03". */
internal fun defaultPlaylistName(date: java.time.LocalDate): String = "Sonora $date"

/** Kilohertz without trailing zeros, in the language's numbers: "44.1", "48", "22.05". */
private fun khz(hz: Int, locale: Locale): String = LocaleFormats.decimal(hz / 1000.0, 3, locale)
