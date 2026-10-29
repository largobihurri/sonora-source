package io.sonora.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.car.CarLibraryTree
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.playlist.ObservePlaylists
import io.sonora.core.domain.port.LibraryRepository

/** The library as Android Auto browses it, over the same library, marks and playlists the app shows. */
@Module
@InstallIn(SingletonComponent::class)
object CarLibraryModule {
    @Provides
    fun provideCarLibraryTree(library: LibraryRepository, marks: LibraryMarksRepository, playlists: ObservePlaylists) =
        CarLibraryTree(library, marks, playlists::invoke)
}
