package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.reference.PdbReader
import io.sonora.core.rekordbox.reference.RawPage
import io.sonora.core.rekordbox.reference.hex
import org.junit.Test

class PdbPageTest {

    private val template = PdbReader.read(PdbTemplates.exportPdb())

    @Test
    fun `row counts pack 13 bits of offsets and 11 bits of valid rows`() {
        assertThat(PdbPage.packRowCounts(rowOffsets = 7, validRows = 2)).isEqualTo(0x00_4007)
        assertThat(PdbPage.packRowCounts(rowOffsets = 27, validRows = 27)).isEqualTo(0x03_601B)
        // As rekordbox wrote them: columns page 34 of the empty export holds 27 rows.
        assertThat(template.page(34).bytes.copyOfRange(0x18, 0x1C)).isEqualTo(hex("1b6003 24"))
    }

    @Test
    fun `row index takes 36 bytes per full group and 4 plus 2 per row for the last one`() {
        assertThat(PdbPage.rowIndexBytes(0)).isEqualTo(0)
        assertThat(PdbPage.rowIndexBytes(1)).isEqualTo(6)
        assertThat(PdbPage.rowIndexBytes(16)).isEqualTo(36)
        assertThat(PdbPage.rowIndexBytes(17)).isEqualTo(42)
        assertThat(PdbPage.rowIndexBytes(284)).isEqualTo(640)
    }

    @Test
    fun `index pages match rekordbox for empty tables and tables with data`() {
        assertThat(PdbPage.indexPage(PdbTable.TRACKS, firstDataPage = null)).isEqualTo(template.page(1).bytes)
        assertThat(PdbPage.indexPage(PdbTable.ARTWORK, firstDataPage = null)).isEqualTo(template.page(27).bytes)
        assertThat(PdbPage.indexPage(PdbTable.COLORS, firstDataPage = 14)).isEqualTo(template.page(13).bytes)
        assertThat(PdbPage.indexPage(PdbTable.HISTORY, firstDataPage = 40)).isEqualTo(template.page(39).bytes)
    }

    @Test
    fun `a one-row data page matches the sync record page of the empty export`() {
        val page = DataPage(PdbTable.HISTORY)
        assertThat(page.tryAdd(HistoryRow(trackCount = 0, date = "2022-02-02"))).isTrue()

        assertThat(page.encode(pageIndex = 40, nextPage = 41)).isEqualTo(template.page(40).bytes)
        assertThat(page.usedSize).isEqualTo(40)
        assertThat(page.freeSize).isEqualTo(4010)
    }

    @Test
    fun `row groups are written from the page end with presence and transaction flags`() {
        val page = DataPage(PdbTable.KEYS)
        repeat(17) { assertThat(page.tryAdd(KeyRow(it + 1, "Am"))).isTrue() }
        val raw = RawPage(5, page.encode(pageIndex = 5, nextPage = 9, sequence = 3))

        assertThat(raw.pageIndexField).isEqualTo(5)
        assertThat(raw.type).isEqualTo(PdbTable.KEYS.id)
        assertThat(raw.nextPage).isEqualTo(9)
        assertThat(raw.sequence).isEqualTo(3)
        assertThat(raw.flags).isEqualTo(0x24)
        assertThat(raw.rowOffsetCount to raw.validRowCount).isEqualTo(17 to 17)
        assertThat(raw.u16(0x20)).isEqualTo(17)
        assertThat(raw.usedSize).isEqualTo(17 * 12)
        assertThat(raw.freeSize).isEqualTo(4056 - 17 * 12 - 42)
        assertThat(raw.groupFlags(0)).isEqualTo(0xFFFF to 0xFFFF)
        assertThat(raw.groupFlags(1)).isEqualTo(0x0001 to 0x0001)
        assertThat((0 until 17).map(raw::rowOffset)).isEqualTo((0 until 17).map { it * 12 })
        // Group 1 holds only one offset: the bytes before it are free heap, left zero.
        assertThat(raw.bytes.copyOfRange(4096 - 36 - 6, 4096 - 36)).isEqualTo(hex("c000 0100 0100"))
        assertThat(raw.rows.map { it.bytes.toList() }).isEqualTo((1..17).map { KeyRow(it, "Am").encode(0).toList() })
    }

    @Test
    fun `a page refuses the row that does not fit and keeps a non-negative free size`() {
        val page = DataPage(PdbTable.PLAYLIST_ENTRIES)
        var added = 0
        while (page.tryAdd(PlaylistEntryRow(added + 1, added + 1, 1))) added++

        // 284 rows of 12 bytes is what rekordbox fits on a page too (num_rows export, page 18).
        assertThat(added).isEqualTo(284)
        assertThat(page.freeSize).isEqualTo(8)
        assertThat(page.rowCount).isEqualTo(284)
    }

    @Test
    fun `slot shift follows the row position on the page`() {
        val page = DataPage(PdbTable.ARTISTS)
        page.tryAdd(ArtistRow(1, "A"))
        page.tryAdd(ArtistRow(2, "B"))
        val raw = RawPage(6, page.encode(pageIndex = 6, nextPage = 41))
        assertThat(raw.rows.map { it.u16(2) }).containsExactly(0x00, 0x20).inOrder()
    }
}
