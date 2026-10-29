package io.sonora.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.smart.RoomSmartPlaylists
import io.sonora.core.domain.library.smart.ObserveSmartPlaylist
import io.sonora.core.domain.library.smart.SmartPlaylistRepository
import java.time.Clock

/** The smart playlists of Library > Playlists (Recently Added, Most Played, Never Played). */
@Module
@InstallIn(SingletonComponent::class)
interface SmartPlaylistBindings {
    @Binds
    fun bindSmartPlaylists(impl: RoomSmartPlaylists): SmartPlaylistRepository
}

@Module
@InstallIn(SingletonComponent::class)
object SmartPlaylistModule {
    @Provides
    fun provideObserveSmartPlaylist(repository: SmartPlaylistRepository, clock: Clock) = ObserveSmartPlaylist(repository, clock)
}
