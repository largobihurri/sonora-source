package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveImportEntry
import java.time.ZoneOffset
import org.junit.Test

class ImportHistoryTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")

    @Test
    fun `a row says when the import was made and how many songs it brought`() {
        val entry = ArchiveImportEntry(1, "Mix.zip", 1_759_500_000_000, "tree", "folder", songCount = 12)

        val line = ImportHistoryText.line(entry, ZoneOffset.UTC, en)

        assertThat(line).endsWith(" · 12 songs")
        assertThat(line).contains("2025")
        assertThat(ImportHistoryText.line(entry.copy(songCount = 1), ZoneOffset.UTC, es)).endsWith(" · 1 canción")
    }

    @Test
    fun `each end of a deletion has its own words`() {
        assertThat(ImportHistoryText.notice(HistoryNotice.Deleted(3), en))
            .isEqualTo(NoticeText("Import Deleted", "Its folder is gone from your phone, and 3 songs from your library."))
        assertThat(ImportHistoryText.notice(HistoryNotice.Incomplete(deleted = 1, failed = 2), en).message)
            .isEqualTo("2 songs couldn’t be deleted. The import stays in the history so you can try again.")
        assertThat(ImportHistoryText.notice(HistoryNotice.Incomplete(deleted = 3, failed = 0), en).message)
            .isEqualTo("Its folder couldn’t be deleted. The import stays in the history so you can try again.")
        assertThat(ImportHistoryText.notice(HistoryNotice.StorageRefused, es).title).isEqualTo("No se puede eliminar aquí")
        assertThat(ImportHistoryText.notice(HistoryNotice.Failed, en).message).isEqualTo("Something went wrong. Try again.")
    }
}
