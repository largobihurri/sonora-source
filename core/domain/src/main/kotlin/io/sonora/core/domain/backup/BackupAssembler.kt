package io.sonora.core.domain.backup

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.PlaylistWithEntries

/** Builds a [LibraryBackup] from the current library's state. */
object BackupAssembler {
    /**
     * The backup made at [nowMs]. Its song table holds every song a playlist, a song mark or a play count names, with
     * the library's tags, or the playlist entry's title and artist for a song the library no longer has; settings
     * pass [BackupSettingsPolicy].
     */
    fun assemble(
        nowMs: Long,
        library: List<SongIdentity>,
        playlists: List<PlaylistWithEntries>,
        favourites: List<BackupFavourite>,
        pins: List<BackupPin>,
        hidden: List<HiddenItem>,
        plays: List<BackupPlays>,
        settings: List<BackupSettingsFile>,
        djAnalyses: List<String> = emptyList(),
    ): LibraryBackup {
        val referenced = LinkedHashSet<String>()
        playlists.forEach { playlist -> playlist.entries.mapTo(referenced) { it.trackUri } }
        favourites.filter { it.ref.kind == MarkKind.SONG }.mapTo(referenced) { it.ref.key }
        pins.filter { it.ref.kind == MarkKind.SONG }.mapTo(referenced) { it.ref.key }
        hidden.filter { it.ref.kind == MarkKind.SONG }.mapTo(referenced) { it.ref.key }
        plays.mapTo(referenced) { it.key }

        val inLibrary = library.associateBy { it.key }
        val snapshots = playlists.asSequence().flatMap { it.entries }.associateBy { it.trackUri }
        val songs = referenced.mapNotNull { key ->
            inLibrary[key] ?: snapshots[key]?.let { SongIdentity(key, it.title, it.artist, album = "", durationMs = 0) }
        }
        return LibraryBackup(
            createdAtMs = nowMs,
            songs = songs,
            playlists = playlists.map { BackupPlaylist(it.playlist.id, it.playlist.name, it.playlist.description, it.entries.map { e -> e.trackUri }) },
            favourites = favourites,
            pins = pins,
            hidden = hidden,
            plays = plays,
            settings = BackupSettingsPolicy.filter(settings),
            djAnalyses = djAnalyses,
        )
    }
}
