package io.sonora.core.data.library.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import io.sonora.core.data.archive.db.ArchiveImportDao
import io.sonora.core.data.archive.db.ArchiveImportEntity
import io.sonora.core.data.archive.db.ArchiveImportSongEntity
import io.sonora.core.data.backup.db.BackupDao
import io.sonora.core.data.conversion.db.ConversionBatchEntity
import io.sonora.core.data.conversion.db.ConversionBatchItemEntity
import io.sonora.core.data.conversion.db.ConversionHistoryDao
import io.sonora.core.data.library.duplicates.db.DuplicateDao
import io.sonora.core.data.library.duplicates.db.DuplicateDismissalEntity
import io.sonora.core.data.library.duplicates.db.DuplicateFingerprintEntity
import io.sonora.core.data.library.duplicates.db.DuplicateSettingsEntity
import io.sonora.core.data.playlist.db.PlaylistDao
import io.sonora.core.data.playlist.db.PlaylistEntity
import io.sonora.core.data.playlist.db.PlaylistEntryEntity
import io.sonora.core.domain.library.AlbumGrouping
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        TrackEntity::class,
        LibraryFolderEntity::class,
        TagReadFailureEntity::class,
        ConversionBatchEntity::class,
        ConversionBatchItemEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        FavouriteEntity::class,
        PinEntity::class,
        HiddenItemEntity::class,
        PlayCountEntity::class,
        ArchiveImportEntity::class,
        ArchiveImportSongEntity::class,
        DuplicateDismissalEntity::class,
        DuplicateFingerprintEntity::class,
        DuplicateSettingsEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // 2: tag_read_failures
        AutoMigration(from = 2, to = 3), // 3: tag_read_failures.last_failure_ms, tracks (folder_tree_uri, folder_path) index
        AutoMigration(from = 3, to = 4), // 4: converter history (conversion_batches, conversion_batch_items)
        AutoMigration(from = 4, to = 5), // 5: playlists (playlists, playlist_entries)
        AutoMigration(from = 5, to = 6), // 6: tracks.codec, bitrate_kbps, release_date, copyright, details_version
        AutoMigration(from = 6, to = 7), // 7: favourites, pins, hidden_items
        AutoMigration(from = 7, to = 8), // 8: play_counts
        AutoMigration(from = 8, to = 9), // 9: archive import history (archive_imports, archive_import_songs)
        AutoMigration(from = 9, to = 10), // 10: duplicates (duplicate_dismissals, duplicate_fingerprints, duplicate_settings)
        // 11: conversion_batch_items.profile_id, filled from each item's batch
        AutoMigration(from = 10, to = 11, spec = LibraryDatabase.ItemProfileMigration::class),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    /** Version 11 gives every converted file its own profile: existing files take their batch's. */
    class ItemProfileMigration : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE conversion_batch_items SET profile_id = " +
                    "(SELECT b.profile_id FROM conversion_batches b WHERE b.id = conversion_batch_items.batch_id) " +
                    "WHERE profile_id IS NULL",
            )
        }
    }

    abstract fun libraryDao(): LibraryDao

    abstract fun conversionHistoryDao(): ConversionHistoryDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun libraryMarksDao(): LibraryMarksDao

    abstract fun playCountDao(): PlayCountDao

    abstract fun archiveImportDao(): ArchiveImportDao

    abstract fun duplicateDao(): DuplicateDao

    /** Backup and restore over the tables above; no tables of its own. */
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "library-index.db"
    }
}

/** New location columns for a file whose content did not change. */
data class LocationUpdate(val documentUri: String, val folderPath: String, val folderImageUri: String?)

// Every column below is equal across an album's tracks after grouping; MAX() just picks it deterministically.
/**
 * Tracks still in the library: not removed from Sonora themselves, through their album, or through their artist
 * (as track or album artist). Hidden rows stay indexed so restoring one needs no rescan.
 */
private const val VISIBLE = "document_uri NOT IN (SELECT item_key FROM hidden_items WHERE kind = 'SONG') " +
    "AND CAST(album_id AS TEXT) NOT IN (SELECT item_key FROM hidden_items WHERE kind = 'ALBUM') " +
    "AND CAST(artist_id AS TEXT) NOT IN (SELECT item_key FROM hidden_items WHERE kind = 'ARTIST') " +
    "AND CAST(album_artist_id AS TEXT) NOT IN (SELECT item_key FROM hidden_items WHERE kind = 'ARTIST')"

private const val ALBUM_SELECT = "SELECT album_id, MAX(album) AS album, MAX(album_artist) AS album_artist, " +
    "MAX(album_artist_id) AS album_artist_id, MAX(year) AS year, COUNT(*) AS track_count, " +
    "MAX(artwork_uri) AS artwork_uri, MAX(date_added_sec) AS date_added_sec, " +
    "MAX(is_compilation) AS is_compilation FROM tracks WHERE $VISIBLE"
private const val ALBUM_GROUP = "GROUP BY album_id"
private const val NO_TRACK = "2147483647"
private const val TITLE_ORDER = "title COLLATE NOCASE, id"

/** Newest added first; songs added together (a scan stamps them alike) in album order. */
private const val NEWEST_ADDED_ORDER = "date_added_sec DESC, album COLLATE NOCASE, album_id, COALESCE(disc_number, 0), " +
    "COALESCE(track_number, $NO_TRACK), $TITLE_ORDER"

/**
 * Folder paths compare below the library folder's root: a path minus its first segment, the library folder's name
 * (`''` for `Music`, `/Rock/Live` for `Music/Rock/Live`). Stored paths keep the name the folder had when it was
 * scanned, so a folder listed under its current name still finds every track of its tree.
 */
private const val STORED_BELOW_ROOT = "substr(folder_path, instr(folder_path || '/', '/'))"
private const val ASKED_BELOW_ROOT = "substr(:path, instr(:path || '/', '/'))"
private const val AT_FOLDER = "folder_tree_uri = :treeUri AND $STORED_BELOW_ROOT = $ASKED_BELOW_ROOT"
private const val IN_FOLDER = "folder_tree_uri = :treeUri AND ($STORED_BELOW_ROOT = $ASKED_BELOW_ROOT OR " +
    "substr($STORED_BELOW_ROOT, 1, length($ASKED_BELOW_ROOT) + 1) = $ASKED_BELOW_ROOT || '/')"

@Dao
abstract class LibraryDao {

    // Folders

    @Query("SELECT * FROM library_folders ORDER BY display_name COLLATE NOCASE, tree_uri")
    abstract fun folders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders ORDER BY added_at_ms, tree_uri")
    abstract suspend fun folderList(): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders WHERE tree_uri = :treeUri")
    abstract suspend fun folder(treeUri: String): LibraryFolderEntity?

    /** Keeps an existing entry (and its added time) when the folder is added again. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE tree_uri = :treeUri")
    abstract suspend fun deleteFolderRow(treeUri: String)

    @Query("DELETE FROM tracks WHERE folder_tree_uri = :treeUri")
    abstract suspend fun deleteTracksInFolder(treeUri: String)

    @Query("DELETE FROM tag_read_failures WHERE folder_tree_uri = :treeUri")
    abstract suspend fun deleteReadFailuresInFolder(treeUri: String)

    @Transaction
    open suspend fun removeFolder(treeUri: String) {
        deleteTracksInFolder(treeUri)
        deleteReadFailuresInFolder(treeUri)
        deleteFolderRow(treeUri)
    }

    // Scanning

    @Query(
        "SELECT document_uri, size_bytes, last_modified_ms, date_added_sec, folder_path, folder_image_uri, " +
            "embedded_artwork_uri, details_version FROM tracks WHERE folder_tree_uri = :treeUri",
    )
    abstract suspend fun indexedFiles(treeUri: String): List<IndexedFileRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE document_uri IN (:documentUris)")
    abstract suspend fun deleteTracks(documentUris: List<String>)

    @Query("UPDATE tracks SET folder_path = :folderPath, folder_image_uri = :folderImageUri WHERE document_uri = :documentUri")
    abstract suspend fun updateLocation(documentUri: String, folderPath: String, folderImageUri: String?)

    /** Drops [removed] files and applies [moved] locations atomically. */
    @Transaction
    open suspend fun applyListing(removed: List<String>, moved: List<LocationUpdate>) {
        // API 29's SQLite allows at most 999 bound arguments per statement.
        removed.chunked(MAX_BOUND_ARGS).forEach { deleteTracks(it) }
        moved.forEach { updateLocation(it.documentUri, it.folderPath, it.folderImageUri) }
    }

    @Query("SELECT * FROM tag_read_failures WHERE folder_tree_uri = :treeUri")
    abstract suspend fun readFailures(treeUri: String): List<TagReadFailureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReadFailures(failures: List<TagReadFailureEntity>)

    @Query("DELETE FROM tag_read_failures WHERE document_uri IN (:documentUris)")
    abstract suspend fun deleteReadFailureRows(documentUris: List<String>)

    @Transaction
    open suspend fun deleteReadFailures(documentUris: Collection<String>) {
        documentUris.chunked(MAX_BOUND_ARGS).forEach { deleteReadFailureRows(it) }
    }

    /** Stores one batch of read [tracks] with the failure counts it raised ([failures]) and cleared ([cleared]). */
    @Transaction
    open suspend fun storeBatch(
        tracks: List<TrackEntity>,
        failures: List<TagReadFailureEntity>,
        cleared: Collection<String>,
    ) {
        upsertTracks(tracks)
        upsertReadFailures(failures)
        deleteReadFailures(cleared)
    }

    /**
     * Stores one retagged file's row and regroups the whole library, as a scan does at its end: a new album or artist
     * can move the file to another album or change the albums it leaves. Its read-failure record no longer applies.
     * A row that left the library meanwhile (its folder removed, a scan dropping it) is not brought back: returns
     * whether the row was stored.
     */
    @Transaction
    open suspend fun storeRetagged(track: TrackEntity): Boolean {
        if (trackByUri(track.documentUri) == null) return false
        updateTracks(listOf(track))
        deleteReadFailureRows(listOf(track.documentUri))
        val all = allTracks()
        val resolution = AlbumGrouping.resolve(all.map { it.toScanned() })
        val updated = all.mapNotNull { row -> resolution[row.documentUri]?.let(row::withResolution)?.takeIf { it != row } }
        if (updated.isNotEmpty()) updateTracks(updated)
        return true
    }

    /** Tracks whose folder was removed while a scan was still writing them. */
    @Query("DELETE FROM tracks WHERE folder_tree_uri NOT IN (SELECT tree_uri FROM library_folders)")
    abstract suspend fun deleteOrphanTracks()

    @Query("DELETE FROM tag_read_failures WHERE folder_tree_uri NOT IN (SELECT tree_uri FROM library_folders)")
    abstract suspend fun deleteOrphanReadFailures()

    @Query("SELECT * FROM tracks")
    abstract suspend fun allTracks(): List<TrackEntity>

    /** Every indexed file's URI, hidden songs included. */
    @Query("SELECT document_uri FROM tracks")
    abstract suspend fun allTrackUris(): List<String>

    @Query("SELECT * FROM tracks WHERE document_uri = :documentUri")
    abstract suspend fun trackByUri(documentUri: String): TrackEntity?

    @Update
    abstract suspend fun updateTracks(tracks: List<TrackEntity>)

    // Library reads

    @Query("SELECT * FROM tracks WHERE $VISIBLE ORDER BY $TITLE_ORDER")
    abstract fun tracksByTitle(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE $VISIBLE ORDER BY album COLLATE NOCASE, COALESCE(disc_number, 0), " +
            "COALESCE(track_number, $NO_TRACK), $TITLE_ORDER",
    )
    abstract fun tracksByAlbum(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE $VISIBLE ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, " +
            "COALESCE(track_number, $NO_TRACK), $TITLE_ORDER",
    )
    abstract fun tracksByArtist(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE album_id = :albumId AND $VISIBLE " +
            "ORDER BY COALESCE(disc_number, 0), COALESCE(track_number, $NO_TRACK), $TITLE_ORDER",
    )
    abstract fun tracksForAlbum(albumId: Long): Flow<List<TrackEntity>>

    /** The artist's own songs (as track artist), by title. */
    @Query("SELECT * FROM tracks WHERE artist_id = :artistId AND $VISIBLE ORDER BY $TITLE_ORDER")
    abstract fun tracksForArtist(artistId: Long): Flow<List<TrackEntity>>

    // Smart playlists

    /** Songs added at or after [sinceSec], newest first. */
    @Query("SELECT * FROM tracks WHERE date_added_sec >= :sinceSec AND $VISIBLE ORDER BY $NEWEST_ADDED_ORDER")
    abstract fun recentlyAddedTracks(sinceSec: Long): Flow<List<TrackEntity>>

    /** Up to [limit] songs played at least once, most plays first, ties by the latest play. Re-emits on plays. */
    @Query(
        "SELECT tracks.* FROM tracks JOIN play_counts ON track_uri = document_uri WHERE play_count > 0 AND $VISIBLE " +
            "ORDER BY play_count DESC, last_played_ms DESC, $TITLE_ORDER LIMIT :limit",
    )
    abstract fun mostPlayedTracks(limit: Int): Flow<List<TrackEntity>>

    /** Songs never played, newest added first. Re-emits on plays. */
    @Query(
        "SELECT * FROM tracks WHERE document_uri NOT IN (SELECT track_uri FROM play_counts WHERE play_count > 0) " +
            "AND $VISIBLE ORDER BY $NEWEST_ADDED_ORDER",
    )
    abstract fun neverPlayedTracks(): Flow<List<TrackEntity>>

    @Query("$ALBUM_SELECT $ALBUM_GROUP ORDER BY album COLLATE NOCASE, album_id")
    abstract fun albums(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT $ALBUM_GROUP ORDER BY date_added_sec DESC, album_id LIMIT :limit")
    abstract fun recentlyAddedAlbums(limit: Int): Flow<List<AlbumRow>>

    /** Albums by the artist plus albums (compilations) where the artist has a track. */
    @Query(
        "$ALBUM_SELECT AND album_id IN (SELECT album_id FROM tracks WHERE artist_id = :artistId " +
            "OR album_artist_id = :artistId) $ALBUM_GROUP ORDER BY album COLLATE NOCASE, album_id",
    )
    abstract fun albumsForArtist(artistId: Long): Flow<List<AlbumRow>>

    @Query(
        "SELECT artist_id, MAX(artist) AS artist, COUNT(DISTINCT album_id) AS album_count, " +
            "COUNT(*) AS track_count, MAX(date_added_sec) AS date_added_sec FROM tracks WHERE $VISIBLE GROUP BY artist_id ORDER BY artist COLLATE NOCASE, artist_id",
    )
    abstract fun artists(): Flow<List<ArtistRow>>

    /**
     * Every visible album of each (track) artist that has artwork, with the album's artwork, date added and the total
     * plays of its visible songs (all of them, other artists' songs on a compilation included). Re-emits on plays.
     */
    @Query(
        "SELECT pairs.artist_id AS artist_id, albums.album_id AS album_id, albums.artwork_uri AS artwork_uri, " +
            "albums.plays AS plays, albums.date_added_sec AS date_added_sec " +
            "FROM (SELECT DISTINCT artist_id, album_id FROM tracks WHERE $VISIBLE) AS pairs " +
            "JOIN (SELECT album_id, MAX(artwork_uri) AS artwork_uri, MAX(date_added_sec) AS date_added_sec, " +
            "SUM(COALESCE(play_count, 0)) AS plays FROM tracks LEFT JOIN play_counts ON track_uri = document_uri " +
            "WHERE $VISIBLE GROUP BY album_id) AS albums ON albums.album_id = pairs.album_id " +
            "WHERE albums.artwork_uri IS NOT NULL",
    )
    abstract fun artistAlbumCovers(): Flow<List<ArtistAlbumCoverRow>>

    // Folder browsing. Paths compare exactly (substr, not LIKE: LIKE folds ASCII case and treats % and _ as wildcards).

    @Query(
        "SELECT f.tree_uri, f.display_name, COUNT(t.id) AS track_count FROM library_folders f " +
            "LEFT JOIN (SELECT * FROM tracks WHERE $VISIBLE) t ON t.folder_tree_uri = f.tree_uri GROUP BY f.tree_uri, f.display_name, f.added_at_ms " +
            "ORDER BY f.display_name COLLATE NOCASE, f.added_at_ms, f.tree_uri",
    )
    abstract fun folderRoots(): Flow<List<FolderRootRow>>

    /** Track counts of [path] and every directory below it, in library folder [treeUri], by stored path. */
    @Query("SELECT folder_path, COUNT(*) AS track_count FROM tracks WHERE $IN_FOLDER AND $VISIBLE GROUP BY folder_path")
    abstract fun folderTrackCounts(treeUri: String, path: String): Flow<List<FolderCountRow>>

    @Query("SELECT * FROM tracks WHERE $AT_FOLDER AND $VISIBLE")
    abstract fun tracksInFolder(treeUri: String, path: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE $IN_FOLDER AND $VISIBLE")
    abstract suspend fun tracksUnderFolder(treeUri: String, path: String): List<TrackEntity>

    private companion object {
        const val MAX_BOUND_ARGS = 900
    }
}
