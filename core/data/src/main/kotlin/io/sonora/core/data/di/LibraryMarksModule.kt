package io.sonora.core.data.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryMarksDao
import io.sonora.core.data.library.edit.SafLibraryFileDeleter
import io.sonora.core.data.library.marks.RoomLibraryMarks
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.RekordboxExportRequester

/** Favourites, pins, hidden items and "Delete from Phone". */
@Module
@InstallIn(SingletonComponent::class)
interface LibraryMarksBindings {
    @Binds
    fun bindLibraryMarks(impl: RoomLibraryMarks): LibraryMarksRepository

    @Binds
    fun bindLibraryFileDeleter(impl: SafLibraryFileDeleter): LibraryFileDeleter

    /**
     * Bound by the CDJ export feature once it accepts preselected songs; while it is absent the library shows
     * "Export as Rekordbox Playlist" disabled.
     */
    @BindsOptionalOf
    fun optionalRekordboxExportRequester(): RekordboxExportRequester
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryMarksModule {
    @Provides
    fun provideLibraryMarksDao(database: LibraryDatabase): LibraryMarksDao = database.libraryMarksDao()

    @Provides
    fun provideDeleteFromPhone(files: LibraryFileDeleter, playlists: PlaylistRepository, marks: LibraryMarksRepository) =
        DeleteFromPhone(files, playlists, marks)
}
