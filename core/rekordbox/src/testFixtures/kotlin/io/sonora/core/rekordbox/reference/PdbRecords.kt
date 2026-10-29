package io.sonora.core.rekordbox.reference

/**
 * One row of the `tracks` table of `export.pdb` (table 0), read from the layout in Deep Symmetry's "Rekordbox Export
 * Structure Analysis" (https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/exports.html, "Track Rows") and
 * crate-digger's `rekordbox_pdb.ksy`. The `*Id` fields point at the rows of the other tables (0 means none);
 * [tempoHundredths] is the tempo in hundredths of a BPM; [analyzePath] is the `.DAT` analysis file from the stick root.
 */
data class PdbTrack(
    val id: Int,
    val title: String,
    val artistId: Int,
    val albumId: Int,
    val genreId: Int,
    val labelId: Int,
    val keyId: Int,
    val artworkId: Int,
    val filePath: String,
    val fileName: String,
    val analyzePath: String,
    val analyzeDate: String,
    val dateAdded: String,
    val comment: String,
    val tempoHundredths: Int,
    val durationSeconds: Int,
    val sampleRate: Long,
    val sampleDepth: Int,
    val bitrate: Long,
    val fileSize: Long,
    val fileType: Int,
    val trackNumber: Long,
    val year: Int,
) {
    val tempoBpm: Double get() = tempoHundredths / 100.0
}

/** One row of the `artists` table (table 2): [id] and [name]. */
data class PdbArtist(val id: Int, val name: String)

/** One row of the `keys` table (table 5): [id] and the key's [name] as the players show it. */
data class PdbKey(val id: Int, val name: String)

/** Row layouts of the typed tables: byte offsets from the start of the row (Deep Symmetry's analysis, `rekordbox_pdb.ksy`). */
internal object PdbRowParser {
    /** Position of the track row's array of 21 `u16` string offsets, each from the start of the row. */
    private const val TRACK_STRING_OFFSETS = 0x5E
    private const val TRACK_ANALYZE_PATH = 14
    private const val TRACK_ANALYZE_DATE = 15
    private const val TRACK_DATE_ADDED = 10
    private const val TRACK_COMMENT = 16
    private const val TRACK_TITLE = 17
    private const val TRACK_FILE_NAME = 19
    private const val TRACK_FILE_PATH = 20

    /** Artist rows with this bit in their subtype (0x64) keep a two-byte name offset at 0x0A instead of the one at 9. */
    private const val FAR_NAME_FLAG = 0x04

    fun track(row: RawRow): PdbTrack {
        fun text(index: Int): String = row.string(row.u16(TRACK_STRING_OFFSETS + 2 * index))
        return PdbTrack(
            id = row.u32(0x48).toInt(),
            title = text(TRACK_TITLE),
            artistId = row.u32(0x44).toInt(),
            albumId = row.u32(0x40).toInt(),
            genreId = row.u32(0x3C).toInt(),
            labelId = row.u32(0x28).toInt(),
            keyId = row.u32(0x20).toInt(),
            artworkId = row.u32(0x1C).toInt(),
            filePath = text(TRACK_FILE_PATH),
            fileName = text(TRACK_FILE_NAME),
            analyzePath = text(TRACK_ANALYZE_PATH),
            analyzeDate = text(TRACK_ANALYZE_DATE),
            dateAdded = text(TRACK_DATE_ADDED),
            comment = text(TRACK_COMMENT),
            tempoHundredths = row.u32(0x38).toInt(),
            durationSeconds = row.u16(0x54),
            sampleRate = row.u32(0x08),
            sampleDepth = row.u16(0x52),
            bitrate = row.u32(0x30),
            fileSize = row.u32(0x10),
            fileType = row.u16(0x5A),
            trackNumber = row.u32(0x34),
            year = row.u16(0x50),
        )
    }

    fun artist(row: RawRow): PdbArtist {
        val nameAt = if (row.u16(0) and FAR_NAME_FLAG != 0) row.u16(0x0A) else row.u8(9)
        return PdbArtist(row.u32(4).toInt(), row.string(nameAt))
    }

    fun key(row: RawRow): PdbKey = PdbKey(row.u32(0).toInt(), row.string(8))
}
