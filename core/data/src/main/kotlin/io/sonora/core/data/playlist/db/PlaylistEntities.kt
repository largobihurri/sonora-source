package io.sonora.core.data.playlist.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries

/** One user playlist. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    fun toDomain() = Playlist(id, name, description, createdAtMs, updatedAtMs)
}

/**
 * One song of a playlist at [position] (0-based, dense). The song is its file's [trackUri], not the scanner's row,
 * so rescans keep it; [title] and [artist] are the snapshot shown once the file is gone. Deleting the playlist
 * deletes its entries (foreign key cascade).
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlist_id", "position"], unique = true), Index("track_uri")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val position: Int,
    @ColumnInfo(name = "track_uri") val trackUri: String,
    val title: String,
    val artist: String,
) {
    fun toDomain() = PlaylistEntry(id, trackUri, title, artist)

    companion object {
        fun of(entry: PlaylistEntry, playlistId: Long, position: Int) =
            PlaylistEntryEntity(entry.id, playlistId, position, entry.trackUri, entry.title, entry.artist)
    }
}

/** A playlist row with its entry rows (in no particular order; [toDomain] sorts them). */
data class PlaylistWithEntryRows(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlist_id") val entries: List<PlaylistEntryEntity>,
) {
    fun toDomain() = PlaylistWithEntries(playlist.toDomain(), entries.sortedBy { it.position }.map { it.toDomain() })
}
