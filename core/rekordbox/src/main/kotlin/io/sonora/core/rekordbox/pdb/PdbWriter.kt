package io.sonora.core.rekordbox.pdb

import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.artwork.ArtworkPaths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Builds a complete `export.pdb` (the classic Device Library the CDJ/XDJ players read) for a [DeviceLibrary].
 *
 * Layout, as in rekordbox's own exports: page 0 holds the header and the directory of all 20 tables; table `T` starts
 * with its index page `2T+1` and its first data page is `2T+2` (a zero page while the table is empty), so pages 0..40
 * always exist. Further data pages are appended after page 40 in table order; the last data page of each table links a
 * fresh page index past the end of the file, its `empty_candidate`, and `next_unused_page` lies past all of them.
 *
 * Colours, columns, menu and the unknown table 18 hold rekordbox's fixed data and are copied from the empty template;
 * every other table is generated, the sync record (table 19) included.
 */
object PdbWriter {
    private val TEMPLATE_TABLES = listOf(PdbTable.COLORS, PdbTable.COLUMNS, PdbTable.MENU, PdbTable.UNKNOWN_18)
    private val GENERATED_TABLES = PdbTable.entries - TEMPLATE_TABLES.toSet()

    /** Pages 0..2T+2 of the last table: the header, then an index page and a reserved page per table. */
    private val BASE_PAGE_COUNT = 2 * PdbTable.entries.size + 1
    private const val TABLE_DIRECTORY_AT = 0x1C
    private const val HEADER_CONSTANT_0X10 = 5
    private const val NEXT_PAGE_AT = 0x0C
    private const val SEQUENCE_AT = 0x14

    fun write(library: DeviceLibrary): ByteArray = write(rowsOf(library))

    /**
     * The rows of every generated table for [library], in the order they are written. Artists, albums (per name and
     * artist), genres, labels and keys are numbered from 1 in order of first use; blank names count as none. Artwork
     * rows keep the tracks' artwork ids, in id order, each pointing at its 80x80 picture ([ArtworkPaths.small]).
     * Playlists become root playlists numbered from 1 in list order.
     */
    fun rowsOf(library: DeviceLibrary): Map<PdbTable, List<PdbRow>> {
        val artists = Numbering<String>()
        val albums = Numbering<Pair<String, Int>>()
        val genres = Numbering<String>()
        val labels = Numbering<String>()
        val keys = Numbering<MusicalKey>()
        val exportDate = library.exportDate.toString()

        val tracks = library.tracks.map { track ->
            val artistId = artists.idOf(track.artist.orNullIfBlank())
            TrackRow(
                id = track.id,
                title = track.title,
                filePath = track.filePath,
                fileName = track.fileName,
                analyzePath = track.analyzePath,
                analyzeDate = exportDate,
                dateAdded = track.dateAdded.toString(),
                sampleRate = track.sampleRate,
                fileSize = track.fileSize,
                bitrate = track.bitrateKbps,
                tempoHundredths = (track.tempoBpm * 100).roundToInt(),
                sampleDepth = track.bitDepth,
                durationSeconds = track.durationSeconds,
                fileType = track.fileType.code,
                artistId = artistId,
                albumId = albums.idOf(track.album.orNullIfBlank()?.let { it to artistId }),
                genreId = genres.idOf(track.genre.orNullIfBlank()),
                labelId = labels.idOf(track.label.orNullIfBlank()),
                keyId = keys.idOf(track.key),
                artworkId = track.artworkId ?: 0,
                trackNumber = track.trackNumber ?: 0,
                year = track.year ?: 0,
                comment = track.comment.orEmpty(),
            )
        }
        val playlists = library.playlists.mapIndexed { i, playlist ->
            PlaylistTreeRow(id = i + 1, parentId = 0, sortOrder = i, isFolder = false, name = playlist.name)
        }
        val entries = library.playlists.flatMapIndexed { i, playlist ->
            playlist.trackIds.mapIndexed { position, trackId -> PlaylistEntryRow(position + 1, trackId, playlistId = i + 1) }
        }
        return mapOf(
            PdbTable.TRACKS to tracks,
            PdbTable.GENRES to genres.rows { id, name -> GenreRow(id, name) },
            PdbTable.ARTISTS to artists.rows { id, name -> ArtistRow(id, name) },
            PdbTable.ALBUMS to albums.rows { id, (name, artistId) -> AlbumRow(id, name, artistId) },
            PdbTable.LABELS to labels.rows { id, name -> LabelRow(id, name) },
            // Alphanumeric names ("8A"): the XDJ-700 shows them as written and has no key display setting (C21).
            PdbTable.KEYS to keys.rows { id, key -> KeyRow(id, key.alphanumeric) },
            PdbTable.PLAYLIST_TREE to playlists,
            PdbTable.PLAYLIST_ENTRIES to entries,
            PdbTable.ARTWORK to library.tracks.mapNotNull { it.artworkId }.distinct().sorted()
                .map { ArtworkRow(it, ArtworkPaths.small(it)) },
            PdbTable.HISTORY to listOf(HistoryRow(trackCount = tracks.size, date = exportDate)),
        ).filterValues { it.isNotEmpty() }
    }

    /** Lays out [rows] (generated tables only; missing tables stay empty) around the template tables. */
    fun write(rows: Map<PdbTable, List<PdbRow>>): ByteArray {
        require(rows.keys.none { it in TEMPLATE_TABLES }) { "colours, columns, menu and table 18 come from the template" }
        val template = PdbTemplates.exportPdb()
        val pages = GENERATED_TABLES.associateWith { paginate(it, rows[it].orEmpty()) }

        var nextFree = BASE_PAGE_COUNT
        val pageIndices = GENERATED_TABLES.associateWith { table ->
            pages.getValue(table).indices.map { if (it == 0) table.firstDataPage else nextFree++ }
        }
        val pageCount = nextFree
        val candidates = mutableMapOf<PdbTable, Int>()
        GENERATED_TABLES.forEach { table ->
            candidates[table] = if (pages.getValue(table).isEmpty()) table.firstDataPage else nextFree++
        }
        TEMPLATE_TABLES.forEach { candidates[it] = nextFree++ }

        val file = ByteArray(pageCount * PdbPage.SIZE)
        fun put(index: Int, page: ByteArray) = page.copyInto(file, index * PdbPage.SIZE)

        val lastPages = mutableMapOf<PdbTable, Int>()
        for (table in GENERATED_TABLES) {
            val tablePages = pages.getValue(table)
            val indices = pageIndices.getValue(table)
            put(table.indexPage, PdbPage.indexPage(table, indices.firstOrNull()))
            tablePages.forEachIndexed { i, page ->
                put(indices[i], page.encode(indices[i], nextPage = indices.getOrElse(i + 1) { candidates.getValue(table) }))
            }
            lastPages[table] = indices.lastOrNull() ?: table.indexPage
        }
        for (table in TEMPLATE_TABLES) {
            put(table.indexPage, PdbPage.indexPage(table, table.firstDataPage))
            val data = templatePage(template, table.firstDataPage)
            check(data.u32(0x08) == table.id && data.u8(0x1B) == PdbPage.FLAGS_DATA) { "template page of $table" }
            data.putInt(NEXT_PAGE_AT, candidates.getValue(table))
            put(table.firstDataPage, data.array())
            lastPages[table] = table.firstDataPage
        }

        val header = ByteBuffer.wrap(file, 0, PdbPage.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x04, PdbPage.SIZE)
        header.putInt(0x08, PdbTable.entries.size)
        header.putInt(0x0C, nextFree)
        header.putInt(0x10, HEADER_CONSTANT_0X10)
        header.putInt(SEQUENCE_AT, templatePage(template, 0).getInt(SEQUENCE_AT))
        PdbTable.entries.forEach { table ->
            val at = TABLE_DIRECTORY_AT + 16 * table.id
            header.putInt(at, table.id)
            header.putInt(at + 4, candidates.getValue(table))
            header.putInt(at + 8, table.indexPage)
            header.putInt(at + 12, lastPages.getValue(table))
        }
        return file
    }

    /** Fills pages in row order; a row that does not fit starts a new page, a row too big for an empty page is refused. */
    private fun paginate(table: PdbTable, rows: List<PdbRow>): List<DataPage> {
        val pages = mutableListOf<DataPage>()
        for (row in rows) {
            if (pages.lastOrNull()?.tryAdd(row) == true) continue
            val page = DataPage(table)
            require(page.tryAdd(row)) { "$row does not fit on an empty page" }
            pages += page
        }
        return pages
    }

    private fun templatePage(template: ByteArray, index: Int): ByteBuffer =
        ByteBuffer.wrap(template.copyOfRange(index * PdbPage.SIZE, (index + 1) * PdbPage.SIZE)).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.u32(at: Int): Int = getInt(at)

    private fun ByteBuffer.u8(at: Int): Int = get(at).toInt() and 0xFF

    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    /** Ids 1, 2, ... in order of first use; `null` keys get id 0, "none". */
    private class Numbering<K : Any> {
        private val ids = LinkedHashMap<K, Int>()

        fun idOf(key: K?): Int = if (key == null) 0 else ids.getOrPut(key) { ids.size + 1 }

        fun <R> rows(row: (Int, K) -> R): List<R> = ids.map { (key, id) -> row(id, key) }
    }
}
