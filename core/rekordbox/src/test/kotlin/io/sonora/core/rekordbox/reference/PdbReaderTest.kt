package io.sonora.core.rekordbox.reference

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.DemoTracks
import io.sonora.core.rekordbox.pdb.ArtistRow
import io.sonora.core.rekordbox.pdb.DeviceLibrary
import io.sonora.core.rekordbox.pdb.DeviceTrack
import io.sonora.core.rekordbox.pdb.KeyRow
import io.sonora.core.rekordbox.pdb.PdbTable
import io.sonora.core.rekordbox.pdb.PdbWriter
import io.sonora.core.rekordbox.pdb.SyntheticLibrary
import io.sonora.core.rekordbox.pdb.TrackRow
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The typed `export.pdb` reader: round trips against [PdbWriter] (several pages, long and non-ASCII strings) and a read
 * of rekordbox's own demo export.
 */
class PdbReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every track row of a written library reads back, across pages`() {
        val library = SyntheticLibrary.build(300)
        val pdb = PdbReader.read(PdbWriter.write(library))
        val rows = PdbWriter.rowsOf(library).getValue(PdbTable.TRACKS).map { it as TrackRow }

        assertThat(pdb.dataPages(PdbTable.TRACKS).size).isGreaterThan(1)
        assertThat(pdb.tracks).hasSize(300)
        for ((track, row) in pdb.tracks.zip(rows)) {
            assertThat(track).isEqualTo(
                PdbTrack(
                    id = row.id,
                    title = row.title,
                    artistId = row.artistId,
                    albumId = row.albumId,
                    genreId = row.genreId,
                    labelId = row.labelId,
                    keyId = row.keyId,
                    artworkId = row.artworkId,
                    filePath = row.filePath,
                    fileName = row.fileName,
                    analyzePath = row.analyzePath,
                    analyzeDate = row.analyzeDate,
                    dateAdded = row.dateAdded,
                    comment = row.comment,
                    tempoHundredths = row.tempoHundredths,
                    durationSeconds = row.durationSeconds,
                    sampleRate = row.sampleRate.toLong(),
                    sampleDepth = row.sampleDepth,
                    bitrate = row.bitrate.toLong(),
                    fileSize = row.fileSize,
                    fileType = row.fileType,
                    trackNumber = row.trackNumber.toLong(),
                    year = row.year,
                ),
            )
        }
    }

    @Test
    fun `a track keeps the values of its device track`() {
        val library = DeviceLibrary(
            tracks = listOf(
                DeviceTrack(
                    id = 42, title = "Señal 東京", artist = "Ärtist", album = "Álbum", tempoBpm = 127.5,
                    durationSeconds = 301, sampleRate = 44_100, bitDepth = 16, bitrateKbps = 1411, fileSize = 52_000_123,
                    filePath = "/Contents/Ärtist/Álbum/Señal 東京.aiff",
                    analyzePath = "/PIONEER/USBANLZ/P016/0000875E/ANLZ0000.DAT",
                    dateAdded = LocalDate.of(2026, 9, 1), key = MusicalKey.A_MINOR, trackNumber = 3, year = 2025,
                ),
            ),
            exportDate = LocalDate.of(2026, 10, 3),
        )

        val track = PdbReader.read(PdbWriter.write(library)).tracks.single()

        assertThat(track.id).isEqualTo(42)
        assertThat(track.title).isEqualTo("Señal 東京")
        assertThat(track.tempoHundredths).isEqualTo(12_750)
        assertThat(track.tempoBpm).isEqualTo(127.5)
        assertThat(track.durationSeconds).isEqualTo(301)
        assertThat(track.filePath).isEqualTo("/Contents/Ärtist/Álbum/Señal 東京.aiff")
        assertThat(track.fileName).isEqualTo("Señal 東京.aiff")
        assertThat(track.analyzePath).isEqualTo("/PIONEER/USBANLZ/P016/0000875E/ANLZ0000.DAT")
        assertThat(track.fileSize).isEqualTo(52_000_123)
        assertThat(track.year).isEqualTo(2025)
        assertThat(track.trackNumber).isEqualTo(3)
        assertThat(track.dateAdded).isEqualTo("2026-09-01")
    }

    @Test
    fun `the keys table maps ids to names and the artists table ids to names`() {
        val library = SyntheticLibrary.build(300)
        val pdb = PdbReader.read(PdbWriter.write(library))
        val rows = PdbWriter.rowsOf(library)

        val keys = rows.getValue(PdbTable.KEYS).map { it as KeyRow }
        assertThat(pdb.keys).containsExactlyElementsIn(keys.map { PdbKey(it.id, it.name) }).inOrder()
        assertThat(pdb.keyNames).containsExactlyEntriesIn(keys.associate { it.id to it.name })
        val artists = rows.getValue(PdbTable.ARTISTS).map { it as ArtistRow }
        assertThat(pdb.artists).containsExactlyElementsIn(artists.map { PdbArtist(it.id, it.name) }).inOrder()
        val firstTrack = pdb.tracks.first { it.keyId != 0 }
        assertThat(pdb.keyNames.getValue(firstTrack.keyId)).isNotEmpty()
    }

    @Test
    fun `an empty library has no tracks, artists or keys`() {
        val pdb = PdbReader.read(PdbWriter.write(DeviceLibrary(emptyList(), exportDate = LocalDate.of(2026, 10, 3))))

        assertThat(pdb.tracks).isEmpty()
        assertThat(pdb.artists).isEmpty()
        assertThat(pdb.keys).isEmpty()
    }

    @Test
    fun `rekordbox's own demo export reads with its tracks, artist and keys`() {
        val demo = PdbReader.read(DemoTracks.resource("export.pdb"))

        assertThat(demo.tracks.map { it.title }).containsExactly("Demo Track 1", "Demo Track 2").inOrder()
        val first = demo.tracks[0]
        assertThat(first.id).isEqualTo(1)
        assertThat(first.tempoHundredths).isEqualTo(12_800)
        assertThat(first.durationSeconds).isEqualTo(172)
        assertThat(first.filePath).isEqualTo("/Contents/Loopmasters/UnknownAlbum/Demo Track 1.mp3")
        assertThat(first.analyzePath).isEqualTo("/PIONEER/USBANLZ/P016/0000875E/ANLZ0000.DAT")
        assertThat(first.keyId).isEqualTo(5)
        assertThat(demo.tracks[1].tempoHundredths).isEqualTo(12_000)
        assertThat(demo.tracks[1].analyzePath).isEqualTo("/PIONEER/USBANLZ/P053/0001D21F/ANLZ0000.DAT")
        assertThat(demo.artists).containsExactly(PdbArtist(1, "Loopmasters"))
        assertThat(demo.keyNames).containsExactly(1, "Dm", 2, "Am", 3, "E", 4, "D", 5, "Fm")
        assertThat(demo.keyNames.getValue(first.keyId)).isEqualTo("Fm")
    }

    @Test
    fun `reading a path gives the same tracks as reading its bytes`() {
        val file = folder.newFile("export.pdb").also { it.writeBytes(DemoTracks.resource("export.pdb")) }

        assertThat(PdbReader.read(file.toPath()).tracks.map { it.id }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a file that is not a whole number of pages is truncated`() {
        val full = PdbWriter.write(SyntheticLibrary.build(300))

        for (cut in listOf(0, 100, 4_095, 4_096 + 10, full.size - 1)) {
            val failure = assertThrows(PdbFormatException::class.java) { PdbReader.read(full.copyOf(cut)) }
            assertThat(failure.truncated).isTrue()
        }
    }

    @Test
    fun `a file whose pages stop before the end of a table is truncated`() {
        val full = PdbWriter.write(SyntheticLibrary.build(300))
        val tracksPages = PdbReader.read(full).chain(PdbTable.TRACKS)
        val keep = tracksPages.last().index // drop the last tracks page and everything after it

        val failure = assertThrows(PdbFormatException::class.java) { PdbReader.read(full.copyOf(keep * 4_096)) }

        assertThat(failure.truncated).isTrue()
    }

    @Test
    fun `a file with a bad page size is refused`() {
        val bytes = PdbWriter.write(SyntheticLibrary.build(5)).also { it[4] = 0x00; it[5] = 0x20 }

        val failure = assertThrows(PdbFormatException::class.java) { PdbReader.read(bytes) }

        assertThat(failure.truncated).isFalse()
    }
}
