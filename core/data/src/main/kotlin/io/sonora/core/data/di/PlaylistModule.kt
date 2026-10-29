package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.playlist.RoomPlaylistRepository
import io.sonora.core.data.playlist.db.PlaylistDao
import io.sonora.core.domain.library.ObserveConvertedMusic
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.AddTracksToPlaylist
import io.sonora.core.domain.playlist.CreatePlaylist
import io.sonora.core.domain.playlist.DeletePlaylist
import io.sonora.core.domain.playlist.EditPlaylistDetails
import io.sonora.core.domain.playlist.MovePlaylistEntry
import io.sonora.core.domain.playlist.ObservePlaylist
import io.sonora.core.domain.playlist.ObservePlaylists
import io.sonora.core.domain.playlist.RemovePlaylistEntry
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Playlists in the app's Room database and the domain's playlist use cases built on them. */
@Module
@InstallIn(SingletonComponent::class)
interface PlaylistBindings {
    @Binds
    fun bindPlaylistRepository(impl: RoomPlaylistRepository): PlaylistRepository
}

@Module
@InstallIn(SingletonComponent::class)
object PlaylistModule {
    @Provides
    fun providePlaylistDao(database: LibraryDatabase): PlaylistDao = database.playlistDao()

    /** Converted files outside the library folders still play from playlists, through the converter's history. */
    private fun ObserveConvertedMusic.tracks(): Flow<List<Track>> = this().map { sections -> sections.flatMap { it.tracks } }

    @Provides
    fun provideObservePlaylists(playlists: PlaylistRepository, library: LibraryRepository, converted: ObserveConvertedMusic) =
        ObservePlaylists(playlists, library, converted = converted.tracks())

    @Provides
    fun provideObservePlaylist(playlists: PlaylistRepository, library: LibraryRepository, converted: ObserveConvertedMusic) =
        ObservePlaylist(playlists, library, converted = converted.tracks())

    @Provides
    fun provideCreatePlaylist(playlists: PlaylistRepository) = CreatePlaylist(playlists)

    @Provides
    fun provideEditPlaylistDetails(playlists: PlaylistRepository) = EditPlaylistDetails(playlists)

    @Provides
    fun provideDeletePlaylist(playlists: PlaylistRepository) = DeletePlaylist(playlists)

    @Provides
    fun provideAddTracksToPlaylist(playlists: PlaylistRepository) = AddTracksToPlaylist(playlists)

    @Provides
    fun provideRemovePlaylistEntry(playlists: PlaylistRepository) = RemovePlaylistEntry(playlists)

    @Provides
    fun provideMovePlaylistEntry(playlists: PlaylistRepository) = MovePlaylistEntry(playlists)
}
