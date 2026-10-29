package io.sonora.core.data.library.duplicates.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The duplicate finder's tables in the library index. */
@Dao
abstract class DuplicateDao {

    /** Every indexed file, hidden songs included: a hidden copy still takes space on the phone. */
    @Query(
        "SELECT document_uri, title, artist, tag_title, tag_artist, tag_album, tag_album_artist, genre, year, " +
            "track_number, duration_ms, size_bytes, last_modified_ms, mime_type, codec, bitrate_kbps, sample_rate_hz, " +
            "bits_per_sample, channel_count, folder_path, file_name, embedded_artwork_uri, date_added_sec FROM tracks",
    )
    abstract suspend fun files(): List<DuplicateFileRow>

    @Query("SELECT document_uri FROM tracks")
    abstract suspend fun fileUris(): List<String>

    // Dismissals

    @Query("SELECT * FROM duplicate_dismissals")
    abstract suspend fun dismissals(): List<DuplicateDismissalEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDismissals(rows: List<DuplicateDismissalEntity>)

    // Fingerprints

    @Query("SELECT * FROM duplicate_fingerprints")
    abstract suspend fun fingerprints(): List<DuplicateFingerprintEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFingerprints(rows: List<DuplicateFingerprintEntity>)

    @Query("DELETE FROM duplicate_fingerprints WHERE document_uri IN (:documentUris)")
    abstract suspend fun deleteFingerprintRows(documentUris: List<String>)

    /** Stores [rows] and drops the fingerprints of [gone] files, at once. */
    @Transaction
    open suspend fun updateFingerprints(rows: List<DuplicateFingerprintEntity>, gone: Collection<String>) {
        // API 29's SQLite allows at most 999 bound arguments per statement.
        gone.chunked(MAX_BOUND_ARGS).forEach { deleteFingerprintRows(it) }
        rows.chunked(MAX_BOUND_ARGS).forEach { upsertFingerprints(it) }
    }

    // Settings: one row, created with its defaults on first write.

    @Query("SELECT * FROM duplicate_settings WHERE id = 0")
    abstract fun settings(): Flow<DuplicateSettingsEntity?>

    @Query("SELECT * FROM duplicate_settings WHERE id = 0")
    abstract suspend fun getSettings(): DuplicateSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSettings(row: DuplicateSettingsEntity)

    /** Changes the settings row atomically, starting from its defaults. */
    @Transaction
    open suspend fun editSettings(change: (DuplicateSettingsEntity) -> DuplicateSettingsEntity) {
        putSettings(change(getSettings() ?: DuplicateSettingsEntity()).copy(id = DuplicateSettingsEntity.ROW))
    }

    private companion object {
        const val MAX_BOUND_ARGS = 900
    }
}
