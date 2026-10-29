package io.sonora.core.rekordbox.pdb

/**
 * One row of an `export.pdb` table, as rekordbox lays it out on the page heap. [encode] gives the row's heap bytes,
 * 4-byte aligned and padded the way rekordbox pads that row type; [slot] is the row's position in its page's row index,
 * which rows with a subtype header repeat as `index_shift = 0x20 * slot`.
 *
 * New row types (history playlists, colour or menu rows) are added as new implementations without touching the pages.
 */
sealed interface PdbRow {
    val table: PdbTable

    fun encode(slot: Int): ByteArray
}

/**
 * Bytes rekordbox reserves for rows with a subtype header (artists, albums, the sync record): 6 more than the row's
 * length without its alignment gaps, rounded up to 4. Seen in every such row of the reference exports; readers ignore
 * the reserve, it only keeps the pages like rekordbox's.
 */
private const val SUBTYPE_ROW_RESERVE = 6
private const val INDEX_SHIFT_STEP = 0x20
private const val MAX_SLOT = 0x07FF

private fun indexShift(slot: Int): Int {
    require(slot in 0..MAX_SLOT) { "slot out of range: $slot" }
    return slot * INDEX_SHIFT_STEP
}

/** A string at the end of a subtype row, behind a u8 offset; UTF-16 starts 4-byte aligned. Returns the alignment gap. */
private fun LittleEndianBuffer.trailingName(name: String, offsetFieldAt: Int): Int {
    val gap = if (DeviceSqlString.isUtf16(name)) alignTo(4) else 0
    setU8(offsetFieldAt, size)
    bytes(DeviceSqlString.encode(name))
    return gap
}

/** Pads a subtype row like rekordbox: [SUBTYPE_ROW_RESERVE] bytes past its length without the [gap], aligned to 4. */
private fun LittleEndianBuffer.reserveLikeRekordbox(gap: Int) {
    zeros(align4(size - gap + SUBTYPE_ROW_RESERVE) - size)
}

/**
 * A track (table 0): the 0x5E fixed bytes, 21 u16 string offsets (from the row start) and the strings from 0x88.
 *
 * Fields the export does not know (composer, remixer, ratings, play counts...) default to 0 or empty. The defaults of
 * the unknown fields are what rekordbox writes in the larger reference export: the u16 pair at 0x18 (519/52395),
 * `unknownString2/3` ("1", the demo export has "3") and `publishTrackInformation`/`autoloadHotCues` ("ON"); [checksum]
 * (the u32 at 0x14, random-looking in rekordbox's files) is 0 as rex writes it.
 */
data class TrackRow(
    val id: Int,
    val title: String,
    val filePath: String,
    val fileName: String,
    val analyzePath: String,
    val analyzeDate: String,
    val dateAdded: String,
    val sampleRate: Int,
    val fileSize: Long,
    val bitrate: Int,
    val tempoHundredths: Int,
    val sampleDepth: Int,
    val durationSeconds: Int,
    val fileType: Int,
    val artistId: Int = 0,
    val albumId: Int = 0,
    val genreId: Int = 0,
    val labelId: Int = 0,
    val keyId: Int = 0,
    val artworkId: Int = 0,
    val composerId: Int = 0,
    val originalArtistId: Int = 0,
    val remixerId: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val playCount: Int = 0,
    val year: Int = 0,
    val color: Int = 0,
    val rating: Int = 0,
    val checksum: Long = 0,
    val databaseConstants: Pair<Int, Int> = 519 to 52395,
    val comment: String = "",
    val isrc: String = "",
    val lyricist: String = "",
    val unknownString2: String = "1",
    val unknownString3: String = "1",
    val unknownString4: String = "",
    val message: String = "",
    val publishTrackInformation: String = "ON",
    val autoloadHotCues: String = "ON",
    val unknownString5: String = "",
    val unknownString6: String = "",
    val releaseDate: String = "",
    val mixName: String = "",
    val unknownString7: String = "",
    val unknownString8: String = "",
) : PdbRow {
    override val table: PdbTable get() = PdbTable.TRACKS

    /** The 21 strings in their on-disk order; the first, the ISRC, has its own encoding. */
    private val strings: List<String>
        get() = listOf(
            isrc, lyricist, unknownString2, unknownString3, unknownString4, message, publishTrackInformation,
            autoloadHotCues, unknownString5, unknownString6, dateAdded, releaseDate, mixName, unknownString7,
            analyzePath, analyzeDate, comment, title, unknownString8, fileName, filePath,
        )

    override fun encode(slot: Int): ByteArray = littleEndian {
        u16(SUBTYPE)
        u16(indexShift(slot))
        u32(BITMASK)
        u32(sampleRate)
        u32(composerId)
        u32(fileSize)
        u32(checksum)
        u16(databaseConstants.first)
        u16(databaseConstants.second)
        u32(artworkId)
        u32(keyId)
        u32(originalArtistId)
        u32(labelId)
        u32(remixerId)
        u32(bitrate)
        u32(trackNumber)
        u32(tempoHundredths)
        u32(genreId)
        u32(albumId)
        u32(artistId)
        u32(id)
        u16(discNumber)
        u16(playCount)
        u16(year)
        u16(sampleDepth)
        u16(durationSeconds)
        u16(CONSTANT_0X56)
        u8(color)
        u8(rating)
        u16(fileType)
        u16(OFFSET_ARRAY_MARKER)
        val offsetsAt = size
        zeros(2 * STRING_COUNT)
        check(size == STRINGS_START) { "track row header is $size bytes" }
        strings.forEachIndexed { i, text ->
            if (DeviceSqlString.isUtf16(text)) alignTo(4)
            setU16(offsetsAt + 2 * i, size)
            bytes(if (i == 0) DeviceSqlString.encodeIsrc(text) else DeviceSqlString.encode(text))
        }
        alignTo(4)
    }

    private companion object {
        const val SUBTYPE = 0x24
        const val BITMASK = 0x000C_0700L
        const val CONSTANT_0X56 = 0x29
        const val OFFSET_ARRAY_MARKER = 0x0003 // rex: rekordbox crashes on rows without it
        const val STRING_COUNT = 21
        const val STRINGS_START = 0x88
    }
}

/** An artist (table 2): subtype 0x60, index shift, id, the offset-array marker 3 and a u8 offset of the name. */
data class ArtistRow(val id: Int, val name: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.ARTISTS

    override fun encode(slot: Int): ByteArray = littleEndian {
        u16(0x60)
        u16(indexShift(slot))
        u32(id)
        u8(3)
        val nameOffsetAt = size
        u8(0)
        val gap = trailingName(name, nameOffsetAt)
        reserveLikeRekordbox(gap)
    }
}

/** An album (table 3): subtype 0x80, index shift, its artist, id and a u8 offset of the name. */
data class AlbumRow(val id: Int, val name: String, val artistId: Int = 0) : PdbRow {
    override val table: PdbTable get() = PdbTable.ALBUMS

    override fun encode(slot: Int): ByteArray = littleEndian {
        u16(0x80)
        u16(indexShift(slot))
        u32(0)
        u32(artistId)
        u32(id)
        u32(0)
        u8(3)
        val nameOffsetAt = size
        u8(0)
        val gap = trailingName(name, nameOffsetAt)
        reserveLikeRekordbox(gap)
    }
}

/** A genre (table 1): id and name. */
data class GenreRow(val id: Int, val name: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.GENRES

    override fun encode(slot: Int): ByteArray = idAndName(id, name)
}

/** A label (table 4): id and name. */
data class LabelRow(val id: Int, val name: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.LABELS

    override fun encode(slot: Int): ByteArray = idAndName(id, name)
}

/** A key (table 5): id, the id again and the name ([classicName] in rekordbox's own exports). */
data class KeyRow(val id: Int, val name: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.KEYS

    override fun encode(slot: Int): ByteArray = littleEndian {
        u32(id)
        u32(id)
        bytes(DeviceSqlString.encode(name))
        alignTo(4)
    }
}

/** An artwork image (table 13): id and path from the stick root, e.g. `/PIONEER/Artwork/00001/a1.jpg`. */
data class ArtworkRow(val id: Int, val path: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.ARTWORK

    override fun encode(slot: Int): ByteArray = idAndName(id, path)
}

/** A playlist or folder (table 7): parent (0 at the root), 0, its 0-based position among its siblings, id, folder flag. */
data class PlaylistTreeRow(val id: Int, val parentId: Int, val sortOrder: Int, val isFolder: Boolean, val name: String) :
    PdbRow {
    override val table: PdbTable get() = PdbTable.PLAYLIST_TREE

    override fun encode(slot: Int): ByteArray = littleEndian {
        u32(parentId)
        u32(0)
        u32(sortOrder)
        u32(id)
        u32(if (isFolder) 1 else 0)
        bytes(DeviceSqlString.encode(name))
        alignTo(4)
    }
}

/** A track in a playlist (table 8): its 1-based position, the track and the playlist. */
data class PlaylistEntryRow(val entryIndex: Int, val trackId: Int, val playlistId: Int) : PdbRow {
    override val table: PdbTable get() = PdbTable.PLAYLIST_ENTRIES

    override fun encode(slot: Int): ByteArray = littleEndian {
        u32(entryIndex)
        u32(trackId)
        u32(playlistId)
    }
}

/**
 * The sync record (table 19) rekordbox writes on every export: subtype 0x0280, index shift, the number of tracks on the
 * stick, 0, the date (`yyyy-MM-dd`), 0x1E19, the format version "1000" and an empty device label.
 */
data class HistoryRow(val trackCount: Int, val date: String) : PdbRow {
    override val table: PdbTable get() = PdbTable.HISTORY

    override fun encode(slot: Int): ByteArray = littleEndian {
        u16(0x0280)
        u16(indexShift(slot))
        u32(trackCount)
        u32(0)
        bytes(DeviceSqlString.encode(date))
        u16(0x1E19)
        bytes(DeviceSqlString.encode("1000"))
        bytes(DeviceSqlString.encode(""))
        reserveLikeRekordbox(gap = 0)
    }
}

private fun idAndName(id: Int, name: String): ByteArray = littleEndian {
    u32(id)
    bytes(DeviceSqlString.encode(name))
    alignTo(4)
}
