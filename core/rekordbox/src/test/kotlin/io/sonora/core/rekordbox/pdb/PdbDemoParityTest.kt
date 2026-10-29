package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.DemoTracks
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.reference.RawPdb
import io.sonora.core.rekordbox.reference.RawRow
import java.time.LocalDate
import org.junit.Test

/**
 * Rebuilds rows of rekordbox's demo export (copied from rekordcrate, see the README next to it) and compares them with
 * the originals: row encoders byte for byte when fed the exact values rekordbox stored, the whole writer field by field
 * when fed the demo library as a [DeviceLibrary].
 *
 * Remaining differences, all by design:
 * - rekordbox leaves 52 zero bytes after each demo track row (44..60 in larger exports, no rule found); the writer only
 *   pads track rows to 4 bytes. Readers locate everything through the row's own string offsets.
 * - Rows sit in other page slots (the demo pages hold deleted rows), so `index_shift` differs.
 * - The demo's keys table holds five keys from rekordbox's whole collection (ids 1..5, the tracks use 5 = Fm); the writer
 *   only writes the keys its tracks use, numbered from 1, and names them alphanumerically ("4A"), since the XDJ-700
 *   shows the names as written.
 * - Per-track values whose meaning is unknown: the u32 at 0x14 (random-looking, the writer writes 0 like rex), the u16
 *   pair at 0x18 (64128/1511 here, 519/52395 in the larger num_rows export, which the writer uses), and the strings
 *   `unknown_string2/3` ("3" here, "1" in num_rows and rex) and `publish_track_information` ("" here, "ON" in num_rows).
 */
class PdbDemoParityTest {

    private val demo = PdbReader.read(DemoTracks.resource("export.pdb"))

    private fun demoTrackRow(id: Int, fileSize: Long, checksum: Long, tempo: Int, duration: Int, anlz: String) = TrackRow(
        id = id,
        title = "Demo Track $id",
        filePath = "/Contents/Loopmasters/UnknownAlbum/Demo Track $id.mp3",
        fileName = "Demo Track $id.mp3",
        analyzePath = "/PIONEER/USBANLZ/$anlz/ANLZ0000.DAT",
        analyzeDate = "2022-02-02",
        dateAdded = "2018-05-25",
        sampleRate = 44_100,
        fileSize = fileSize,
        bitrate = 320,
        tempoHundredths = tempo,
        sampleDepth = 16,
        durationSeconds = duration,
        fileType = DeviceFileType.MP3.code,
        artistId = 1,
        labelId = 1,
        keyId = 5,
        checksum = checksum,
        databaseConstants = 64128 to 1511,
        comment = "Tracks by www.loopmasters.com",
        unknownString2 = "3",
        unknownString3 = "3",
        publishTrackInformation = "",
    )

    @Test
    fun `track rows are byte-identical up to rekordbox's trailing slack`() {
        val real = demo.rows(PdbTable.TRACKS)
        val ours = listOf(
            demoTrackRow(1, 6_899_624, 214_020_570, 12_800, 172, "P016/0000875E").encode(real[0].slot),
            demoTrackRow(2, 5_124_342, 263_879_995, 12_000, 128, "P053/0001D21F").encode(real[1].slot),
        )

        for ((row, encoded) in real.zip(ours)) {
            assertThat(encoded).isEqualTo(row.bytes.copyOf(encoded.size))
            assertThat(row.bytes.size - encoded.size).isEqualTo(52)
            assertThat(row.bytes.drop(encoded.size).all { it.toInt() == 0 }).isTrue()
        }
    }

    @Test
    fun `artist, label and key rows are byte-identical`() {
        val artist = demo.rows(PdbTable.ARTISTS).single()
        assertThat(ArtistRow(1, "Loopmasters").encode(artist.slot)).isEqualTo(artist.bytes)
        assertThat(LabelRow(1, "Loopmasters").encode(0)).isEqualTo(demo.rows(PdbTable.LABELS).single().bytes)
        val keys = listOf("Dm", "Am", "E", "D", "Fm").mapIndexed { i, name -> KeyRow(i + 1, name).encode(i).toList() }
        assertThat(demo.rows(PdbTable.KEYS).map { it.bytes.toList() }).isEqualTo(keys)
        assertThat(listOf(MusicalKey.D_MINOR, MusicalKey.A_MINOR, MusicalKey.E_MAJOR, MusicalKey.D_MAJOR, MusicalKey.F_MINOR)
            .map { it.classicName }).containsExactly("Dm", "Am", "E", "D", "Fm").inOrder()
    }

    @Test
    fun `sync record matches rekordbox's latest one apart from its slot`() {
        val real = demo.rows(PdbTable.HISTORY).single()
        assertThat(HistoryRow(trackCount = 2, date = "2022-02-02").encode(real.slot)).isEqualTo(real.bytes)
    }

    @Test
    fun `writer output for the demo library matches the demo export field by field`() {
        val ours = PdbReader.read(PdbWriter.write(demoLibrary))
        PdbReader.checkStructure(ours)

        // The one by-design difference in the resolved fields: Sonora names keys alphanumerically (Fm is 4A).
        assertThat(trackFields(ours)).isEqualTo(trackFields(demo).map { it + ("key" to "4A") })
        assertThat(trackFields(demo).map { it["key"] }).containsExactly("Fm", "Fm")
        assertThat(ours.rows(PdbTable.ARTISTS).map { it.bytes.drop(4) })
            .isEqualTo(demo.rows(PdbTable.ARTISTS).map { it.bytes.drop(4) })
        assertThat(ours.rows(PdbTable.LABELS).map { it.bytes.toList() })
            .isEqualTo(demo.rows(PdbTable.LABELS).map { it.bytes.toList() })
        assertThat(ours.rows(PdbTable.ALBUMS)).isEmpty()
        assertThat(ours.rows(PdbTable.GENRES)).isEmpty()
        assertThat(ours.rows(PdbTable.PLAYLIST_TREE)).isEmpty()
        assertThat(ours.rows(PdbTable.HISTORY).single().bytes.copyOfRange(4, 40))
            .isEqualTo(demo.rows(PdbTable.HISTORY).single().bytes.copyOfRange(4, 40))
    }

    /** Every track field a player reads, with ids resolved to names; the fields listed in the class comment are left out. */
    private fun trackFields(pdb: RawPdb): List<Map<String, Any>> {
        fun names(table: PdbTable, idAt: Int, nameAt: (RawRow) -> Int) =
            pdb.rows(table).associate { it.u32(idAt) to it.string(nameAt(it)) }
        val artists = names(PdbTable.ARTISTS, 4) { it.u8(9) }
        val labels = names(PdbTable.LABELS, 0) { 4 }
        val keys = names(PdbTable.KEYS, 0) { 8 }
        val stringNames = listOf(
            "isrc", "lyricist", null, null, "u4", "message", null, "autoload", "u5", "u6", "date_added",
            "release_date", "mix", "u7", "analyze_path", "analyze_date", "comment", "title", "u8", "filename", "file_path",
        )
        return pdb.rows(PdbTable.TRACKS).map { row ->
            val fields = mutableMapOf<String, Any>(
                "bitmask" to row.u32(0x04), "sample_rate" to row.u32(0x08), "file_size" to row.u32(0x10),
                "artwork" to row.u32(0x1C), "key" to (keys[row.u32(0x20)] ?: ""), "label" to (labels[row.u32(0x28)] ?: ""),
                "bitrate" to row.u32(0x30), "track_number" to row.u32(0x34), "tempo" to row.u32(0x38),
                "genre" to row.u32(0x3C), "album" to row.u32(0x40), "artist" to (artists[row.u32(0x44)] ?: ""),
                "id" to row.u32(0x48), "disc" to row.u16(0x4C), "plays" to row.u16(0x4E), "year" to row.u16(0x50),
                "depth" to row.u16(0x52), "duration" to row.u16(0x54), "0x56" to row.u16(0x56), "colour" to row.u8(0x58),
                "rating" to row.u8(0x59), "file_type" to row.u16(0x5A), "0x5c" to row.u16(0x5C),
            )
            stringNames.forEachIndexed { i, name -> if (name != null) fields[name] = row.string(row.u16(0x5E + 2 * i)) }
            fields
        }
    }

    private fun demoTrack(id: Int, fileSize: Long, bpm: Double, duration: Int, anlz: String) = DeviceTrack(
        id = id,
        title = "Demo Track $id",
        artist = "Loopmasters",
        album = null,
        tempoBpm = bpm,
        durationSeconds = duration,
        sampleRate = 44_100,
        bitDepth = 16,
        bitrateKbps = 320,
        fileSize = fileSize,
        filePath = "/Contents/Loopmasters/UnknownAlbum/Demo Track $id.mp3",
        analyzePath = "/PIONEER/USBANLZ/$anlz/ANLZ0000.DAT",
        dateAdded = LocalDate.of(2018, 5, 25),
        label = "Loopmasters",
        key = MusicalKey.F_MINOR,
        comment = "Tracks by www.loopmasters.com",
        fileType = DeviceFileType.MP3,
    )

    private val demoLibrary = DeviceLibrary(
        tracks = listOf(
            demoTrack(1, 6_899_624, 128.0, 172, "P016/0000875E"),
            demoTrack(2, 5_124_342, 120.0, 128, "P053/0001D21F"),
        ),
        exportDate = LocalDate.of(2022, 2, 2),
    )

    companion object {
        /** The demo library as a [DeviceLibrary], for the oracle files. */
        fun demoLibrary(): DeviceLibrary = PdbDemoParityTest().demoLibrary
    }
}
