package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.DestinationCoverage
import io.sonora.core.domain.archive.ImportOutcome
import org.junit.Test

class ImportTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val entries = List(12) { ArchiveEntry("$it.flac", "Song $it", "Band", "Road Trip") }
    private val playlist = ArchiveManifest(ArchiveKind.PLAYLIST, "Road Trip", entries)

    @Test
    fun `the summary says what the archive holds`() {
        assertThat(ImportText.summary(ArchiveSummary(false, playlist), en)).isEqualTo("Sonora playlist · 12 songs")
        assertThat(ImportText.summary(ArchiveSummary(false, playlist.copy(kind = ArchiveKind.ALBUM, entries = entries.take(1))), en))
            .isEqualTo("Sonora album · 1 song")
        assertThat(ImportText.summary(ArchiveSummary(false, playlist.copy(kind = ArchiveKind.SELECTION)), en))
            .isEqualTo("Songs picked in Sonora · 12 songs")
        assertThat(ImportText.summary(ArchiveSummary(true, null), en)).isEqualTo("Password-protected ZIP archive")
        assertThat(ImportText.summary(ArchiveSummary(false, null), es)).isEqualTo("Archivo ZIP")
    }

    @Test
    fun `a wrong or lost password is said plainly`() {
        assertThat(ImportText.passwordFooter(PasswordError.WRONG, en)).isEqualTo("The password is not correct. Try again.")
        assertThat(ImportText.passwordFooter(PasswordError.NEEDED_AGAIN, en)).contains("Enter the password again")
        assertThat(ImportText.passwordFooter(null, en)).isEqualTo("Ask whoever sent the archive for its password.")
    }

    @Test
    fun `each failure has its own sentence`() {
        assertThat(ImportText.failure(ArchiveFailure.Damaged, en)).isEqualTo("The archive is damaged or is not a ZIP file.")
        assertThat(ImportText.failure(ArchiveFailure.NoMusic, en)).isEqualTo("There is no music in the archive.")
        assertThat(ImportText.failure(ArchiveFailure.NotEnoughSpace(3L shl 20, 1L shl 20), en))
            .isEqualTo("Not enough space: 3.0\u00A0MiB needed, 1.0\u00A0MiB free.")
        assertThat(ImportText.failure(ArchiveFailure.DestinationUnwritable, es)).contains("carpeta")
        assertThat(ImportText.failure(ArchiveFailure.Unexpected, en)).isEqualTo("Something went wrong. Try again.")
    }

    @Test
    fun `the destination note says whether the folder joins the library`() {
        assertThat(ImportText.destinationFooter(DestinationCoverage.JOINS_LIBRARY, en)).contains("allow its new folder")
        assertThat(ImportText.destinationFooter(DestinationCoverage.IN_LIBRARY, en)).contains("appear in your library")
        assertThat(ImportText.destinationFooter(null, en)).contains("converter")
    }

    @Test
    fun `the result names the playlist and what was left out`() {
        val outcome = ImportOutcome(DestinationCoverage.IN_LIBRARY, "Road Trip", playlistSongs = 11, missing = 1)
        assertThat(ImportText.resultNotes(outcome, skipped = 2, en)).containsExactly(
            "The playlist “Road Trip” was recreated with 11 songs.",
            "1 song isn’t in your library yet; it may appear after the next scan.",
            "2 files were left out: only music is extracted.",
        ).inOrder()
        assertThat(ImportText.resultNotes(outcome.copy(coverage = DestinationCoverage.JOINS_LIBRARY, missing = 0), skipped = 0, en))
            .containsExactly("The playlist “Road Trip” was recreated with 11 songs.", "The folder was added to your library.").inOrder()
        assertThat(ImportText.resultNotes(outcome.copy(playlistName = null, playlistSongs = 0, missing = 0), skipped = 0, en)).isEmpty()
        val outside = ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, null, 0, 0)
        assertThat(ImportText.resultNotes(outside, skipped = 0, en)).containsExactly(
            "The songs are not in your library yet. Tap Add to Library and allow their folder.",
        )
        assertThat(ImportText.resultNotes(outside, skipped = 0, en, wrongFolder = true)).containsExactly(
            "That folder isn’t the one with the imported songs, so it wasn’t added.",
            "The songs are not in your library yet. Tap Add to Library and allow their folder.",
        ).inOrder()
    }
}
