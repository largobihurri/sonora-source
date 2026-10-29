package io.sonora.core.rekordbox.pdb

/**
 * Page layout of `export.pdb` (4096-byte pages, little-endian). Every page starts with a 32-byte header:
 *
 * `0x00` 0 · `0x04` page index · `0x08` table type · `0x0C` next page · `0x10` sequence · `0x14` 0 ·
 * `0x18` 24 bits: row offsets (low 13 bits) and valid rows (high 11 bits) · `0x1B` flags (`0x64` index, `0x24` data) ·
 * `0x1C` u16 free size · `0x1E` u16 used size.
 */
object PdbPage {
    const val SIZE = 4096
    const val HEADER_BYTES = 0x20
    const val HEAP_START = 0x28
    const val HEAP_BYTES = SIZE - HEAP_START

    internal const val FLAGS_INDEX = 0x64
    internal const val FLAGS_DATA = 0x24
    internal const val ROWS_PER_GROUP = 16
    internal const val GROUP_BYTES = 4 + 2 * ROWS_PER_GROUP
    internal const val MAX_ROWS = 0x07FF

    private const val INDEX_TAIL_ZERO_BYTES = 20
    private const val NO_PAGE = 0x03FF_FFFFL
    private const val EMPTY_INDEX_ENTRY = 0x1FFF_FFF8L

    /** The 24-bit row-count field at `0x18`: [rowOffsets] in the low 13 bits, [validRows] in the high 11. */
    fun packRowCounts(rowOffsets: Int, validRows: Int): Int {
        require(rowOffsets in 0..0x1FFF && validRows in 0..MAX_ROWS) { "row counts out of range" }
        return rowOffsets or (validRows shl 13)
    }

    /**
     * Bytes the row index takes at the end of a data page holding [rows] rows: 36 per full group of 16 (16 offsets,
     * presence flags, transaction flags) and 4 plus 2 per row for the last, partial group, which rekordbox writes only
     * that far.
     */
    fun rowIndexBytes(rows: Int): Int {
        val partial = rows % ROWS_PER_GROUP
        return rows / ROWS_PER_GROUP * GROUP_BYTES + if (partial == 0) 0 else 4 + 2 * partial
    }

    /**
     * The index page (`2T+1`) every table starts with, as rekordbox writes it for a freshly written table: the header
     * links the reserved page `2T+2`; the body repeats the page index and the first data page (`0x03FFFFFF` while
     * empty), and has no entries (rekordbox only adds entries for pages with deleted rows).
     */
    fun indexPage(table: PdbTable, firstDataPage: Int?): ByteArray {
        require(firstDataPage == null || firstDataPage == table.firstDataPage) { "first data page must be 2T+2" }
        val bytes = littleEndian {
            header(table.indexPage, table, next = table.firstDataPage, sequence = 1, packedCounts = 0, FLAGS_INDEX, 0, 0)
            u16(0x1FFF)
            u16(0x1FFF)
            u16(0x03EC)
            u16(0) // next entry offset
            u32(table.indexPage)
            u32(firstDataPage?.toLong() ?: NO_PAGE)
            u32(NO_PAGE)
            u32(0)
            u16(0) // entries
            u16(0x1FFF) // first empty entry: none
            while (size < SIZE - INDEX_TAIL_ZERO_BYTES) u32(EMPTY_INDEX_ENTRY)
        }
        return bytes.copyOf(SIZE)
    }

    internal fun LittleEndianBuffer.header(
        pageIndex: Int,
        table: PdbTable,
        next: Int,
        sequence: Int,
        packedCounts: Int,
        flags: Int,
        freeSize: Int,
        usedSize: Int,
    ) {
        u32(0)
        u32(pageIndex)
        u32(table.id)
        u32(next)
        u32(sequence)
        u32(0)
        u8(packedCounts and 0xFF)
        u8(packedCounts ushr 8 and 0xFF)
        u8(packedCounts ushr 16)
        u8(flags)
        u16(freeSize)
        u16(usedSize)
    }
}

/**
 * A data page of [table] being filled. Rows go onto the heap from `0x28` in insertion order; their offsets go into
 * groups of 16 written backwards from the page end. A row is only taken when it fits together with its offset, so a
 * full page refuses it and the caller starts the next page: no row is ever dropped or written past the row index.
 */
class DataPage(val table: PdbTable) {
    private val rows = mutableListOf<ByteArray>()

    val rowCount: Int get() = rows.size

    var usedSize: Int = 0
        private set

    val freeSize: Int get() = PdbPage.HEAP_BYTES - usedSize - PdbPage.rowIndexBytes(rows.size)

    /** Adds [row] in the next slot if it fits on this page; `false` leaves the page unchanged. */
    fun tryAdd(row: PdbRow): Boolean {
        require(row.table == table) { "${row.table} row on a $table page" }
        if (rows.size == PdbPage.MAX_ROWS) return false
        val bytes = row.encode(slot = rows.size)
        check(bytes.size % 4 == 0) { "row of ${bytes.size} bytes is not 4-byte aligned" }
        if (usedSize + bytes.size + PdbPage.rowIndexBytes(rows.size + 1) > PdbPage.HEAP_BYTES) return false
        rows += bytes
        usedSize += bytes.size
        return true
    }

    /**
     * The page as stored at [pageIndex], linking [nextPage]. The data header at `0x20` records one transaction that
     * inserted every row (count, first index 0), as in rekordbox's freshly written pages, and the transaction flags of
     * each group equal its presence flags.
     */
    fun encode(pageIndex: Int, nextPage: Int, sequence: Int = 1): ByteArray {
        val page = littleEndian {
            with(PdbPage) {
                header(
                    pageIndex, table, nextPage, sequence, packRowCounts(rows.size, rows.size), PdbPage.FLAGS_DATA,
                    freeSize, usedSize,
                )
            }
            u16(rows.size)
            u16(0)
            u32(0)
            rows.forEach(::bytes)
        }.copyOf(PdbPage.SIZE)
        var offset = 0
        val offsets = rows.map { row -> offset.also { offset += row.size } }
        offsets.chunked(PdbPage.ROWS_PER_GROUP).forEachIndexed { group, groupOffsets ->
            val end = PdbPage.SIZE - group * PdbPage.GROUP_BYTES
            val presence = (1 shl groupOffsets.size) - 1
            page.putU16(end - 4, presence)
            page.putU16(end - 2, presence)
            groupOffsets.forEachIndexed { i, rowOffset -> page.putU16(end - 6 - 2 * i, rowOffset) }
        }
        return page
    }

    private fun ByteArray.putU16(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = (value ushr 8).toByte()
    }
}
