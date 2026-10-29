package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.LittleEndianBytes
import io.sonora.core.rekordbox.reference.hex
import org.junit.Test

/**
 * Golden bytes of every generated row type. Rows quoted "from rekordbox" are copied from real exports: the rekordcrate
 * demo and empty exports, and rekordcrate's large `data/pdb/num_rows` export (not committed, see PdbDemoParityTest).
 */
class PdbRowTest {

    @Test
    fun `key, label and genre rows are id and name, padded to 4 bytes`() {
        // From rekordbox (demo export, keys and labels pages).
        assertThat(KeyRow(1, "Dm").encode(0)).isEqualTo(hex("01000000 01000000 07446d 00"))
        assertThat(KeyRow(3, "E").encode(2)).isEqualTo(hex("03000000 03000000 0545 0000"))
        assertThat(LabelRow(1, "Loopmasters").encode(0)).isEqualTo(hex("01000000 19 4c6f6f706d617374657273"))
        // From rekordbox (num_rows export, genres page).
        assertThat(GenreRow(2, "#deep #beatdown").encode(1))
            .isEqualTo(hex("02000000 21 2364656570202362656174646f776e"))
    }

    @Test
    fun `artist row has subtype 0x60, the slot shift, and 6 more bytes reserved like rekordbox`() {
        // From rekordbox (demo export): the artist sits in slot 1, so index_shift is 0x20.
        assertThat(ArtistRow(1, "Loopmasters").encode(1))
            .isEqualTo(hex("6000 2000 01000000 03 0a 19 4c6f6f706d617374657273 000000000000"))
        // From rekordbox (num_rows export): a non-ascii name moves to offset 0x0c, aligned to 4.
        assertThat(ArtistRow(0x23, "Manie Sans Délire").encode(34)).isEqualTo(
            hex(
                "6000 4004 23000000 03 0c 0000 90 2600 00 4d0061006e00690065002000530061006e00730020004400e9006c0069" +
                    "0072006500 000000000000",
            ),
        )
    }

    @Test
    fun `album row has subtype 0x80, its artist and the name at 0x16`() {
        // From rekordbox (num_rows export).
        assertThat(AlbumRow(1, "The Worst of Gehm", artistId = 0).encode(0)).isEqualTo(
            hex("8000 0000 00000000 00000000 01000000 00000000 03 16 25 54686520576f727374206f66204765686d 0000000000000000"),
        )
        assertThat(AlbumRow(6, "Wind / Phazzled", artistId = 12).encode(5)).isEqualTo(
            hex("8000 a000 00000000 0c000000 06000000 00000000 03 16 21 57696e64202f205068617a7a6c6564 000000000000"),
        )
        // A non-ascii name starts at 0x18; rekordbox reserves 6 bytes past the unaligned length, rounded to 4.
        val name = "1ØPILLS003 MASTER MP3s"
        assertThat(AlbumRow(2, name).encode(1)).isEqualTo(
            hex("8000 2000 00000000 00000000 02000000 00000000 03 18 0000 90 3000 00") +
                name.toByteArray(Charsets.UTF_16LE) + hex("00000000"),
        )
    }

    @Test
    fun `playlist tree, playlist entry and artwork rows`() {
        assertThat(PlaylistTreeRow(id = 2, parentId = 0, sortOrder = 1, isFolder = false, name = "Warm up").encode(1))
            .isEqualTo(hex("00000000 00000000 01000000 02000000 00000000 11 5761726d207570"))
        assertThat(PlaylistTreeRow(id = 1, parentId = 0, sortOrder = 0, isFolder = true, name = "Sets").encode(0))
            .isEqualTo(hex("00000000 00000000 00000000 01000000 01000000 0b 53657473 000000"))
        // From rekordbox (num_rows export).
        assertThat(PlaylistEntryRow(entryIndex = 1, trackId = 1, playlistId = 6).encode(0))
            .isEqualTo(hex("01000000 01000000 06000000"))
        assertThat(ArtworkRow(1, "/PIONEER/Artwork/00001/a1.jpg").encode(0))
            .isEqualTo(hex("01000000 3d") + "/PIONEER/Artwork/00001/a1.jpg".toByteArray() + hex("0000"))
    }

    @Test
    fun `history row is the sync record of the empty export`() {
        // From rekordbox (empty export, table 19).
        assertThat(HistoryRow(trackCount = 0, date = "2022-02-02").encode(0)).isEqualTo(
            hex("8002 0000 00000000 00000000 17 323032322d30322d3032 191e 0b 31303030 03 000000000000000000"),
        )
        assertThat(HistoryRow(trackCount = 300, date = "2026-10-03").encode(0).copyOf(8)).isEqualTo(hex("8002 0000 2c010000"))
    }

    @Test
    fun `track row puts every field at its documented offset`() {
        val row = TrackRow(
            id = 42,
            title = "Señorita",
            filePath = "/Contents/Artist/Album/Senorita.aiff",
            fileName = "Senorita.aiff",
            analyzePath = "/PIONEER/USBANLZ/P123/0000ABCD/ANLZ0000.DAT",
            analyzeDate = "2026-10-03",
            dateAdded = "2026-09-30",
            sampleRate = 44100,
            fileSize = 52_000_000,
            bitrate = 1411,
            tempoHundredths = 12_450,
            sampleDepth = 16,
            durationSeconds = 295,
            fileType = DeviceFileType.AIFF.code,
            artistId = 3,
            albumId = 4,
            genreId = 5,
            labelId = 6,
            keyId = 7,
            trackNumber = 8,
            year = 2024,
            comment = "Tested",
        )
        val bytes = row.encode(slot = 3)
        val view = object : LittleEndianBytes(bytes) {}

        assertThat(view.u16(0x00)).isEqualTo(0x24)
        assertThat(view.u16(0x02)).isEqualTo(0x60)
        assertThat(view.u32(0x04)).isEqualTo(0x000C0700L)
        assertThat(view.u32(0x08)).isEqualTo(44100L)
        assertThat(view.u32(0x10)).isEqualTo(52_000_000L)
        assertThat(view.u32(0x14)).isEqualTo(0L)
        assertThat(view.u16(0x18) to view.u16(0x1A)).isEqualTo(519 to 52395)
        assertThat(view.u32(0x20)).isEqualTo(7L)
        assertThat(view.u32(0x28)).isEqualTo(6L)
        assertThat(view.u32(0x30)).isEqualTo(1411L)
        assertThat(view.u32(0x34)).isEqualTo(8L)
        assertThat(view.u32(0x38)).isEqualTo(12_450L)
        assertThat(view.u32(0x3C)).isEqualTo(5L)
        assertThat(view.u32(0x40)).isEqualTo(4L)
        assertThat(view.u32(0x44)).isEqualTo(3L)
        assertThat(view.u32(0x48)).isEqualTo(42L)
        assertThat(view.u16(0x50)).isEqualTo(2024)
        assertThat(view.u16(0x52)).isEqualTo(16)
        assertThat(view.u16(0x54)).isEqualTo(295)
        assertThat(view.u16(0x56)).isEqualTo(0x29)
        assertThat(view.u16(0x5A)).isEqualTo(0x0C)
        assertThat(view.u16(0x5C)).isEqualTo(0x0003)

        val offsets = List(21) { view.u16(0x5E + 2 * it) }
        assertThat(offsets.first()).isEqualTo(0x88)
        assertThat(offsets).isInOrder()
        val strings = offsets.map(view::string)
        assertThat(strings).containsExactly(
            "", "", "1", "1", "", "", "ON", "ON", "", "", "2026-09-30", "", "", "",
            "/PIONEER/USBANLZ/P123/0000ABCD/ANLZ0000.DAT", "2026-10-03", "Tested", "Señorita", "",
            "Senorita.aiff", "/Contents/Artist/Album/Senorita.aiff",
        ).inOrder()
        // The utf-16 title starts on a 4-byte boundary of the row; the gap before it is zero.
        val titleAt = offsets[17]
        assertThat(titleAt % 4).isEqualTo(0)
        assertThat(bytes.copyOfRange(offsets[16] + 7, titleAt).all { it.toInt() == 0 }).isTrue()
        assertThat(bytes.size % 4).isEqualTo(0)
    }
}
