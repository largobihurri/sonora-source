package io.sonora.core.domain.library.marks

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** What kind of library item a mark (favourite, pin, hidden) is about. Names are persisted: never rename one. */
enum class MarkKind { SONG, ALBUM, ARTIST, PLAYLIST, FOLDER }

/**
 * One library item as marks name it: a song by its file's stable URI (it survives rescans, like playlist entries),
 * an album, artist or playlist by its id, a folder by its tree and path.
 */
data class ItemRef(val kind: MarkKind, val key: String) {
    companion object {
        fun song(track: Track) = ItemRef(MarkKind.SONG, track.uri)
        fun song(uri: String) = ItemRef(MarkKind.SONG, uri)
        fun album(album: Album) = ItemRef(MarkKind.ALBUM, album.id.toString())
        fun album(albumId: Long) = ItemRef(MarkKind.ALBUM, albumId.toString())
        fun artist(artistId: Long) = ItemRef(MarkKind.ARTIST, artistId.toString())
        fun playlist(playlistId: Long) = ItemRef(MarkKind.PLAYLIST, playlistId.toString())
        fun folder(folder: FolderRef) = ItemRef(MarkKind.FOLDER, "${folder.treeUri}\n${folder.path}")
    }
}

/**
 * The list a pin belongs to: an item pinned in one list stays at the top of that list only. Ids are persisted: never
 * change how one is built.
 */
@JvmInline
value class PinContext(val id: String) {
    companion object {
        val SONGS = PinContext("songs")
        val ALBUMS = PinContext("albums")
        val ARTISTS = PinContext("artists")
        val PLAYLISTS = PinContext("playlists")
        val FOLDERS = PinContext("folders")
        val CONVERTED = PinContext("converted")
        fun album(albumId: Long) = PinContext("album:$albumId")
        fun artist(artistId: Long) = PinContext("artist:$artistId")
        fun playlist(playlistId: Long) = PinContext("playlist:$playlistId")
        fun folder(folder: FolderRef) = PinContext("folder:${folder.treeUri}\n${folder.path}")
    }
}

/** An item removed from Sonora (its files stay); [title] and [subtitle] are a snapshot to list it by in Settings. */
data class HiddenItem(val ref: ItemRef, val title: String, val subtitle: String, val hiddenAtMs: Long)

/** Favourites, per-list pins and the items removed from Sonora. Flows re-emit when the marks change. */
interface LibraryMarksRepository {
    /** The keys of [kind]'s favourites, the most recently added first. */
    fun favourites(kind: MarkKind): Flow<List<String>>

    suspend fun setFavourite(ref: ItemRef, favourite: Boolean)

    /** The keys pinned in [context], in the order they were pinned. */
    fun pins(context: PinContext): Flow<List<String>>

    suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean)

    /** Every hidden item, the most recently hidden first. */
    fun hidden(): Flow<List<HiddenItem>>

    /** Hides [item] from every library list; its files stay where they are. */
    suspend fun hide(item: HiddenItem)

    /** Shows a hidden item again. */
    suspend fun restore(ref: ItemRef)

    /** Drops every mark of the songs at [uris] (after their files were deleted). */
    suspend fun forgetSongs(uris: Collection<String>)

    /**
     * Hands the marks of deleted songs to other files of the same songs, all at once: each key of [moves] (a deleted
     * file's URI) gives its value (the copy kept) its favourite and its pins (a mark either one had stays) and adds its
     * play count to the copy's, keeping the latest time played; its hidden mark is dropped. Then the deleted songs'
     * marks are forgotten, as by [forgetSongs]. A store that cannot hand marks over only forgets them.
     */
    suspend fun moveSongs(moves: Map<String, String>) = forgetSongs(moves.keys)
}
