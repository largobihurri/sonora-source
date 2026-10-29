package io.sonora.feature.settings

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.DestinationCoverage
import io.sonora.core.domain.archive.ImportOutcome

/** The words of the archive import, built from the copy in [Strings] so they are tested on the JVM. */
internal object ImportText {

    /** What [summary] holds: a Sonora song, album or playlist with its count, or a plain or protected ZIP. */
    fun summary(summary: ArchiveSummary, strings: Strings): String {
        val manifest = summary.manifest
            ?: return strings.get(if (summary.encrypted) R.string.import_zip_encrypted else R.string.import_zip)
        val count = strings.plural(R.plurals.import_song_count, manifest.entries.size, manifest.entries.size)
        return when (manifest.kind) {
            ArchiveKind.SONG -> strings.get(R.string.import_kind_song)
            ArchiveKind.ALBUM -> strings.get(R.string.import_kind_album, count)
            ArchiveKind.PLAYLIST -> strings.get(R.string.import_kind_playlist, count)
            ArchiveKind.SELECTION -> strings.get(R.string.import_kind_selection, count)
        }
    }

    /** Under the password field: why it is asked again, or where to get it. */
    fun passwordFooter(error: PasswordError?, strings: Strings): String = strings.get(
        when (error) {
            PasswordError.WRONG -> R.string.import_password_wrong
            PasswordError.NEEDED_AGAIN -> R.string.import_password_again
            null -> R.string.import_password_hint
        },
    )

    /** Under the destination: whether the music reaches the library from there. */
    fun destinationFooter(coverage: DestinationCoverage?, strings: Strings): String = strings.get(
        when (coverage) {
            null -> R.string.import_destination_none
            DestinationCoverage.IN_LIBRARY -> R.string.import_destination_in
            DestinationCoverage.JOINS_LIBRARY, DestinationCoverage.OUTSIDE_LIBRARY -> R.string.import_destination_joins
        },
    )

    /** The sentence a failed import shows. */
    fun failure(failure: ArchiveFailure, strings: Strings): String = when (failure) {
        ArchiveFailure.Damaged -> strings.get(R.string.import_error_damaged)
        ArchiveFailure.NoMusic -> strings.get(R.string.import_error_no_music)
        is ArchiveFailure.NotEnoughSpace -> strings.get(
            R.string.import_error_no_space,
            LocaleFormats.fileSize(failure.neededBytes, strings),
            LocaleFormats.fileSize(failure.availableBytes, strings),
        )
        is ArchiveFailure.UnreadableSource -> strings.get(R.string.import_error_unreadable)
        ArchiveFailure.DestinationUnwritable -> strings.get(R.string.import_error_unwritable)
        ArchiveFailure.WrongPassword, ArchiveFailure.PasswordRequired -> strings.get(R.string.import_error_password)
        ArchiveFailure.PasswordNeededAgain -> strings.get(R.string.import_password_again)
        ArchiveFailure.Unexpected -> strings.get(R.string.import_error_unexpected)
    }

    /**
     * The notes of a finished import: the playlist made, songs not found yet, the folder added (or another one picked,
     * or none: the songs are outside the library), files left out.
     */
    fun resultNotes(outcome: ImportOutcome, skipped: Int, strings: Strings, wrongFolder: Boolean = false): List<String> = buildList {
        outcome.playlistName?.let {
            add(strings.plural(R.plurals.import_done_playlist, outcome.playlistSongs, it, outcome.playlistSongs))
        }
        if (outcome.missing > 0) add(strings.plural(R.plurals.import_done_missing, outcome.missing, outcome.missing))
        if (wrongFolder) add(strings.get(R.string.import_done_wrong_folder))
        when (outcome.coverage) {
            DestinationCoverage.JOINS_LIBRARY -> add(strings.get(R.string.import_done_folder_added))
            DestinationCoverage.OUTSIDE_LIBRARY -> add(strings.get(R.string.import_done_outside))
            DestinationCoverage.IN_LIBRARY -> Unit
        }
        if (skipped > 0) add(strings.plural(R.plurals.import_done_skipped, skipped, skipped))
    }
}
