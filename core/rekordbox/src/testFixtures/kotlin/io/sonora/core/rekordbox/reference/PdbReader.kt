package io.sonora.core.rekordbox.reference

import io.sonora.core.rekordbox.pdb.PdbTable
import java.nio.file.Files
import java.nio.file.Path

private const val PAGE = 4096
private const val HEAP_START = 0x28
private const val HEAP_BYTES = PAGE - HEAP_START

/**
 * Little-endian reads from a byte array, shared by the raw page and row views. A read past the end of [bytes] throws
 * [PdbFormatException]; [baseOffset] is where [bytes] start in the file, when known, for its message.
 */
abstract class LittleEndianBytes(val bytes: ByteArray) {
    protected open val baseOffset: Int get() = 0

    fun u8(offset: Int): Int {
        require(offset, 1)
        return bytes[offset].toInt() and 0xFF
    }

    fun u16(offset: Int): Int {
        require(offset, 2)
        return u8(offset) or (u8(offset + 1) shl 8)
    }

    fun u32(offset: Int): Long {
        require(offset, 4)
        return u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    }

    /** The DeviceSQL string starting at [offset]. */
    fun string(offset: Int): String = PdbReader.decodeString(bytes, offset, baseOffset)

    private fun require(offset: Int, length: Int) {
        if (offset < 0 || offset.toLong() + length > bytes.size) {
            throw PdbFormatException(
                "$length bytes at +$offset lie past the end of a ${bytes.size}-byte structure at $baseOffset",
                baseOffset + offset.coerceAtLeast(0),
                truncated = true,
            )
        }
    }
}

/** One entry of the page-0 table directory. */
data class RawTable(val type: Int, val emptyCandidate: Int, val firstPage: Int, val lastPage: Int)

/**
 * A row as found on a data page: its heap [offset], its [slot] in the row index and the bytes up to the next row.
 * [pageIndex], when given, locates the row in the file for error messages.
 */
class RawRow(val offset: Int, val slot: Int, bytes: ByteArray, private val pageIndex: Int = -1) :
    LittleEndianBytes(bytes) {
    override val baseOffset: Int get() = if (pageIndex < 0) 0 else pageIndex * PAGE + HEAP_START + offset
}

/** One 4096-byte page, header fields decoded. */
class RawPage(val index: Int, bytes: ByteArray) : LittleEndianBytes(bytes) {
    override val baseOffset: Int get() = index * PAGE
    val pageIndexField: Int get() = u32(0x04).toInt()
    val type: Int get() = u32(0x08).toInt()
    val nextPage: Int get() = u32(0x0C).toInt()
    val sequence: Int get() = u32(0x10).toInt()
    private val packedCounts: Int get() = u8(0x18) or (u8(0x19) shl 8) or (u8(0x1A) shl 16)
    val rowOffsetCount: Int get() = packedCounts and 0x1FFF
    val validRowCount: Int get() = packedCounts ushr 13
    val flags: Int get() = u8(0x1B)
    val freeSize: Int get() = u16(0x1C)
    val usedSize: Int get() = u16(0x1E)
    val isIndex: Boolean get() = flags and 0x40 != 0
    val isZero: Boolean get() = bytes.all { it.toInt() == 0 }

    /** Presence flags and transaction flags of row group [group], counted from the end of the page. */
    fun groupFlags(group: Int): Pair<Int, Int> = u16(PAGE - 36 * group - 4) to u16(PAGE - 36 * group - 2)

    /** Heap offset of row [slot]. */
    fun rowOffset(slot: Int): Int = u16(PAGE - 36 * (slot / 16) - 6 - 2 * (slot % 16))

    /** The present rows in slot order, each cut at the next row's start (or at `used_size`). */
    val rows: List<RawRow>
        get() {
            val present = (0 until rowOffsetCount).filter { slot -> groupFlags(slot / 16).first shr (slot % 16) and 1 == 1 }
            val starts = present.map(::rowOffset).sorted()
            return present.map { slot ->
                val start = rowOffset(slot)
                val end = starts.firstOrNull { it > start } ?: usedSize
                if (end < start || HEAP_START + end > PAGE) {
                    throw PdbFormatException("row $slot of page $index spans heap $start..$end", index * PAGE + HEAP_START + start)
                }
                RawRow(start, slot, bytes.copyOfRange(HEAP_START + start, HEAP_START + end), index)
            }
        }
}

/** A parsed `export.pdb`: the raw pages and the typed rows of the tables the calibration tool reads. */
class RawPdb(val file: ByteArray) {
    private val header = object : LittleEndianBytes(file) {}
    val pageSize: Int get() = header.u32(0x04).toInt()
    val tableCount: Int get() = header.u32(0x08).toInt()
    val nextUnusedPage: Int get() = header.u32(0x0C).toInt()
    val sequence: Int get() = header.u32(0x14).toInt()
    val pageCount: Int get() = file.size / PAGE

    val tables: List<RawTable>
        get() = List(tableCount) { i ->
            val at = 0x1C + 16 * i
            RawTable(header.u32(at).toInt(), header.u32(at + 4).toInt(), header.u32(at + 8).toInt(), header.u32(at + 12).toInt())
        }

    fun page(index: Int): RawPage {
        if (index < 0 || index >= pageCount) {
            throw PdbFormatException("page $index is outside the file's $pageCount pages", index.toLong().times(PAGE).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), truncated = index >= pageCount)
        }
        return RawPage(index, file.copyOfRange(index * PAGE, (index + 1) * PAGE))
    }

    /** The pages of [table], index page first, following `next_page` from `first_page` to `last_page`. */
    fun chain(table: PdbTable): List<RawPage> {
        val entry = tables[table.id]
        val pages = mutableListOf(page(entry.firstPage))
        while (pages.last().index != entry.lastPage) {
            if (pages.size > pageCount) throw PdbFormatException("page chain of $table loops", entry.firstPage * PAGE)
            pages += page(pages.last().nextPage)
        }
        return pages
    }

    fun dataPages(table: PdbTable): List<RawPage> = chain(table).filterNot { it.isIndex }

    fun rows(table: PdbTable): List<RawRow> = dataPages(table).flatMap { it.rows }

    /** The rows of the tracks table (0), typed. */
    val tracks: List<PdbTrack> get() = rows(PdbTable.TRACKS).map(PdbRowParser::track)

    /** The rows of the artists table (2), typed. */
    val artists: List<PdbArtist> get() = rows(PdbTable.ARTISTS).map(PdbRowParser::artist)

    /** The rows of the keys table (5), typed. */
    val keys: List<PdbKey> get() = rows(PdbTable.KEYS).map(PdbRowParser::key)

    /** Key names by key id. */
    val keyNames: Map<Int, String> get() = keys.associate { it.id to it.name }
}

/**
 * Reader of `export.pdb`, the Device Library database of rekordbox exports, independent of this repository's writer so
 * it can check it, and usable on files written by rekordbox itself. Pages are 4096 bytes and everything is
 * little-endian, unlike the big-endian ANLZ files.
 *
 * Format, as published: Deep Symmetry, "Rekordbox Export Structure Analysis", Database Exports
 * (https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/exports.html: file and page headers, row groups,
 * DeviceSQL strings, track, artist and key rows) and crate-digger's `rekordbox_pdb.ksy`
 * (https://github.com/Deep-Symmetry/crate-digger); rekordcrate (https://github.com/Holzhaus/rekordcrate) agrees on
 * the same layouts.
 *
 * [read] checks what it can without walking the tables (a whole number of pages, the page size, every table's first and
 * last page inside the file) and throws [PdbFormatException]; reading a table's rows follows its page chain and throws
 * the same when a page or a row is cut. [checkStructure] is the stricter check the writer's own files must pass.
 */
object PdbReader {
    /** @throws PdbFormatException when [file] is not a whole `export.pdb`. */
    fun read(file: ByteArray): RawPdb {
        if (file.size < PAGE || file.size % PAGE != 0) {
            throw PdbFormatException("file of ${file.size} bytes is not a whole number of $PAGE-byte pages", file.size, truncated = true)
        }
        val pdb = RawPdb(file)
        if (pdb.pageSize != PAGE) throw PdbFormatException("page size is ${pdb.pageSize}, $PAGE expected", 4)
        if (pdb.tableCount !in 0..(PAGE - 0x1C) / 16) throw PdbFormatException("table count ${pdb.tableCount}", 8)
        for (table in pdb.tables) {
            for (page in listOf(table.firstPage, table.lastPage)) {
                if (page !in 0 until pdb.pageCount) {
                    throw PdbFormatException(
                        "table ${table.type} uses page $page, the file has ${pdb.pageCount} pages", file.size, truncated = page >= pdb.pageCount,
                    )
                }
            }
        }
        return pdb
    }

    /** Reads the file at [path]. */
    fun read(path: Path): RawPdb = read(Files.readAllBytes(path))

    /**
     * The DeviceSQL string at [offset] of [bytes] (a row, which is [baseOffset] bytes into the file): a short ASCII
     * string (odd first byte, length in its upper bits), or a long one (`0x40` ASCII, `0x90` UTF-16LE, or `0x90` with
     * a 3 marker for ISO-8859 text as the ISRC uses it).
     */
    fun decodeString(bytes: ByteArray, offset: Int, baseOffset: Int = 0): String {
        fun cut(): Nothing =
            throw PdbFormatException("string at +$offset runs past the end of its ${bytes.size}-byte row", baseOffset + offset, true)
        if (offset < 0 || offset >= bytes.size) cut()
        val kind = bytes[offset].toInt() and 0xFF
        if (kind and 1 == 1) {
            val length = (kind ushr 1) - 1
            if (length < 0 || offset + 1 + length > bytes.size) cut()
            return String(bytes, offset + 1, length, Charsets.US_ASCII)
        }
        if (offset + 4 > bytes.size) cut()
        val length = (bytes[offset + 1].toInt() and 0xFF) or ((bytes[offset + 2].toInt() and 0xFF) shl 8)
        if (length < 4 || offset + length > bytes.size) cut()
        return when (kind) {
            0x40 -> String(bytes, offset + 4, length - 4, Charsets.US_ASCII)
            0x90 -> if (bytes[offset + 4].toInt() == 3) {
                String(bytes, offset + 5, maxOf(length - 6, 0), Charsets.US_ASCII)
            } else {
                String(bytes, offset + 4, length - 4, Charsets.UTF_16LE)
            }
            else -> throw PdbFormatException("unknown DeviceSQL string kind 0x%02x at +%d".format(kind, offset), baseOffset + offset)
        }
    }

    /**
     * Checks the layout rules of a freshly written file: page-0 header, every table chain from its index page to its last
     * page, page headers, row index groups, `free_size`/`used_size`, non-overlapping 4-byte aligned rows, empty
     * candidates and `next_unused_page`, and that every page of the file is either in exactly one chain or all zeros.
     */
    fun checkStructure(pdb: RawPdb) {
        check(pdb.file.size % PAGE == 0) { "file size ${pdb.file.size} is not a whole number of pages" }
        check(pdb.pageSize == PAGE && pdb.tableCount == 20) { "bad page-0 header" }
        val owner = arrayOfNulls<PdbTable>(pdb.pageCount)
        val referenced = mutableSetOf<Int>()
        for (table in PdbTable.entries) {
            val entry = pdb.tables[table.id]
            check(entry.type == table.id) { "directory entry ${table.id} has type ${entry.type}" }
            check(entry.firstPage == table.indexPage) { "$table starts at ${entry.firstPage}" }
            val chain = pdb.chain(table)
            chain.forEach { page ->
                check(page.index < pdb.pageCount) { "$table page ${page.index} lies past the end of the file" }
                check(owner[page.index] == null) { "page ${page.index} is in both ${owner[page.index]} and $table" }
                owner[page.index] = table
                check(page.pageIndexField == page.index && page.type == table.id) { "page ${page.index} header" }
            }
            checkIndexPage(chain.first(), firstData = chain.getOrNull(1)?.index)
            chain.drop(1).forEach(::checkDataPage)
            check(chain.last().nextPage == entry.emptyCandidate) { "$table last page does not link its empty candidate" }
            val candidate = entry.emptyCandidate
            check(candidate >= pdb.pageCount || pdb.page(candidate).isZero) { "$table candidate $candidate is in use" }
            referenced += chain.map { it.index } + candidate
        }
        check(pdb.nextUnusedPage > referenced.max()) { "next_unused_page ${pdb.nextUnusedPage} is already used" }
        val candidates = pdb.tables.map { it.emptyCandidate }
        check(candidates.toSet().size == candidates.size) { "two tables share an empty candidate" }
        for (index in 1 until pdb.pageCount) {
            check(owner[index] != null || pdb.page(index).isZero) { "page $index is in no table but not empty" }
        }
        val lastUsed = owner.indices.last { owner[it] != null }
        check(lastUsed == pdb.pageCount - 1) { "file has trailing pages after page $lastUsed" }
    }

    private fun checkIndexPage(page: RawPage, firstData: Int?) {
        check(page.flags == 0x64 && page.rowOffsetCount == 0 && page.freeSize == 0 && page.usedSize == 0) {
            "index page ${page.index} header"
        }
        check(page.nextPage == page.index + 1) { "index page ${page.index} does not link its reserved page" }
        check(page.u16(0x20) == 0x1FFF && page.u16(0x22) == 0x1FFF && page.u16(0x24) == 0x03EC) { "index header" }
        check(page.u32(0x28).toInt() == page.index) { "index page ${page.index} repeats another index" }
        check(page.u32(0x2C) == (firstData?.toLong() ?: 0x03FF_FFFFL)) { "index page ${page.index} next" }
        check(page.u32(0x30) == 0x03FF_FFFFL && page.u32(0x34) == 0L) { "index page ${page.index} magic" }
        check(page.u16(0x38) == 0 && page.u16(0x3A) == 0x1FFF) { "index page ${page.index} has entries" }
        for (at in 0x3C until PAGE - 20 step 4) check(page.u32(at) == 0x1FFF_FFF8L) { "index entry at $at" }
        for (at in PAGE - 20 until PAGE) check(page.u8(at) == 0) { "index page tail at $at" }
    }

    private fun checkDataPage(page: RawPage) {
        val count = page.rowOffsetCount
        check(page.flags == 0x24) { "data page ${page.index} flags 0x%02x".format(page.flags) }
        check(count in 1..2047 && page.validRowCount == count) { "data page ${page.index} row counts" }
        check(page.u16(0x20) == count && page.u16(0x22) == 0 && page.u32(0x24) == 0L) { "data page ${page.index} 0x20" }
        val indexBytes = rowIndexBytes(count)
        check(page.usedSize % 4 == 0) { "used_size ${page.usedSize} not aligned" }
        check(page.freeSize == HEAP_BYTES - page.usedSize - indexBytes) { "page ${page.index} free_size ${page.freeSize}" }
        check(page.usedSize + indexBytes <= HEAP_BYTES) { "page ${page.index} overflows its row index" }
        for (group in 0 until (count + 15) / 16) {
            val rowsInGroup = minOf(16, count - 16 * group)
            val mask = (1 shl rowsInGroup) - 1
            check(page.groupFlags(group) == mask to mask) { "page ${page.index} group $group flags" }
        }
        val offsets = (0 until count).map(page::rowOffset)
        check(offsets == offsets.sorted() && offsets.toSet().size == count) { "page ${page.index} offsets out of order" }
        check(offsets.first() == 0 && offsets.all { it % 4 == 0 && it < page.usedSize }) { "page ${page.index} offsets" }
        val heapEnd = HEAP_START + page.usedSize
        for (at in heapEnd until PAGE - indexBytes) check(page.u8(at) == 0) { "page ${page.index} garbage at $at" }
    }

    /** Bytes the row index takes at the end of a data page with [rows] rows: 36 per full group, 4 + 2n for the rest. */
    fun rowIndexBytes(rows: Int): Int = rows / 16 * 36 + if (rows % 16 == 0) 0 else 4 + 2 * (rows % 16)
}
