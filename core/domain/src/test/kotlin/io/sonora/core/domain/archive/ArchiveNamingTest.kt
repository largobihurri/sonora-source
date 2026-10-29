package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.BatchNaming
import io.sonora.core.domain.model.Track
import org.junit.Test

class ArchiveNamingTest {

    private fun track(
        title: String,
        artist: String = "Band",
        uri: String = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F$title.flac",
        mimeType: String = "audio/flac",
        trackNumber: Int? = null,
    ) = Track(
        id = 1, uri = uri, title = title, artist = artist, album = "Record", albumArtist = "Band", durationMs = 180_000,
        trackNumber = trackNumber, discNumber = 1, year = 2026, albumId = 1, artworkUri = null, mimeType = mimeType,
        sizeBytes = 1_000,
    )

    @Test
    fun `album and playlist entries are numbered in order with artist and title`() {
        val names = ArchiveNaming.fileNames(ArchiveKind.ALBUM, listOf(track("Intro"), track("Song", artist = "Guest")))
        assertThat(names).containsExactly("01 - Band - Intro.flac", "02 - Guest - Song.flac").inOrder()
    }

    @Test
    fun `the number is as wide as the count needs`() {
        val names = ArchiveNaming.fileNames(ArchiveKind.PLAYLIST, List(120) { track("T$it") })
        assertThat(names.first()).isEqualTo("001 - Band - T0.flac")
        assertThat(names.last()).isEqualTo("120 - Band - T119.flac")
    }

    @Test
    fun `a single song has no number`() {
        assertThat(ArchiveNaming.fileNames(ArchiveKind.SONG, listOf(track("Solo")))).containsExactly("Band - Solo.flac")
    }

    @Test
    fun `names hold no path separators or FAT-illegal characters`() {
        val name = ArchiveNaming.fileNames(ArchiveKind.SONG, listOf(track("Back/In: Black?", artist = "AC/DC"))).single()
        assertThat(name).isEqualTo("AC_DC - Back_In_ Black_.flac")
        assertThat(name.none { it in "\"*/:<>?\\|" }).isTrue()
    }

    @Test
    fun `an artist-less track is named by its title and a title-less one by a placeholder`() {
        val names = ArchiveNaming.fileNames(ArchiveKind.ALBUM, listOf(track("Alone", artist = " "), track("  ", artist = "")))
        assertThat(names).containsExactly("01 - Alone.flac", "02 - Track.flac").inOrder()
    }

    @Test
    fun `long names are shortened before the extension`() {
        val name = ArchiveNaming.fileNames(ArchiveKind.SONG, listOf(track("x".repeat(400)))).single()
        assertThat(BatchNaming.byteLength(name)).isAtMost(ArchiveNaming.MAX_FILE_BYTES)
        assertThat(name).endsWith(".flac")
        assertThat(name).startsWith("Band - xxx")
    }

    @Test
    fun `duplicates are disambiguated ignoring case`() {
        val long = "y".repeat(300)
        val names = ArchiveNaming.fileNames(ArchiveKind.SONG, listOf(track(long), track(long.uppercase())))
        assertThat(names[0].lowercase()).isNotEqualTo(names[1].lowercase())
        assertThat(names[1]).endsWith(" (2).flac")
    }

    @Test
    fun `the extension comes from the file, else from the MIME type`() {
        val fromFile = track("A", uri = "content://p/tree/t/document/primary%3AMusic%2FA.AIFF")
        val fromMime = track("B", uri = "content://media/external/audio/media/42", mimeType = "audio/mpeg")
        val unknown = track("C", uri = "content://media/external/audio/media/43", mimeType = "application/octet-stream")
        assertThat(ArchiveNaming.extensionOf(fromFile)).isEqualTo("aiff")
        assertThat(ArchiveNaming.extensionOf(fromMime)).isEqualTo("mp3")
        assertThat(ArchiveNaming.extensionOf(unknown)).isEqualTo("audio")
    }

    @Test
    fun `the folder is named after the album or playlist, safely`() {
        assertThat(ArchiveNaming.folderName("Road Trip: 2026/27")).isEqualTo("Road Trip_ 2026_27")
        assertThat(ArchiveNaming.folderName("   ")).isEqualTo(ArchiveNaming.DEFAULT_NAME)
        assertThat(ArchiveNaming.folderName("..")).isEqualTo(ArchiveNaming.DEFAULT_NAME)
        assertThat(ArchiveNaming.folderName("con")).isEqualTo("con_")
        assertThat(BatchNaming.byteLength(ArchiveNaming.folderName("z".repeat(200)))).isAtMost(ArchiveNaming.MAX_FOLDER_BYTES)
        assertThat(ArchiveNaming.archiveFileName("Mix: Summer")).isEqualTo("Mix_ Summer.zip")
    }

    @Test
    fun `the layout puts the manifest and the music in one folder, entries in order with their tags`() {
        val tracks = listOf(track("Intro", trackNumber = 1), track("Song", trackNumber = 2))
        val layout = ArchiveNaming.layout(ArchiveKind.ALBUM, "Record", tracks)

        assertThat(layout.folder).isEqualTo("Record")
        assertThat(layout.manifestPath).isEqualTo("Record/sonora.json")
        assertThat(layout.entryPath(1)).isEqualTo("Record/02 - Band - Song.flac")
        assertThat(layout.manifest.kind).isEqualTo(ArchiveKind.ALBUM)
        assertThat(layout.manifest.name).isEqualTo("Record")
        assertThat(layout.manifest.entries[0]).isEqualTo(
            ArchiveEntry(
                file = "01 - Band - Intro.flac", title = "Intro", artist = "Band", album = "Record", albumArtist = "Band",
                trackNumber = 1, discNumber = 1, durationMs = 180_000,
            ),
        )
    }
}
