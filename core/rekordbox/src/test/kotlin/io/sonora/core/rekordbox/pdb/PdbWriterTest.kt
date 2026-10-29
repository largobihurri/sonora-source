package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.reference.RawTable
import io.sonora.core.rekordbox.reference.hex
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

class PdbWriterTest {

    @Test
    fun `an empty library dated like the template is byte-identical to rekordbox's empty export`() {
        val written = PdbWriter.write(DeviceLibrary(tracks = emptyList(), exportDate = LocalDate.of(2022, 2, 2)))

        assertThat(written).isEqualTo(PdbTemplates.exportPdb())
    }

    @Test
    fun `the template passes the structural checks the writer's files are held to`() {
        PdbReader.checkStructure(PdbReader.read(PdbTemplates.exportPdb()))
    }

    @Test
    fun `artists, albums, genres, labels and keys are numbered in order of first use, artwork keeps its ids`() {
        val rows = PdbWriter.rowsOf(library)

        assertThat(rows.getValue(PdbTable.ARTISTS)).containsExactly(ArtistRow(1, "Alpha"), ArtistRow(2, "Beta")).inOrder()
        assertThat(rows.getValue(PdbTable.ALBUMS))
            .containsExactly(AlbumRow(1, "First", artistId = 1), AlbumRow(2, "First", artistId = 2)).inOrder()
        assertThat(rows.getValue(PdbTable.GENRES)).containsExactly(GenreRow(1, "Techno"))
        assertThat(rows.getValue(PdbTable.LABELS)).containsExactly(LabelRow(1, "Label"))
        assertThat(rows.getValue(PdbTable.KEYS)).containsExactly(KeyRow(1, "8A"), KeyRow(2, "12B")).inOrder()
        assertThat(rows.getValue(PdbTable.ARTWORK)).containsExactly(ArtworkRow(1, "/PIONEER/Artwork/00001/a1.jpg"))
        assertThat(rows.getValue(PdbTable.HISTORY)).containsExactly(HistoryRow(trackCount = 3, date = "2026-10-03"))

        val tracks = rows.getValue(PdbTable.TRACKS).map { it as TrackRow }
        assertThat(tracks.map { listOf(it.id, it.artistId, it.albumId, it.genreId, it.labelId, it.keyId, it.artworkId) })
            .containsExactly(listOf(7, 1, 1, 1, 1, 1, 1), listOf(8, 2, 2, 0, 0, 2, 0), listOf(9, 0, 0, 0, 0, 1, 1))
            .inOrder()
    }

    @Test
    fun `artwork rows keep the tracks' artwork ids, in id order, each at rekordbox's path for its id`() {
        val tracks = library.tracks.mapIndexed { i, track -> track.copy(artworkId = listOf(21, 3, 21)[i]) }

        val rows = PdbWriter.rowsOf(library.copy(tracks = tracks))

        assertThat(rows.getValue(PdbTable.ARTWORK)).containsExactly(
            ArtworkRow(3, "/PIONEER/Artwork/00001/a3.jpg"),
            ArtworkRow(21, "/PIONEER/Artwork/00002/a21.jpg"),
        ).inOrder()
        assertThat(rows.getValue(PdbTable.TRACKS).map { (it as TrackRow).artworkId }).containsExactly(21, 3, 21).inOrder()
    }

    @Test
    fun `artwork rows are laid out as in rekordbox's num_rows export and read back`() {
        // The bytes of rows a1 and a100 in rekordcrate's data/pdb/num_rows/export.pdb (rekordbox's own export).
        assertThat(ArtworkRow(1, "/PIONEER/Artwork/00001/a1.jpg").encode(0))
            .isEqualTo(hex("01000000 3d") + "/PIONEER/Artwork/00001/a1.jpg".toByteArray() + hex("0000"))
        assertThat(ArtworkRow(100, "/PIONEER/Artwork/00006/a100.jpg").encode(0))
            .isEqualTo(hex("64000000 41") + "/PIONEER/Artwork/00006/a100.jpg".toByteArray())

        val pdb = PdbReader.read(PdbWriter.write(library))
        PdbReader.checkStructure(pdb)
        val artwork = pdb.rows(PdbTable.ARTWORK).single()
        assertThat(artwork.u32(0)).isEqualTo(1L)
        assertThat(artwork.string(4)).isEqualTo("/PIONEER/Artwork/00001/a1.jpg")
        assertThat(pdb.rows(PdbTable.TRACKS).map { it.u32(0x1C) }).containsExactly(1L, 0L, 1L).inOrder()
    }

    @Test
    fun `keys are named in the alphanumeric notation, which the XDJ-700 shows as written`() {
        // The XDJ-700 (firmware 1.13) has no key display setting and shows the keys table's names verbatim.
        val fMinor = library.copy(tracks = library.tracks.take(1).map { it.copy(key = MusicalKey.F_MINOR) }, playlists = emptyList())

        assertThat(PdbWriter.rowsOf(fMinor).getValue(PdbTable.KEYS)).containsExactly(KeyRow(1, "4A"))
    }

    @Test
    fun `track rows carry the device track values`() {
        val track = PdbWriter.rowsOf(library).getValue(PdbTable.TRACKS).first() as TrackRow

        assertThat(track.title).isEqualTo("One")
        assertThat(track.tempoHundredths).isEqualTo(12_800)
        assertThat(track.sampleRate).isEqualTo(44_100)
        assertThat(track.sampleDepth).isEqualTo(16)
        assertThat(track.bitrate).isEqualTo(1411)
        assertThat(track.durationSeconds).isEqualTo(200)
        assertThat(track.fileType).isEqualTo(0x0C)
        assertThat(track.fileName).isEqualTo("One.aiff")
        assertThat(track.dateAdded).isEqualTo("2026-09-01")
        assertThat(track.analyzeDate).isEqualTo("2026-10-03")
        assertThat(track.trackNumber).isEqualTo(2)
        assertThat(track.year).isEqualTo(2025)
        assertThat(track.comment).isEqualTo("Nice")
    }

    @Test
    fun `playlists become root nodes in list order with 1-based entries`() {
        val rows = PdbWriter.rowsOf(library)

        assertThat(rows.getValue(PdbTable.PLAYLIST_TREE)).containsExactly(
            PlaylistTreeRow(id = 1, parentId = 0, sortOrder = 0, isFolder = false, name = "Warm up"),
            PlaylistTreeRow(id = 2, parentId = 0, sortOrder = 1, isFolder = false, name = "Peak"),
        ).inOrder()
        assertThat(rows.getValue(PdbTable.PLAYLIST_ENTRIES)).containsExactly(
            PlaylistEntryRow(entryIndex = 1, trackId = 9, playlistId = 1),
            PlaylistEntryRow(entryIndex = 2, trackId = 7, playlistId = 1),
            PlaylistEntryRow(entryIndex = 1, trackId = 8, playlistId = 2),
        ).inOrder()
    }

    @Test
    fun `a small library is laid out on the reserved pages and passes the structural checks`() {
        val pdb = PdbReader.read(PdbWriter.write(library))

        PdbReader.checkStructure(pdb)
        assertThat(pdb.pageCount).isEqualTo(41)
        assertThat(pdb.tables[PdbTable.TRACKS.id]).isEqualTo(RawTable(0, 41, 1, 2))
        assertThat(pdb.tables[PdbTable.ALBUMS.id]).isEqualTo(RawTable(3, 44, 7, 8))
        assertThat(pdb.tables[PdbTable.UNKNOWN_9.id]).isEqualTo(RawTable(9, 20, 19, 19))
        assertThat(pdb.rows(PdbTable.TRACKS).map { it.u32(0x48) }).containsExactly(7L, 8L, 9L).inOrder()
        assertThat(pdb.rows(PdbTable.COLORS)).hasSize(8)
        assertThat(pdb.rows(PdbTable.COLUMNS)).hasSize(27)
        assertThat(pdb.rows(PdbTable.MENU)).hasSize(22)
        assertThat(pdb.rows(PdbTable.UNKNOWN_18)).hasSize(17)
    }

    @Test
    fun `a row too big for one page is rejected`() {
        val huge = library.tracks.first().copy(comment = "é".repeat(2100))
        assertThrows(IllegalArgumentException::class.java) {
            PdbWriter.write(library.copy(tracks = listOf(huge), playlists = emptyList()))
        }
    }

    @Test
    fun `playlists may only hold tracks of the library`() {
        assertThrows(IllegalArgumentException::class.java) {
            library.copy(playlists = listOf(DevicePlaylist("Ghost", listOf(99))))
        }
    }

    private fun track(id: Int, title: String) = DeviceTrack(
        id = id,
        title = title,
        artist = null,
        album = null,
        tempoBpm = 128.0,
        durationSeconds = 200,
        sampleRate = 44_100,
        bitDepth = 16,
        bitrateKbps = 1411,
        fileSize = 35_000_000,
        filePath = "/Contents/UnknownArtist/UnknownAlbum/$title.aiff",
        analyzePath = "/PIONEER/USBANLZ/P001/0000000$id/ANLZ0000.DAT",
        dateAdded = LocalDate.of(2026, 9, 1),
    )

    private val library = DeviceLibrary(
        tracks = listOf(
            track(7, "One").copy(
                artist = "Alpha", album = "First", genre = "Techno", label = "Label", key = MusicalKey.A_MINOR,
                trackNumber = 2, year = 2025, comment = "Nice", artworkId = 1,
            ),
            track(8, "Two").copy(artist = "Beta", album = "First", genre = " ", key = MusicalKey.E_MAJOR),
            track(9, "Three").copy(artist = "", key = MusicalKey.A_MINOR, artworkId = 1),
        ),
        playlists = listOf(DevicePlaylist("Warm up", listOf(9, 7)), DevicePlaylist("Peak", listOf(8))),
        exportDate = LocalDate.of(2026, 10, 3),
    )
}
