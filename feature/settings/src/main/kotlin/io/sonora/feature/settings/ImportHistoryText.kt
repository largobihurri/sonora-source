package io.sonora.feature.settings

import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.archive.ArchiveImportEntry
import java.time.ZoneId

/** The words of Settings › Import History, built from the copy in [Strings] so they are tested on the JVM. */
internal object ImportHistoryText {

    /** When [entry] was imported, in [zone], and how many songs it brought. */
    fun line(entry: ArchiveImportEntry, zone: ZoneId, strings: Strings): String = strings.get(
        R.string.import_history_line,
        LocaleFormats.dateTime(entry.importedAtMs, zone, strings.locale),
        strings.plural(R.plurals.import_song_count, entry.songCount, entry.songCount),
    )

    fun notice(notice: HistoryNotice, strings: Strings): NoticeText = when (notice) {
        is HistoryNotice.Deleted -> NoticeText(
            strings.get(R.string.import_history_deleted_title),
            strings.plural(R.plurals.import_history_deleted_message, notice.songs, notice.songs),
        )
        is HistoryNotice.Incomplete -> NoticeText(
            strings.get(R.string.import_history_incomplete_title),
            if (notice.failed > 0) {
                strings.plural(R.plurals.import_history_incomplete_songs, notice.failed, notice.failed)
            } else {
                strings.get(R.string.import_history_incomplete_folder)
            },
        )
        HistoryNotice.StorageRefused -> NoticeText(
            strings.get(R.string.import_history_refused_title),
            strings.get(R.string.import_history_refused_message),
        )
        HistoryNotice.Failed -> NoticeText(
            strings.get(R.string.import_history_failed_title),
            strings.get(R.string.import_history_failed_message),
        )
    }
}
