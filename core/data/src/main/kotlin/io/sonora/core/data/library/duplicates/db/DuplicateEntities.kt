package io.sonora.core.data.library.duplicates.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.sonora.core.domain.library.duplicates.DuplicateFingerprint

/**
 * One file of a group the user marked "Not a duplicate"; [dismissalId] names the group (a stable hash of its files),
 * so a group is a set of rows.
 */
@Entity(tableName = "duplicate_dismissals", primaryKeys = ["dismissal_id", "document_uri"])
data class DuplicateDismissalEntity(
    @ColumnInfo(name = "dismissal_id") val dismissalId: Long,
    @ColumnInfo(name = "document_uri") val documentUri: String,
)

/** What the duplicate scans learnt about one file, valid while its size and modification time stay the same. */
@Entity(tableName = "duplicate_fingerprints")
data class DuplicateFingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "document_uri") val documentUri: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    @ColumnInfo(name = "song_key") val songKey: String?,
    @ColumnInfo(name = "rules_version") val rulesVersion: Int,
) {
    fun toDomain() = DuplicateFingerprint(documentUri, sizeBytes, lastModifiedMs, contentHash, songKey, rulesVersion)

    companion object {
        fun of(f: DuplicateFingerprint) =
            DuplicateFingerprintEntity(f.uri, f.sizeBytes, f.lastModifiedMs, f.contentHash, f.songKey, f.rulesVersion)
    }
}

/** The duplicate checks' one row of settings: the notification switch, its last count, the first analysis done. */
@Entity(tableName = "duplicate_settings")
data class DuplicateSettingsEntity(
    @PrimaryKey val id: Int = ROW,
    @ColumnInfo(name = "notify", defaultValue = "1") val notify: Boolean = true,
    @ColumnInfo(name = "last_notified_count", defaultValue = "0") val lastNotifiedCount: Int = 0,
    @ColumnInfo(name = "first_analysis_done", defaultValue = "0") val firstAnalysisDone: Boolean = false,
) {
    companion object {
        const val ROW = 0
    }
}

/** The columns of one indexed track the duplicate finder compares. */
data class DuplicateFileRow(
    @ColumnInfo(name = "document_uri") val documentUri: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "tag_title") val tagTitle: String?,
    @ColumnInfo(name = "tag_artist") val tagArtist: String?,
    @ColumnInfo(name = "tag_album") val tagAlbum: String?,
    @ColumnInfo(name = "tag_album_artist") val tagAlbumArtist: String?,
    val genre: String?,
    val year: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val codec: String?,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int?,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int?,
    @ColumnInfo(name = "bits_per_sample") val bitsPerSample: Int?,
    @ColumnInfo(name = "channel_count") val channelCount: Int?,
    @ColumnInfo(name = "folder_path") val folderPath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "embedded_artwork_uri") val embeddedArtworkUri: String?,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
)

