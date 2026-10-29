package io.sonora.feature.settings.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.backup.RestorePreview
import io.sonora.core.domain.backup.RestoreReport
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.feature.settings.XmlStrings
import java.time.ZoneOffset
import org.junit.Test

class BackupTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    @Test
    fun `the last backup shows its date, or that there is none`() {
        assertThat(BackupText.lastBackup(null, ZoneOffset.UTC, en)).isEqualTo("No backup yet")
        val line = BackupText.lastBackup(1_791_158_400_000, ZoneOffset.UTC, es)
        assertThat(line).startsWith("Última copia: ")
        assertThat(line).contains("2026")
    }

    @Test
    fun `the preview counts playlists and songs found and not found`() {
        assertThat(BackupText.preview(RestorePreview(2, 120, 4), en)).isEqualTo("2 playlists · 120 songs found · 4 not found")
        assertThat(BackupText.preview(RestorePreview(1, 1, 1), es)).isEqualTo("1 lista · 1 canción encontrada · 1 no encontrada")
        assertThat(BackupText.preview(RestorePreview(3, 5, 0), eu)).isEqualTo("3 zerrenda · 5 abesti aurkitu dira · 0 ez dira aurkitu")
    }

    @Test
    fun `the report says what was added and how many songs were not found`() {
        val gone = SongIdentity("k", "Halo", "Beyoncé", "", 0)
        assertThat(BackupText.report(RestoreReport(2, 10, emptyList()), en)).isEqualTo("2 playlists added.")
        assertThat(BackupText.report(RestoreReport(1, 10, listOf(gone)), en))
            .isEqualTo("1 playlist added.\n\n1 song isn’t in your library:")
    }

    @Test
    fun `an unfound song is named as well as its tags allow`() {
        assertThat(BackupText.song(SongIdentity("k", "Halo", "Beyoncé", "", 0), en)).isEqualTo("Beyoncé — Halo")
        assertThat(BackupText.song(SongIdentity("k", "Halo", " ", "", 0), en)).isEqualTo("Halo")
        assertThat(BackupText.song(SongIdentity("k", "", "", "", 0), es)).isEqualTo("Canción desconocida")
    }

    @Test
    fun `every notice has its words`() {
        val words = BackupMessage.entries.map { BackupText.message(it, en) }
        assertThat(words).containsNoDuplicates()
        assertThat(BackupText.message(BackupMessage.NEWER_VERSION, en))
            .isEqualTo("This backup was made by a newer version of Sonora. Update Sonora to restore it.")
    }
}
