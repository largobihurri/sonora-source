package io.sonora.core.data.archive.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ImportedSong

/** One finished archive import ([ArchiveImportEntry]). */
@Entity(tableName = "archive_imports")
data class ArchiveImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "archive_name") val archiveName: String,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
    @ColumnInfo(name = "destination_tree_uri") val destinationTreeUri: String,
    @ColumnInfo(name = "folder_uri") val folderUri: String,
    @ColumnInfo(name = "playlist_id") val playlistId: Long?,
) {
    fun toDomain(songCount: Int) =
        ArchiveImportEntry(id, archiveName, importedAtMs, destinationTreeUri, folderUri, playlistId, songCount)

    companion object {
        fun of(entry: ArchiveImportEntry) = ArchiveImportEntity(
            id = entry.id,
            archiveName = entry.archiveName,
            importedAtMs = entry.importedAtMs,
            destinationTreeUri = entry.destinationTreeUri,
            folderUri = entry.folderUri,
            playlistId = entry.playlistId,
        )
    }
}

/** One song an import brought, in the import's order; it goes with its import. */
@Entity(
    tableName = "archive_import_songs",
    foreignKeys = [
        ForeignKey(
            entity = ArchiveImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["import_id", "position"], unique = true)],
)
data class ArchiveImportSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "import_id") val importId: Long,
    val position: Int,
    @ColumnInfo(name = "document_key") val documentKey: String,
    val title: String,
    val artist: String,
) {
    fun toDomain() = ImportedSong(documentKey, title, artist)
}

/** An import row with how many songs it brought. */
data class ImportWithCount(
    @Embedded val entry: ArchiveImportEntity,
    @ColumnInfo(name = "song_count") val songCount: Int,
) {
    fun toDomain() = entry.toDomain(songCount)
}
