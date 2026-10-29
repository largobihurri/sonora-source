package io.sonora.feature.settings.backup

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.backup.RestorePreview
import io.sonora.core.domain.backup.RestoreReport
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.feature.settings.R
import java.time.ZoneId

/** The words of Settings › Backup & Restore, built from the copy in [Strings] so they are tested on the JVM. */
internal object BackupText {

    /** "Last backup: 4 Oct 2026, 18:30", or that there is none yet. */
    fun lastBackup(ms: Long?, zone: ZoneId, strings: Strings): String =
        ms?.let { strings.get(R.string.backup_last, LocaleFormats.dateTime(it, zone, strings.locale)) }
            ?: strings.get(R.string.backup_never)

    /** "2 playlists · 120 songs found · 4 not found". */
    fun preview(preview: RestorePreview, strings: Strings): String = strings.get(
        R.string.backup_preview_summary,
        strings.plural(R.plurals.backup_playlists, preview.playlists, preview.playlists),
        strings.plural(R.plurals.backup_songs_found, preview.songsMatched, preview.songsMatched),
        strings.plural(R.plurals.backup_songs_missing, preview.songsNotFound, preview.songsNotFound),
    )

    /** What a restore added, then how many songs it could not find (when any). */
    fun report(report: RestoreReport, strings: Strings): String {
        val added = strings.plural(R.plurals.backup_playlists_added, report.playlistsAdded, report.playlistsAdded)
        if (report.unmatched.isEmpty()) return added
        val count = report.unmatched.size
        return added + "\n\n" + strings.plural(R.plurals.backup_unmatched_header, count, count)
    }

    /** One song the restore could not find: "Artist — Title", the title alone, or "Unknown song". */
    fun song(song: SongIdentity, strings: Strings): String = when {
        song.title.isBlank() -> strings.get(R.string.backup_unknown_song)
        song.artist.isBlank() -> song.title
        else -> strings.get(R.string.backup_song_line, song.artist, song.title)
    }

    fun message(message: BackupMessage, strings: Strings): String = strings.get(
        when (message) {
            BackupMessage.SAVED -> R.string.backup_saved
            BackupMessage.WRITE_FAILED -> R.string.backup_write_failed
            BackupMessage.UNREADABLE -> R.string.backup_unreadable
            BackupMessage.OTHER_FORMAT -> R.string.backup_other_format
            BackupMessage.NEWER_VERSION -> R.string.backup_newer_version
            BackupMessage.MALFORMED -> R.string.backup_malformed
            BackupMessage.RESTORE_FAILED -> R.string.backup_restore_failed
            BackupMessage.NO_PICKER -> R.string.backup_no_picker
        },
    )
}
