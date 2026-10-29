package io.sonora.core.data.library.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ResolvedTrack
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.AlbumCover
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.Track

@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long,
) {
    fun toDomain() = LibraryFolder(treeUri, displayName, addedAtMs)
}

/**
 * One indexed audio file: file identity, raw tags as read (`tag_*` and the technical columns) and the
 * library-wide resolution written by the grouping rules (display names, album/artist ids, album artwork).
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["document_uri"], unique = true),
        Index(value = ["folder_tree_uri", "folder_path"]),
        Index("album_id"),
        Index("artist_id"),
        Index("album_artist_id"),
    ],
)
data class TrackEntity(
    /** Stable hash of [documentUri], so playlists and queues keep pointing at the file across rescans. */
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "document_uri") val documentUri: String,
    @ColumnInfo(name = "folder_tree_uri") val folderTreeUri: String,
    @ColumnInfo(name = "folder_path") val folderPath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
    @ColumnInfo(name = "tag_title") val tagTitle: String?,
    @ColumnInfo(name = "tag_artist") val tagArtist: String?,
    @ColumnInfo(name = "tag_album") val tagAlbum: String?,
    @ColumnInfo(name = "tag_album_artist") val tagAlbumArtist: String?,
    @ColumnInfo(name = "tag_compilation") val tagCompilation: Boolean,
    val genre: String?,
    val year: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int?,
    @ColumnInfo(name = "bits_per_sample") val bitsPerSample: Int?,
    @ColumnInfo(name = "channel_count") val channelCount: Int?,
    @ColumnInfo(name = "embedded_artwork_uri") val embeddedArtworkUri: String?,
    @ColumnInfo(name = "folder_image_uri") val folderImageUri: String?,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    val album: String,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "album_artist") val albumArtist: String,
    @ColumnInfo(name = "album_artist_id") val albumArtistId: Long,
    @ColumnInfo(name = "is_compilation") val isCompilation: Boolean,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
    /** Short codec name (`FLAC`, `ALAC`, `MP3`...), `null` when unknown. */
    val codec: String? = null,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int? = null,
    /** Tagged date: `yyyy`, `yyyy-MM` or `yyyy-MM-dd`. */
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val copyright: String? = null,
    /**
     * Which [DETAILS_VERSION] of the scanner read this row; rows from an older one are read again once, even when the
     * file did not change, so columns added later get filled.
     */
    @ColumnInfo(name = "details_version", defaultValue = "0") val detailsVersion: Int = DETAILS_VERSION,
) {
    val tags
        get() = TrackTags(
            title = tagTitle, artist = tagArtist, album = tagAlbum, albumArtist = tagAlbumArtist, genre = genre,
            year = year, trackNumber = trackNumber, discNumber = discNumber, isCompilation = tagCompilation,
            durationMs = durationMs, sampleRateHz = sampleRateHz, bitsPerSample = bitsPerSample,
            channelCount = channelCount, codec = codec, bitrateKbps = bitrateKbps, releaseDate = releaseDate,
            copyright = copyright,
        )

    fun toScanned() = ScannedTrack(documentUri, folderPath, fileName, tags, embeddedArtworkUri, folderImageUri)

    fun withResolution(r: ResolvedTrack) = copy(
        title = r.title, artist = r.artist, artistId = r.artistId, album = r.album, albumId = r.albumId,
        albumArtist = r.albumArtist, albumArtistId = r.albumArtistId, isCompilation = r.isCompilation,
        artworkUri = r.artworkUri,
    )

    fun toDomain() = Track(
        id = id,
        uri = documentUri,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        albumId = albumId,
        artistId = artistId,
        artworkUri = artworkUri,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateAddedSec = dateAddedSec,
        genre = genre,
        folderPath = folderPath,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        channelCount = channelCount,
        codec = codec,
        bitrateKbps = bitrateKbps,
        releaseDate = releaseDate,
        copyright = copyright,
    )

    companion object {
        /** 1: codec, bitrate, release date and copyright. */
        const val DETAILS_VERSION = 1

        /** A freshly read file; the resolution columns hold [resolution], normally from its own scan batch. */
        fun of(
            file: ScannedTrack,
            folderTreeUri: String,
            mimeType: String,
            identity: FileIdentity,
            dateAddedSec: Long,
            resolution: ResolvedTrack,
        ) = TrackEntity(
            id = AlbumGrouping.stableId("track", file.documentUri),
            documentUri = file.documentUri,
            folderTreeUri = folderTreeUri,
            folderPath = file.folderPath,
            fileName = file.fileName,
            mimeType = mimeType,
            sizeBytes = identity.sizeBytes,
            lastModifiedMs = identity.lastModifiedMs,
            dateAddedSec = dateAddedSec,
            tagTitle = file.tags.title,
            tagArtist = file.tags.artist,
            tagAlbum = file.tags.album,
            tagAlbumArtist = file.tags.albumArtist,
            tagCompilation = file.tags.isCompilation,
            genre = file.tags.genre,
            year = file.tags.year,
            trackNumber = file.tags.trackNumber,
            discNumber = file.tags.discNumber,
            durationMs = file.tags.durationMs,
            sampleRateHz = file.tags.sampleRateHz,
            bitsPerSample = file.tags.bitsPerSample,
            channelCount = file.tags.channelCount,
            embeddedArtworkUri = file.embeddedArtworkUri,
            folderImageUri = file.folderImageUri,
            title = "",
            artist = "",
            artistId = 0L,
            album = "",
            albumId = 0L,
            albumArtist = "",
            albumArtistId = 0L,
            isCompilation = false,
            artworkUri = null,
            codec = file.tags.codec,
            bitrateKbps = file.tags.bitrateKbps,
            releaseDate = file.tags.releaseDate,
            copyright = file.tags.copyright,
            detailsVersion = DETAILS_VERSION,
        ).withResolution(resolution)
    }
}

/**
 * Failed tag reads of [documentUri], the last one at [lastFailureMs]. Below the scanner's attempt limit it counts
 * reads of a version the index does not hold yet (a new or changed file); at the limit the file was indexed untagged
 * and the count goes on with the occasional re-reads of that fallback entry (see `ReadRetry`).
 */
@Entity(tableName = "tag_read_failures", indices = [Index("folder_tree_uri")])
data class TagReadFailureEntity(
    @PrimaryKey @ColumnInfo(name = "document_uri") val documentUri: String,
    @ColumnInfo(name = "folder_tree_uri") val folderTreeUri: String,
    val failures: Int,
    @ColumnInfo(name = "last_failure_ms", defaultValue = "0") val lastFailureMs: Long = 0L,
)

/** File identity and location columns of one indexed track, for the incremental diff. */
data class IndexedFileRow(
    @ColumnInfo(name = "document_uri") val documentUri: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
    @ColumnInfo(name = "folder_path") val folderPath: String,
    @ColumnInfo(name = "folder_image_uri") val folderImageUri: String?,
    @ColumnInfo(name = "embedded_artwork_uri") val embeddedArtworkUri: String?,
    @ColumnInfo(name = "details_version") val detailsVersion: Int = TrackEntity.DETAILS_VERSION,
) {
    val identity get() = FileIdentity(documentUri, sizeBytes, lastModifiedMs)
}

/** Album aggregate computed from the tracks table. */
data class AlbumRow(
    @ColumnInfo(name = "album_id") val id: Long,
    val album: String,
    @ColumnInfo(name = "album_artist") val albumArtist: String,
    @ColumnInfo(name = "album_artist_id") val albumArtistId: Long,
    val year: Int?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
    @ColumnInfo(name = "is_compilation") val isCompilation: Boolean,
) {
    fun toDomain() = Album(
        id = id,
        title = album,
        artist = albumArtist,
        year = year,
        trackCount = trackCount,
        artworkUri = artworkUri,
        artistId = albumArtistId,
        dateAddedSec = dateAddedSec,
        isCompilation = isCompilation,
    )
}

/** Artist aggregate computed from the tracks table (track artists). */
data class ArtistRow(
    @ColumnInfo(name = "artist_id") val id: Long,
    val artist: String,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
) {
    fun toDomain() = Artist(id = id, name = artist, albumCount = albumCount, trackCount = trackCount, dateAddedSec = dateAddedSec)
}

/** One album of an artist with artwork, as a cover candidate for the artist's row. */
data class ArtistAlbumCoverRow(
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
    val plays: Int,
    @ColumnInfo(name = "date_added_sec") val dateAddedSec: Long,
) {
    fun toCover() = AlbumCover(albumId = albumId, artworkUri = artworkUri, plays = plays, dateAddedSec = dateAddedSec)
}

/** Number of tracks directly in one directory of a library folder. */
data class FolderCountRow(
    @ColumnInfo(name = "folder_path") val folderPath: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/** A library folder with the number of tracks inside it. */
data class FolderRootRow(
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)
