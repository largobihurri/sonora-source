package io.sonora.core.data.library.edit

import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.tags.ArtworkCache
import io.sonora.core.data.library.tags.RawTags
import io.sonora.core.data.library.tags.TagMapper
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagField
import javax.inject.Inject

/**
 * Puts a retagged file's new tags into the library at once, without a rescan: its index row takes the tags, the
 * file's new size and date (so the next scan sees it unchanged) and its new embedded picture, then the library is
 * regrouped, since an album or artist change can move it to another album.
 */
class LibraryRetag @Inject constructor(private val dao: LibraryDao, private val artworkCache: ArtworkCache) {

    /**
     * [track] with the [written] tags as the library now lists it; for a file outside the library (e.g. a converted
     * file) only the returned copy carries them. [sizeBytes] and [lastModifiedMs] describe the rewritten file.
     */
    suspend fun store(track: Track, written: SongTags, sizeBytes: Long?, lastModifiedMs: Long?): Track {
        val tags = TagMapper.map(
            RawTags(
                title = written.fields[TagField.TITLE],
                artist = written.fields[TagField.ARTIST],
                album = written.fields[TagField.ALBUM],
                albumArtist = written.fields[TagField.ALBUM_ARTIST],
                genre = written.fields[TagField.GENRE],
                year = written.fields[TagField.YEAR],
                track = written.fields[TagField.TRACK_NUMBER],
                disc = written.fields[TagField.DISC_NUMBER],
            ),
        )
        val retagged = track.copy(
            title = tags.title ?: track.title,
            artist = tags.artist ?: track.artist,
            album = tags.album ?: track.album,
            albumArtist = tags.albumArtist ?: track.albumArtist,
            genre = tags.genre,
            year = tags.year,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
        )
        val row = dao.trackByUri(track.uri) ?: return retagged
        // A broken picture or a full cache costs the artwork only, as in a scan.
        val artwork = written.artwork?.let { runCatching { artworkCache.store(it.bytes) }.getOrNull() }
        val stored = dao.storeRetagged(
            row.copy(
                // Compilation flag and stream details are not edited.
                tagTitle = tags.title, tagArtist = tags.artist, tagAlbum = tags.album, tagAlbumArtist = tags.albumArtist,
                genre = tags.genre, year = tags.year, trackNumber = tags.trackNumber, discNumber = tags.discNumber,
                embeddedArtworkUri = artwork,
                sizeBytes = sizeBytes ?: row.sizeBytes,
                // Unknown: a date no file has, so the next scan reads the file again.
                lastModifiedMs = lastModifiedMs ?: 0L,
            ),
        )
        // Left the library while being saved: like a file outside it, only the returned copy carries the tags.
        if (!stored) return retagged
        return dao.trackByUri(track.uri)?.toDomain() ?: retagged
    }
}
